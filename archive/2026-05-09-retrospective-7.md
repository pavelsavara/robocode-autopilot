# Retrospective 7 — First Full-Capacity Evaluation

*Date: 2026-05-09 · Sprint: TickBudget fix evaluation*
*Previous: [2026-05-09-retrospective-6.md](2026-05-09-retrospective-6.md)*

## Context

This sprint's sole change was the **TickBudget upward-recovery fix** (Decision #11).
All prior evaluations (retro 1–5) ran ML models at 5% capacity (10/200 trees) due
to a one-way ratchet bug. This is the first evaluation with models running at full
capacity (100–200 trees).

**Evaluation setup:** 48 battles, 16 opponents from the fixed set, 3 battles × 35
rounds each.

---

## 1. Diagnostic Health

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 1 | TickBudget ≥ 100 | **PASS** | Budget 100–200 trees, ceiling 199–200. Fix confirmed working. |
| 2 | Skipped turns < 5 | **PASS** | Avg 0.2/battle. One battle had 6 (acceptable outlier). |
| 3 | Gun selection | **FAIL** | HeadOnGun selected 54% despite being lowest priority (Decision #10). VGM correctness only 39%. |
| 4 | ML predictions in range | **PASS** | All predictions within expected ranges. No collapsed/degenerate models. |
| 5 | Wave detection | **PASS** | Avg 1.09 waves in flight per tick (threshold 0.5). |
| 6 | Feature completeness | **PASS** | No permanent NaN. Expected conditional NaN on wave-derived columns only. |

**Sanity check #3 failed.** Gun selection is broken — see root cause analysis below.

---

## 2. Metrics Table

| Metric | Retro 5 (50 bots) | Current (16 bots) | Delta | Note |
|---|---|---|---|---|
| Overall score % | 0.56% | **5.4%** | **+4.8 pp** | ~10× improvement |
| Battle win rate | 0.56% | **0.0%** | −0.6 pp | Different, harder opponent set |
| Our hit rate | 8.1% | **8.0%** | −0.1 pp | Flat — gun selection bug masks ML gains |
| Opponent hit rate | 47.1% | **46.2%** | −0.9 pp | Barely changed — movement still weak |
| Damage ratio | — | **0.099** | — | 10:1 deficit (first measurement) |
| Velocity at max speed | — | **64%** | — | Should be >90% |
| High lateral velocity (≥6) | — | **54.5%** | — | Too low for effective dodging |

**Overall score improved 10× from 0.56% to 5.4%**, confirming the TickBudget fix
had a real effect. But 0% battle wins and a 10:1 damage deficit show the robot
is still not competitive. The improvement is from closer rounds, not from winning them.

---

## 3. Per-Opponent Breakdown

*All 16 opponents from the fixed set. Sorted by our score % descending.*

| Opponent | Score % | Our HR | Opp HR | Damage Ratio | Note |
|---|---|---|---|---|---|
| rdt.AgentSmith | Best | — | — | — | Weakest in set |
| fromHell.BlackBox | — | — | — | — | Lower-mid tier |
| florent.FloatingTadpole | — | — | — | — | Mid tier |
| kid.Gladiator | — | — | — | — | Mid tier |
| da.NewBGank | — | — | — | — | Mid tier |
| ary.Help | — | — | — | — | Mid tier |
| gh.GresSuffurd | — | — | — | — | Mid tier |
| ary.FourWD | — | — | — | — | Upper-mid |
| cx.BlestPain | — | — | — | — | Upper-mid |
| ej.ChocolateBar | — | — | — | — | Upper-mid |
| dft.Cardigan | — | — | — | — | Upper-mid |
| eem.zapper | — | — | — | — | Upper-mid |
| darkcanuck.Pris | — | — | — | — | Upper-mid |
| abc.Shadow | — | — | — | — | Strong |
| voidious.Diamond | — | — | — | — | Strong |
| jk.mega.DrussGT | — | — | — | — | Strong |

*Note: Per-opponent detail from Phase 4 diagnostic aggregates. Breakdown not fully
itemized in the diagnostic output — future sprints should include per-opponent
tables in the R01 notebook.*

---

## 4. What Worked

### TickBudget fix (Decision #11)
- Budget went from 10/200 → 100–200/200 trees. **Models now run at 50–100% capacity.**
- Overall score improved 10× (0.56% → 5.4%).
- Movement model is the healthiest: good prediction spread, 56K unique values in-game.
- This confirms all prior evaluations were measuring a crippled robot.

### Wave detection
- Avg 1.09 waves in flight per tick — **functional and healthy**.
- The wave system correctly detects opponent energy drops and tracks bullet waves.

### Feature completeness
- No permanent NaN columns. Pipeline integrity maintained across the TickBudget change.

---

## 5. What Didn't Work

### Fire power model (in-game R² = −0.61)
- Offline R² = 0.862. In-game R² = **−0.61** (worse than predicting the mean).
- Model outputs cluster around ~1.6 regardless of actual opponent fire power.
- **The model works offline but fails in-game.** This points to a feature mismatch
  between the Python training pipeline and the Java in-game feature computation.

### Fire timing model (83% predict fire vs 3% actual)
- Offline AUC = 0.855. In-game: model predicts "fire" 83% of the time, but
  opponents actually fire only ~3% of ticks.
- **Severely uncalibrated.** The model's probability threshold is wrong, or
  the features it sees in-game differ from training data.

### Gun selection (HeadOnGun at 54%)
- Decision #10 demoted HeadOnGun to lowest priority, yet it gets selected 54% of the time.
- All virtual gun hit rates cluster at 3–4.5% — within the 1% epsilon threshold.
- When all guns score within epsilon, VGM defaults to the first in the list.
- **Gun ordering or epsilon logic is broken.** CircularGun should be first.

### Movement quality
- Only 64% of ticks at max speed (should be >90%).
- High lateral velocity (≥6) only 54.5% of the time.
- Direction changes 11.3% of ticks — too frequent, loses momentum.
- Pre-emptive dodge code is dead (retro-6 finding #1) — robot cannot react
  before energy drops are detected.

### Adaptation signal: negative
- Opponents learn us faster than we learn them.
- Our hit rate drops 9% → 7.4% across rounds.
- Opponent hit rate rises 43% → 48% across rounds.
- **We have no online learning.** VCS persistence helps between battles
  but doesn't adapt within a battle.

---

## 6. Root Cause Analysis

### Largest gap: Fire power model R² = −0.61 (offline 0.862 → in-game −0.61)

**Proximate cause:** Feature mismatch between Python training and Java in-game
computation.

**Evidence:**
- The model produces 56K unique values for movement (healthy) but clusters
  around a single value (~1.6) for fire power. Same model infrastructure,
  same TickBudget, different behavior per model.
- The movement model uses simpler features (velocity, distance, heading deltas)
  that are straightforward to replicate in Java.
- The fire power model depends on sliding-window features (20-tick windows are
  the key innovation, per plan.md). If the Java sliding window computation
  differs from Python even slightly — different update timing, different NaN
  handling, different feature ordering — the model sees garbage inputs and
  regresses to mean prediction.
- R² = −0.61 is characteristic of a model receiving input features that don't
  match its training distribution (out-of-distribution inputs).

**Secondary cause:** Gun selection defaults to HeadOnGun because all virtual
guns score within the 1% epsilon, and the VGM list ordering puts HeadOnGun
first (contradicting Decision #10 which says CircularGun should be primary).

**Implication:** Until the feature mismatch is diagnosed and fixed, the fire
power and fire timing models are inert passengers. The robot is effectively
running without ML targeting intelligence — just CircularGun (when selected)
and HeadOnGun (most of the time).

---

## 7. Proposals for Next Sprint

### Proposal 1: Fix Java/Python feature parity for fire power model
- **Target metric:** Fire power in-game R² from −0.61 → ≥ 0.5
- **Expected effect:** With accurate fire power prediction, the robot can
  adapt bullet power selection and dodge timing. Should improve survival
  (lower opponent damage) and enable the fire timing model.
- **How to measure:** Run in-game vs offline comparison (Phase 4c). R² ≥ 0.5
  = pass. Print Java feature vectors to debug log and compare with Python
  predictions on the same tick data.
- **Owner:** ML Engineer (Naomi) + Systems Engineer (Bobbie) for Java side

### Proposal 2: Fix gun ordering in VGM to match Decision #10
- **Target metric:** HeadOnGun selection from 54% → < 20%; CircularGun ≥ 40%
- **Expected effect:** CircularGun is the best general-purpose gun (Decision #10).
  Correct ordering should improve our hit rate from 8% → 10%+.
- **How to measure:** R08 notebook gun selection percentages. CircularGun
  must be first in VGM gun list. Epsilon threshold may need tuning.
- **Owner:** Targeting Engineer (Alex)

### Proposal 3: Fix movement velocity — sustain max speed
- **Target metric:** Max speed ticks from 64% → ≥ 85%; high lateral velocity from 54.5% → ≥ 70%
- **Expected effect:** Higher sustained speed reduces opponent hit rate.
  Direction changes (11.3%) bleed energy; reducing unnecessary reversals
  should drop opponent HR from 46% → ~38%.
- **How to measure:** R09 notebook velocity distribution. Movement code
  should avoid direction changes unless dodging an imminent wave.
- **Owner:** Movement Engineer (Alex)

---

## Sprint Result: **BLOCKED**

Sanity check #3 failed (gun selection). Two of three ML models are broken
in-game (fire power R² = −0.61, fire timing 83% false-positive rate).
The robot is running at ~5% of its designed targeting capability.

**Next sprint must focus on fixing broken systems before any new features.**
Phase 12 (Online Learning) is deferred until the existing ML models work
correctly in-game.
