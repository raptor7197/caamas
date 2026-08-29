import os
import sys
import json
import argparse
import numpy as np
import tensorflow as tf

sys.path.insert(0, os.path.dirname(__file__))
from gru_model import build_gru_model, create_sequence_dataset, WarmupCosineDecay


def load_frappe_data(data_dir: str) -> tuple[list[list[int]], int]:
    import pandas as pd

    csv_path = os.path.join(data_dir, "Frappe_x1", "frappe_x1.csv")
    if os.path.exists(csv_path):
        df = pd.read_csv(csv_path)
    else:
        alt = os.path.join(data_dir, "frappe.csv")
        if os.path.exists(alt):
            df = pd.read_csv(alt)
        else:
            print(f"[WARN] Frappe data not found at {csv_path}. Generating synthetic data.")
            return _generate_synthetic_frappe()

    user_col = next(c for c in df.columns if "user" in c.lower())
    item_col = next(c for c in df.columns if "item" in c.lower() or "app" in c.lower())

    users = df[user_col].unique()
    id_map = {}
    app_sequences: list[list[int]] = []
    next_id = 1
    for uid in users:
        udf = df[df[user_col] == uid].sort_values(by=df.columns[0])
        seq = []
        for _, row in udf.iterrows():
            app = row[item_col]
            if app not in id_map:
                id_map[app] = next_id
                next_id += 1
            seq.append(id_map[app])
        if len(seq) >= 3:
            app_sequences.append(seq)

    return app_sequences, next_id


def _generate_synthetic_frappe(
    num_users: int = 200, num_apps: int = 500, events_per_user: int = 50
) -> tuple[list[list[int]], int]:
    rng = np.random.default_rng(42)
    app_sequences: list[list[int]] = []
    for _ in range(num_users):
        seq = list(rng.integers(1, num_apps, size=events_per_user))
        app_sequences.append(seq)
    return app_sequences, num_apps


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", default="datasets")
    parser.add_argument("--seq-len", type=int, default=10)
    parser.add_argument("--embed-dim", type=int, default=128)
    parser.add_argument("--hidden-units", type=int, default=64)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--epochs", type=int, default=30)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--output", default="models/camms_gru.tflite")
    args = parser.parse_args()

    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)

    print("[INFO] Loading Frappe dataset...")
    app_sequences, vocab_size = load_frappe_data(args.data_dir)
    print(f"[INFO] Loaded {len(app_sequences)} user sequences, vocab={vocab_size}")

    train_seqs = app_sequences[: int(len(app_sequences) * 0.8)]
    val_seqs = app_sequences[int(len(app_sequences) * 0.8) :]

    train_ds = create_sequence_dataset(train_seqs, args.seq_len, vocab_size, args.batch_size)
    val_ds = create_sequence_dataset(val_seqs, args.seq_len, vocab_size, args.batch_size)

    model = build_gru_model(
        vocab_size=vocab_size,
        embedding_dim=args.embed_dim,
        hidden_units=args.hidden_units,
        seq_len=args.seq_len,
    )
    model.summary()

    total_steps = max(1, args.epochs * (len(train_seqs) // args.batch_size))
    lr_schedule = WarmupCosineDecay(peak_lr=args.lr, warmup_steps=500, total_steps=total_steps)
    optimizer = tf.keras.optimizers.Adam(learning_rate=lr_schedule)
    top3_metric = tf.keras.metrics.SparseTopKCategoricalAccuracy(k=3, name="sparse_top_3_categorical_accuracy")
    model.compile(
        optimizer=optimizer,
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy", top3_metric],
    )

    callbacks = [
        tf.keras.callbacks.EarlyStopping(patience=5, restore_best_weights=True),
        # ReduceLROnPlateau dropped: it mutates optimizer.lr directly, which Keras
        # doesn't support once the optimizer is driven by a LearningRateSchedule
        # (the warmup+cosine schedule below already provides the LR curve).
        tf.keras.callbacks.ModelCheckpoint(
            "models/camms_gru_checkpoint.keras", save_best_only=True
        ),
    ]

    print("[INFO] Training GRU model...")
    history = model.fit(
        train_ds,
        validation_data=val_ds,
        epochs=args.epochs,
        callbacks=callbacks,
    )

    with open("models/training_history.json", "w") as f:
        hist_dict = {k: [float(v) for v in vals] for k, vals in history.history.items()}
        json.dump(hist_dict, f, indent=2)

    print("[INFO] Converting to TFLite with INT8 weight quantization...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.int8]

    def representative_dataset():
        for batch in train_ds.take(100):
            yield [tf.cast(batch[0], tf.float32)]

    converter.representative_dataset = representative_dataset
    # TFLITE_BUILTINS alongside _INT8 lets ops that can't run INT8 (GRU internals
    # often can't) fall back to float instead of failing conversion outright.
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS_INT8,
        tf.lite.OpsSet.TFLITE_BUILTINS,
    ]
    # Keep the index input un-quantized: these are Embedding token IDs, not a
    # continuous signal — quantizing them to int8 overflows for any ID > 127 and
    # silently mis-indexes the embedding table.
    converter.inference_input_type = tf.int32
    converter.inference_output_type = tf.float32

    tflite_model = converter.convert()
    with open(args.output, "wb") as f:
        f.write(tflite_model)

    size_kb = os.path.getsize(args.output) / 1024
    print(f"[INFO] TFLite model saved to {args.output} ({size_kb:.1f} KB)")

    final_acc = history.history["val_sparse_top_3_categorical_accuracy"][-1]
    print(f"[INFO] Validation top-3 accuracy: {final_acc:.3f}")
    print(f"[DONE] Training complete.")


if __name__ == "__main__":
    main()
