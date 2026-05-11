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
    'jk.mini.CunobelinDC', 'pe.SandboxDT', 'oog.mega.Saguaro',
    # Upper-mid (top 20-50 tier)
    'cx.BlestPain', 'ary.FourWD', 'ej.ChocolateBar',
    'dft.Cardigan', 'eem.zapper', 'darkcanuck.Pris',
    'kc.serpent.WaveSerpent', 'tobe.Fusion', 'simonton.mega.SniperFrog', 'mld.Moebius',
    # Mid (top 50-100 tier)
    'florent.FloatingTadpole', 'kid.Gladiator',
    'da.NewBGank', 'ary.Help', 'gh.GresSuffurd',
    'kawigi.sbf.FloodHT', 'nz.jdc.micro.HedgehogGF',
    # Lower-mid / archetypes
    'rdt.AgentSmith.AgentSmith', 'fromHell.BlackBox',
    'voidious.Dookious', 'lxx.Tomcat',
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

### CI mode (default) — 3-stage pipeline

Push to `main` triggers a 3-stage `workflow_run` chain. Each stage
triggers the next automatically on success.

```
push to main
    │
[1] 1-sprint-battles.yml (~7 min)
    ├── build: compile robot JAR + pipeline
    ├── battles: 16 opponents × 3 battles (4 parallel chunks)
    │   + self-battle (45–55% sanity band)
    ├── combine-csv: column-aware merge (ticks, waves, scores, internal)
    ├── sanity: 7 automated checks → sanity-report.json
    └── aggregate: summary.json + score table
         │ workflow_run [completed]
[2] 2-sprint-train.yml (~3 min)
    ├── train: 3 models in parallel (fire_power, fire_timing, movement)
    ├── notebooks: R01–R10 retrospective notebooks → HTML
    └── assemble: collect models + .dat → commit to sprint/{N}-models branch
         │ workflow_run [completed]
[3] 3-sprint-eval.yml (~7 min)
    ├── build: compile robot JAR with retrained *Data.java overlaid
    ├── battles: same 16 opponents × 3 battles + self-battle
    ├── combine-csv + sanity checks
    └── aggregate: sprint-eval-summary.json
```

**Total CI time: ~17 min** (stages overlap slightly due to queueing).

**Two evaluation points per sprint:**
- **Stage 1 summary** (`sprint-summary`): code changes only, old models
- **Stage 3 summary** (`sprint-eval-summary`): code changes + freshly
  retrained models — this is the authoritative sprint score

**Monitoring:**
1. `gh run watch <stage-1-id> --exit-status` in async terminal
2. Agent continues work; terminal notifies on completion
3. Stage 2 + Stage 3 auto-trigger; watch with
   `gh run list --limit 3` to track progress
4. If any stage fails → `gh run view <id> --log-failed`, diagnose, push fix

**Downloading results:**
```bash
# Stage 1: code-only results
gh run download <stage-1-id> -n sprint-summary -D output/sprint-s1
gh run download <stage-1-id> -n sanity-report -D output/sprint-s1

# Stage 2: model metrics
gh run download <stage-2-id> -n retrain-summary -D output/sprint-s2

# Stage 3: code + retrained models (authoritative)
gh run download <stage-3-id> -n sprint-eval-summary -D output/sprint-s3
gh run download <stage-3-id> -n eval-sanity-report -D output/sprint-s3
```

**Manual Stage 3 trigger** (if auto-trigger fails due to workflow rename):
```bash
gh workflow run 3-sprint-eval.yml -f sprint_branch=sprint/N-models
```

**CI outputs stay in CI:**
- `sprint/{N}-models` branch with `*Data.java` + `DefaultDataFile.java`
- Recording artifacts (7-day retention)
- CSV artifacts (14-day retention)

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

## Phase 4: Diagnose & Analyse

In CI mode, sanity checks and notebooks run **inside CI** (stages 2–3).
The AI team does NOT have direct access to CSVs — it reviews the
downloaded summary artifacts and CI-rendered notebook HTML.

### 4a. Mandatory sanity checks

CI stage 2 runs automated sanity checks against the combined CSVs
and produces `sanity-report.json`. The AI agent downloads this artifact
and reviews the pass/fail results.

| # | Check | Automated in CI | Pass criteria |
|---|-------|----------------|---------------|
| 1 | **CSV row count** | ticks.csv rows > 100 | Enough data for analysis |
| 2 | **Waves present** | waves.csv exists | Wave detection working |
| 3 | **Scores present** | scores.csv exists | Round outcomes recorded |
| 4 | **Column count** | ticks.csv columns > 10 | Features extracted |
| 5 | **Battle count** | scores.csv rows > 5 | Multiple opponents |
| 6 | **Tick density** | rows / 3000 > 0 | Reasonable ticks per battle |
| 7 | **Feature order** | FEATURE_NAMES matches feature_cols.json for all 3 models | No training/deployment mismatch (Sprint 24 root cause) |
| 8 | **Cross-predict** | Python model on internal.csv vs Java predictions corr ≥ 0.95 | Models produce consistent predictions across languages |

**Bonus ML checks (CI Stage 3):**

| # | Check | Pass criteria |
|---|-------|---------------|
| B1 | **Fire power R²** | In-game R² ≥ 0.5 at fire events |
| B2 | **Fire timing calibration** | Predicted rate within 5× of actual |
| B3 | **Prediction distribution** | All model predictions have std > threshold |
| B4 | **Movement R²** | In-game R² ≥ 0.3 |
| B5 | **Fire timing AUC** | In-game AUC ≥ 0.6 |

**If ANY check fails:** Stop. The failure IS the sprint finding. Fix the
broken system. Do not proceed to performance analysis.

### 4b. Performance review (from downloaded artifacts)

The AI team reviews these downloaded artifacts — it does NOT process
raw CSVs locally:

| Stage | Artifact | What to review |
|---|---|---|
| 1 | `sprint-summary/summary.json` | Code-only scores (old models) |
| 1 | `sanity-report/sanity-report.json` | 7 sanity checks pass/fail |
| 2 | `retrain-summary/retrain-summary.json` | Model R², AUC + sprint branch |
| 3 | `sprint-eval-summary/summary.json` | **Authoritative:** code + retrained models |
| 3 | `eval-sanity-report/sanity-report.json` | Stage 3 sanity checks + ML consistency (checks 7–8, B1–B5) |

**Three-way comparison in the retrospective:**

| Metric | Previous sprint | Stage 1 (code only) | Stage 3 (code + models) |
|---|---|---|---|
| Overall score % | ... | ... | ... |
| Win rate | ... | ... | ... |
| Self-battle | ... | ... | ... |

The **Stage 3 score** is the sprint's official result. Stage 1 isolates
the effect of code changes; the delta between Stage 1 and Stage 3
shows the marginal value of retraining on the new battle data.

### 4c. Model quality review (from retrain-summary.json)

Review the training metrics produced by CI Stage 2:

- **Fire power R²** — compare with previous sprint's offline R²
- **Movement R²** — same
- **Fire timing AUC** — same
- **Sprint branch** — inspect `sprint/{N}-models` diff if metrics changed

**Exit criteria:** All sanity checks pass in both Stage 1 and Stage 3.
Metrics reviewed and compared with previous sprint. Decision made on
whether to merge the sprint model branch.

---

## Phase 5: Retrospective & Commit (Coordinator leads)

### 5a. Write retrospective

Goes in `archive/YYYY-MM-DD-retrospective-N.md`. **Coordinator writes it** using
downloaded CI artifacts from all three stages (sprint-summary, retrain-summary,
sprint-eval-summary).

Required sections:

1. **Diagnostic health** — results of sanity checks from Stage 1 AND Stage 3.
2. **Metrics table** — 3-way comparison: previous sprint / Stage 1 (code only) /
   Stage 3 (code + retrained models). Stage 3 is the authoritative score.
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

### 5b. Merge CI models

CI Stage 2 trains models and commits `*Data.java` files to a
`sprint/{N}-models` branch. Stage 3 has already evaluated the robot
with these models — use the **Stage 3 score** to decide.

1. **If Stage 3 score improved or held steady:** merge the model files
   into `main`:
   ```bash
   git fetch origin
   git checkout origin/sprint/{N}-models -- \
     robot/src/main/java/cz/zamboch/distilled/FirePowerData.java \
     robot/src/main/java/cz/zamboch/distilled/FireTimingData.java \
     robot/src/main/java/cz/zamboch/distilled/MovementData.java \
     robot/src/main/java/cz/zamboch/distilled/DefaultDataFile.java
   git commit -m "merge sprint/{N} CI-trained models"
   ```
   Note: `DefaultDataFile.java` contains VCS histogram priors from battle
   data (`.dat` files). It is only updated when the CI `.dat` merge succeeds —
   check `retrain-summary.json` or the sprint branch diff to confirm.
2. **If Stage 3 score regressed:** do NOT merge. Note the regression in
   the retrospective and investigate root cause next sprint.

Since Stage 3 already evaluated the retrained models, there is **no need
for an extra CI run** after merging — the Stage 3 score IS the sprint result.
The next push to `main` will trigger a fresh pipeline for the next sprint.

### 5c. Revert and commit

- **Revert** changes that caused measurable harm (git revert on main).
- **Tag** the sprint result on `main` with:
  `Sprint N: <key delta>, kept <X>, reverted <Y>`
- **Coordinator reviews** the final state and retrospective.

### 5d. Sprint close

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
