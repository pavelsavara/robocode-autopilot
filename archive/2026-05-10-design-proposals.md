# Design Proposals — robocode-autopilot

*Written 2026-05-10 after a full code review (core + robot + pipeline + tests).
The wiki has been updated to match the actual code; this document collects
forward-looking proposals and the open questions that have to be answered
before any of them ship.*

---

## 1. Executive summary of what I read

| Module | Files | Source size | Tests | Verdict |
|---|---|---|---|---|
| `core/` | 60 | 354 KB | 13 (~1.4 K lines) | Solid foundation. Stateless feature pipeline, virtual gun manager, and reachable-envelope generator are mature. |
| `pipeline/` | 28 | 97 KB | 24 (~3.5 K lines) | Best-tested module. Each offline feature class has its own unit test + a `FeatureIntegrationTest` parity check. |
| `robot/` | 15 | 1.36 MB (~1.3 MB Base64 model data) | 2 | Wiring + four large embedded blobs (`FirePowerData`, `MovementData`, `FireTimingData`, `DefaultDataFile`). Smoke test + model-loading test only. |

**What works well**
- `Feature` enum + array-backed `Whiteboard` storage gives O(1) feature read/write with no hashing per tick. The `Transformer` does a topological sort once and then iterates a `List<IInGameFeatures>` per tick — this is the right shape.
- `GbmTreeEnsemble` is a flat-array interpreter. No generated code, no per-tick allocation, and it supports adaptive tree-count truncation through `TickBudget` — a real, useful knob.
- `ReachableEnvelope` pre-computes ~9 byte arrays of 2-px-grid offsets per |velocity|, ships them as `static final byte[]` (not Base64), and uses zero-allocation scan helpers. Genuinely good.
- The pipeline writes three CSVs per perspective with a clean separation between inputs (`ticks.csv`, `waves.csv`) and outcomes/constants (`scores.csv`). The `_loader.py` whitelist of merge-safe columns is the right line of defense against tautological ML.
- 50-shot rolling hit-rate window inside `VirtualGunManager` plus the priority-aware ε-tie-break (3% epsilon) is a robust, almost-no-tuning gun selector.

**What I found drifting between code and docs**
- `wiki/architecture.md` listed the gun strategies in the wrong order, used ε=0.01 instead of the actual `HIT_RATE_EPSILON = 0.03`, said "50-bullet" pool when the code uses 64, "15-45 ticks" reversal interval when the code uses 25-55, and quoted the wrong fire-power distance thresholds. **Fixed in this commit.**
- `wiki/ml-results.md` was a sprint behind plan.md (R²=0.862 vs 0.913, no in-game number, no per-feature lift table). **Fixed in this commit.**

**What I found drifting in the code itself**
- `OPPONENT_INFERRED_GUN_HEAT` is declared in the `Feature` enum, computed by the pipeline-only `OpponentPredictionOfflineFeatures`, and listed as a dependency by `GbmFireTimingPredictor.getDependencies()`, but **no in-game producer ever sets it**. `MlDerivedFeatures.processOpponentPrediction` only sets `OPPONENT_WALL_AHEAD_DISTANCE`. The fire-timing model's `FEATURE_NAMES` does not include it either, so the trained tree never splits on it. The only consumer is the heuristic fallback path that runs when the model fails to load. Effectively it is dead in-game. (See proposal §5.)
- `WindowFeatures` keeps mutable `double[N_FEATURES][WINDOW]` ring buffers and running sums *as fields on the feature class*. Every other feature is stateless and stores inter-tick state in the `Whiteboard`. This violates the rule documented in `.github/copilot-instructions.md`. The class is `final` and the bug doesn't manifest because we use a single instance per battle, but it makes the rest of the system irregular. (See proposal §3.)
- `VcsSamplingGun` is a fully implemented anti-profiling variant of `VcsGun`. It is imported by `Autopilot.java` (line 24) but **not registered** in `createGunManager()`. Either drop it or actually use it. (See proposal §6.)
- `GbmFirePowerPredictor` / `GbmMovementPredictor` / `GbmFireTimingPredictor` each carry a `@Deprecated` `loadModel()` method that no longer does anything; the model load happens in the static block. Cosmetic, but the deprecation has been there for a while.
- `MlpGfTargeting` returns a uniform [1/61, 1/61, …] distribution. Nothing reads it. It is real surface area for no benefit. (See proposal §7.)

**What is genuinely under-tested**
- No test for `GbmTreeEnsemble` numerical correctness against a Python reference.
- No test for `FeatureMapping.extract()` (the bridge between CSV column names and the in-game `Feature` enum).
- No test for `TickBudget` — yet a `TickBudget` ratchet bug cost the project ~1 month of broken in-game R² (see Sprints 9–12 retrospectives).
- No round-trip test for `PersistenceManager` (write → read → assert state equal). The "DATA_LOAD … status" log is the only feedback signal in production.
- No test for `EnergyRatioStrategyComputer` (kill-shot logic, distance scaling, predicted-fire-power dodge urgency boost). One unit test would prevent a whole class of strategy regressions.
- `Autopilot.java` itself — the actual robot wiring — is exercised only by the `AutopilotSmokeTest`, which mostly checks that the JAR loads.

These gaps map onto a proposal in §2.

---

## 2. Test coverage proposal

In priority order. Each item is one focused JUnit test class, no integration harness needed.

| # | What | Why | Cost |
|---|---|---|---|
| T1 | `GbmTreeEnsembleTest` | Pin a small synthetic ensemble (3 trees, hand-computed leaves) and assert `predictRaw(features, maxTrees)` matches expected sums for `maxTrees ∈ {1, 2, 3, 999}`. Catches off-by-one in tree-truncation, base-score accounting, and NaN-as-missing routing. | 1 file |
| T2 | `FeatureMappingTest` | `buildIndex(["distance", "unknown_thing", "our_x"])` → `[Feature.DISTANCE, null, Feature.OUR_X]`. `extract()` puts NaN for nulls and unset features. This is the bridge that silently broke for ~23 features in Sprint 10. | 1 file |
| T3 | `TickBudgetTest` | The Sprint 12 ratchet bug deserves a permanent guard: skip during round 0 tick<10 → ignored; skip during round 1 → halves; 200 skip-free ticks → ceiling +1; persistence round-trip. | 1 file |
| T4 | `PersistenceManagerRoundTripTest` | Save → load → verify each `IPersistable` section restores byte-equal. Today, version mismatch + corrupt-length both go to a log line; a round-trip test catches schema drift before a battle. | 1 file |
| T5 | `EnergyRatioStrategyComputerTest` | Fire-power table-driven test: (ourEnergy, oppEnergy, distance) → (firePower, aggression, randomWaveSelection). Especially the kill-shot edge cases (opp < 0.5 → 0.1; opp < 4 → opp/4). | 1 file |
| T6 | `MovementStrategyManagerTest` | Round-rotation in first N rounds, EMA selection after, persistence of `activeIndex` + `avgDamage[]`. | 1 file |
| T7 | `VcsHistogramStoreTest` | LRU eviction at 30 entries; `loadInto()` writes through to Whiteboard arrays correctly; round-trip via `IPersistable`. | 1 file |
| T8 | `WindowFeaturesTest` | Add 30 known values, query mean/std at window=10, window=20, assert against Python pandas `rolling().std(ddof=1)`. Plus a reset test. | 1 file |
| T9 | `MultiWaveFeaturesTest` | Detect fire on energy drop → wave added with correct `originX/Y, fireBearing, fireLateralDir`. Wave passes target → removed and VCS bin incremented. | 1 file |
| T10 | `EnvelopeFeaturesTest` | At centre-of-field with v=8: `ENVELOPE_FILL_RATIO ≈ 1.0`, `REACHABLE_GF_RANGE > 0` when a wave exists, both 0 in corner. | 1 file |

**Estimated effort:** one focused day each, parallelizable.

**What I would not write as a unit test:** the in-game R² gap. That belongs in the `intuition/retrospective/` notebooks and the `compare_features.py` diagnostic, not in JUnit.

---

## 3. Make `WindowFeatures` stateless (or mark it as the documented exception)

Two equivalent fixes. Pick one, then enforce it with an `IInGameFeatures` interface assertion.

**Option A — move state to `Whiteboard`** (consistent with every other feature class).
Add to `Whiteboard`:

```java
private final WindowState windowState = new WindowState(N_FEATURES, WINDOW);
public WindowState getWindowState() { return windowState; }
```

Then `WindowFeatures.process()` reads/writes `wb.getWindowState()` and the class itself is a pure `final` strategy with no fields. This is the right thing to do — and it makes per-perspective windowing in `Player.processTurn` automatic, because each perspective has its own `Whiteboard`.

**Option B — explicitly document the exception.** Add a `@WhiteboardLifecycle("instance-per-battle")` annotation (or just a Javadoc paragraph) and check in `Transformer.register()` that any feature with non-static fields declares it. That is honest about the invariant we already rely on.

I recommend Option A. It's the same cost (one method, one field-move) and removes the irregularity rather than ratifying it.

---

## 4. Tighten the `Feature` enum into producer/consumer roles

The enum is currently flat: 90+ values, all peers. There is no compile-time check that:

- a feature listed as a `getDependencies()` is actually produced by some registered processor (`OPPONENT_INFERRED_GUN_HEAT` is the canary);
- a feature listed as `getOutputFeatures()` is actually written in `process()` (you can lie to `Transformer` and it will silently top-sort an empty node);
- the enum value used in `setFeature(...)` is in the producer's declared output set (you can write a feature you never declared).

**Cheap proposal.** A boot-time assertion in `Transformer.resolveDependencies()`:

1. After topological sort, for every dependency listed by any registered feature, assert there exists a registered feature that lists it as an output **or** that it is on a hard-coded "raw whiteboard input" list (`OUR_X`, `TICKS_SINCE_SCAN`, etc., set by the robot before `transformer.process()`).
2. Optionally, in tests: instrument each feature's `process()` with a `Whiteboard` proxy that records every `setFeature` and assert the recorded set is a subset of `getOutputFeatures()`.

This catches the `OPPONENT_INFERRED_GUN_HEAT` class of bug at JAR load instead of at the next retraining run.

**More-expensive proposal (optional).** Split `Feature` into three enums: `RawFeature` (set by Robocode events / `Whiteboard`), `DerivedFeature` (set by an `IInGameFeatures`), `PredictorFeature` (set by an ML predictor). Then `setFeature` is overloaded to accept the right kind only. This is a refactor I would only do if there is a second category of similar bugs.

---

## 5. Decide what to do about `OPPONENT_INFERRED_GUN_HEAT`

Three options:

- **(a)** Add `OpponentInferredGunHeatFeatures` to core and register it in `createTransformer()`. The formula is `max(0, (1 + lastFirePower/5) − elapsed × gunCoolingRate)`. Five lines of code. **Then retrain `fire_timing` with this column included** — the importance is unknown but it is the single most directly action-relevant signal we don't have in-game.
- **(b)** Delete the enum value, the `FeatureMapping` entry, the dependency from `GbmFireTimingPredictor`, and the pipeline-only producer. The trained model already doesn't use it.
- **(c)** Status quo (keep the dead dependency).

**Recommendation: (a).** It is on the critical path for fire-timing accuracy and the 5-line cost is the smallest in this proposal.

---

## 6. Resolve `VcsSamplingGun`

It's a 110-line implementation of probabilistic GF sampling — an anti-profiling counter to the peak-firing `VcsGun`. The plan/strategy docs explicitly mention this as a known top-bot technique against adaptive opponents.

- **(a) Register it.** Add `strategies.add(new VcsSamplingGun())` between `VcsGun` and `PredictiveGun` in `createGunManager()`. The VGM's hit-rate selector will pick whichever variant works against the current opponent. Cost: one line + retrospective check that performance does not regress against simple opponents.
- **(b) Delete it.** Cleaner module surface, less to test, and we can re-add it if the strategy team decides anti-profiling is the next thing to ship.

**Recommendation: (a).** The selection logic is exactly the kind of A/B that the VGM was built for. Letting `VirtualGunManager` arbitrate is much safer than committing globally to peak-firing forever.

---

## 7. Finish or delete `MlpGfTargeting`

Current state: returns a uniform `double[61]`, not consumed by anything, registered in `whiteboard.getPredictorRegistry()` but never read.

- **(a) Finish Phase 8** as planned in `archive/2026-05-02-plan-mlp-targeting.md`: train the MLP, generate `MlpGfTargetingModel.java` with weight matrices and forward pass, wire it into `VcsGun.peakSmoothed()` as a Bayesian prior (`P(GF) = λ·MLP(GF) + (1−λ)·VCS(GF)`, `λ = 50/(50+n)`). This is a multi-week effort.
- **(b) Delete the stub.** It is unfinished surface area that confuses readers. We can re-add the skeleton when actually starting Phase 8.

**Recommendation: (b) for now,** because the in-game R² gap on fire-power is the binding constraint and chasing GF priors before fixing that is a distraction. Add a TODO in `archive/2026-05-02-plan-mlp-targeting.md` instead of carrying live dead code.

---

## 8. Make in-game / offline parity testable from CI

The Sprint 9–18 retrospectives all hit the same wall: the offline R² is 0.913 but the in-game R² varied wildly with no quick way to bisect. We do have `FeatureLogger` (writes per-tick CSV with predicted/actual when `-Dautopilot.featureLog=true`) but the comparison step is manual.

Proposal — automate it:

1. Add `intuition/compare_features.py` (it is mentioned in `plan.md` Workstream B but I did not see the file in the workspace). It should read `output/local/recordings/.../features_fire_power.csv` and the corresponding pipeline `ticks.csv` for the same battle, align rows by `(round, tick)`, and emit per-feature Pearson correlation. Anything `< 0.95` is a bug.
2. Wire it into `local-pipeline.ps1` so every sprint produces a divergence report by default.
3. Track the top divergent feature count in the retrospective. The current target ("≥ 0.95 per feature") becomes a CI gate once Workstream A (CI offload) lands.

**This is the highest-leverage non-code-change in the list.** The in-game R² gap of `0.913 → 0.48` is roughly 2× the cost of every other proposal here, and we can't even quantify *which* features are diverging without this tool.

---

## 9. Sanity / assert review

The codebase is conservative about asserts — that is correct for Robocode (a thrown exception kills the round). What's there:

- **`Whiteboard.advanceTick()`**: clears `featureSet[]` and `features[]` to NaN every tick. This is the "free" assert: any feature read of an unset value is loud (NaN), not silent (stale value). Good.
- **`Autopilot.run()`** wraps movement and gun output in `Double.isNaN` checks and falls back to `(0, 0)` movement / `0` gun turn with a `WARN NaN_*` log line. Good — production-safe.
- **`Autopilot.onScannedRobot`** guards opponent X/Y/energy against NaN before writing to the Whiteboard. Good.
- **`Transformer.process()`** throws `IllegalStateException` if `resolveDependencies()` was not called. Good.
- **`PersistenceManager.loadWithStatus()`** isolates per-section failures so a corrupt `VcsHistogramStore` does not lose `VirtualGunManager` data. Excellent — this kind of failure is the most common in production.

What I would add (cheap):

- **`Whiteboard.setFeature` should reject `Double.isInfinite`.** NaN is the documented "missing" sentinel; ±∞ is not, and one bad cosine in a feature class would propagate silently into the GBM model where it splits on `> threshold`.
- **`VirtualGunManager.onScan`** assumes `wb.hasFeature(Feature.DISTANCE)`. It dereferences `wb.getFeature(Feature.DISTANCE)` directly (= NaN if absent). On the very first scan tick, before `SpatialFeatures` runs, this would put a NaN-distance virtual bullet in the pool. Add a guard or fix the registration order so `gunManager.onScan` is called *after* `transformer.process` (it already is — see `onScannedRobot` — but the assumption is implicit).
- **`GbmTreeEnsemble.evaluateTree`** uses `feat < features.length ? features[feat] : NaN`. Worth asserting at `setMaxTrees` that `featureIndex.length == FEATURE_NAMES.length` to catch a future drift between the embedded model and the `FEATURE_NAMES` array.

---

## 10. Module / package boundaries

I think these are largely correct. One observation:

- `cz.zamboch.distilled.PredictiveGun` lives in the `robot` module, not `core`. It implements `IGunStrategy` (a core interface), reads no Robocode API, and could move to `core.gun` cleanly. Today it sits next to the GBM data classes because it *consumes* `PREDICTED_LAT_VEL_5`. That's fine.
- `cz.zamboch.distilled.MlpGfTargeting` similarly implements `core.predictors.IGfTargetingPredictor`. Same observation.
- `cz.zamboch.trivial.OpponentProfileData` is a static lookup table with no robot dependencies — could move to `core` as well.

Pure cleanup. Do this in the same PR as the §7 decision on `MlpGfTargeting`.

---

## 11. Process / non-code

- The wiki had drifted in three places (gun ordering, ε, distance thresholds). I patched them. Adding a "verify wiki against code" step to the sprint diagnostic checklist would prevent the next round of drift. One way: a Python script that grep-greps the wiki for known constants (`HIT_RATE_EPSILON`, `BULLET_POOL_SIZE`, `FLIP_MIN_TICKS`) and asserts they match the values in the corresponding `.java` file.
- The `Decision Log` in `plan.md` is currently 17 entries deep and overflows the page. Consider splitting "active decisions" (last 6 months) from "historical" (everything older).

---

## 12. Asks before any of this ships

I'm sending this as a proposal, not a plan, because several of the choices depend on what you want to optimise for. Below is the question set that will decide priority order. Answers shape which proposals land first.
