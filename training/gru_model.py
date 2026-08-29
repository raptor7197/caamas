import tensorflow as tf
import numpy as np


def build_gru_model(
    vocab_size: int,
    embedding_dim: int = 128,
    hidden_units: int = 64,
    dropout: float = 0.2,
    seq_len: int = 10,
) -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(seq_len,), dtype=tf.int32, name="app_sequence")
    x = tf.keras.layers.Embedding(
        vocab_size, embedding_dim, mask_zero=True, name="app_embedding"
    )(inputs)
    x = tf.keras.layers.GRU(
        hidden_units,
        return_sequences=False,
        dropout=dropout,
        recurrent_dropout=0.0,
        name="gru_layer",
    )(x)
    x = tf.keras.layers.Dense(hidden_units // 2, activation="relu", name="projection")(x)
    x = tf.keras.layers.Dropout(dropout)(x)
    outputs = tf.keras.layers.Dense(vocab_size, activation="softmax", name="app_output")(x)

    model = tf.keras.Model(inputs=inputs, outputs=outputs, name="camms_gru")
    return model


def create_sequence_dataset(
    sequences: list[list[int]],
    seq_len: int = 10,
    vocab_size: int | None = None,
    batch_size: int = 64,
) -> tf.data.Dataset:
    xs, ys = [], []
    for seq in sequences:
        if len(seq) < 2:
            continue
        for i in range(1, min(len(seq), seq_len + 1)):
            window = seq[max(0, i - seq_len) : i]
            if len(window) < seq_len:
                window = [0] * (seq_len - len(window)) + window
            xs.append(window)
            ys.append(seq[i] if i < len(seq) else seq[-1])

    xs = np.array(xs, dtype=np.int32)
    ys = np.array(ys, dtype=np.int32)

    ds = tf.data.Dataset.from_tensor_slices((xs, ys))
    ds = ds.shuffle(10000).batch(batch_size).prefetch(tf.data.AUTOTUNE)
    return ds


class WarmupCosineDecay(tf.keras.optimizers.schedules.LearningRateSchedule):
    def __init__(
        self,
        peak_lr: float = 1e-3,
        warmup_steps: int = 500,
        total_steps: int = 5000,
        min_lr: float = 1e-6,
    ):
        super().__init__()
        self.peak_lr = peak_lr
        self.warmup_steps = warmup_steps
        self.total_steps = total_steps
        self.min_lr = min_lr

    def __call__(self, step: tf.Tensor) -> tf.Tensor:
        step_f = tf.cast(step, tf.float32)
        warmup = step_f / tf.cast(self.warmup_steps, tf.float32)
        warmup = tf.minimum(warmup, 1.0)
        cosine = tf.cos(tf.constant(np.pi / 2) * (step_f - self.warmup_steps) / (self.total_steps - self.warmup_steps))
        cosine = tf.where(step_f < self.warmup_steps, 1.0, cosine)
        # __call__ previously returned this [min_lr/peak_lr, 1.0]-ish factor directly as
        # the learning rate — multiply by peak_lr so it's an actual LR, not a bare multiplier.
        min_ratio = self.min_lr / self.peak_lr
        factor = min_ratio + (1.0 - min_ratio) * tf.cast(warmup * cosine, tf.float32)
        return self.peak_lr * factor

    def get_config(self):
        return {
            "peak_lr": self.peak_lr,
            "warmup_steps": self.warmup_steps,
            "total_steps": self.total_steps,
            "min_lr": self.min_lr,
        }
