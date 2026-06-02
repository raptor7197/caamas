import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import numpy as np
import tensorflow as tf

from training.gru_model import build_gru_model, create_sequence_dataset, WarmupCosineDecay


def test_model_creation():
    model = build_gru_model(vocab_size=100, embedding_dim=32, hidden_units=16, seq_len=5)
    assert model is not None
    assert model.input_shape == (None, 5)
    assert model.output_shape == (None, 100)
    assert len(model.layers) == 5  # Input, Embedding, GRU, Dense, Dropout, Output
    print(f"[PASS] Model created: {sum(tf.keras.backend.count_params(p) for p in model.trainable_weights)} params")


def test_model_inference():
    model = build_gru_model(vocab_size=100, embedding_dim=32, hidden_units=16, seq_len=5)
    x = np.array([[1, 2, 3, 4, 5]], dtype=np.int32)
    y = model.predict(x, verbose=0)
    assert y.shape == (1, 100)
    assert np.isclose(np.sum(y[0]), 1.0, atol=0.01)
    print(f"[PASS] Model inference OK, output sum={np.sum(y[0]):.3f}")


def test_sequence_dataset():
    sequences = [[1, 2, 3, 4, 5], [10, 20, 30, 40, 50, 60]]
    ds = create_sequence_dataset(sequences, seq_len=3, vocab_size=100, batch_size=2)
    for x, y in ds.take(1):
        assert x.shape[1] == 3
        assert y.shape[0] == x.shape[0]
        print(f"[PASS] Dataset produces batches: x={x.shape}, y={y.shape}")


def test_tflite_conversion():
    model = build_gru_model(vocab_size=50, embedding_dim=16, hidden_units=8, seq_len=4)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    assert len(tflite_model) > 0
    assert len(tflite_model) < 500 * 1024  # Should be under 500KB
    print(f"[PASS] TFLite conversion: {len(tflite_model) / 1024:.1f} KB")


def test_warmup_cosine_decay():
    schedule = WarmupCosineDecay(warmup_steps=100, total_steps=1000)
    lr_at_50 = schedule(50).numpy()
    lr_at_500 = schedule(500).numpy()
    assert lr_at_50 > 0
    assert lr_at_500 > 0
    print(f"[PASS] WarmupCosineDecay: lr@50={lr_at_50:.6f}, lr@500={lr_at_500:.6f}")


def test_tflite_export_integration():
    from training.tflite_export import export_tflite, benchmark_tflite

    model = build_gru_model(vocab_size=50, embedding_dim=16, hidden_units=8, seq_len=4)
    x = np.array([[1, 2, 3, 4]], dtype=np.int32)
    _ = model.predict(x, verbose=0)

    with tempfile.NamedTemporaryFile(suffix=".tflite", delete=False) as f:
        tflite_path = f.name

    try:
        calibration_data = np.random.randint(1, 50, size=(100, 4)).astype(np.float32)
        export_tflite(None, tflite_path, calibration_data=calibration_data, quantize=True)
        assert os.path.getsize(tflite_path) > 0

        result = benchmark_tflite(tflite_path, x, num_threads=1)
        assert result["latency_ms"] > 0
        assert len(result["top3_indices"]) == 3
        print(f"[PASS] TFLite benchmark: {result['latency_ms']:.2f} ms, top-3={result['top3_indices']}")
    finally:
        if os.path.exists(tflite_path):
            os.unlink(tflite_path)


if __name__ == "__main__":
    test_model_creation()
    test_model_inference()
    test_sequence_dataset()
    test_tflite_conversion()
    test_warmup_cosine_decay()
    test_tflite_export_integration()
    print("\nAll Python tests passed!")
