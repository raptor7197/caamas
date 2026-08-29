# caamas

context-aware adaptive memory solution for mobile agentic systems. basically, we're trying to stop your android phone from choking when running ai agents.

## what does it do?

- **predictive app preloading** — a gru model guesses which app you'll open next and loads it before you tap
- **smart memory management** — adaptive arc cache watches pressure metrics and evicts intelligently instead of crashing
- **kv-cache compression** — shrinks llm context memory by 75% (fp16 → int4) with <1% quality loss
- **thermal scaling** — keeps the phone from melting during inference; gracefully degrades features as it heats up
- **federated learning** — phones learn from each other without uploading data, using flower framework + differential privacy (per-client clipping + Gaussian noise, ε accounted via opacus's RDP accountant, target ε≤8 — not yet independently audited)

## tech stack

| component | language | job |
|---|---|---|
| memory daemon | c++ | arc cache, psi monitor, working-set tracking, compaction |
| android app | kotlin | ui, flower client, thermal listener, tflite inference |
| training pipeline | python | gru model on frappe/myket datasets, int8 quantization |
| fl server | python | fedavg aggregation with differential privacy |
| build system | cmake/makefile | cross-compile daemon for android |

## project structure

```
caamas/
├── android/camms-daemon/      # c++ native daemon (arc, psi, working-set, thermal, kv-cache)
├── main/android/app/          # kotlin android app + jni bridge (llama.cpp/whisper.cpp inference)
├── fl_server/                 # flower federated learning server
├── training/                  # gru model training & tflite export
├── benchmarks/                # microbench & system tests
├── CMakeLists.txt             # c++ build
├── pyproject.toml             # python deps (tensorflow, flwr, opacus)
└── plan.md                    # full 12-week implementation blueprint
```

## the 12-week plan

**phase 1** (weeks 1-4): build gru predictor, arc cache, psi monitor, kv-cache compression, thermal monitor
**phase 2** (weeks 5-8): android daemon, jni bridge, preloading actions, federated learning integration
**phase 3** (weeks 9-12): hardening, stress testing, comprehensive benchmarking

## key metrics

- app cold-launch latency: -40% p95 vs stock android
- thrashing events: 0 under normal load
- lmk kill count: -60% vs stock
- gru inference: <5ms per prediction
- model size: <500kb (target 100kb)
- kv-cache memory: -75% vs fp16
- fl round time: <10 min per round (100 clients)
- battery overhead: <2% daily drain

## datasets

- **frappe** (288k samples) — context-aware app usage with time/location/weather
- **myket** (694k installs) — app installation sequences
- **synthetic** — cold-start simulation and fl scaling tests

## cold-start strategy

days 1-3: population prior only (72% top-3 coverage)
days 4-5: collect logs, gru hits 50% confidence
days 6-7: model confidence ≥60%, preloading activates
day 8+: full camms operation with fl uploads

## key risks & mitigations

| risk | mitigation |
|---|---|
| gru never hits 60% confidence | lower to 50% after 14 days; fallback safe |
| kernel changes blocked | use mglru + damon sysfs instead |
| zram compaction jank | slice into ≤5ms chunks; skip if thermal warm |
| flower sdk incompatible | pin v1.28 stable; test before upgrade |
| tflite gru unsupported | use flex delegate + cpu fallback |
| fl drains battery | gate on idle+charging+wifi; max 200mah/session |
| dp degrades accuracy | expect 5-10% loss at ε=8; tune budget |

## getting started

1. clone repo
2. `bash scripts/setup.sh` to fetch llama.cpp + whisper.cpp
3. python training: `camms-train` trains gru on frappe dataset
4. export: `camms-export` quantizes to int8 tflite
5. c++ build: `cmake -B build && make -C build` for the standalone daemon (`android/camms-daemon/`; not yet wired into the mobile app build)
6. android: open `main/android/` in android studio — the app builds llama.cpp/whisper.cpp via its own CMake config
7. fl simulation: `camms-simulate` runs fedavg with 100 synthetic clients

## stress tests covered

- fast app switching (4 apps in 30s) vs ml/heuristic conflict
- unpredictable users (traveler in new timezone)
- thermal throttling indefinite degradation
- kv-cache quantization hallucination over long contexts
- fl stale model trap (14 days no update)
- ui micro-stuttering during compaction

## evaluation reports

- microbenchmarks: inference latency, compaction throughput, quantize/dequantize speed
- system benchmarks: am start -w latency, thrashing events, lmk kill counts
- thermal stress: 30-min sustained inference
- battery historian: daily drain comparison
- fl convergence: ε budget tracking, model accuracy degradation

this is heavy-duty research infrastructure. if you can navigate the plan.md, you know what you're doing.
