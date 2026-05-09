# Retrospective 8 — Fix Critical Systems + Sanity Script

*Date: 2026-05-09 · Sprint: 8 (Fix 3 critical issues + automated sanity)*
*Previous: [2026-05-09-retrospective-7.md](2026-05-09-retrospective-7.md)*

## Context

Sprint 7 ended BLOCKED with three critical issues: fire power model broken
in-game (R²=−0.61), gun selection defaulting to HeadOnGun (54%), and
movement too slow (64% at max speed). Sprint 8 targeted all three fixes
plus an automated sanity-check script.

**Changes delivered (4 branches, all reviewed and approved by Holden):**

1. **Naomi — fix-feature-parity**: Fixed window feature computation order in
   `train_distill.py` (fire events filtered AFTER window computation). Fixed
   Java `WindowFeatures` ddof=0→ddof=1 (Bessel's correction). Retrained fire
   power model (offline R² 0.862→0.946).
2. **Bobbie — fix-gun-ordering**: Increased VGM epsilon 0.01→0.03. Fixed
   bestRate ratchet-down bug in tie-break. Added 11 unit tests.
3. **Alex — fix-movement-velocity**: Fixed wall-proximity oscillation (cooldown
   gate). Increased flip interval 15-45→25-55 ticks. Wired pre-emptive dodge
   (was dead code). Implemented dodge commitment (4-tick hold). Added 6 unit tests.
4. **Amos — fix-sanity-script**: Created `scripts/sanity-check.ps1` +
   `sanity_check.py`. 6 mandatory + 3 bonus ML checks. Exit code 0/1/2.

**Process note:** The first merge attempt skipped the Phase 2b review gate.
Two fixes appeared to backfire due to unreviewed interactions. Sprint was
restarted with proper review. Holden reviewed all 4 branches, approved all,
then merged. See §8 Process Lesson below.

**Evaluation setup:** 48 battles, 16 opponents from the fixed set, 3 battles
× 35 rounds each.

---

## 1. Diagnostic Health

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 1 | TickBudget ≥ 100 | **PASS** | mean=200, min=200, max=200 (full capacity) |
| 2 | Skipped turns < 5 | **PASS** | avg 0.4/battle, max 4 |
| 3 | Gun selection | **FAIL** | gun0 selected 67%, best HR gun3 at 3.6% |
| 4 | ML predictions in range | **PASS** | all in range, variance OK |
| 5 | Wave detection | **PASS** | avg 1.21 waves/tick (up from 1.09) |
| 6 | Feature completeness | **PASS** | 0 critical NaN |
| B1 | Fire power R² (in-game) | **WARN** | −3.46 (worse than Sprint 7's −0.61) |
| B2 | Fire timing calibration | **WARN** | 59% predicted vs 3% actual (was 83%) |
| B3 | Prediction distribution | **PASS** | healthy variance |

**Sanity check #3 still fails.** Gun selection remains broken — gun0 at 67%
(up from 54%). Fire power model R² degraded further despite offline improvement.

---

## 2. Metrics Table

| Metric | Sprint 7 | Sprint 8 | Delta | Note |
|---|---|---|---|---|
| Overall score % | 5.4% | **5.1%** | **−0.3 pp** | Slight regression |
| Battle win rate | 0.0% | **0.0%** | — | Still zero |
| Fire power R² (in-game) | −0.61 | **−3.46** | −2.85 | Got worse despite offline gains |
| Fire power R² (offline) | 0.862 | **0.946** | +0.084 | Offline improved |
| Fire timing (pred vs actual) | 83% vs 3% | **59% vs 3%** | Improved | Still heavily miscalibrated |
| Gun selection: gun0 % | 54% | **67%** | +13 pp | Worse — primary gun still wrong |
| Wave detection (waves/tick) | 1.09 | **1.21** | +0.12 | Slightly improved |
| Skipped turns (avg/battle) | 0.2 | **0.4** | +0.2 | Still healthy |

---

## 3. Per-Opponent Breakdown

*Sorted by Sprint 8 score % descending. Delta = Sprint 8 − Sprint 7.*

| Opponent | Sprint 8 | Sprint 7 | Delta | Tier |
|---|---|---|---|---|
| cx.BlestPain | 14.7% | 12.0% | **+2.7** | Upper-mid |
| ej.ChocolateBar | 12.3% | 10.3% | **+2.0** | Upper-mid |
| kid.Gladiator | 9.7% | 5.0% | **+4.7** | Mid |
| ary.FourWD | 7.3% | 7.7% | −0.4 | Upper-mid |
| florent.FloatingTadpole | 5.7% | 9.7% | **−4.0** | Mid |
| eem.zapper | 4.7% | 5.0% | −0.3 | Upper-mid |
| da.NewBGank | 4.3% | 5.3% | −1.0 | Mid |
| ary.Help | 4.0% | 5.3% | −1.3 | Mid |
| rdt.AgentSmith | 3.7% | 3.3% | +0.4 | Lower-mid |
| gh.GresSuffurd | 3.0% | 2.3% | +0.7 | Mid |
| dft.Cardigan | 2.7% | 4.7% | **−2.0** | Upper-mid |
| abc.Shadow | 2.0% | 1.0% | +1.0 | Strong |
| darkcanuck.Pris | 2.0% | 2.0% | — | Upper-mid |
| jk.mega.DrussGT | 2.0% | 2.3% | −0.3 | Strong |
| voidious.Diamond | 1.7% | 2.0% | −0.3 | Strong |
| fromHell.BlackBox | 1.3% | 2.3% | −1.0 | Lower-mid |

**Winners (+1 pp or more):** BlestPain, ChocolateBar, Gladiator, Shadow,
GresSuffurd — mostly mid-tier opponents.

**Losers (−1 pp or more):** FloatingTadpole (−4.0), Cardigan (−2.0),
Help (−1.3), NewBGank (−1.0), BlackBox (−1.0) — mixed tiers.

Net: gains and losses roughly cancel. No systematic improvement.

---

## 4. What Worked

### Sanity-check script (Amos)
- **First automated diagnostic tool.** 6 mandatory + 3 bonus checks with
  clear pass/fail/warn. Exit codes 0/1/2 for CI integration.
- Immediately caught gun selection failure and fire power R² regression.
- This is infrastructure that pays dividends every sprint. **Keep.**

### Code quality improvements
- **17 new unit tests** (11 gun ordering + 6 movement). First meaningful
  test coverage for VGM and movement subsystems.
- Bessel's correction fix (ddof=0→ddof=1) is correct regardless of
  performance impact — training and inference must match.
- Fire timing calibration improved (83%→59% predicted fire rate). Still
  bad, but directionally correct.

### Offline model improvement (Naomi)
- Fire power offline R² improved 0.862→0.946 via corrected window
  feature computation order. The training pipeline is now more correct.

### Wave detection improved
- 1.09→1.21 waves/tick, suggesting movement changes improved wave tracking.

### Process fix
- Identified the review-gate skip as root cause of the initial failed merge.
- Re-ran sprint with proper review. All 4 branches approved before merge.

---

## 5. What Didn't Work

### Fire power model still broken in-game (R² = −3.46, WORSE than −0.61)
- Offline R² improved 0.862→0.946, but in-game R² degraded −0.61→−3.46.
- The feature parity fix (filter order + Bessel's correction) fixed the
  **training side** but may have introduced a **new** divergence on the
  Java inference side, or the retrained model is now more sensitive to
  remaining mismatches.
- **This is the #1 problem.** The model is anti-correlated with reality.

### Gun selection still broken (67%, WORSE than 54%)
- Bobbie's epsilon fix (0.01→0.03) and ratchet-down bug fix were correct
  in isolation, but gun0 selection increased from 54%→67%.
- Likely cause: wider epsilon (3%) means more guns fall within the "tie"
  band, and the tie-break order is still wrong, or the bug fix changed
  which gun wins ties in an unexpected way.

### Overall score regressed (5.4%→5.1%)
- Net −0.3 pp. Within noise for 48 battles, but not the improvement
  targeted. The sprint goal was to fix broken systems — two of three
  fixes appear to have backfired in practice.

### Movement changes mixed
- Some opponents show improvement (Gladiator +4.7 pp), others show
  regression (FloatingTadpole −4.0 pp). The dodge commitment and
  wider flip interval help against predictable opponents but may hurt
  against adaptive ones.

---

## 6. Root Cause Analysis

### Primary gap: Fire power R² = −3.46 (offline 0.946 → in-game −3.46)

**Proximate cause:** The feature parity fix corrected training but widened
the Java/Python divergence at inference time.

**Evidence:**
- Offline R² improved (model is better when given correct features).
- In-game R² got worse (model is now MORE sensitive to feature
  mismatches because it was trained on correct features).
- The ddof=0→ddof=1 fix means Java now computes sample stddev
  (divides by n−1), but if the **retrained model** was trained on
  ddof=1 features while any remaining Java feature still uses ddof=0,
  the mismatch is wider than before.
- Sprint 7's model tolerated Java's ddof=0 because it was also trained
  on ddof=0 data. Now training uses ddof=1 but Java uses ddof=1 too —
  so the ddof fix is internally consistent. The remaining mismatch must
  be elsewhere: feature ordering, NaN handling, or the filter-order fix
  in `train_distill.py` not being mirrored in Java.

**Root cause hypothesis:** The `train_distill.py` fix (filter fire events
AFTER window computation) changed which rows the model trains on and what
window statistics it sees. The Java `WindowFeatures` may still compute
windows using the old row order (no fire-event filtering concept in Java
because Java computes live). This creates a systematic feature distribution
shift that the more-accurate model amplifies.

### Secondary gap: Gun selection at 67%

**Proximate cause:** Epsilon increase from 1%→3% expanded the tie band,
and the tie-break resolution still favors gun0.

**Evidence:** Best HR is gun3 at 3.6%, meaning all guns are below 4%.
With 3% epsilon, almost all guns fall within the tie band of each other.
The tie-break logic (ratchet-down fix) may now resolve ties differently
than intended.

---

## 7. Proposals for Next Sprint

### Proposal 1: Diagnose Java/Python feature divergence with logging
- **Target metric:** Fire power in-game R² from −3.46 → ≥ 0.0
- **Expected effect:** Identify exactly which features diverge. Print
  Java feature vectors to a log file and compare with Python predictions
  on identical tick data. Fix the specific mismatched features.
- **How to measure:** Feature-by-feature comparison on ≥100 matched ticks.
  Correlation per feature must be ≥0.95. After fix, in-game R² ≥ 0.0.
- **Owner:** Naomi (ML) + Amos (Systems — Java logging infrastructure)
- **Note:** This is a diagnostic sprint. No new features until the
  fire power model works in-game.

### Proposal 2: Fix gun tie-break to respect priority ordering
- **Target metric:** Gun0 selection from 67% → < 25%; primary gun ≥ 40%
- **Expected effect:** When guns are within epsilon, tie-break should
  favor higher-priority guns (CircularGun first per Decision #10).
  Verify the gun list order and tie-break logic end-to-end.
- **How to measure:** R08 gun selection percentages. Write a unit test
  that asserts tie-break order matches the gun priority list.
- **Owner:** Bobbie (Targeting)

### Proposal 3: Hold movement changes, measure in isolation
- **Target metric:** Confirm movement changes are net-positive
- **Expected effect:** Run evaluation with ONLY movement changes
  (revert gun + ML changes) to isolate movement impact. The mixed
  per-opponent results (some +4.7 pp, some −4.0 pp) need to be
  understood before committing.
- **How to measure:** Run 16-opponent eval with movement branch only.
  If overall score ≥ 5.4% (Sprint 7 baseline), keep. Otherwise revert.
- **Owner:** Alex (Movement)

---

## 8. Process Lesson: Don't Skip the Review Gate

**What happened:** The first Sprint 8 merge attempt skipped Phase 2b
(code review). All 4 branches were merged directly to main without
Holden's review. Two fixes appeared to backfire — score dropped and
gun selection worsened. It was unclear whether the code was wrong or
the fixes interacted badly.

**What we did:** Reverted to pre-merge state. Holden reviewed all 4
branches individually, approved each, then merged one at a time.
The final evaluation showed similar results, confirming the issues
are in the approach (not in review-skipping bugs), but the process
violation meant we wasted a full cycle on an ambiguous merge.

**Rule reinforced:** Phase 2b (review gate) is mandatory. No branch
merges without reviewer approval. This is in `sprint.md` and must
not be skipped under time pressure.

---

## Sprint Result: **MISS**

**Target:** Fix fire power R², gun selection, and movement velocity.
**Actual:** Fire power R² worsened (−0.61→−3.46). Gun selection worsened
(54%→67%). Movement mixed. Overall score −0.3 pp.

**However — net positive infrastructure:**
- 4 correct code fixes landed (training pipeline, Bessel's correction,
  VGM epsilon, movement oscillation, dead-code wiring, dodge commitment).
- 17 new unit tests added.
- Automated sanity-check script operational.
- Process gap (missing review gate) identified and fixed.
- Offline model quality improved (R² 0.862→0.946).

**The fixes are directionally correct but expose a deeper problem:**
the Java/Python feature divergence is the binding constraint. Until
in-game features match training features, no amount of offline model
improvement will help. Sprint 9 must be a diagnostic sprint focused
on feature-by-feature Java/Python comparison.
