# Sprint Process

*Each sprint targets a measurable improvement (e.g. +5% win rate).
See [team.md](team.md) for roles and artifact ownership.*

---

## Sprint Cadence

A sprint has **5 phases**. The coordinator leads Phase 1 and Phase 5.
No phase may be skipped.

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ Phase 0  │  │ Phase 1  │  │ Phase 2  │  │ Phase 3  │  │ Phase 4  │
│ Diagnose │  │ Plan &   │  │ Build &  │  │ Battle & │  │ Retro &  │
│ (auto)   │  │ Assign   │  │ Test     │  │ Record   │  │ Commit   │
└──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘
  ML Eng       Coordinator    Dev team     Systems Eng    Coordinator
```

### Plateau detection (auto-skip retrain-only sprints)

If the last 3 consecutive sprints are within ±0.3 pp of each other AND
no code changes were made, the coordinator must escalate:
*"Retrain plateau detected — code change required. Which structural
improvement has the highest expected value?"*
Do NOT run another retrain-only sprint.

### Movement mandate

**Every 3rd sprint must include a movement proposal.** The opponent hit
rate (~40%) is the binding constraint. Alex should not sit idle while
the damage ratio stays at 1:10+.

---

## Phase 0: Feature Divergence Diagnostic (ML Engineer)

**Before planning, run the feature comparison diagnostic.**

1. Run a diagnostic battle with FeatureLogger enabled (1 opponent, 10 rounds)
2. Execute `scripts/compare_features.py` against the diagnostic data
3. If any feature has correlation < 0.90 with the pipeline value,
   **that becomes the sprint's top proposal** — fix the worst feature.
4. If all features are ≥ 0.90, skip to Phase 1 with a clean bill of health.

Phase 0 is optional when the in-game R² is already ≥ 0.7.

---

## Phase 1: Planning (Coordinator leads)

**Inputs:** Previous retrospective's proposals, current win-rate gap.

1. **Coordinator** selects max 3 proposals from the previous retrospective.
   If a proposal is a major architecture or ML algorithm change, it must
   be the **only** item — no other proposals alongside it.
2. **Coordinator** assigns each proposal to the responsible engineer:
   - Movement changes → Movement Engineer (Alex)
   - Gun changes → Targeting Engineer (Bobbie)
   - ML model / feature divergence changes → ML Engineer (Naomi)
   - Pipeline / CI / infra changes → Systems Engineer (Amos)
3. **Coordinator** confirms the **fixed opponent set** (same set every sprint):

```powershell
$opponents = @(
    # Strong (top 10-20 tier)
    'abc.Shadow', 'voidious.Diamond', 'jk.mega.DrussGT',
    'wiki.mega.CunobelinDC', 'pe.SandboxDT', 'oog.mega.Saguaro',
    # Upper-mid (top 20-50 tier)
    'cx.BlestPain', 'ary.FourWD', 'ej.ChocolateBar',
    'dft.Cardigan', 'eem.zapper', 'darkcanuck.Pris',
    'rsj.Electro', 'tobe.Fusion', 'simonton.Wyrm', 'mld.Moebius',
    # Mid (top 50-100 tier)
    'florent.FloatingTadpole', 'kid.Gladiator',
    'da.NewBGank', 'ary.Help', 'gh.GresSuffurd',
    'kawigi.micro.Shiz', 'nz.jdc.nano.LittleBlackBook',
    # Lower-mid / archetypes
    'rdt.AgentSmith.AgentSmith', 'fromHell.BlackBox',
    'tobe.mini.Charon', 'dw.Rattlesnake',
    'sample.TrackFire', 'sample.Walls',
    # Self-battle (diagnostic — expect 48-52%)
    'cz.zamboch.Autopilot'
)
```

**Self-battle:** `cz.zamboch.Autopilot` is included as a diagnostic.
Score must be 48–52%. Skew > 55% indicates a position or initialization bug.

**Exit criteria:** Every engineer knows what they're building and which
metric they're targeting.

---

## Phase 2: Build & Test

### Engineers implement their assigned proposals

- All agents work directly on **`main`** in parallel.
- Agents coordinate via domain boundaries — each owns distinct files (see routing.md).
- Code must pass existing tests before battles.

### Test Author writes tests for new code

- Every new or changed class gets unit tests covering:
  - Normal operation with typical inputs
  - Edge cases: NaN inputs, zero velocity, wall boundaries, empty histograms
  - Regression tests for any bug the change fixes
- Integration test: full Transformer pipeline with synthetic Whiteboard state
  produces plausible feature values.

### Code Quality Reviewer reviews changes on main

Review checklist:
- [ ] `final` classes unless designed for inheritance
- [ ] `static` inner classes unless outer reference needed
- [ ] No mutable state in feature classes (all state in Whiteboard)
- [ ] No I/O in core module
- [ ] No per-tick heap allocation in hot paths
- [ ] Persistence format backward-compatible (or version bumped)
- [ ] Tests present and meaningful

**Gate:** All tests must pass and Holden must review before battles.

### ML Engineer retrains models (if data or features changed)

- Run `train_distill.py` on latest CSV data.
- Export to Java via `export_gbm_java.py`.
- Record training metrics (R², MAE, AUC) — these go into the retrospective.

**Exit criteria:** All changes committed to `main`, all tests green,
robot JAR builds cleanly.

---

## Phase 3: Battle & Record

Two modes: **CI** (default) or **local** (fallback). Both use the same
underlying scripts (`run-battle.mjs`, pipeline binary, `train_distill.py`).

### CI mode (default)

1. Push code changes to `main` → triggers `sprint-pipeline.yml`
2. AI agent starts `gh run watch <run-id> --exit-status` in async terminal
3. Agent continues other work while CI runs (~70 min total):
   - Stage 1: build + 250 battles (50×5, 10 parallel chunks) + self-battle
   - Stage 2: process recordings → CSV, run 6 sanity checks
   - Stage 3: retrain models + run notebooks + merge .dat (parallel)
4. Terminal notifies when complete (or failed)
5. If failed → agent reads `gh run view --log-failed`, diagnoses, pushes fix
6. On success → agent downloads summary artifacts (~50KB total):
   - `summary.json` — per-opponent scores
   - `sanity-report.json` — 6 checks pass/fail
   - `retrain-summary.json` — model metrics + sprint branch name
   - Notebook HTML — pre-rendered retrospective plots

**CI outputs stay in CI:**
- `sprint/{N}-models` branch with `*Data.java` + `DefaultDataFile.java`
- Recording artifacts (~1.25GB, 90-day retention)
- CSV artifacts

### Local mode (fallback)

Use when CI is unavailable or for rapid iteration:

```bash
node scripts/local-pipeline.mjs --opponents 50 --battles-per-opponent 5
```

Same steps as CI but sequential on local machine. Recordings, CSVs, and
models written to `output/local/`.

**Exit criteria:** All pipeline stages complete. Summary and sanity report
available for Phase 4.

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

## Phase 5: Retrospective & Commit (Coordinator leads)

### 5a. Write retrospective

Goes in `archive/YYYY-MM-DD-retrospective-N.md`. **Coordinator writes it** using
data from the engineers' notebooks and sanity checks.

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
7. **Naomi's statistical commentary** — ML Engineer writes a section analysing:
   - Per-opponent score trends across the last 3–5 sprints
   - Variance analysis: which opponents are high-variance vs stable
   - Model calibration: predicted vs actual distributions
   - Any anomalies or surprising patterns in the data
8. **Naomi's feature research** — ML Engineer researches one unimplemented
   feature from [archive/2026-05-01-features.md](archive/2026-05-01-features.md).
   Write a brief analysis: what the feature measures, expected importance,
   implementation complexity, and recommendation (implement / defer / skip).
9. **Proposals for next sprint** — max 3 changes (or 1 if major). Each states:
   - What metric it targets
   - Expected effect (directional + rough magnitude)
   - How to measure success/failure
   - Who implements it

### 5b. Revert and commit

- **Revert** changes that caused measurable harm (git revert on main).
- **Tag** the sprint result on `main` with:
  `Sprint N: <key delta>, kept <X>, reverted <Y>`
- **Coordinator reviews** the final state and retrospective.

### 5c. Sprint close

Coordinator declares the sprint result:
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
| Retrain-only sprint when plateaued | Wastes a full cycle for 0 gain | Check plateau rule: 3 sprints ±0.3 pp = code change required |
| Skip Naomi's analysis | Misses statistical patterns | Every retro needs sections 7 + 8 |
| Ignore movement for 3+ sprints | Opponent HR stays at 40% | Movement mandate: every 3rd sprint |

---

## Measurement Infrastructure

- **Retrospective notebooks** live in `intuition/retrospective/`.
- Each reads from `output/local/csv/` and is self-contained.
- Notebooks should compare current sprint vs previous sprint (two-column tables).
- Add new metric cells as needs arise — never delete old metrics.
- **Automated sanity script** (owned by Systems Engineer) runs all 6 checks
  and produces a pass/fail summary before any human analysis.
- **Sprint-only filtering:** `sanity_check.py --battle-ids sprint_battles.json`
  restricts checks to current-sprint data only (avoids dilution from old recordings).
- **Incremental CSV:** pipeline Java binary skips already-processed `.br` files
  (use `--force` to reprocess all). Saves ~80% time on re-runs.
