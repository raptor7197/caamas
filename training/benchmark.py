import os
import sys
import argparse
import numpy as np
import tensorflow as tf

sys.path.insert(0, os.path.dirname(__file__))
from gru_model import build_gru_model
from tflite_export import benchmark_tflite, export_tflite


def compute_perplexity(probs: np.ndarray, targets: np.ndarray) -> float:
    probs = np.clip(probs, 1e-10, 1.0)
    nll = -np.mean(np.log(probs[np.arange(len(targets)), targets]))
    return float(np.exp(nll))


def full_benchmark(
    keras_model: tf.keras.Model,
    tflite_path: str,
    test_sequences: list[list[int]],
    seq_len: int = 10,
    vocab_size: int = 500,
):
    from tflite_export import benchmark_tflite

    results = {}

    dummy_input = np.zeros((1, seq_len), dtype=np.int32)
    perf = benchmark_tflite(tflite_path, dummy_input)
    results["inference"] = perf

    model_size_kb = os.path.getsize(tflite_path) / 1024
    results["model_size_kb"] = model_size_kb

    import time

    interpreter = tf.lite.Interpreter(model_path=tflite_path, num_threads=4)
    interpreter.allocate_tensors()
    in_det = interpreter.get_input_details()[0]
    out_det = interpreter.get_output_details()[0]

    all_probs = []
    all_targets = []
    correct_top1 = 0
    correct_top3 = 0
    total = 0

    start = time.perf_counter()
    for seq in test_sequences:
        if len(seq) < 2:
            continue
        for i in range(1, min(len(seq), seq_len + 1)):
            window = seq[max(0, i - seq_len) : i]
            if len(window) < seq_len:
                window = [0] * (seq_len - len(window)) + window
            x = np.array([window], dtype=np.int32)
            target = seq[i] if i < len(seq) else seq[-1]

            input_scale, input_zp = in_det["quantization"]
            if input_scale != 0:
                qx = (x / input_scale + input_zp).astype(np.int8)
            else:
                qx = x.astype(np.int8)
            interpreter.set_tensor(in_det["index"], qx)
            interpreter.invoke()
            probs = interpreter.get_tensor(out_det["index"])[0]

            all_probs.append(probs)
            all_targets.append(target)

            top3 = np.argsort(probs)[-3:][::-1]
            if top3[0] == target:
                correct_top1 += 1
            if target in top3:
                correct_top3 += 1
            total += 1

    elapsed = time.perf_counter() - start

    probs_arr = np.array(all_probs)
    targets_arr = np.array(all_targets)
    perplexity = compute_perplexity(probs_arr, targets_arr)

    results["accuracy"] = {
        "top1": correct_top1 / max(total, 1),
        "top3": correct_top3 / max(total, 1),
        "total_samples": total,
    }
    results["perplexity"] = perplexity
    results["throughput"] = total / elapsed if elapsed > 0 else 0

    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--keras-model", default="models/camms_gru_checkpoint.keras")
    parser.add_argument("--tflite-model", default="models/camms_gru.tflite")
    parser.add_argument("--seq-len", type=int, default=10)
    parser.add_argument("--vocab-size", type=int, default=500)
    args = parser.parse_args()

    import json

    rng = np.random.default_rng(42)
    synthetic_test = [list(rng.integers(1, args.vocab_size, size=30)) for _ in range(50)]

    quant_path = args.tflite_model.replace(".tflite", "_int8.tflite")
    if not os.path.exists(quant_path):
        if os.path.exists(args.keras_model):
            model = tf.keras.models.load_model(args.keras_model)
        else:
            model = build_gru_model(args.vocab_size, seq_len=args.seq_len)

        x = np.zeros((1, args.seq_len), dtype=np.int32)
        _ = model(x)

        trainer_dir = os.path.dirname(__file__)
        calib = np.concatenate([np.array(s[:10]) for s in synthetic_test if len(s) >= 10])
        export_tflite(args.keras_model if os.path.exists(args.keras_model) else None,
                      quant_path, calibration_data=calib)

    results = full_benchmark(
        None, quant_path, synthetic_test, args.seq_len, args.vocab_size
    )

    print("=" * 60)
    print("CAMMS GRU Model Benchmark")
    print("=" * 60)
    print(f"  Model size:       {results['model_size_kb']:.1f} KB")
    print(f"  Inference:        {results['inference']['latency_ms']:.2f} ms")
    print(f"  Top-1 accuracy:   {results['accuracy']['top1']:.3f}")
    print(f"  Top-3 accuracy:   {results['accuracy']['top3']:.3f}")
    print(f"  Perplexity:       {results['perplexity']:.3f}")
    print(f"  Throughput:       {results['throughput']:.0f} samples/sec")
    print("=" * 60)

    results_path = "models/benchmark_results.json"
    with open(results_path, "w") as f:
        json.dump(results, f, indent=2)
    print(f"[INFO] Results saved to {results_path}")


if __name__ == "__main__":
    main()
