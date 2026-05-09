# Retrospective 9 — Diagnostics, Gun Priority Fix, Movement Tuning

*Date: 2026-05-09 · Sprint: 9 (Diagnostic sprint — 3 proposals from Sprint 8)*
*Previous: [2026-05-09-retrospective-8.md](2026-05-09-retrospective-8.md)*

## Context

Sprint 8 ended MISS (−0.3 pp) with fire power model still broken in-game
(R²=−3.46), gun selection worsened (67% gun0), and mixed movement results.
Sprint 9 targeted all three proposals: feature divergence diagnosis,
gun tie-break fix, and movement improvements.

**Changes delivered (3 branches, all reviewed and approved by Holden):**

1. **Naomi + Amos — feature-logging-sprint9**: Created `FeatureLogger.java`
   for dumping Java feature vectors at runtime, plus `compare_features.py`
   for automated Java/Python comparison. Naomi's code review found **no
   code-level mismatch** between Java and Python feature computation — the
   divergence must be at runtime input values, not computation logic.
   Diagnostic infrastructure created but not yet run against battle data.
2. **Bobbie — fix-gun-tiebreak-sprint9**: Root-caused gun selection bug —
   CircularGun's hardcoded `getConfidence()=1.0` acted as a permanent
   ceiling, starving other guns. Replaced with clean two-pass index-based
   priority system. Reordered gun list: CircularGun > VcsGun > PredictiveGun
   > LinearGun > HeadOnGun. 17 unit tests.
3. **Alex — movement-improvements-sprint9**: Ahead hysteresis (0.15 rad
   dead-zone) to prevent oscillation, proportional dodge commitment (2–8
   ticks scaled to wave TTI), removed random direction flips during
   pre-emptive dodge. 9 unit tests (3 new).

**Review note:** Branch 1 (gun tiebreak) was initially REJECTED because it
included premature feature logger wiring in `Autopilot.java` — a cross-branch
dependency that would have broken the build. Amos applied the lockout rule
(remove the wiring), then re-submitted. Approved on second pass.

**Evaluation setup:** 48 battles, 16 opponents, 3 battles × 35 rounds each.

**Models retrained** on local battle data:
- Fire power: R² = 0.837, MAE = 0.078 (offline)
- Movement N=5: R² = 0.834, MAE = 1.838 (offline)
- Fire timing: AUC = 0.808

---

## 1. Diagnostic Health

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 1 | TickBudget ≥ 100 | **PASS** | mean=200, min=200, max=200 (full capacity) |
| 2 | Skipped turns < 5 | **PASS** | avg 0.6/battle, max 4 |
| 3 | Gun selection | **PASS** | gun0(CircularGun)=68%, HR=3.5% — BEST gun now selected most |
| 4 | ML predictions in range | **PASS** | all in range, variance OK |
| 5 | Wave detection | **PASS** | avg 1.29 waves/tick (up from 1.21) |
| 6 | Feature completeness | **PASS** | 0 critical NaN |
| B1 | Fire power R² (in-game) | **WARN** | −3.67 (was −3.46, still broken) |
| B2 | Fire timing calibration | **WARN** | 58% predicted vs 3% actual |
| B3 | Prediction distribution | **PASS** | healthy variance |

**All 6 mandatory checks PASS for the first time.** Sanity check #3 (gun
selection) now passes — CircularGun is the highest hit-rate gun AND the
most-selected gun. The two WARN items (B1, B2) are ML model in-game
calibration issues that remain the top priority.

---

## 2. Metrics Table

| Metric | Sprint 8 | Sprint 9 | Delta | Note |
|---|---|---|---|---|
| Overall score % | 5.1% | **6.1%** | **+1.0 pp** | Best ever |
| Battle win rate | 0.0% | **0.0%** | — | Still zero |
| Fire power R² (in-game) | −3.46 | **−3.67** | −0.21 | Slightly worse, still broken |
| Fire power R² (offline) | 0.946 | **0.837** | −0.109 | Retrained on local data |
| Fire timing (pred vs actual) | 59% vs 3% | **58% vs 3%** | −1 pp | Still heavily miscalibrated |
| Gun0 selection % | 67% | **68%** | +1 pp | Now CORRECT — gun0 is CircularGun w/ best HR |
| Gun0 HR | ??? | **3.5%** | — | Highest of all 5 guns |
| HeadOnGun selection % | 54%→67% | **4%** | — | Demoted as intended (Decision #10) |
| Wave detection (waves/tick) | 1.21 | **1.29** | +0.08 | Improved |
| Skipped turns (avg/battle) | 0.4 | **0.6** | +0.2 | Healthy |

---

## 3. Per-Opponent Breakdown

*Sorted by Sprint 9 score % descending. Delta = Sprint 9 − Sprint 8.*

| Opponent | Sprint 9 | Sprint 8 | Delta | Tier |
|---|---|---|---|---|
| cx.BlestPain | 19.0% | 14.7% | **+4.3** | Upper-mid |
| ej.ChocolateBar | 15.0% | 12.3% | **+2.7** | Upper-mid |
| kid.Gladiator | 11.3% | 9.7% | **+1.6** | Mid |
| florent.FloatingTadpole | 11.0% | 5.7% | **+5.3** | Mid |
| ary.FourWD | 7.7% | 7.3% | +0.4 | Upper-mid |
| eem.zapper | 6.7% | 4.7% | **+2.0** | Upper-mid |
| ary.Help | 4.0% | 4.0% | — | Mid |
| da.NewBGank | 4.0% | 4.3% | −0.3 | Mid |
| rdt.AgentSmith | 3.3% | 3.7% | −0.4 | Lower-mid |
| dft.Cardigan | 3.0% | 2.7% | +0.3 | Upper-mid |
| jk.mega.DrussGT | 3.0% | 2.0% | **+1.0** | Strong |
| gh.GresSuffurd | 2.3% | 3.0% | −0.7 | Mid |
| darkcanuck.Pris | 2.0% | 2.0% | — | Upper-mid |
| voidious.Diamond | 2.0% | 1.7% | +0.3 | Strong |
| fromHell.BlackBox | 1.7% | 1.3% | +0.4 | Lower-mid |
| abc.Shadow | 1.3% | 2.0% | −0.7 | Strong |

**Winners (+1 pp or more):** FloatingTadpole (+5.3), BlestPain (+4.3),
ChocolateBar (+2.7), zapper (+2.0), Gladiator (+1.6), DrussGT (+1.0) —
6 of 16 opponents improved significantly.

**Losers (−1 pp or more):** None. The worst regressions are GresSuffurd
(−0.7) and Shadow (−0.7) — both within noise.

**Key observation:** FloatingTadpole recovered from its Sprint 8 regression
(5.7% → 11.0%), surpassing even Sprint 7's 9.7%. The movement hysteresis
fix likely eliminated the oscillation that FloatingTadpole was exploiting.

---

## 4. What Worked

### Gun priority fix (Bobbie) — SPRINT HIGHLIGHT
- **Root cause identified and eliminated.** CircularGun's hardcoded
  `getConfidence()=1.0` was the actual bug — it acted as a ceiling
  that prevented any other gun from winning the VGM selection.
- Two-pass index-based priority system is clean and testable.
- **Sanity check #3 now PASSES** for the first time since Sprint 6.
- HeadOnGun dropped from 54% (Sprint 7) → 67% (Sprint 8) → **4%** — exactly
  where Decision #10 intended it.
- 17 unit tests ensure this stays fixed.

### Movement improvements (Alex) — NET POSITIVE
- Ahead hysteresis prevents the oscillation that hurt FloatingTadpole.
- Proportional dodge commitment (2–8 ticks) avoids over-committing to
  distant waves and under-committing to imminent waves.
- **6 of 16 opponents improved ≥1 pp. Zero opponents regressed ≥1 pp.**
  This is the cleanest movement result we've had.
- Wave detection up 1.21 → 1.29 waves/tick.

### Feature logging infrastructure (Naomi + Amos) — DIAGNOSTIC READY
- `FeatureLogger.java` + `compare_features.py` ready for next sprint.
- Code review confirmed no algorithmic mismatch — narrows the search
  space to runtime input divergence (scan timing, event ordering, etc.).

### Overall score: best ever
- 6.1% is a new project record, up from 5.1% (Sprint 8) and 5.4% (Sprint 7).
- First sprint where all 6 mandatory sanity checks pass.
- First sprint with no per-opponent regressions ≥1 pp.

### Process discipline held
- Holden's initial REJECT of Branch 1 caught a cross-branch dependency
  that would have broken the build. Amos fixed it before merge.
- The lockout rule works: catch problems at review, not after merge.

---

## 5. What Didn't Work

### Fire power model still broken in-game (R² = −3.67)
- In-game R² slightly worsened (−3.46 → −3.67). The model is still
  anti-correlated with reality.
- Offline R² dropped from 0.946 to 0.837, but this is because the model
  was retrained on local battle data (smaller dataset, different opponent
  mix). The offline metric is still healthy.
- **The feature divergence diagnosis infrastructure was built but not
  exercised.** We know the computation logic matches; we need to run
  FeatureLogger against live battles and compare actual values.

### Fire timing model still miscalibrated (58% predicted vs 3% actual)
- Essentially unchanged from Sprint 8 (59% vs 3%). This model thinks
  opponents fire on 58% of ticks when they actually fire on 3%.
- The model is not being used for gun decisions (PredictiveGun only),
  so the impact is limited, but it represents wasted computation.

### Battle win rate still 0%
- Despite 6.1% score, we haven't won a single battle. The score comes
  from survival bonus and damage dealt within losing rounds.
- To win battles, we need to either dramatically improve hit rate (3.5%
  is very low) or dramatically reduce opponent hit rate. Both require
  the ML models to work in-game.

### Offline model quality declined with local data
- Fire power R² 0.946 → 0.837 and movement R² 0.866 → 0.834 after
  retraining on local battle data. The local dataset (48 battles) is
  much smaller than the original rumble dataset (~1944 battles).
- The retrained models are less general. Consider merging local and
  rumble data for future retraining, or keeping rumble-trained models
  as the production baseline.

---

## 6. Root Cause Analysis

### Primary gap: Fire power R² = −3.67 (offline 0.837 → in-game −3.67)

**What we now know:**
- Code review (Naomi) confirmed Java and Python compute features using
  the same formulas. The algorithmic logic matches.
- The divergence is therefore in **runtime inputs**: scan timing, event
  ordering, tick alignment, or state initialization. The Java robot sees
  slightly different raw values than the Python pipeline reconstructs
  from CSV, and these differences compound through 20-tick sliding windows.

**Next diagnostic step:** Run `FeatureLogger` in a live battle, extract
feature vectors at known ticks, and run `compare_features.py` against the
same ticks from ticks.csv. Identify which features diverge and by how much.

### Secondary gap: 0% win rate despite 6.1% score

**Analysis:** Our hit rate (3.5%) vs opponent hit rate (~46%) gives a
damage ratio of roughly 1:13. We survive via movement but cannot deal
enough damage to win. The guns are selecting correctly now (CircularGun
primary), but CircularGun's 3.5% HR is very low even for a circular
targeting gun. Possible causes:
- Fire power predictions are wrong (model broken), causing us to fire
  at incorrect power levels.
- Fire timing predictions cause us to dodge at wrong moments.
- CircularGun's circular prediction is correct but opponents don't
  move in circles — we need a better primary gun (GF targeting).

### Tertiary: Local retraining quality

The local 48-battle dataset is too small for robust training. Offline R²
dropped ~0.1 across the board. Future retraining should merge local and
rumble data, or use local data only for diagnostic validation.

---

## 7. Proposals for Next Sprint

### Proposal 1: Run FeatureLogger and fix top divergent features
- **Target metric:** Fire power in-game R² from −3.67 → ≥ 0.0
- **Method:** Enable `FeatureLogger` in a diagnostic battle. Extract Java
  feature vectors at fire-detection ticks. Run `compare_features.py` to
  identify top-5 divergent features. Fix Java or Python side as needed.
- **How to measure:** Per-feature correlation ≥ 0.95 on matched ticks.
  After fix, in-game R² ≥ 0.0.
- **Owner:** Naomi (ML) + Amos (Systems — run infrastructure)

### Proposal 2: Improve CircularGun targeting accuracy
- **Target metric:** CircularGun HR from 3.5% → ≥ 5%
- **Method:** Profile CircularGun miss patterns. Likely causes: stale
  scan data (firing on old position), incorrect bullet speed for
  predicted fire power, or circular prediction failing for non-circular
  movers. Consider adding lateral velocity weighting or GF-based
  correction.
- **How to measure:** R02 gun accuracy notebook per-gun HR.
- **Owner:** Bobbie (Targeting)

### Proposal 3: Retrain models on merged rumble + local data
- **Target metric:** Offline R² ≥ 0.86 for fire power and movement
- **Method:** Combine original ~1944-battle rumble dataset with local
  battle data. Retrain all 3 models. This restores generalization while
  incorporating local opponent behavior.
- **How to measure:** Offline R² on held-out test set.
- **Owner:** Naomi (ML)

---

## 8. Sprint Result: **HIT** (marginal)

**Target:** Diagnose feature divergence, fix gun selection, improve movement.
**Actual:**
- Gun selection **FIXED** — CircularGun now primary (68%, best HR).
  HeadOnGun demoted to 4%. Sanity check #3 passes for the first time.
- Movement **IMPROVED** — 6 opponents gained ≥1 pp, none regressed ≥1 pp.
  Cleanest movement result in project history.
- Feature divergence **PARTIALLY ADDRESSED** — diagnostic infrastructure
  built, code review confirmed no algorithmic mismatch. Runtime
  comparison not yet executed.
- Overall score **6.1%** — new project record (+1.0 pp over Sprint 8).
- All 6 mandatory sanity checks **PASS** for the first time.

**Why "marginal":** The primary ML issue (fire power R²=−3.67) remains
unresolved. The diagnostic tooling was built but the actual diagnosis
didn't happen this sprint. The score improvement is real but comes from
gun ordering and movement fixes, not from ML models working correctly.
The binding constraint hasn't changed: until in-game features match
training features, ML predictions will remain anti-correlated with
reality. Sprint 10 must execute the feature comparison.

**Trajectory:** 5.4% → 5.1% → **6.1%**. First upward sprint in two
cycles. The gun and movement fixes are delivering real value. If the
ML models can be made to work in-game, the score ceiling is much higher.
