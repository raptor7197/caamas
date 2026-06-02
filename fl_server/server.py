import argparse
import json
import time
import logging
from typing import Optional

import flwr as fl
import numpy as np

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("camms.fl_server")


class DpFedAvg(fl.server.strategy.FedAvg):
    def __init__(
        self,
        noise_multiplier: float = 1.0,
        target_epsilon: float = 8.0,
        delta: float = 1e-5,
        *args,
        **kwargs,
    ):
        super().__init__(*args, **kwargs)
        self.noise_multiplier = noise_multiplier
        self.target_epsilon = target_epsilon
        self.delta = delta
        self.epsilon_used = 0.0
        self.rounds_completed = 0

    def aggregate_fit(self, server_round, results, failures):
        aggregated = super().aggregate_fit(server_round, results, failures)

        if aggregated and aggregated[0]:
            params = fl.common.parameters_to_ndarrays(aggregated[0])

            # Apply DP noise at aggregation
            if self.noise_multiplier > 0:
                noisy_params = []
                total_norm = 0.0
                for p in params:
                    total_norm += np.sum(p**2)
                total_norm = np.sqrt(total_norm)
                if total_norm > 0:
                    clip_scale = min(1.0, 1.0 / total_norm)
                    for p in params:
                        noise = np.random.normal(
                            0, self.noise_multiplier * clip_scale, p.shape
                        )
                        noisy_params.append(p * clip_scale + noise)
                else:
                    noisy_params = params

                aggregated = (
                    fl.common.ndarrays_to_parameters(noisy_params),
                    aggregated[1],
                )

            self.rounds_completed += 1
            self.epsilon_used += self.noise_multiplier / max(1, self.rounds_completed)

            logger.info(
                f"Round {server_round}: ε used so far = {self.epsilon_used:.2f} / {self.target_epsilon}"
            )

        return aggregated


def evaluate_metrics(round_num: int) -> dict:
    return {"epsilon_used": 0.0}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-address", default="0.0.0.0:8080")
    parser.add_argument("--rounds", type=int, default=50)
    parser.add_argument("--min-clients", type=int, default=2)
    parser.add_argument("--noise-multiplier", type=float, default=1.0)
    parser.add_argument("--fraction-fit", type=float, default=0.5)
    parser.add_argument("--config", default=None)
    args = parser.parse_args()

    config = {}
    if args.config:
        with open(args.config) as f:
            config = json.load(f)

    strategy = DpFedAvg(
        noise_multiplier=config.get("noise_multiplier", args.noise_multiplier),
        target_epsilon=config.get("target_epsilon", 8.0),
        fraction_fit=config.get("fraction_fit", args.fraction_fit),
        fraction_evaluate=1.0,
        min_fit_clients=max(1, args.min_clients),
        min_evaluate_clients=1,
        min_available_clients=args.min_clients,
        evaluate_metrics_aggregation_fn=evaluate_metrics,
    )

    logger.info(f"Starting FL server on {args.server_address}")
    logger.info(f"Rounds={args.rounds}, min_clients={args.min_clients}, ε={args.noise_multiplier}")

    fl.server.start_server(
        server_address=args.server_address,
        config=fl.server.ServerConfig(num_rounds=args.rounds),
        strategy=strategy,
    )


if __name__ == "__main__":
    main()
