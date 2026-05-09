# Iterative Improvement Process

## Goal

Achieve a measurable improvement target (e.g. 20% win rate) through
disciplined iteration cycles. Each cycle produces evidence-based decisions
about what to keep, revert, or try next.

---

## Cycle Structure

### 1. Implement (max 3 proposals from previous round)

- Apply the code changes proposed in the previous retrospective.
- If the proposal is a major architecture or ML algorithm change, it must be
  the **only** change in the cycle — no other proposals alongside it.
  This isolates large-effect variables.
- Each change must be independently revertable (separate logical commits
  or clearly marked sections).

### 2. Retrain ML models

- Run `train_distill.py` on the latest CSV data.
- Export to Java via `export_gbm_java.py`.
- Record training metrics (R², MAE, AUC) in the retrospective.

### 3. Run battles

- Build the robot JAR and deploy.
- Run via `local-pipeline.ps1` against the **fixed opponent set** (same set
  every cycle for comparability).
- Process recordings into CSVs.

### 4. Run diagnostic notebooks

Before drawing ANY conclusions, verify system health:

#### 4a. Mandatory sanity checks (must ALL pass before proceeding)

| # | Check | How | Pass criteria |
|---|-------|-----|---------------|
| 1 | **TickBudget** | `grep "budget=" debug.log \| tail -5` | budget ≥ 100 (models at ≥50% capacity) |
| 2 | **Skipped turns** | `grep SKIPPED debug.log \| wc -l` | < 5 per battle; none in steady-state (round > 0) |
| 3 | **Gun selection** | Per-gun hit rates from internal.csv | All guns have data; selected gun has highest rate |
| 4 | **ML prediction ranges** | PREDICTED_FIRE_POWER, PREDICTED_LAT_VEL_5, PREDICTED_OPPONENT_FIRES_3 from internal.csv | Fire power ∈ [0.1, 3.0], lat vel ∈ [-8, 8], fire prob ∈ [0, 1] |
| 5 | **Wave detection** | Count opponent waves in flight from internal.csv | Average > 0.5 waves in flight per tick |
| 6 | **Feature completeness** | Check for NaN columns in ticks.csv | No critical features permanently NaN |

If ANY check fails, **fix the broken system first** — do not proceed to
performance analysis. The failure IS the retrospective finding.

#### 4b. Performance metrics (compare with previous cycle)

| Dimension | Source | Key metrics |
|-----------|--------|-------------|
| **Survival** | scores.csv | Win rate, avg ticks survived, rounds won |
| **Offense** | scores.csv + ticks.csv | Our hit rate, damage dealt/round, fire power used |
| **Defense** | scores.csv + ticks.csv | Opponent hit rate, damage received/round |
| **Movement** | ticks.csv / internal.csv | Avg velocity, time at max speed, wall collisions |
| **Targeting** | internal.csv | Per-gun selection %, per-gun hit rates |
| **ML quality** | internal.csv vs ticks.csv | In-game prediction error vs offline training error |

#### 4c. In-game vs offline prediction comparison

For each ML model, compare the in-game predictions (from internal.csv) with
what the offline model would predict on the same features (from ticks.csv):

- **Fire power:** Does PREDICTED_FIRE_POWER match the offline model's output
  when given the same feature vector? Large discrepancy = feature mismatch
  between Java and Python.
- **Movement:** Does PREDICTED_LAT_VEL_5 track actual opponent lateral
  velocity 5 ticks later? Compute in-game R² and compare with training R².
- **Fire timing:** Is the fire probability calibrated? Bin predictions into
  deciles and check actual fire rates per bin.

### 5. Write retrospective

The retrospective document goes in `archive/YYYY-MM-DD-retrospective-N.md`.

Required sections:

1. **Diagnostic health** — results of all 6 sanity checks. If any failed,
   this dominates the retrospective.
2. **Metrics table** — all dimensions from 4b, with columns for previous
   cycle and current cycle and delta.
3. **Per-opponent breakdown** — win rate, hit rates, damage for each
   opponent, sorted by win rate descending.
4. **What worked** — which of the implemented proposals improved which
   metrics, with specific numbers.
5. **What didn't work** — which proposals hurt or had no effect, with
   specific numbers. These will be reverted.
6. **Root cause analysis** — for the largest remaining gap to the target,
   identify the proximate cause with evidence (not speculation).
   Bad: "opponents are too strong."
   Good: "opponent HR 35% because wave surf only activates on 12% of ticks
   (measured: avg 0.3 waves in flight) — most combat happens without any
   active dodge."
7. **Proposals for next cycle** — max 3 concrete changes, or 1 if it's a
   major architecture change. Each proposal must state:
   - What metric it targets
   - Expected effect (directional, with rough magnitude)
   - How to measure success/failure

### 6. Revert and commit

- **Revert** changes that caused measurable harm (metrics went down with
  no compensating improvement elsewhere).
- **Commit** the net-positive changes with a message summarizing the cycle
  number, key metric deltas, and what was kept vs reverted.

### 7. Repeat

Go back to step 1 with the new proposals.

---

## Anti-patterns to avoid

| Anti-pattern | Why it's bad | What to do instead |
|---|---|---|
| Blame the opponent | Avoids diagnosing our own bugs | Check sanity list first |
| Change 5 things at once | Can't attribute improvement/regression | Max 3 proposals (1 if major) |
| Skip notebooks, eyeball battle output | Misses systemic issues (budget=10) | Always run full diagnostic |
| Conclude from 1 battle | Variance in 35 rounds is huge | Min 3 battles × 35 rounds per opponent |
| Keep broken changes "just in case" | Accumulates debt, masks effects | Revert what hurt, commit what helped |
| Tune hyperparameters before checking health | Optimizing a broken system | Sanity checks are step 1, not optional |
| Write retrospective without data | Produces narrative, not insight | Every claim needs a number |

---

## Fixed opponent set

Use the same set across all cycles for comparability. Include a mix of
difficulty levels so we can track progress across the spectrum:

```powershell
$opponents = @(
    # Strong (top 10-20 tier)
    'abc.Shadow', 'voidious.Diamond', 'jk.mega.DrussGT',
    # Upper-mid (top 20-50 tier)
    'cx.BlestPain', 'ary.FourWD', 'ej.ChocolateBar',
    'dft.Cardigan', 'eem.zapper', 'darkcanuck.Pris',
    # Mid (top 50-100 tier)
    'florent.FloatingTadpole', 'kid.Gladiator',
    'da.NewBGank', 'ary.Help', 'gh.GresSuffurd',
    # Lower-mid
    'rdt.AgentSmith.AgentSmith', 'fromHell.BlackBox'
)
```

---

## Measurement infrastructure

- **Retrospective notebooks** live in `intuition/retrospective/`.
- Each notebook reads from `output/local/csv/` and is self-contained.
- Notebooks should be parameterized to compare two runs (current vs baseline).
- Add new metric cells as new analysis needs arise — never delete old metrics,
  only add.
