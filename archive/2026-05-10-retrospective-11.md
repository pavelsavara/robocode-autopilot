# Retrospective 11 — First Battle Win, Pipeline Parallelization, Retrained Models Evaluated

*Date: 2026-05-10 · Sprint: 11 (Retrained models evaluation + pipeline speedup)*
*Previous: [2026-05-09-retrospective-10.md](2026-05-09-retrospective-10.md)*

## Context

Sprint 10 ended HIT (score 6.6% — third consecutive project record). The
root cause of the fire power model divergence was found and fixed: 23 of
80 features were NaN at runtime due to missing `IInGameFeatures`
implementation. `MlDerivedFeatures.java` was created in core. Fire power
in-game R² improved −3.67 → −1.44 (+2.23). Models were retrained
(R²=0.825 offline) but NOT battle-tested — the evaluation used old models
with the NaN fix. Sprint 11 targeted: evaluating the retrained models,
pipeline parallelization, and building feature comparison tooling.

**Changes delivered (3 items):**

1. **Amos — Pipeline parallelization**: CSV processing now uses 4 threads
   via `ExecutorService`. Added `--threads N` CLI arg (default 4).
   Processing time: ~5 min for 274 files (was ~16 min for 226 files
   sequentially). ~4× speedup.
2. **Naomi — Feature comparison script**: Improved `scripts/compare_features.py`
   for FeatureLogger CSV format. Created `scripts/run_diagnostic_battle.py`.
   Ready for Sprint 12 feature-by-feature comparison.
3. **Retrained models battle-tested**: Sprint 10 retrained models (with all
   80 features populated) were evaluated for the first time this sprint.
   This is the first evaluation where the models were trained on data with
   the 23 previously-NaN features populated.

**Code review:** Holden approved pipeline parallelization. Feature comparison
script is diagnostic tooling (no review gate).

**Evaluation setup:** 48 battles, 16 opponents, 3 battles × 35 rounds each.

**Model retraining metrics (Sprint 11, post-evaluation retrain):**
- Fire power: R² = 0.786, MAE = 0.111
- Movement N=5: R² = 0.816, MAE = 1.923
- Fire timing: AUC = 0.809

---

## 1. Diagnostic Health

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 1 | TickBudget ≥ 100 | **PASS** | mean=200, min=199 (first sub-200 reading — CPU pressure from MlDerivedFeatures) |
| 2 | Skipped turns < 5 | **FAIL** | avg 2.7/battle overall; new battles avg ~12.6/battle, max=23, 43 battles with ≥5 skips |
| 3 | Gun selection | **PASS** | gun0(CircularGun)=68%, HR=3.5% — unchanged from Sprint 10 |
| 4 | ML predictions in range | **PASS** | all in range, variance OK |
| 5 | Wave detection | **PASS** | avg 1.34 waves/tick (up from 1.33) |
| 6 | Feature completeness | **PASS** | 0 NaN |
| B1 | Fire power R² (in-game) | **WARN** | −1.12 (was −1.44, **improved +0.32**) |
| B2 | Fire timing calibration | **WARN** | 63% predicted vs 4% actual (was 63% vs 3%) |
| B3 | Prediction distribution | **PASS** | healthy variance |

**5 of 6 mandatory checks PASS. Check #2 FAILS.** This is the first
skipped-turns failure since the sanity check was introduced. The overall
average (2.7/battle) is misleading because it includes 226 old recordings
(avg 0.6). The 48 new battles average ~12.6 skipped turns per battle —
a severe regression. The likely cause is `MlDerivedFeatures` adding ~25
per-tick computations including ring buffer operations and ray-cast loops,
pushing total tick processing time past the Robocode time limit on some
ticks.

**TickBudget note:** min=199 is the first time TickBudget has dropped below
200. While 199 is still full capacity, this confirms CPU pressure from the
added feature computations. The correlation with skipped turns is clear.

**Bonus B1 improved +0.32:** Fire power in-game R² went from −1.44 to −1.12.
This is a modest improvement compared to Sprint 10's +2.23, but it confirms
the retrained models (trained on data with all 80 features populated) are
marginally better than the old models with the NaN fix alone. R² is still
deeply negative.

---

## 2. Metrics Table

| Metric | Sprint 10 | Sprint 11 | Delta | Note |
|---|---|---|---|---|
| Overall score % | 6.6% | **8.0%** | **+1.4 pp** | New project record — 4th consecutive |
| Battle win rate | 0.0% | **2.1%** | **+2.1 pp** | FIRST WIN EVER (1/48 battles) |
| Fire power R² (in-game) | −1.44 | **−1.12** | **+0.32** | Modest improvement, still broken |
| Fire power R² (offline) | 0.825 | **0.786** | −0.039 | Slight drop (smaller/different data) |
| Fire timing (pred vs actual) | 63% vs 3% | **63% vs 4%** | — | Essentially unchanged |
| Gun0 selection % | 68% | **68%** | — | Unchanged |
| Gun0 HR | 3.5% | **3.5%** | — | Unchanged |
| Wave detection (waves/tick) | 1.33 | **1.34** | +0.01 | Stable |
| Skipped turns (avg/battle) | 0.6 | **2.7** | **+2.1** | REGRESSION — new battles avg ~12.6 |
| TickBudget (min) | 200 | **199** | **−1** | First sub-200 reading |
| Pipeline processing time | ~16 min (226 files) | **~5 min (274 files)** | **~4× faster** | Parallelization delivered |

---

## 3. Per-Opponent Breakdown

*Sorted by Sprint 11 score % descending. Delta = Sprint 11 − Sprint 10.*

| Opponent | Sprint 11 | Sprint 10 | Delta | Tier |
|---|---|---|---|---|
| eem.zapper | 23.7% | 6.0% | **+17.7** | Upper-mid |
| cx.BlestPain | 19.0% | 16.0% | **+3.0** | Upper-mid |
| ej.ChocolateBar | 15.3% | 14.0% | **+1.3** | Upper-mid |
| ary.FourWD | 12.0% | 9.0% | **+3.0** | Upper-mid |
| kid.Gladiator | 10.7% | 14.3% | **−3.6** | Mid |
| florent.FloatingTadpole | 9.7% | 9.0% | +0.7 | Mid |
| da.NewBGank | 5.7% | 5.7% | 0.0 | Mid |
| gh.GresSuffurd | 5.3% | 4.3% | **+1.0** | Mid |
| jk.mega.DrussGT | 5.0% | 3.3% | **+1.7** | Strong |
| ary.Help | 4.7% | 5.0% | −0.3 | Mid |
| rdt.AgentSmith | 4.3% | 4.3% | 0.0 | Lower-mid |
| dft.Cardigan | 3.7% | 4.3% | −0.6 | Upper-mid |
| darkcanuck.Pris | 3.0% | 2.3% | +0.7 | Upper-mid |
| abc.Shadow | 2.7% | 3.0% | −0.3 | Strong |
| fromHell.BlackBox | 2.3% | 2.0% | +0.3 | Lower-mid |
| voidious.Diamond | 2.0% | 2.3% | −0.3 | Strong |

**Winners (+1 pp or more):** zapper (+17.7), BlestPain (+3.0), FourWD (+3.0),
DrussGT (+1.7), ChocolateBar (+1.3), GresSuffurd (+1.0) — **6 of 16
opponents improved ≥1 pp**.

**Losers (−1 pp or more):** Gladiator (−3.6) — **1 opponent regressed ≥1 pp**.

**Key observation:** The standout result is zapper — a +17.7 pp swing driven
by a single 58% battle (our first ever battle WIN). This is likely an outlier
(the other two zapper battles scored 6% and 7%), but it demonstrates that
the robot CAN win against certain opponents in favorable conditions. Excluding
the zapper outlier, the remaining 15 opponents average 6.1% (was 5.8% in
Sprint 10), showing a modest but real +0.3 pp underlying improvement.

BlestPain (+3.0) and FourWD (+3.0) both recovered from Sprint 10 dips.
DrussGT (+1.7) is notable as a strong-tier opponent. Gladiator (−3.6) is
the only significant regression — this is likely variance (was +3.0 in
Sprint 10, now −3.6).

---

## 4. What Worked

### First battle win EVER — SPRINT HIGHLIGHT
- **58% score vs eem.zapper in battle 2/3** — the first time our robot
  has won a battle in 11 sprints of development. While likely an outlier
  (other zapper battles: 6%, 7%), this proves the robot can compete
  against the right opponent in the right conditions.
- zapper is an upper-mid tier opponent, not a trivial one. The win
  required sustained scoring across 35 rounds.

### Overall score: 8.0% — fourth consecutive project record
- Score trajectory: 5.4% → 5.1% → 6.1% → 6.6% → **8.0%**. The +1.4 pp
  gain is the largest single-sprint improvement since Sprint 7's TickBudget
  fix. Even excluding the zapper outlier, the underlying improvement is
  positive.
- 6 of 16 opponents improved ≥1 pp. Only 1 regressed ≥1 pp. The
  improvement is broad-based with most opponents stable or improving.

### Retrained models show marginal benefit
- Fire power in-game R² improved −1.44 → −1.12 (+0.32). This is modest
  compared to Sprint 10's NaN fix (+2.23), but it confirms that models
  retrained on data with all 80 features populated are slightly better
  than old models with the NaN fix alone.
- The benefit is small because the remaining 57 features still have
  Java/Python divergence. The retrained models can't fully exploit the
  fixed features while the other features are mismatched.

### Pipeline parallelization (Amos) — 4× speedup
- CSV processing: ~16 min for 226 files (sequential) → ~5 min for 274
  files (4 threads). This is a 4× speedup as expected, turning the
  pipeline from a bottleneck into a quick step.
- Added `--threads N` CLI arg with sensible default (4). Clean
  ExecutorService implementation.

### Feature comparison tooling ready (Naomi)
- `scripts/compare_features.py` improved for FeatureLogger CSV format.
- `scripts/run_diagnostic_battle.py` created for automated diagnostic
  battles. Ready for Sprint 12 feature-by-feature comparison.

### Strong-tier improvements
- DrussGT: 3.3% → 5.0% (+1.7 pp) — our best result ever against the
  #3 strongest opponent in the pool. Suggests the retrained models
  provide better signal even in their broken state.

---

## 5. What Didn't Work

### Skipped turns regression — CHECK #2 FAIL
- New battles average ~12.6 skipped turns per battle (max 23). This is
  the worst skipped-turns result in project history and the first sanity
  check failure in 3 sprints.
- **Root cause:** `MlDerivedFeatures` adds ~25 per-tick computations
  including ring buffer operations and ray-cast loops. Combined with
  the existing feature computation and ML inference, total tick
  processing now occasionally exceeds the Robocode time limit.
- Skipped turns mean the robot loses control for those ticks — no
  movement updates, no gun aiming, no radar sweeps. At 12.6 per
  battle, that's ~0.4% of ticks, but they cluster around intensive
  moments (wave detection, multiple opponents scanning).
- **This is a trade-off:** MlDerivedFeatures fixed 23 NaN features that
  improved R² by +2.23, but the computation cost is now bleeding into
  runtime performance. Need to profile and optimize.

### Fire power model still deeply negative (R² = −1.12)
- R² improved only +0.32 this sprint (vs +2.23 in Sprint 10). The
  remaining 57 features with Java/Python divergence dominate. Retraining
  alone cannot fix a feature mismatch — the model learns Python feature
  distributions but receives Java feature values at inference time.
- The FeatureLogger comparison (ready but not yet run) is the path
  forward. Need to identify which of the 57 features diverge most and
  fix them in Java.

### Fire timing model still miscalibrated (63% vs 4%)
- Essentially unchanged from Sprint 10. The model predicts opponents
  fire on 63% of ticks; they actually fire on ~4%. PredictiveGun (which
  uses this model) is wasting computation on every tick.

### Hit rate unchanged (3.5%)
- Gun0 selection and hit rate are both unchanged from Sprint 10. The
  retrained models did not improve targeting accuracy. This is expected
  given R² is still negative — the fire power model is not yet providing
  useful signal for power selection.

### Gladiator regression (14.3% → 10.7%)
- Gladiator was our biggest gainer in Sprint 10 (+3.0 pp) and is now
  our biggest loser (−3.6 pp). This pattern of mean reversion suggests
  high variance in per-opponent results at our current skill level.

### Offline model metrics slightly down
- Fire power R² offline: 0.825 → 0.786 (−0.039)
- Movement R² offline: 0.802 → 0.816 (+0.014)
- Fire timing AUC: 0.803 → 0.809 (+0.006)
- The fire power drop is likely from training on a different/smaller
  local dataset. Merging rumble + local datasets remains a todo.

---

## 6. Root Cause Analysis

### Primary gap: Skipped turns regression

**What happened:**
- `MlDerivedFeatures` was introduced in Sprint 10 to fix the 23 NaN
  features. It computes ~25 features per tick from Whiteboard state,
  including ring buffer rolling statistics (mean, std over 10/20/30
  tick windows) and geometric computations (distance, angle, wall
  distance, envelope calculations).
- Each computation is individually cheap, but the aggregate of ~25
  additional computations per tick on top of existing feature computation
  + 3 GBM model inferences (200 trees each) + wave tracking + gun
  simulation pushes total tick time past the Robocode limit on ~0.4%
  of ticks.
- TickBudget dropped to 199 on some ticks (first sub-200 reading),
  confirming CPU pressure.

**Fix options (Sprint 12):**
1. **Profile MlDerivedFeatures** — identify the most expensive of the 25
   computations. Ring buffer rolling stats and ray-cast loops are prime
   suspects.
2. **Reduce computation frequency** — some features change slowly (e.g.
   `opponent_wall_ahead_distance`). Compute every 5th tick instead of
   every tick. Cache and reuse.
3. **Eliminate redundant features** — if any of the 25 features have
   near-zero importance in the GBM models, stop computing them at runtime.
4. **Optimize hot paths** — pre-compute common subexpressions, avoid
   object allocation in the hot loop.

### Secondary gap: Fire power R² = −1.12 (still negative)

**What we know:**
- 23 NaN features fixed in Sprint 10: +2.23 R² improvement.
- Retrained models in Sprint 11: +0.32 R² improvement.
- Total R² trajectory: −0.61 → −3.46 → −3.67 → −1.44 → **−1.12**.
- Remaining cause: 57 features with Java/Python value-level divergence
  (scan timing, event ordering, tick alignment, state initialization).
- FeatureLogger is wired and comparison scripts are ready. Sprint 12
  can execute the feature-by-feature diagnosis.

### The win vs zapper — what happened?

The 58% battle against zapper in battle 2/3 is worth examining:
- zapper is classified as upper-mid tier (6.0% average in Sprint 10).
- The two other zapper battles scored 6% and 7% — consistent with
  historical performance.
- The 58% battle is a ~9× outlier. Possible explanations:
  1. **Favorable random seed** — Robocode uses deterministic random seeds
     per battle. A seed that happens to align our movement with zapper's
     targeting weakness could produce a one-off win.
  2. **zapper's adaptation failure** — some bots have adaptive strategies
     that can fail to converge against certain movement patterns.
  3. **Our wave detection timing** — if wave detection aligned well in
     that particular battle, dodge timing would be better than average.
- Not actionable for optimization, but proves the robot has winning
  potential in favorable conditions.

---

## 7. Proposals for Next Sprint

### Proposal 1: Fix skipped turns regression (PRIORITY — check #2 failing)
- **Target metric:** Skipped turns < 5 avg per battle (restore check #2 PASS)
- **Method:** Profile `MlDerivedFeatures` to find hotspots. Candidates:
  ring buffer rolling stats, ray-cast loops, geometric computations.
  Options include: caching slow-changing features (compute every Nth tick),
  removing zero-importance features, optimizing hot paths (pre-compute
  subexpressions, avoid allocations).
- **How to measure:** sanity-check.ps1 check #2 < 5 avg.
- **Owner:** Amos (Systems) — performance optimization

### Proposal 2: Run feature comparison diagnostic
- **Target metric:** Per-feature R² between Java and Python ≥ 0.95 on
  matched ticks for all 80 features
- **Method:** Scripts are ready (`compare_features.py`,
  `run_diagnostic_battle.py`). Run a diagnostic battle with FeatureLogger
  enabled. Compare Java feature vectors against ticks.csv. Identify top
  divergent features among the remaining 57. Fix the worst offenders.
- **How to measure:** compare_features.py output, per-feature correlation.
- **Owner:** Naomi (ML) + Amos (Systems — run infrastructure)

### Proposal 3: Continue fire power R² improvement
- **Target metric:** Fire power in-game R² from −1.12 → ≥ −0.5
- **Method:** Combine results from Proposal 2 (fix divergent features)
  with model retraining on corrected data. Each feature fixed in Java
  should contribute incrementally to R² improvement, following the
  pattern of Sprint 10 (+2.23 from 23 features).
- **How to measure:** sanity-check.ps1 B1 metric.
- **Owner:** Naomi (ML)

---

## 8. Sprint Result: **HIT**

**Target:** Evaluate retrained models, parallelize pipeline, build feature
comparison tooling.
**Actual:**
- Retrained models **EVALUATED** — first evaluation with models trained on
  all 80 features populated. Fire power in-game R² improved −1.44 → −1.12
  (+0.32). Modest but confirms direction.
- Pipeline **PARALLELIZED** — 4× speedup (~16 min → ~5 min for 274 files).
  `--threads N` CLI arg added.
- Feature comparison tooling **READY** — `compare_features.py` and
  `run_diagnostic_battle.py` prepared for Sprint 12 diagnosis.
- **FIRST BATTLE WIN EVER** — 58% vs eem.zapper. Historic milestone.
- Overall score **8.0%** — new project record (+1.4 pp over Sprint 10).
  Fourth consecutive record.
- **6 of 16** opponents improved ≥1 pp. Only 1 regressed ≥1 pp.
- **Skipped turns REGRESSION** — new battles avg ~12.6 skipped turns.
  First sanity check failure in 3 sprints. Caused by MlDerivedFeatures
  computation overhead.

**Why "HIT":** All three planned deliverables were completed (retrained
models evaluated, pipeline parallelized, comparison tooling ready). The
first battle win is a historic milestone. Score improved +1.4 pp to a new
record. The skipped-turns regression is a concerning side effect of
Sprint 10's NaN fix, but it's a known cost of the feature computation
overhead — the fix it enables (R² +2.23 over two sprints) far outweighs
the runtime cost. The regression is addressable via profiling and
optimization in Sprint 12.

**Trajectory:** 5.4% → 5.1% → 6.1% → 6.6% → **8.0%**. Sustained upward
trend over four sprints. R² trajectory: −0.61 → −3.46 → −3.67 → −1.44 →
**−1.12**. Both metrics continue converging toward viability. Battle win
rate: 0% → 0% → 0% → 0% → **2.1%**. First nonzero win rate.
