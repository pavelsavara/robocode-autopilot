# Sprint Process

*Each sprint targets a measurable improvement (e.g. +5% win rate).
See [team.md](team.md) for roles and artifact ownership.*

---

## Sprint Cadence

A sprint has **5 phases** over a fixed timebox. Ralph opens and closes
each phase. No phase may be skipped.

```
Day 1          Day 2          Day 3          Day 4          Day 5
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ Planning │  │  Build   │  │  Battle  │  │ Diagnose │  │  Retro   │
│ & Design │  │ & Test   │  │ & Record │  │ & Analyse│  │ & Commit │
└──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘
   Ralph         Dev team     Systems Eng   ML + All       Ralph
```

---

## Phase 1: Planning (Ralph leads)

**Inputs:** Previous retrospective's proposals, current win-rate gap.

1. **Ralph** selects max 3 proposals from the previous retrospective.
   If a proposal is a major architecture or ML algorithm change, it must
   be the **only** item — no other proposals alongside it.
2. **Ralph** assigns each proposal to the responsible engineer:
   - Movement changes → Movement Engineer
   - Gun changes → Targeting Engineer
   - ML model changes → ML Engineer
   - Pipeline/infra changes → Systems Engineer
3. **Ralph** confirms the **fixed opponent set** (same set every sprint):

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

**Exit criteria:** Every engineer knows what they're building and which
metric they're targeting.

---

## Phase 2: Build & Test

### Engineers implement their assigned proposals

- Each change goes on a **separate branch** (independently revertable).
- Code must pass existing tests before requesting review.

### Test Author writes tests for new code

- Every new or changed class gets unit tests covering:
  - Normal operation with typical inputs
  - Edge cases: NaN inputs, zero velocity, wall boundaries, empty histograms
  - Regression tests for any bug the change fixes
- Integration test: full Transformer pipeline with synthetic Whiteboard state
  produces plausible feature values.

### Code Quality Reviewer reviews each branch

Review checklist:
- [ ] `final` classes unless designed for inheritance
- [ ] `static` inner classes unless outer reference needed
- [ ] No mutable state in feature classes (all state in Whiteboard)
- [ ] No I/O in core module
- [ ] No per-tick heap allocation in hot paths
- [ ] Persistence format backward-compatible (or version bumped)
- [ ] Tests present and meaningful

**Gate:** No branch merges to `main` without passing review and tests.

### ML Engineer retrains models (if data or features changed)

- Run `train_distill.py` on latest CSV data.
- Export to Java via `export_gbm_java.py`.
- Record training metrics (R², MAE, AUC) — these go into the retrospective.

**Exit criteria:** All branches merged to `main`, all tests green,
robot JAR builds cleanly.

---

## Phase 3: Battle & Record (Systems Engineer leads)

1. Build the robot JAR: `.\gradlew.bat clean :robot:jar`
2. Deploy to `c:\robocode\robots\`
3. Delete stale `autopilot.dat` (clean evaluation, no carryover)
4. Run battles via `local-pipeline.ps1` against the fixed opponent set:
   - 3 battles × 35 rounds per opponent
   - Process recordings into CSVs
5. Verify recording count matches expected (opponents × battles)

**Exit criteria:** `output/local/csv/` populated with complete data for all
opponents.

---

## Phase 4: Diagnose & Analyse (all engineers)

### 4a. Mandatory sanity checks — must ALL pass

| # | Check | Owner | How | Pass criteria |
|---|-------|-------|-----|---------------|
| 1 | **TickBudget** | Systems Eng | `grep "budget=" debug.log` | budget ≥ 100 (≥50% model capacity) |
| 2 | **Skipped turns** | Systems Eng | `grep SKIPPED debug.log` | < 5 per battle; none in steady-state |
| 3 | **Gun selection** | Targeting Eng | Per-gun hit rates from internal.csv | All guns have data; best gun selected |
| 4 | **ML predictions** | ML Engineer | PREDICTED_* ranges from internal.csv | Fire power ∈ [0.1, 3.0], lat vel ∈ [-8, 8], fire prob ∈ [0, 1] |
| 5 | **Wave detection** | Movement Eng | Opponent waves in flight count | Avg > 0.5 waves in flight per tick |
| 6 | **Feature completeness** | ML Engineer | NaN columns in ticks.csv | No critical features permanently NaN |

**If ANY check fails:** Stop. The failure IS the sprint finding. Fix the
broken system. Do not proceed to performance analysis.

### 4b. Performance metrics (each engineer runs their notebooks)

| Dimension | Notebooks | Owner |
|-----------|-----------|-------|
| **Survival** (win rate, ticks survived) | R01 | Systems Eng |
| **Offense** (hit rate, damage dealt) | R02, R08 | Targeting Eng |
| **Defense** (opponent HR, damage received) | R04, R09 | Movement Eng |
| **Damage balance** (dealt vs received) | R03 | Systems Eng |
| **ML quality** (prediction accuracy) | R05, R10 | ML Engineer |
| **Adaptation** (round trends) | R06 | Systems Eng |

Every metric must have a **previous sprint** column and a **delta**.

### 4c. In-game vs offline prediction comparison (ML Engineer)

For each ML model, compare in-game predictions (internal.csv) with
offline model output on the same features (ticks.csv):

- **Fire power:** in-game PREDICTED_FIRE_POWER vs offline model. Large
  discrepancy = feature mismatch between Java and Python.
- **Movement:** PREDICTED_LAT_VEL_5 vs actual lateral velocity 5 ticks later.
  Compute in-game R² and compare with training R².
- **Fire timing:** Bin predictions into deciles, check actual fire rates
  per bin (calibration plot).

**Exit criteria:** All 6 sanity checks pass. All notebook metrics computed
and compared with previous sprint.

---

## Phase 5: Retrospective & Commit (Ralph leads)

### 5a. Write retrospective

Goes in `archive/YYYY-MM-DD-retrospective-N.md`. **Ralph writes it** using
data from the engineers' notebooks.

Required sections:

1. **Diagnostic health** — results of all 6 sanity checks with pass/fail.
2. **Metrics table** — all dimensions from 4b with previous / current / delta
   columns.
3. **Per-opponent breakdown** — win rate, hit rates, damage per opponent,
   sorted by win rate descending.
4. **What worked** — which proposals improved which metrics, with numbers.
5. **What didn't work** — which proposals hurt or had no effect, with numbers.
6. **Root cause analysis** — for the largest remaining gap, identify the
   proximate cause with evidence.
   Bad: "opponents are too strong."
   Good: "opponent HR 35% because wave surf only activates on 12% of ticks
   (measured: avg 0.3 waves in flight)."
7. **Proposals for next sprint** — max 3 changes (or 1 if major). Each states:
   - What metric it targets
   - Expected effect (directional + rough magnitude)
   - How to measure success/failure
   - Who implements it

### 5b. Revert and commit

- **Revert** branches that caused measurable harm.
- **Commit** net-positive changes to `main` with a message:
  `Sprint N: <key delta>, kept <X>, reverted <Y>`
- **Ralph reviews** the final commit message and retrospective.

### 5c. Sprint close

Ralph declares the sprint result:
- **Win:** target metric improved by the sprint goal amount → update plan.md
- **Miss:** target not met → proposals carry into next sprint
- **Blocked:** sanity check failure dominated → next sprint focuses on the fix

---

## Anti-patterns

| Anti-pattern | Why it's bad | What to do instead |
|---|---|---|
| Blame the opponent | Avoids diagnosing our own bugs | Sanity checks first |
| Change 5 things at once | Can't attribute effect | Max 3 proposals (1 if major) |
| Skip notebooks | Misses systemic issues (budget=10) | Always run full diagnostic |
| 1 battle per opponent | Variance too high | Min 3 battles × 35 rounds |
| Keep broken changes | Accumulates debt | Revert what hurt |
| Tune before checking health | Optimizing a broken system | Sanity checks are step 1 |
| Retrospective without data | Narrative, not insight | Every claim needs a number |
| Merge without review | Quality regressions | Code Quality Reviewer gates all merges |
| Merge without tests | Bugs hide until battle | Test Author covers all new code |

---

## Measurement Infrastructure

- **Retrospective notebooks** live in `intuition/retrospective/`.
- Each reads from `output/local/csv/` and is self-contained.
- Notebooks should compare current sprint vs previous sprint (two-column tables).
- Add new metric cells as needs arise — never delete old metrics.
- **Automated sanity script** (owned by Systems Engineer) runs all 6 checks
  and produces a pass/fail summary before any human analysis.
