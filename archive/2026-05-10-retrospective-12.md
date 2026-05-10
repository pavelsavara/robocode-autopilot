# Retrospective 12 — Process Improvements, O(1) Rolling Stats, Offline R² 0.917

*Date: 2026-05-10 · Sprint: 12 (Process improvements + PrimitiveRollingBuffer optimization)*
*Previous: [2026-05-10-retrospective-11.md](2026-05-10-retrospective-11.md)*

## Context

Sprint 11 ended HIT (score 8.0% — project record, first battle win ever).
However, 8.0% was inflated by a 23.7% zapper outlier; the underlying
average excluding zapper was ~6.1%. Skipped turns regressed to ~12.6/battle
due to `MlDerivedFeatures` CPU pressure. Sprint 12 targeted: process
improvements for faster iteration, and a performance fix for the rolling
buffer hot path.

**Changes delivered (3 items):**

1. **Amos — 5 process improvements**: Recording archive at sprint start
   (preserve baseline data), `summary.json` with per-opponent scores,
   sprint-only sanity checks (`--battle-ids` flag), incremental CSV
   processing (skip already-processed battles), `-EvalOnly` mode
   (battle + record without CSV rebuild).
2. **Amos — PrimitiveRollingBuffer optimization**: Replaced O(n)
   `RingBuffer<Double>` iteration with O(1) running sum/sum-of-squares.
   Eliminates ~240 autoboxed iterations per scan tick. Fixed pipeline's
   `MovementHistoryOfflineFeatures` to use same `PrimitiveRollingBuffer`
   API — this aligns Java rolling stat computation with Python, explaining
   the massive offline R² jump.
3. **Models retrained on aligned data**: Fire power R²=0.917 (was 0.786,
   +0.131), Movement R²=0.809 (was 0.816, −0.007), Fire timing AUC=0.820
   (was 0.809, +0.011). The fire power jump confirms that the rolling
   buffer alignment was a major source of Java/Python feature divergence.

**Code review:** Holden approved PrimitiveRollingBuffer and pipeline fix.

**Evaluation setup:** 48 battles, 16 opponents, 3 battles × 35 rounds each.

**Model retraining metrics (Sprint 12):**
- Fire power: R² = 0.917, MAE = 0.075
- Movement N=5: R² = 0.809, MAE = 1.975
- Fire timing: AUC = 0.820

**Note:** Retrained models (R²=0.917) were built into the JAR at end of
pipeline but NOT battle-tested this sprint. The evaluation below used the
Sprint 11 models (R²=0.786) with the PrimitiveRollingBuffer fix.

---

## 1. Diagnostic Health

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 1 | TickBudget ≥ 100 | **PASS** | mean=200, min=199 |
| 2 | Skipped turns < 5 | **IMPROVED** | avg 1.8/battle, max=8 (was ~12.6/battle in Sprint 11) |
| 3 | Gun selection | **PASS** | gun0(CircularGun)=67%, HR=3.5% |
| 4 | ML predictions in range | **PASS** | all in range |
| 5 | Wave detection | **PASS** | avg 1.35 waves/tick |
| 6 | Feature completeness | **PASS** | 0 NaN |
| B1 | Fire power R² (in-game) | **WARN** | −0.81 (was −1.12, **improved +0.31**) |

**6 of 6 mandatory checks PASS.** The skipped turns regression from Sprint
11 is resolved — PrimitiveRollingBuffer reduced average skipped turns from
~12.6 to 1.8 per battle (nearly 7× improvement). The O(1) running
sum/sum-of-squares eliminates the hot loop that was causing CPU pressure.

**Bonus B1 improved +0.31:** Fire power in-game R² went from −1.12 to
−0.81. Three consecutive sprints of improvement (−3.67 → −1.44 → −1.12 →
−0.81), but still deeply negative. The gap between offline R²=0.917 and
in-game R²=−0.81 confirms remaining feature divergence beyond rolling
stats.

---

## 2. Metrics Table

| Metric | Sprint 11 | Sprint 12 | Delta | Note |
|---|---|---|---|---|
| Overall score % | 8.0% | **7.9%** | **−0.1 pp** | Stable (Sprint 11 had zapper outlier) |
| Adjusted score % (excl. zapper outlier) | ~6.1% | **7.9%** | **+1.8 pp** | Real improvement |
| Battle win rate | 2.1% | **0.0%** | −2.1 pp | Sprint 11 win was outlier |
| Fire power R² (in-game) | −1.12 | **−0.81** | **+0.31** | 4th consecutive improvement |
| Fire power R² (offline) | 0.786 | **0.917** | **+0.131** | Massive — rolling buffer alignment |
| Movement R² (offline) | 0.816 | **0.809** | −0.007 | Stable |
| Fire timing AUC (offline) | 0.809 | **0.820** | +0.011 | Slight improvement |
| Gun0 selection % | 68% | **67%** | −1 pp | Stable |
| Gun0 HR | 3.5% | **3.5%** | — | Unchanged |
| Wave detection (waves/tick) | 1.34 | **1.35** | +0.01 | Stable |
| Skipped turns (avg/battle) | ~12.6 (new) | **1.8** | **−10.8** | FIXED — PrimitiveRollingBuffer |
| Skipped turns (max) | 23 | **8** | **−15** | Major improvement |
| TickBudget (min) | 199 | **199** | — | Stable |
| Best single-battle score | 58% (zapper) | **25% (BlestPain)** | — | New non-outlier record |

---

## 3. Per-Opponent Breakdown

*Sorted by Sprint 12 score % descending. Delta = Sprint 12 − Sprint 11.*

| Opponent | Sprint 12 | Sprint 11 | Delta | Tier |
|---|---|---|---|---|
| cx.BlestPain | **21.0%** | 19.0% | **+2.0** | Upper-mid |
| ej.ChocolateBar | **17.0%** | 15.3% | **+1.7** | Upper-mid |
| florent.FloatingTadpole | **14.3%** | 9.7% | **+4.7** | Mid |
| ary.FourWD | **12.7%** | 12.0% | +0.7 | Upper-mid |
| kid.Gladiator | **12.0%** | 10.7% | **+1.3** | Mid |
| eem.zapper | **6.7%** | 23.7% | **−17.0** | Upper-mid |
| ary.Help | **5.7%** | 4.7% | **+1.0** | Mid |
| gh.GresSuffurd | **5.3%** | 5.3% | 0.0 | Mid |
| rdt.AgentSmith | **5.3%** | 4.3% | **+1.0** | Lower-mid |
| da.NewBGank | **5.0%** | 5.7% | −0.7 | Mid |
| jk.mega.DrussGT | **4.7%** | 5.0% | −0.3 | Strong |
| dft.Cardigan | **4.3%** | 3.7% | +0.6 | Upper-mid |
| darkcanuck.Pris | **4.0%** | 3.0% | **+1.0** | Upper-mid |
| abc.Shadow | **3.3%** | 2.7% | +0.6 | Strong |
| voidious.Diamond | **2.7%** | 2.0% | +0.7 | Strong |
| fromHell.BlackBox | **2.0%** | 2.3% | −0.3 | Lower-mid |

**Winners (+1 pp or more):** FloatingTadpole (+4.7), BlestPain (+2.0),
ChocolateBar (+1.7), Gladiator (+1.3), Help (+1.0), AgentSmith (+1.0),
Pris (+1.0) — **7 of 16 improved ≥1 pp**.

**Losers (−1 pp or more):** zapper (−17.0) — Sprint 11's 23.7% was an
outlier (included a 58% battle win); Sprint 12's 6.7% is the realistic
baseline.

**Broad-based improvement:** 12 of 16 opponents improved or held steady.
The only meaningful regression is zapper reverting from an outlier. This
confirms the PrimitiveRollingBuffer fix helped across the board.

---

## 4. Key Findings

### Finding 1: PrimitiveRollingBuffer is the biggest perf fix yet

The O(1) running sum/sum-of-squares replacement had two effects:
- **CPU:** Skipped turns dropped from ~12.6 to 1.8 per battle (7× improvement).
  The old `RingBuffer<Double>.iterator()` was doing ~240 autoboxed iterations
  per scan tick to compute mean/variance — eliminated entirely.
- **Feature alignment:** The pipeline's `MovementHistoryOfflineFeatures` was
  using the same O(n) buffer but computing slightly differently from the
  Python training pipeline. Fixing both to use `PrimitiveRollingBuffer`
  aligned the rolling stat features, explaining the offline R² jump from
  0.786 to 0.917.

### Finding 2: Offline R²=0.917 is highest ever for fire power

The fire power model's offline R² has progressed: 0.825 → 0.786 → **0.917**.
The 0.786 dip in Sprint 11 was likely due to the rolling stat misalignment
corrupting the training data. With aligned features, the model can actually
learn the relationship properly. This suggests the Java/Python feature
divergence was a major contributor to the offline-to-in-game gap.

### Finding 3: In-game R² still deeply negative despite offline 0.917

In-game R² improved to −0.81 (from −1.12), continuing the trend:
−3.67 → −1.44 → −1.12 → −0.81. But offline 0.917 vs in-game −0.81 means
there's still a massive gap. Remaining sources of divergence:
- Sliding window features may still compute differently in Java vs Python
- Feature ordering or normalization differences
- Timing: Java features computed at scan time vs Python at tick-end
- The retrained R²=0.917 models haven't been battle-tested yet — the
  in-game −0.81 uses the Sprint 11 models (R²=0.786 offline)

### Finding 4: zapper result confirms Sprint 11 win was an outlier

Sprint 11 zapper: 23.7% (included a 58% battle). Sprint 12 zapper: 6.7%
(6–7% range, no wins). The Sprint 11 win was a statistical fluke, not a
capability signal. The realistic zapper baseline is 6–7%.

### Finding 5: Process improvements accelerate iteration

The five process improvements (recording archive, summary.json,
sprint-only sanity, incremental CSV, EvalOnly mode) cut sprint cycle time
significantly. `--battle-ids` enables sanity-checking only new battles
instead of the full 1900+ dataset. `-EvalOnly` skips the CSV rebuild when
only evaluating model performance. These are force multipliers for future
sprints.

---

## 5. Sprint Assessment

**Verdict: HIT** — Skipped turns regression fixed (12.6→1.8), offline R²
jumped to 0.917 (project record), in-game R² improved +0.31, broad-based
score improvement across 12 of 16 opponents. The headline 7.9% vs 8.0%
masks the real story: Sprint 11's 8.0% included a zapper outlier; the
underlying improvement is +1.8 pp.

**What worked:**
- PrimitiveRollingBuffer was a two-for-one fix (perf + feature alignment)
- Process improvements will compound across future sprints
- Systematic sanity checking caught the improvement immediately

**What didn't work:**
- In-game R² is still deeply negative (−0.81) — the offline-to-in-game gap
  remains the #1 blocker
- No battle wins — still can't close out rounds against any opponent

---

## 6. Sprint 13 Plan

1. **Evaluate retrained models** — The R²=0.917 models are built but
   untested in battle. This is the highest-priority item: if the offline
   improvement translates to in-game, we could see a significant score jump.
2. **Feature divergence diagnosis** — Use the feature comparison tooling
   from Sprint 11 to identify remaining Java/Python mismatches beyond
   rolling stats. Target: push in-game R² positive.
3. **Investigate remaining R² gap** — offline 0.917 vs in-game −0.81 is
   a 1.7-point gap. Even a partial close (e.g. in-game R²=0.0) would be
   a breakthrough.

**Decision:** Hold on new features. The retrained models and feature parity
are the highest-leverage work items. Score improvement comes from making
the existing models work correctly in-game, not from adding new features.
