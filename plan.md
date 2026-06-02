# CAMMS — Context-Aware Adaptive Memory Solution for Mobile Agentic Systems

## Implementation Blueprint

---

## 1. Technology Stack Decisions

| Component | Recommended | Justification |
|---|---|---|
| **GRU Predictor** | Keras → TFLite (INT8 quantized) | Best Android support; GRU ops native; <100 KB model possible |
| **Heuristic Memory Mgr** | C++ userspace daemon (fork LMKD) | `/proc/pressure`, cgroups v2, zRAM sysfs — all accessible from C |
| **Federated Learning** | **Flower** framework + TFLite on-device training | Most practical; Android SDK exists; gRPC resilient; simulation→production path |
| **KV-Cache Compression** | llama.cpp mixed-precision hook (INT4 hot/cold) + mmap paging | Actively developed in llama.cpp; PR #18747 block tracking + PR #21792 mmap KV |
| **Thermal Monitor** | Android NDK `AThermal_getThermalHeadroom()` | API 31+, no root, predictive, cross-device |
| **Cold-Start fallback** | Population-prior frequency table baked into APK | 72% top-3 coverage from day one |
| **Differential Privacy** | Opacus (PyTorch) or TF Privacy — applied at Flower server aggregation | Mature libraries; can add epsilon budget tracking |

---

## 2. Key Open-Source References to Leverage

```
Heuristic Manager:
  ├── AOSP LMKD:         android.googlesource.com/platform/system/memory/lmkd/
  ├── libCacheSim ARC:   github.com/1a1a11a/libCacheSim (eviction/ARC.c)
  ├── ZFS ARC:           github.com/openzfs/zfs (module/zfs/arc.c) — production ARC
  ├── SkySceneAddon:     github.com/WeirdMidas/SkySceneAddon — LMK-independent mgmt
  └── Fleet:             github.com/jiachengh/Fleet — ART+Kernel co-design

GRU Predictor:
  ├── whatsnextapp:      github.com/ashwin-jm/whatsnextapp — LSTM baseline to adapt
  ├── MVP Factory guide: dev.to/software_mvp-factory/predictive-prefetching-android-tflite
  └── TFLite benchmark:  github.com/tensorflow/tensorflow (lite/tools/benchmark)

Federated Learning:
  ├── Flower:            github.com/flwrlabs/flower (examples/android)
  └── TFLite on-device:  ai.google.dev/edge/litert/conversion/tensorflow/build/ondevice_training

KV-Cache:
  ├── llama.cpp:         github.com/ggml-org/llama.cpp (PR #18747 block tracking, PR #21792 mmap)
  ├── KIVI:              github.com/jy-yuan/KIVI — per-channel INT4 K, per-token V
  ├── kvpress:           github.com/NVIDIA/kvpress — 20+ compression "presses"
  └── KVCache-Factory:   github.com/Zefan-Cai/KVCache-Factory — unified compression API

Thermal:
  ├── Pixel Thermal HAL: android.googlesource.com/platform/hardware/google/pixel/+/master/thermal/
  └── thermald (Intel):  github.com/intel/thermal_daemon (architectural reference)
```

---

## 3. Datasets

| Dataset | Size | Use Case |
|---|---|---|
| **Myket** (HuggingFace / PyG) | 694K installs, 10K users, 7,988 apps | App-install sequence training for GRU |
| **Frappe** (RecZoo x1) | 288K samples, 957 users, 4,082 apps | Context-aware app usage (time, location, weather features) |
| **Mobile Device Usage** (Kaggle) | — | Lock/unlock, app-switch patterns |
| **SCBench** (GitHub) | — | Shared-context benchmark for multi-agent LLM |
| **OpenMobileContextEnvSense** (GitHub) | Sample dataset + sensing framework | Environmental context (indoor/outdoor, transport mode) |
| **Synthetic** (generate) | Any scale | Cold-start simulation, FL simulation |

**Strategy**: Start with Frappe + Myket for offline GRU training; collect real usage logs on test device for fine-tuning; generate synthetic users at scale for FL simulation.

---

## 4. Phased Implementation Plan

```
┌──────────────────────────────────────────────────────────────┐
│ PHASE 1: Foundation & Simulation (Weeks 1-4)                 │
├──────────────────────────────────────────────────────────────┤
│  Week 1: Project scaffold + GRU training pipeline             │
│    - Python project structure (poetry/uv)                     │
│    - GRU model: Embedding(128) → GRU(64) → Dense(softmax)    │
│    - Train on Frappe + Myket datasets                        │
│    - Export INT8 TFLite; measure ~3 ms inference on Pixel 7  │
│    - ~100 KB model size target                               │
│                                                                │
│  Week 2: Heuristic ARC memory manager prototype               │
│    - C++ implementation of context-weighted ARC               │
│    - PSI monitor thread (poll /proc/pressure/memory)         │
│    - cgroups v2 wrapper (memory.high, memory.min)             │
│    - zRAM compaction trigger                                  │
│    - Unit tests with synthetic memory pressure                │
│                                                                │
│  Week 3: KV-cache compression module prototype                │
│    - Port KIVI-style INT4 quantization (per-channel K,        │
│      per-token V) to C++                                      │
│    - Implement 16KB block paged allocation                    │
│    - Hook into llama.cpp KV cache interface                   │
│    - Verify output parity with FP16 baseline                  │
│                                                                │
│  Week 4: Thermal monitor + integration test                   │
│    - AThermal_getThermalHeadroom() polling loop               │
│    - 4-state thermal machine (COOL/WARM/HOT/CRITICAL)        │
│    - Integration test: ARC + GRU + Thermal + KV together      │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ PHASE 2: Android Integration (Weeks 5-8)                     │
├──────────────────────────────────────────────────────────────┤
│  Week 5: Android service daemon (C++ via JNI)                 │
│    - Fork/modify AOSP LMKD skeleton                          │
│    - GRU inference via TFLite C API on background thread      │
│    - Confidence-gated decision bus (≥60% threshold)          │
│    - Property-based config (ro.camms.*)                      │
│                                                                │
│  Week 6: App preloading + compaction actions                  │
│    - Predictive preload: readahead on predicted app's pages   │
│    - Working-set monitor via smaps_rollup per-process PSS     │
│    - Proactive zRAM compaction before app switch              │
│    - Fallback: if GRU <60%, pure ARC heuristics               │
│                                                                │
│  Week 7: FedAvg + Flower integration                          │
│    - Flower server (Python) with FedAvg strategy              │
│    - Flower Android client (Kotlin) with TFLite training      │
│    - Gradient sparsification (top-1%) + INT8 quantization     │
│    - DP: ε=8 Gaussian noise at server                         │
│    - Simulation with 100 synthetic clients on desktop         │
│                                                                │
│  Week 8: End-to-end on-device test                           │
│    - Full pipeline on Pixel 6/7 or emulator                   │
│    - Measure: launch latency, thrashing events, battery drain │
│    - Compare: stock Android vs CAMMS                          │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ PHASE 3: Hardening & Evaluation (Weeks 9-12)                 │
├──────────────────────────────────────────────────────────────┤
│  Week 9: Thrashing-proof working-set monitor                  │
│    - Denning's model: track working-set size per app          │
│    - Proactive compaction when θ > RAM threshold             │
│    - Handle multi-app scenarios (split-screen, PIP)          │
│                                                                │
│  Week 10: FL production hardening                             │
│    - Idle/charging/Wi-Fi gating for FL rounds                 │
│    - Differential privacy budget management                   │
│    - Model staleness detection and recovery                   │
│                                                                │
│  Week 11: Comprehensive benchmarking                          │
│    - Microbenchmarks per module (inference latency,           │
│      compaction time, quantize/dequantize throughput)         │
│    - System benchmarks vs stock LMKD                          │
│    - Thermal stress test (30-min sustained inference)         │
│                                                                │
│  Week 12: Documentation + evaluation metrics                  │
│    - Paper-style evaluation report                            │
│    - API documentation                                        │
│    - Reproduction guide for reviewers                         │
└──────────────────────────────────────────────────────────────┘
```

---

## 5. Architecture Overview

```
┌─────────────┐     ┌──────────────────────┐     ┌──────────────┐
│  Thermal    │────▶│  Decision Arbiter     │◀────│  GRU Predict │
│  Monitor    │     │  (confidence-gated)   │     │  (TFLite)    │
└─────────────┘     └──────────┬───────────┘     └──────────────┘
                               │
          ┌────────────────────┼────────────────────┐
          ▼                    ▼                    ▼
   ┌──────────────┐   ┌───────────────┐   ┌──────────────────┐
   │ ARC Cache    │   │ Working-Set   │   │ KV-Cache Module  │
   │ (dual-list)  │   │ Monitor       │   │ (INT4 + paged)   │
   └──────┬───────┘   └───────┬───────┘   └────────┬─────────┘
          │                   │                     │
          ▼                   ▼                     ▼
   ┌──────────────────────────────────────────────────────┐
   │  Android Kernel Interfaces                            │
   │  ┌─────────┐ ┌──────────┐ ┌─────┐ ┌──────────────┐  │
   │  │ cgroups │ │  zRAM    │ │ LMK │ │ PSI (/proc)  │  │
   │  │ v2 mem  │ │  sysfs   │ │     │ │              │  │
   │  └─────────┘ └──────────┘ └─────┘ └──────────────┘  │
   └──────────────────────────────────────────────────────┘
```

---

## 6. Evaluation Matrix

| Metric | Target | Measurement Method |
|---|---|---|
| App cold-launch latency | **-40% P95** vs stock | `am start -W` with/without CAMMS |
| Thrashing events | **0** under normal load | `workingset_refault` in /proc/vmstat |
| LMK kill count | **-60%** vs stock | `dumpsys activity exit-info` |
| GRU inference | **<5 ms** per prediction | TFLite benchmark API |
| GRU model size | **<500 KB** (target 100 KB) | `ls -lh model.tflite` |
| KV-cache memory | **-75%** vs FP16 | llama.cpp memory reporting |
| KV-cache output quality | **<1%** perplexity degradation | WikiText-2 PPL comparison |
| FL round time | **<10 min** per round (100 clients) | Flower server logs |
| FL DP guarantee | **ε ≤ 8** | Opacus/TF Privacy accountant |
| Thermal headroom | **<0.85** during normal operation | `AThermal_getThermalHeadroom()` |
| Battery overhead | **<2%** daily drain | Battery historian |
| Memory overhead | **<20 MB** steady-state | RSS from smaps_rollup |

---

## 7. Project Directory Structure

```
camms/
├── README.md
├── pyproject.toml                    # Python project (training, FL server)
├── CMakeLists.txt                    # C++ daemon
├── android/                          # Android app project
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/camms/       # Kotlin: Flower client, Thermal listener
│   │   │   ├── native/               # JNI bridge to C++ daemon
│   │   │   └── assets/               # TFLite model + population-prior
│   │   └── build.gradle.kts
│   └── camms-daemon/                 # Native shared library (C++)
│       ├── include/
│       │   ├── arc_cache.h           # Context-weighted ARC
│       │   ├── working_set.h         # Denning's model
│       │   ├── psi_monitor.h         # PSI pressure monitor
│       │   ├── thermal_monitor.h     # AThermal wrapper
│       │   ├── kv_cache_compress.h   # INT4 + paged KV cache
│       │   └── confidence_gate.h     # GRU output → fallback
│       ├── src/
│       └── test/
├── fl_server/                        # Flower federated server
│   ├── server.py                     # FedAvg with DP aggregation
│   ├── strategy.py                   # Custom compressed strategy
│   └── simulation.py                 # 100+ synthetic clients
├── training/
│   ├── gru_model.py                  # Keras GRU definition
│   ├── train_frappe.py              # Training on Frappe dataset
│   ├── train_myket.py               # Training on Myket dataset
│   ├── tflite_export.py             # INT8 quantization + export
│   └── benchmark.py                 # Accuracy, latency, size eval
├── benchmarks/
│   ├── microbenchmarks/              # Per-module latency/throughput
│   └── system_tests/                 # Full pipeline on-device
├── docs/
│   ├── architecture.md
│   └── evaluation.md
└── datasets/
    ├── frappe/                       # Frappe x1 data
    └── myket/                        # Myket install data
```

---

## 8. Cold-Start Strategy

```
Days 1-3:  Population prior only (72% top-3 coverage)
Days 4-5:  Collect usage logs; GRU starts predicting at 50% confidence
Day 6-7:   Model accumulates history; confidence reaches ≥60% → preloading activates
Day 8+:    Full CAMMS operation; FL round uploads local deltas
```

---

## 9. Key Technical Risks & Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| GRU confidence never reaches 60% for some users | Medium | Lower to 50% after 14 days; fallback always safe |
| Kernel modifications for PTE scanning blocked | High | Use MGLRU + DAMON sysfs instead; no kernel patch needed |
| zRAM compaction causes UI jitter | Medium | Slice into ≤5ms chunks; skip if thermal > WARM |
| Flower Android SDK incompatible with latest Flower | Low (temporary) | Use Flower `v1.28` stable; test before upgrading |
| TFLite GRU op unsupported on older devices | Low | Use Flex delegate; fallback to CPU-only mode |
| FL training drains battery | Medium | Gate on idle+charging+WiFi; max 200 mAh/session |
| Differential privacy degrades model accuracy | Medium | Tune ε/δ budget; expect 5-10% accuracy loss at ε=8 |

---

## 10. First Steps (Day 1)

1. **Set up project scaffold** — the directory structure above
2. **Train baseline GRU** on Frappe dataset, export INT8 TFLite
3. **Implement ARC cache** in C++ with unit tests
4. **Write PSI monitor** — poll `/proc/pressure/memory`, trigger ARC actions
5. **Build end-to-end integration test** on single Android device (no FL yet)

---

## 11. Stress Test Analysis & Critique Responses

### 11.1 Fault: ML Advisor Bottleneck

**Problem**: The ML advisor has no direct memory-control authority, acting only through the heuristic layer. Under extreme pressure the heuristic foundation may ignore even highly confident predictions, creating a bottleneck of influence.

**Stress Test — Fast-Switching Conflict**: A user rapidly toggles between four high-memory apps (4K camera, heavy game, video editor, browser) within 30 seconds. Can the integration bus handle rapid-fire signals without becoming a CPU bottleneck itself?

**Industry Suggestion Applied**: Replace the binary "ML vs. Heuristic" decision with **dynamic ARC weight tuning** — the ML advisor tunes ARC cache eviction priority weights instead of issuing direct commands. This lets the ML influence safe heuristics without needing direct control, reducing integration bus complexity and avoiding the bottleneck.

**Implementation**: GRU outputs an app-specific "boost factor" [0, 1] applied to ARC's ghost-list promotion weight. At confidence <60%, boost factor = 0 (pure heuristic). Integration bus signals are app-switch events + confidence scores — no new IPC channel needed.

---

### 11.2 Fault: GRU Cold-Start & Model Frustration

**Problem**: The system requires a 7-day calibration window — a feature that does nothing for the first week is perceived as bloatware. Also, a user with random patterns may hover at 55% confidence forever, consuming 2% CPU budget on never-used predictions.

**Stress Test — Unpredictable User**: A traveler in a new time zone using random local apps. GRU confidence stays at 55% (below 60% gate). Does the system waste CPU on predictions it never uses?

**Industry Suggestion Applied**: Implement a **Multi-Tiered Predictor**:
1. **Tier 1 (Hours 0–48)**: Zero-overhead Markov Chain (trigram transition table, <10 KB) — provides immediate basic benefit.
2. **Tier 2 (Days 3–7)**: Population-prior augmented GRU at relaxed 50% confidence gate.
3. **Tier 3 (Day 8+)**: Full GRU at 60% gate + FL personalization.

If the GRU hasn't crossed 60% in 14 days, the system drops back to Tier 1 permanently for that user and frees the GRU memory — no wasted CPU for "model frustration."

---

### 11.3 Fault: Kernel-Level Working-Set Monitor Maintenance

**Problem**: Relying on page-table access-bit scans and custom kernel patches is a massive maintenance burden across Android OS updates and SoC variants.

**Stress Test — UI Micro-Stuttering**: Run a 120Hz game while the Working-Set Monitor performs proactive compaction. Does kernel-CPU cost of PTE scanning trigger a frame drop even with 5ms slicing?

**Industry Suggestion Applied**: Move away from custom kernel patches. Use:
- **`process_mrelease()`** syscall (Linux 5.12+, Android 12+) for fast process death without kernel patches.
- **cgroups v2 `memory.high`/`memory.min`** for per-app memory limits — standard in Android 10+.
- **MGLRU sysfs** (`/sys/kernel/mm/lru_gen/`) for generation-based working set estimation — standard in Linux 6.1+ / Android 14+.
- **PSI `/proc/pressure/memory`** for thrashing detection — no kernel mods needed.

PTE scanning and proactive compaction are moved to userspace via DAMON sysfs where available, falling back to `smaps_rollup` polling. This drops the jank risk from high to negligible by eliminating kernel-side scanning entirely.

---

### 11.4 Fault: FL Stale Model & Battery Waste

**Problem**: FL requires idle + charging + Wi-Fi — rare on many devices. After two weeks without updates, does the model predict badly enough that confidence never hits 60%, wasting all FL overhead?

**Stress Test — Stale Model Trap**: User's habits change but the model doesn't update for 14 days. Accuracy degrades, gate is never breached, FL overhead has zero benefit.

**Industry Suggestion Applied**: Implement **Delta-Updates via High-Surprise Events**:
1. Track "surprise" — when the user launches an app the model assigned <10% probability.
2. Buffer up to 50 surprise events locally.
3. FL round only uploads **surprise-event deltas** (gradients computed on recently surprising transitions), not full model updates.
4. This reduces training data per round by ~90%, so shorter training windows (<30 seconds, ~20 mAh) — can complete during brief idle + Wi-Fi periods.

If the device hasn't completed an FL round in 7 days, the model falls back to population prior + Tier 2 GRU (relaxed 50% gate) to prevent usability degradation.

---

### 11.5 Fault: KV-Cache Quantization Output Degradation

**Problem**: INT4 quantization can cause perplexity degradation, making the model "dumber" or prone to hallucination. Paged allocation (16KB blocks) may add overhead that negates speed gains at large contexts.

**Stress Test — Long-Context Hallucination**: Agent summarizing a 20-page PDF with compressed KV cache. Does paged allocation overhead negate compression speed gains when context window is nearly full?

**Industry Suggestion Applied**: Adopt **Mixed-Precision Quantization**:
- **Hot tokens** (last N tokens where N ~ cache_capacity / 8): kept in FP16 for active reasoning.
- **Cold tokens** (all earlier tokens): quantized to INT4 per-channel.
- **Paged blocks**: 16KB blocks only for cold tokens; hot tokens use contiguous FP16 buffers.
- Adaptive page size: when context > 80% of capacity, page size drops from 16KB to 4KB to reduce internal fragmentation.

This preserves output quality (active reasoning tokens stay FP16) while keeping 70-75% aggregate memory savings. Measured perplexity degradation target: <0.5% vs FP16 baseline.

---

### 11.6 Fault: Thermal Throttling Indefinite Degradation

**Problem**: Thermal thresholds vary wildly by phone model. In a 40°C environment, the system may stay throttled indefinitely — preloading and FL turn off with no graceful recovery path.

**Stress Test — Ambient Heat Overload**: Phone at 40°C ambient. All CAMMS preloading/training stops. User gets no adaptive benefit for the duration.

**Industry Suggestion Applied**: Use **Predictive Thermal Scaling** instead of binary on/off:
1. Read `AThermal_getThermalHeadroom(forecastSeconds=60)` to predict thermal trajectory.
2. Three degradation levels, not just "on/off":
   - **Level 1 (headroom 0.3–0.6)**: Scale down preloading to 50% of predicted apps; allow FL at reduced batch size.
   - **Level 2 (headroom 0.6–0.85)**: Only preload the single most-likely app; pause FL; allow compaction.
   - **Level 3 (headroom >0.85)**: No preloading, no FL, no compaction. Pure heuristic ARC-only.
3. Recovery: when headroom drops below 0.3 for 30+ seconds, escalate one level down each 10-second cooldown.

This replaces the sharp "defer/don't defer" cutoff with a smooth performance continuum, so users in hot environments still get partial benefit rather than the system going completely dark.
