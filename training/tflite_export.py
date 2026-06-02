import os
import argparse
import numpy as np
import tensorflow as tf


def export_tflite(
    keras_model_path: str,
    output_path: str,
    calibration_data: np.ndarray | None = None,
    quantize: bool = True,
) -> str:
    model = tf.keras.models.load_model(keras_model_path)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)

    if quantize:
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.int8]
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS_INT8,
            tf.lite.OpsSet.TFLITE_BUILTINS,
        ]
        converter.inference_input_type = tf.int8
        converter.inference_output_type = tf.float32

        if calibration_data is not None:

            def rep_dataset():
                for i in range(0, len(calibration_data), 32):
                    batch = calibration_data[i : i + 32]
                    if len(batch) < 32:
                        break
                    yield [tf.cast(batch, tf.float32)]

            converter.representative_dataset = rep_dataset
    else:
        converter.optimizations = [tf.lite.Optimize.DEFAULT]

    tflite_model = converter.convert()
    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(tflite_model)

    size_kb = os.path.getsize(output_path) / 1024
    print(f"[INFO] Exported {output_path} ({size_kb:.1f} KB)")
    return output_path


def benchmark_tflite(model_path: str, input_data: np.ndarray, num_threads: int = 4) -> dict:
    interpreter = tf.lite.Interpreter(
        model_path=model_path, num_threads=num_threads
    )
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    input_scale, input_zero_point = input_details[0]["quantization"]
    if input_scale != 0:
        input_data = (input_data / input_scale + input_zero_point).astype(np.int8)

    interpreter.set_tensor(input_details[0]["index"], input_data)

    import time

    warmup = 10
    for _ in range(warmup):
        interpreter.invoke()

    trials = 100
    start = time.perf_counter()
    for _ in range(trials):
        interpreter.invoke()
    elapsed_ms = (time.perf_counter() - start) / trials * 1000

    output = interpreter.get_tensor(output_details[0]["index"])
    top_k = np.argsort(output[0])[-3:][::-1]

    return {
        "latency_ms": elapsed_ms,
        "top3_indices": top_k.tolist(),
        "model_path": model_path,
        "input_shape": input_details[0]["shape"],
        "output_shape": output_details[0]["shape"],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--keras-model", required=True)
    parser.add_argument("--output", default="models/camms_gru.tflite")
    parser.add_argument("--quantize", action="store_true", default=True)
    parser.add_argument("--benchmark", action="store_true")
    args = parser.parse_args()

    export_tflite(args.keras_model, args.output, quantize=args.quantize)

    if args.benchmark:
        dummy = np.zeros((1, 10), dtype=np.int32)
        result = benchmark_tflite(args.output, dummy)
        print(f"[BENCH] Latency: {result['latency_ms']:.2f} ms")
        print(f"[BENCH] Top-3: {result['top3_indices']}")


if __name__ == "__main__":
    main()
