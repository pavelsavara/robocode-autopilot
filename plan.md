# robocode-autopilot — Project Plan (v5)

*Updated: 2026-05-10 · Previous plans: [archive/2026-05-10-plan.md](archive/2026-05-10-plan.md), [archive/2026-05-04-plan.md](archive/2026-05-04-plan.md)*
*Latest sprint: 20 · Score: 10.2% · In-game R²: +0.48*
*Companion design review (full code audit, 2026-05-10): [archive/2026-05-10-design-proposals.md](design-proposals.md)*

## Vision

Build a competitive Robocode 1v1 robot powered by offline-trained ML models
distilled to Java. The robot uses a multi-strategy architecture with virtual
guns, competing movement strategies, and energy-aware strategy layer.

## Current Performance (16-opponent eval, 2026-05-10)

| Metric | Sprint 9 (baseline) | Sprint 20 (current) | Target |
|---|---|---|---|
| Overall score % | 6.1% | **10.2%** | >50% vs top-50 |
| Battle win rate | 0.0% | 0% | >30% |
| Our hit rate | 3.5% | 3.6% | >10% |
| Opponent hit rate | ~46% | ~40% | <20% |
| Fire power R² (in-game) | −3.67 | **+0.48** | >0.7 |
| Skipped turns/battle | 0.6 | 0.0 | 0 |
| CI eval pipeline | ❌ | ✅ | ✅ |

## Completed Phases (archived — see retrospectives in `archive/`)

Phases 1–8 (rumble, pipeline, notebooks, GBM models, architecture, distillation),
Phase 9–11 (wire predictions, local pipeline, iterative improvement),
Phase 12 (fix broken systems: 23 NaN features, O(1) rolling stats, process improvements),
Sprints 13–18 (sprint loop: score 6.1%→9.1%, R² −3.67→+0.48, VCS fixes, wave surf).

---

## Active Phase: Competitive Improvement Campaign

### Workstream A: CI Offload (Amos — HIGH PRIORITY)

Offload battles to GitHub Actions to unblock local development.
Existing infra: `run-season.yml`, `Dockerfile.battle`, `run-battle.mjs`.
**Constraint:** ~30 Mbps WiFi — minimize data transfer.

1. **Create `eval-sprint.yml` workflow** triggered on push to `main`.
   - Build robot JAR in CI
   - Run battles in matrix (4 runners × 8 opponents each = 32 opponents)
   - Compute `summary.json` in CI, download only that (~2 KB) — NOT recordings
   - Post per-opponent score table as commit status or PR comment
2. **Self-battle job** — add `cz.zamboch.Autopilot` as opponent.
   Sanity check: score must be 48–52%. Skew indicates position/init bug.
3. **Local fallback** — keep `local-pipeline.ps1` working. Use `-EvalOnly`
   locally while CI handles full pipeline.

### Workstream B: Feature Divergence Resolution (Naomi)

In-game R² is +0.48 but offline is 0.91. The remaining features with
Java/Python divergence cap targeting accuracy.

1. **Run FeatureLogger diagnostic** — scripts ready since Sprint 11.
   Execute `compare_features.py` against a diagnostic battle. Identify
   top-10 divergent features.
2. **Fix top divergent features** — target per-feature correlation ≥ 0.95.
3. **Statistical commentary in retrospectives** — Naomi writes a section
   in every retro analysing trends, variance, per-opponent anomalies.
4. **Feature catalog research** — each sprint, Naomi researches one
   unimplemented feature from [archive/2026-05-01-features.md](archive/2026-05-01-features.md)
   and writes an analysis of its potential value in the retrospective.
5. **Add `OPPONENT_INFERRED_GUN_HEAT` in-game producer** — the feature is
   declared in `Feature.java`, listed as a dependency by
   `GbmFireTimingPredictor.getDependencies()`, and produced by the pipeline
   class `OpponentPredictionOfflineFeatures`, but **never set in-game**.
   `MlDerivedFeatures.processOpponentPrediction()` only writes
   `OPPONENT_WALL_AHEAD_DISTANCE` today. Add the gun-heat formula
   `max(0, (1 + lastFirePower/5) − elapsed × gunCoolingRate)` to that
   method and **retrain `fire_timing` with the new column included** —
   the model's current `FEATURE_NAMES` does not list it, so the trained
   tree never splits on it. ([design-proposals.md §5](design-proposals.md))

### Workstream B2: CI Full Pipeline — Automated Retrain Loop

Automate the complete cycle in CI so that the AI squad's role reduces to
code/notebook changes and retrospectives — no manual local pipeline runs.

**Goal:** Push code → CI battles → CI generates CSV → CI retrains models →
CI exports to Java → next push uses new models. Humans review results
and make decisions; machines do the grinding.

**Prerequisites:** Workstream B (feature divergence) should close the R² gap
first — retraining on misaligned features wastes compute.

1. **Wire `Dockerfile.pipeline`** into a `retrain.yml` workflow.
   The image already exists; needs a workflow that:
   - Downloads recordings from battle jobs (intra-CI, not to local)
   - Runs the Java pipeline to produce CSV
   - Runs `_run_fire_timing.py`, `_run_multiwave.py` etc. to retrain
   - Exports models via `export_gbm_java.py`
   - Opens a PR with updated `*Data.java` files
2. **Cloud dataset accumulation** — store CSV outputs as workflow artifacts
   or on a `data` branch (Git LFS). Each CI run appends new battles;
   retraining uses the accumulated dataset, not just the latest sprint.
3. **Trigger rules** — retrain on `workflow_dispatch` (manual) initially.
   Later: auto-retrain when accumulated battles exceed a threshold
   (e.g. 100 new battles since last train).
4. **Validation gate** — after retraining, run a quick 5-opponent eval
   with the new model. If overall score drops >2pp vs baseline, block
   the PR and flag for human review.
5. **Data transfer budget** — CSV stays in CI. Only `summary.json` and
   the model PR diff cross the wire. Recordings are ephemeral.

### Workstream C: Movement (Alex — EVERY 3RD SPRINT MINIMUM)

Opponent HR ~40% is the biggest gap. Movement received only 2 tasks
in 10 sprints despite being the binding constraint.

1. **Improve wave surf danger scoring** — use per-opponent VCS profiles
   for precise GF danger assessment.
2. **Implement true precise prediction** — simulate exact future positions
   including wall bouncing and deceleration.
3. **Anti-profiling (GF flattening)** — randomize dodge direction when no
   wave is imminent.

### Workstream D: Targeting (Bobbie)

Hit rate 3.6% is extremely low. VCS gun now has correct segments
(Sprint 16 fix) but hasn't outperformed CircularGun yet.

1. **Histogram smoothing** — kernel density estimation on VCS histograms.
2. **Use predicted fire power in bullet speed** — VCS and CircularGun
   should use ML fire power prediction for wave speed calculation.
3. **More VCS segments** — add velocity bucket and acceleration.
4. **Register `VcsSamplingGun`** — anti-profiling counter (probabilistic
   GF sampling vs `VcsGun`'s peak-firing). The class is fully implemented
   in `core.gun` but not wired in `Autopilot.createGunManager()`. Insert
   between `VcsGun` and `PredictiveGun`; the VGM hit-rate selector will
   pick whichever variant works against the current opponent. Verify in
   the retrospective that performance does not regress against simple
   opponents. ([design-proposals.md §6](design-proposals.md))

### Workstream E: Opponent Expansion (Amos)

Expand evaluation from 16 to 32 opponents for broader competitive coverage.
Download and validate 16 new opponent JARs from Robocode Archive.
Add archetype coverage: ram bots, surfers, spinners, nano bots.

### Workstream F: Code Quality & Test Coverage (Holden + Test Author)

Picked from the 2026-05-10 design review ([design-proposals.md](design-proposals.md)).
These are *known bugs* and *test debt*, not feature work.

#### F1. Known bugs (one per sprint, Holden owns)

1. **Move `WindowFeatures` state to `Whiteboard`** — currently the class
   carries mutable `double[][]` ring buffers + running sums as fields,
   violating the "all inter-tick state in `Whiteboard`" rule from
   [.github/copilot-instructions.md](.github/copilot-instructions.md).
   Fix: add `WindowState` to `Whiteboard`, make `WindowFeatures` a pure
   `final` strategy. ([design-proposals.md §3](design-proposals.md))
2. **Boot-time dependency check in `Transformer.resolveDependencies()`** —
   after the topological sort, assert every declared dependency is either
   produced by a registered feature or on the raw-input whitelist
   (`OUR_X`, `TICKS_SINCE_SCAN`, etc.). Catches the
   `OPPONENT_INFERRED_GUN_HEAT` class of bug at JAR load instead of at
   the next retraining run. ([design-proposals.md §4](design-proposals.md))
3. **`Whiteboard.setFeature` rejects `Double.isInfinite`** — NaN is
   already the documented "missing" sentinel; one bad cosine in a feature
   class would propagate ±∞ silently into the GBM model. Five-line
   guard. ([design-proposals.md §9](design-proposals.md))
4. **`GbmTreeEnsemble` dimension assertion** — fail loudly at construction
   if `featureIndex.length ≠ FEATURE_NAMES.length`, to catch future drift
   between the embedded model and the loader. ([design-proposals.md §9](design-proposals.md))

#### F2. Test backlog T1–T10 (one per sprint, Test Author owns)

Distributed across the next 10 sprints in priority order. Each item is one
focused JUnit test class. Full rationale in
[design-proposals.md §2](design-proposals.md).

| Order | Test | Catches |
|---|---|---|
| T2 | `FeatureMappingTest` | The 23-NaN-features bug class (Sprint 10 root cause). |
| T3 | `TickBudgetTest` | The Sprint 12 one-way-ratchet bug; ceiling recovery; persistence round-trip. |
| T5 | `EnergyRatioStrategyComputerTest` | Kill-shot logic, distance scaling, predicted-fire-power dodge urgency. Strategy regression net. |
| T1 | `GbmTreeEnsembleTest` | Tree-truncation off-by-one, base-score, NaN-as-missing routing vs Python reference. |
| T4 | `PersistenceManagerRoundTripTest` | Schema drift, version-mismatch handling, per-section failure isolation. |
| T6 | `MovementStrategyManagerTest` | First-pass rotation, EMA selection, persistence of `activeIndex`. |
| T7 | `VcsHistogramStoreTest` | LRU eviction at 30 entries, `loadInto` array copy, persistence. |
| T8 | `WindowFeaturesTest` | Mean / std vs pandas `rolling().std(ddof=1)` reference; reset behaviour. |
| T9 | `MultiWaveFeaturesTest` | Wave creation on detected fire, prune-and-update-VCS-on-pass. |
| T10 | `EnvelopeFeaturesTest` | `ENVELOPE_FILL_RATIO`, `REACHABLE_GF_RANGE`; corner vs centre cases. |

---

## Backlog (post-R²-grind)

- **Finish Phase 8: MLP GF Targeting.** `MlpGfTargeting` currently returns a
  uniform `[1/61]×61` distribution and is not consumed. Tackle this as the
  *last* step before the team returns to R² grinding (per coordinator
  decision 2026-05-10): train the MLP, generate `MlpGfTargetingModel.java`
  with weight matrices, wire into `VcsGun.peakSmoothed()` as a Bayesian
  prior `P(GF) = λ·MLP(GF) + (1−λ)·VCS(GF)`, `λ = 50/(50+n)`. Plan in
  [archive/2026-05-02-plan-mlp-targeting.md](archive/2026-05-02-plan-mlp-targeting.md).
  ([design-proposals.md §7](design-proposals.md))

---

## Honest ML Baselines (Sprint 18)

| Task | Model | Metric | Offline | In-Game |
|---|---|---|---|---|
| Fire power | XGBoost (200t) | R² | **0.913** | **+0.48** |
| Movement N=5 | GBM-window (200t) | R² | **0.760** | — |
| Fire timing | GBM-window (200t) | AUC | **0.815** | — |

---

## Key Decisions Log

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Java 8 target | Required for Robocode engine classloader |
| 2 | Stateless features | All inter-tick state in Whiteboard |
| 3 | 20-tick sliding windows | Key innovation for all ML tasks |
| 4 | Base64-embed models, no file I/O | Robocode sandbox blocks getResourceAsStream |
| 5 | Compact 200-tree models | 500KB budget; full 800-tree too slow |
| 6 | Orbit-primary movement | Constant wave surf oscillation hurts |
| 7 | CircularGun as primary | Best general-purpose; HeadOnGun demoted |
| 8 | Fix broken systems before features | Decision #13 — still applies |
| 9 | O(1) rolling stats | PrimitiveRollingBuffer aligns Java/Python |
| 10 | VCS segments at fire time | Lateral direction + distance captured correctly |
| 11 | VCS-guided orbital direction | Use wave danger histograms for non-imminent waves |
| 12 | Process improvements (Sprint 12) | Archive recordings, incremental CSV, sprint-only sanity |
| 13 | Coordinator leads planning/retro | Ralph PM role folded into coordinator |
| 14 | Naomi: statistical analysis + feature research | Per-sprint commentary + catalog research |
| 15 | Movement work every 3 sprints | Mandate — opponent HR is binding constraint |
| 16 | Amos owns CI pipeline | GH Actions offload, minimize data transfer |
| 17 | Expand to 32 opponents | Broader competitive coverage + archetype diversity |
| 18 | Add OPPONENT_INFERRED_GUN_HEAT in-game producer | Closes a feature-divergence gap caught in 2026-05-10 design review |
| 19 | Register VcsSamplingGun in VGM | Anti-profiling counter; let the hit-rate selector A/B between peak and sampling |
| 20 | Move WindowFeatures state to Whiteboard | Re-establish the "all state in Whiteboard" invariant; re-enables future per-instance refactors |
| 21 | Unit test backlog T1–T10 | One test class per sprint; pins Sprint 10 / Sprint 12 root-cause bugs and four under-tested classes |
| 22 | MlpGfTargeting deferred to backlog | Not the binding constraint while in-game R² < 0.7; revisit before next R² grind |
| 23 | Automate full retrain loop in CI (B2) | AI squad focuses on code/notebook changes and retrospectives; machines do the grinding |

---

## Documentation Index

- **Architecture:** [wiki/architecture.md](wiki/architecture.md)
- **Wiki:** [wiki/](wiki/) — physics, features, leakage, ML results, pipeline, strategy
- **Archive:** [archive/](archive/) — historical planning documents
- **Sprint process:** [sprint.md](sprint.md)
- **Team:** [team.md](team.md)
- **Copilot instructions:** [.github/copilot-instructions.md](.github/copilot-instructions.md)
