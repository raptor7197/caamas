import argparse
import json
import logging
from typing import List, Optional, Tuple, Union

import numpy as np

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("camms.fl_server")

NDArrays = List[np.ndarray]


def clip_and_average(
    base_weights: NDArrays,
    client_weights: List[NDArrays],
    num_examples: List[int],
    clip_norm: float,
    noise_multiplier: float,
) -> NDArrays:
    """Core DP-FedAvg math, kept dependency-free (no flwr/opacus types) so it can be
    unit-tested without either installed — see `_self_check()` below.

    Clips each client's *update* (client_weights - base_weights) to L2 norm
    `clip_norm` before averaging, then adds Gaussian noise calibrated to that same
    bound. Returns the new global weights (base_weights + noisy averaged delta).
    """
    if not client_weights:
        raise ValueError("client_weights must be non-empty")

    clipped_deltas: List[NDArrays] = []
    for weights in client_weights:
        delta = [new - old for new, old in zip(weights, base_weights)]
        l2_norm = float(np.sqrt(sum(np.sum(d.astype(np.float64) ** 2) for d in delta)))
        scale = min(1.0, clip_norm / l2_norm) if l2_norm > 0 else 1.0
        clipped_deltas.append([d * scale for d in delta])

    total_examples = sum(num_examples)
    avg_delta = [np.zeros_like(d) for d in clipped_deltas[0]]
    for client_delta, n in zip(clipped_deltas, num_examples):
        weight = n / total_examples
        for i, d in enumerate(client_delta):
            avg_delta[i] = avg_delta[i] + d * weight

    if noise_multiplier > 0:
        noise_std = clip_norm * noise_multiplier / len(client_weights)
        avg_delta = [
            d + np.random.normal(0, noise_std, d.shape).astype(d.dtype) for d in avg_delta
        ]

    return [w + d for w, d in zip(base_weights, avg_delta)]


def evaluate_metrics(metrics: List[Tuple[int, dict]]) -> dict:
    """Weighted-average aggregation for federated evaluation.

    Must match Flower's `evaluate_metrics_aggregation_fn` contract:
    `(list[(num_examples, metrics_dict)]) -> dict`, not `(round_num) -> dict` —
    the old signature meant this either raised or was silently never called.
    """
    total = sum(n for n, _ in metrics)
    if total == 0:
        return {}
    aggregated: dict = {}
    keys = {k for _, m in metrics for k in m}
    for key in keys:
        aggregated[key] = sum(n * m.get(key, 0.0) for n, m in metrics) / total
    return aggregated


def _self_check():
    """`python fl_server/server.py --self-check` — exercises clip_and_average and
    evaluate_metrics without needing flwr/opacus installed."""
    base = [np.zeros(4)]
    # Client A's update is way over the clip bound; client B's is small and untouched.
    client_a = [np.array([10.0, 0.0, 0.0, 0.0])]
    client_b = [np.array([0.1, 0.0, 0.0, 0.0])]

    result = clip_and_average(base, [client_a, client_b], [1, 1], clip_norm=1.0, noise_multiplier=0.0)
    delta = result[0]
    # Averaged delta must be well under client A's raw (unclipped) magnitude of 10.
    assert np.linalg.norm(delta) < 1.0, f"clip not applied: {delta}"
    assert delta[0] > 0, "expected a positive x-component after averaging"

    agg = evaluate_metrics([(10, {"accuracy": 0.8}), (30, {"accuracy": 0.4})])
    expected = (10 * 0.8 + 30 * 0.4) / 40
    assert abs(agg["accuracy"] - expected) < 1e-9, agg

    assert evaluate_metrics([]) == {}

    print("[OK] clip_and_average + evaluate_metrics self-check passed")


try:
    import flwr as fl
    from flwr.common import FitRes, Parameters, ndarrays_to_parameters, parameters_to_ndarrays
    from flwr.server.client_proxy import ClientProxy
    from opacus.accountants.rdp import RDPAccountant

    class DpFedAvg(fl.server.strategy.FedAvg):
        """DP-FedAvg (McMahan et al., "Learning Differentially Private Recurrent
        Language Models"): each client's *update* (new weights minus the global
        weights it started from) is clipped to L2 norm `clip_norm` before
        averaging, and Gaussian noise calibrated to that same bound is added to
        the average. Clipping the already-averaged params post-hoc (the previous
        implementation) gives no real epsilon guarantee — DP composition requires
        clipping each client's contribution *before* it's mixed with the others.

        Privacy spend is tracked with opacus's RDP accountant (real (noise,
        sample-rate) composition), not an ad-hoc accumulator — rounds stop being
        accepted once the target epsilon is reached.
        """

        def __init__(
            self,
            noise_multiplier: float = 1.0,
            target_epsilon: float = 8.0,
            delta: float = 1e-5,
            clip_norm: float = 1.0,
            *args,
            **kwargs,
        ):
            super().__init__(*args, **kwargs)
            self.noise_multiplier = noise_multiplier
            self.target_epsilon = target_epsilon
            self.delta = delta
            self.clip_norm = clip_norm
            self.accountant = RDPAccountant()
            self.current_weights: Optional[NDArrays] = None
            self.budget_exhausted = False

        def initialize_parameters(self, client_manager):
            params = super().initialize_parameters(client_manager)
            if params is not None:
                self.current_weights = parameters_to_ndarrays(params)
            return params

        def aggregate_fit(
            self,
            server_round: int,
            results: List[Tuple["ClientProxy", "FitRes"]],
            failures: List[Union[Tuple["ClientProxy", "FitRes"], BaseException]],
        ):
            if self.budget_exhausted:
                logger.warning(
                    f"Round {server_round}: DP budget already exhausted — refusing to aggregate further"
                )
                return None, {}
            if not results:
                return None, {}

            if self.current_weights is None:
                # initialize_parameters wasn't populated (e.g. resuming) — seed from the
                # first client's starting point instead of crashing.
                self.current_weights = parameters_to_ndarrays(results[0][1].parameters)

            client_weights = [parameters_to_ndarrays(fit_res.parameters) for _, fit_res in results]
            num_examples = [fit_res.num_examples for _, fit_res in results]

            self.current_weights = clip_and_average(
                self.current_weights, client_weights, num_examples,
                self.clip_norm, self.noise_multiplier,
            )

            # ---- real epsilon accounting via RDP composition ----
            sample_rate = len(results) / max(1, self.min_available_clients)
            self.accountant.step(noise_multiplier=self.noise_multiplier, sample_rate=sample_rate)
            epsilon = self.accountant.get_epsilon(delta=self.delta)
            logger.info(f"Round {server_round}: ε = {epsilon:.3f} / target {self.target_epsilon}")

            if epsilon >= self.target_epsilon:
                self.budget_exhausted = True
                logger.warning(
                    f"Round {server_round}: DP budget reached (ε={epsilon:.3f} ≥ {self.target_epsilon}) "
                    "— this is the last round that will train; subsequent rounds are refused."
                )

            return ndarrays_to_parameters(self.current_weights), {"epsilon": epsilon}

    _FL_AVAILABLE = True
except ImportError:
    _FL_AVAILABLE = False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-address", default="0.0.0.0:8080")
    parser.add_argument("--rounds", type=int, default=50)
    parser.add_argument("--min-clients", type=int, default=2)
    parser.add_argument("--noise-multiplier", type=float, default=1.0)
    parser.add_argument("--clip-norm", type=float, default=1.0)
    parser.add_argument("--fraction-fit", type=float, default=0.5)
    parser.add_argument("--config", default=None)
    parser.add_argument("--self-check", action="store_true", help="Run the dependency-free math self-check and exit")
    args = parser.parse_args()

    if args.self_check:
        _self_check()
        return

    if not _FL_AVAILABLE:
        raise SystemExit("flwr and opacus must be installed to run the FL server (see pyproject.toml)")

    config = {}
    if args.config:
        with open(args.config) as f:
            config = json.load(f)

    strategy = DpFedAvg(
        noise_multiplier=config.get("noise_multiplier", args.noise_multiplier),
        target_epsilon=config.get("target_epsilon", 8.0),
        clip_norm=config.get("clip_norm", args.clip_norm),
        fraction_fit=config.get("fraction_fit", args.fraction_fit),
        fraction_evaluate=1.0,
        min_fit_clients=max(1, args.min_clients),
        min_evaluate_clients=1,
        min_available_clients=args.min_clients,
        evaluate_metrics_aggregation_fn=evaluate_metrics,
    )

    logger.info(f"Starting FL server on {args.server_address}")
    logger.info(f"Rounds={args.rounds}, min_clients={args.min_clients}, noise={args.noise_multiplier}")

    fl.server.start_server(
        server_address=args.server_address,
        config=fl.server.ServerConfig(num_rounds=args.rounds),
        strategy=strategy,
    )


if __name__ == "__main__":
    main()
