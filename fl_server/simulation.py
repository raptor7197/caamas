import argparse
import logging
import numpy as np
from typing import Optional

import flwr as fl
import tensorflow as tf

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("camms.simulation")


class GruClient(fl.client.NumPyClient):
    def __init__(
        self,
        model: tf.keras.Model,
        train_data: tuple,
        val_data: tuple,
        user_id: int,
    ):
        self.model = model
        self.x_train, self.y_train = train_data
        self.x_val, self.y_val = val_data
        self.user_id = user_id

    def get_parameters(self, config):
        return self.model.get_weights()

    def fit(self, parameters, config):
        self.model.set_weights(parameters)
        batch_size = config.get("batch_size", 32)
        epochs = config.get("local_epochs", 3)

        # Simulate on-device training constraints
        train_ds = tf.data.Dataset.from_tensor_slices((self.x_train.astype(np.int32), self.y_train))
        train_ds = train_ds.shuffle(1000).batch(batch_size).prefetch(tf.data.AUTOTUNE)

        history = self.model.fit(
            train_ds,
            epochs=epochs,
            verbose=0,
        )

        accuracy = float(history.history["sparse_categorical_accuracy"][-1])
        loss = float(history.history["loss"][-1])

        return self.model.get_weights(), len(self.x_train), {"accuracy": accuracy, "loss": loss}

    def evaluate(self, parameters, config):
        self.model.set_weights(parameters)

        val_ds = tf.data.Dataset.from_tensor_slices((self.x_val.astype(np.int32), self.y_val))
        val_ds = val_ds.batch(32)

        loss, accuracy = self.model.evaluate(val_ds, verbose=0)
        return loss, len(self.x_val), {"accuracy": float(accuracy)}


def create_synthetic_client_data(
    vocab_size: int,
    seq_len: int,
    num_samples: int,
    pattern_strength: float = 0.7,
    seed: int = 42,
) -> tuple:
    rng = np.random.default_rng(seed)
    num_samples = max(10, num_samples)

    xs = []
    ys = []

    for _ in range(num_samples):
        seq = rng.integers(1, vocab_size, size=seq_len)
        if rng.random() < pattern_strength:
            target = seq[-1]
        else:
            target = rng.integers(1, vocab_size)
        xs.append(seq)
        ys.append(target)

    x = np.array(xs, dtype=np.int32)
    y = np.array(ys, dtype=np.int32)

    split = int(len(x) * 0.8)
    return (x[:split], y[:split]), (x[split:], y[split:])


def client_fn(context: fl.client.ClientAppContext) -> fl.client.Client:
    # Each simulated client has slightly different usage patterns
    user_id = context.node_id if context.node_id else 42
    seed = user_id % 10000

    train, val = create_synthetic_client_data(
        vocab_size=500, seq_len=10, num_samples=200, seed=seed
    )

    model = build_gru_model(vocab_size=500)
    return GruClient(model, train, val, user_id)


def build_gru_model(vocab_size: int = 500) -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(10,), dtype=tf.int32)
    x = tf.keras.layers.Embedding(vocab_size, 128, mask_zero=True)(inputs)
    x = tf.keras.layers.GRU(64, return_sequences=False)(x)
    x = tf.keras.layers.Dense(32, activation="relu")(x)
    outputs = tf.keras.layers.Dense(vocab_size, activation="softmax")(x)

    model = tf.keras.Model(inputs=inputs, outputs=outputs)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--num-clients", type=int, default=100)
    parser.add_argument("--rounds", type=int, default=10)
    parser.add_argument("--vocab-size", type=int, default=500)
    args = parser.parse_args()

    logger.info(
        f"Starting FL simulation with {args.num_clients} clients, {args.rounds} rounds"
    )

    def _client_fn(cid: str) -> fl.client.Client:
        seed = hash(cid) % 10000
        train, val = create_synthetic_client_data(
            vocab_size=args.vocab_size, seq_len=10, num_samples=200, seed=seed
        )
        model = build_gru_model(args.vocab_size)
        return GruClient(model, train, val, int(cid))

    strategy = fl.server.strategy.FedAvg(
        fraction_fit=0.3,
        fraction_evaluate=0.2,
        min_fit_clients=max(1, int(args.num_clients * 0.3)),
        min_evaluate_clients=1,
        min_available_clients=args.num_clients,
    )

    history = fl.simulation.start_simulation(
        client_fn=_client_fn,
        num_clients=args.num_clients,
        config=fl.server.ServerConfig(num_rounds=args.rounds),
        strategy=strategy,
        client_resources={"num_cpus": 1},
    )

    logger.info(f"FL simulation complete.")
    logger.info(f"Final accuracy: {history.metrics_centralized.get('accuracy', [[0]])[-1][1]:.3f}")

    results_path = "models/fl_simulation_results.json"
    with open(results_path, "w") as f:
        import json
        json.dump(history.metrics_centralized, f, indent=2)
    logger.info(f"Results saved to {results_path}")


if __name__ == "__main__":
    main()
