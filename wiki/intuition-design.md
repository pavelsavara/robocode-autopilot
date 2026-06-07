# ML Intuition — Design Specification

This document specifies what [`scripts/intuition.py`](scripts/intuition.py) computes
every time it runs, and the report it (re)generates at `wiki/ML-intuition.md`. It is
the contract between the data pipeline and the ML engineer who will design the
offline targeting/movement models.

The purpose of the report is **statistical orientation**: before anyone trains a
model, they should be able to read `wiki/ML-intuition.md` and answer "what is this
game, what is in this dataset, what is learnable, how hard is it, and what are the
traps." Every number in the report is reproducible by re-running the script against
the same (or a newer) battle-csv-producer directory.

---

## Producer contract (input file layout)

`intuition.py` reads a `battle-csv-producer` run directory. The pipeline writes one
sub-directory per matchup and per perspective —
`<robotA>__vs__<robotB>/PerspectiveN-<robot>/<file>.csv` — and the script depends on the
following files:

| File | Produced from | Used by |
|------|---------------|---------|
| `dejavu-waves.csv` | the in-game hero's **real** fires (`our_fire_is_real==1`), reconstructed by the vendored `net.sf.robocode.dejavu` package | primary targeting labels (B/C/F/G/H) |
| `autopilot-waves.csv` | the autopilot **shadow gun** — its would-be fires on the hero's body (real + virtual fan) | C4/G1 floor line, optional G3 cross-check |
| `their-waves.csv` | incoming opponent bullets (defensive) | Section D |
| `ticks.csv` | per-tick own state | B5/B7, F1 |
| `scan.csv` | per-scan opponent observation (`scan_`-prefixed columns) | A6/A7, B1–B6 |
| `scores.csv` | per-round result (`round_hit_rate`, `result`) | A1, G2 |

**Producer contract (the split already exists).** The producer
[`CsvWriter`](pipeline/src/main/java/cz/zamboch/autopilot/pipeline/CsvWriter.java) emits
all six files above per perspective, with `dejavu-waves.csv` (the in-game hero's real gun,
~88k real waves) already split from `autopilot-waves.csv` (the autopilot shadow gun, ~16k
real + ~162k virtual fan). These are two *distinct guns on the same body* firing at
different ticks, so one cannot be derived from the other. `dejavu-waves.csv` and
`autopilot-waves.csv` share the same `OUR_WAVES` column schema (columns are
`f.name().toLowerCase()`, prefixed by `battle_id, round, tick`). The run-directory layout
the script walks is:

```
pipeline/build/battle-csv-producer/
  <baseSeed>-<timestampMillis>/                  ← one run directory (the script's RUN_DIR)
    <robotA>__vs__<robotB>/                       ← one matchup (sanitized robot names)
      Perspective0-<robotA>/                       ← one perspective (robot = dir suffix)
        ticks.csv  scan.csv  their-waves.csv
        autopilot-waves.csv  dejavu-waves.csv  scores.csv
      Perspective1-<robotB>/
        …same six files…
```

`intuition.py` records in the report header which contract it resolved (which wave files
were found) and a per-file SHA-256 checksum. If a required file is **missing** from a
perspective, the script must mark its dependent sections "unavailable" and record it in the
header — never silently substitute biased autopilot labels for dejavu labels, and never
fail silently. (Historical fallback: if a perspective ever carries only a legacy
single-file `our-waves.csv`, treat it as the autopilot gun **only** and mark every
dejavu-sourced section unavailable.)

---

## Scope & conventions

- **Input**: a `battle-csv-producer` run directory (default: newest under
  `pipeline/build/battle-csv-producer/`, override with a positional CLI argument).
- **Unit of analysis**: a **perspective** = one robot's robot-side observer view of
  one matchup. Every perspective contributes both:
  - an **offensive** sample set (its outgoing wave files), and
  - a **defensive** sample set (its incoming `their-waves.csv`).
- **All robots, all perspectives** are included. Self-play matchups
  (`X__vs__X`) are kept but flagged, because both sides share one robot's behavior.
- **Two distinct offensive wave sources — do not conflate them:**
  - **`dejavu-waves.csv` — what the in-game hero actually fired.** Real fires only
    (no virtual fan), fired from the hero's true body position at the hero's real
    fire ticks. **This is the primary targeting-label source** for characterizing the
    game and the opponents (Sections B/C/F/G/H). It is denser (~88k real waves) and
    its `our_break_gf` distribution is unbiased (mean ≈ 0).
  - **`autopilot-waves.csv` — a counterfactual "shadow gun".** These are the bullets the
    autopilot *would have* fired had it controlled the robot; it did **not** — a
    different hero was in the game. It fires from the hero's real body position but at
    its own sparse, self-selected ticks (~16k real + ~162k virtual fan), and its bad
    current aim makes its `our_break_gf` sample **biased** (mean ≈ −0.18). It is used
    **only** as a labeled *current-autopilot baseline (floor)* and an optional
    cross-check of the analytic virtual-gun curve — never to characterize opponents.
- **Targeting label** = `our_break_gf` (GuessFactor where the opponent actually was
  when our wave broke), **taken from `dejavu-waves.csv`** unless a row is explicitly
  labeled as the autopilot baseline. **Movement label** = `their_break_gf` (GuessFactor
  where *we* ended up on the opponent's wave) and `their_hit_us`.
- **Real vs virtual**: only `autopilot-waves.csv` has a virtual fan — `our_fire_is_real==0`
  rows **share the parent real wave's `our_break_gf`** and differ only in
  `our_fire_aim_gf` (used only for the autopilot virtual-gun cross-check). `dejavu-waves.csv`
  is **all real** (`our_fire_is_real==1`), so it carries no virtual-wave leakage.
- **Incoming bullet power is treated as real** (not zap artifact). Zap prevalence is
  reported as a verifiable data-quality line, not used to filter. The current
  `scan_their_inactivity_zap_active` flag is a coarse per-scan-tick signal that the
  pipeline will refine later; the report must therefore measure it directly each run
  rather than trusting a single global number.

### Canonical definitions (used identically by every section)

These are fixed once here so two reports — and two rows within one report — are always
comparable. The script prints them in the report header.

- **Hit (canonical, analytic).** A wave counts as a hit when the aimed GuessFactor lands
  within half a bot-width of the realized break GF: `|aim_gf − break_gf| ≤ gf_tol`, where
  `gf_tol ≈ (18 / fire_distance) / mea` is computed **per wave** (robot half-width 18 px;
  small-angle bot half-angle `18 / fire_distance` radians; `mea` = that wave's max-escape
  angle, which varies with bullet speed). This single rule is used for head-on (C2),
  best-static / ceiling (C5), the virtual-gun curve (G3), and the scoreboard (G1).
  Engine-confirmed hits (`our_break_hit==1`) are reported only in a **separate**
  "engine-hit" column and are never mixed into an analytic comparison.
- **GF binning (canonical).** Entropy (C2), concentration (C3), KL (F2), and VCS occupancy
  (F3) use a **fixed** 47-bin uniform grid over `[−1, 1]` (edges stated in the header).
  Probabilities use **Laplace (add-one) smoothing** so empty bins never make entropy or KL
  undefined.
- **Uncertainty (global).** Every per-opponent rate is reported with its sample size `N`
  and a 95% confidence interval — **Wilson** for proportions (hit rates), **bootstrap**
  (fixed seed, 1000 resamples) for GF peak / entropy / KL. Cells with `N < 200` resolved
  waves are flagged (`†`) and excluded from cross-opponent rankings.
- **Aggregation unit.** "Per opponent" pools every perspective in which that robot is the
  *opponent*; "per matchup" is one `A__vs__B` directory; "per perspective" is one
  robot-side view. Cross-opponent aggregates weight each opponent **once**. Every table
  caption states which unit it uses.
- **Self-play rule.** `X__vs__X` matchups are computed and shown individually but
  **excluded** from all cross-opponent aggregates and rankings (both sides share one
  behavior). They are never double-weighted.

---

## Output contract

`intuition.py` produces:

1. `wiki/ML-intuition.md` — the report, fully regenerated (replaced) each run, with a
   header recording the source directory, generation timestamp, the **resolved producer
   contract** (which wave files were found), and a **per-file content checksum** (SHA-256
   of each CSV — not just row counts, since different data can share a row count) so two
   reports are provably comparable.
2. `wiki/ml-intuition/*.png` — chart assets referenced by the report. Regenerated each
   run. Filenames are **keyed by question ID** (e.g. `C1-gf-hist-<opp>.png`,
   `B7-position-heatmap-<opp>.png`) so the markdown↔asset mapping is mechanical and links
   never break.
3. `wiki/ml-intuition/intuition.json` — a machine-readable sidecar holding the
   per-opponent scoreboard (G1), fingerprints (H1), and readiness flags (I1) so CI can
   regression-diff the numbers without parsing prose.

The report is organized into the lettered sections below. Each **question** maps to
exactly one table and/or one figure. Tables are the source of truth; figures aid human
reading. Both must be present and self-describing (no reliance on the script source to
interpret a column).

**Self-consistency assertions.** Because the tables are the source of truth, the script
asserts — and **aborts loudly** on any violation — that: real + virtual counts equal the
per-file total (A2); each per-opponent `N` sums to the global `N`; every reported rate is
in `[0, 1]`; and every GF value is in `[−1, 1]`. A failed assertion stops the run rather
than emitting a subtly wrong report.

---

## Section A — Dataset inventory & integrity

Goal: prove the data is what we think it is before trusting any downstream statistic.

| ID | Question | Answered by | Why the ML engineer cares |
|----|----------|-------------|---------------------------|
| A1 | What does the run physically contain — how many matchups, distinct robots, perspectives, rounds, and data rows per file type? | Inventory table + total MB | Defines sample budget and whether a split is even feasible. |
| A2 | How many **unique GF labels** exist per wave file (dejavu real vs autopilot real vs autopilot virtual)? | Real/virtual/total counts table, per file | The honest targeting training-set size is **dejavu's ~88k real waves**; autopilot's 178k rows are a biased counterfactual, not labels. |
| A3 | What is the missing-value (`NaN`) rate of every column in every file? | Per-column %NaN table | Columns above a NaN threshold are unusable as features without imputation. |
| A4 | What fraction of waves never resolve (break columns `NaN`, e.g. round ended first)? | Unresolved-wave rate, outgoing & incoming | These rows have features but no label — usable only for unsupervised/aux tasks. |
| A5 | Are there duplicate or colliding identifiers (`our_fire_bullet_id`, `their_fire_tick`, `scan_opponent_id_hash`)? | Collision counts | Duplicates inflate counts and break per-fire grouping for leakage-safe splits. |
| A6 | Does each `scan_opponent_id_hash` map stably to one robot name across the run? | Hash↔name table | Confirms identity feature is a clean per-opponent key for per-opponent models. |
| A7 | How prevalent is the **inactivity-zap** flag (`scan_their_inactivity_zap_active`), per opponent, and does it contaminate logged fires? | Zap-rate-per-opponent table + zap∩fire overlap line | Validates the "treat incoming as real" assumption: zap is a per-tick blip, concentrated in a few low-energy bots, and must overlap almost no logged fires. |

## Section B — Game & physics orientation

Goal: characterize the *game* the model lives in (distributions of the core physical
quantities), independent of any learning.

**Data source:** "our" fire-time quantities (B1–B4, B7) come from **`dejavu-waves.csv`**
(the hero's real fires); "their" quantities come from `their-waves.csv`; B5/B6 come from
`ticks.csv`/`scan.csv`. Autopilot-waves are not used here.

| ID | Question | Answered by | Why |
|----|----------|-------------|-----|
| B1 | What is the distribution of engagement **distance** at fire time, overall and per opponent? | Distance histogram (PNG) + percentile table | Distance drives MEA, flight time, and hit probability; sets segmentation ranges. |
| B2 | What bullet **power / bullet speed** do we and opponents choose? | Power histograms (ours vs theirs) + table | Power determines bullet speed, MEA, damage, and gun-heat cadence. |
| B3 | What is the distribution of **lateral** and **advancing velocity** at fire time? | 2D hist / marginal tables | Primary segmentation axes for GuessFactor targeting. |
| B4 | What is the **bullet flight time** distribution and how does it scale with distance & power? | Flight-time histogram + distance/power trend | This is the **label-latency** budget: features at fire time predict an outcome a *variable* number of ticks later — mean ~31, but **per-wave** it scales with distance and power, so any online-eval delay must use each wave's own flight time, not a global constant. |
| B5 | How long are rounds (ticks) and how does energy evolve over a round? | Round-length table + energy-vs-tick curve (PNG) | Bounds episode length and data-per-round; energy gates fire power. |
| B6 | What fraction of ticks have a fresh opponent scan (scan coverage / missed-scan exposure)? | Scan-coverage table | Gaps mean stale features; quantifies sensor reliability per opponent. |
| B7 | Where on the battlefield do fires occur, and how close to walls? | Position heatmap (PNG) + wall-proximity table | Wall proximity shrinks escape angle; reveals positional bias to exploit/avoid. |

## Section C — Targeting (offensive gun): predicting GuessFactor

Goal: quantify how learnable the opponent's position-at-break is, and how far the
current model is from the ceiling. This is the core offensive ML problem.

**Data source:** all GF *distribution / learnability* questions (C1–C3, C5–C9) use
**`dejavu-waves.csv` real waves** (the hero's actual fires; unbiased, ~88k samples).
The **autopilot** appears only in C4 as the *current-autopilot baseline (floor)*, clearly
labeled as a counterfactual shadow gun that was not in the game.

| ID | Question | Answered by | Why |
|----|----------|-------------|-----|
| C1 | What is the distribution of observed `our_break_gf`, overall and per opponent — peaked, flat, or multi-modal? | GF histograms per opponent (PNG) + shape table | A peaked GF profile is learnable (predictable bot); a flat one is a hard flattener. |
| C2 | What is the **GF entropy** (bits) and the **head-on hit baseline** per opponent? | Entropy + GF=0 hit-rate table | Entropy = intrinsic targeting difficulty; head-on is the zero-learning baseline to beat. |
| C3 | How concentrated is GF mass — top-1 bin mass and effective number of bins? | Concentration table | Directly informs VCS bin count and smoothing bandwidth. |
| C4 | What is the **current-autopilot baseline (floor)** vs the **in-game hero**: real-wave hit rate and GF MAE/RMSE, per opponent and per round, **plus a fire-time feature-distribution comparison (distance, lateralV) between the two guns**? | Floor-vs-hero table (autopilot-waves vs dejavu-waves) + selection-bias panel | The autopilot floor (~1.5% hit) is where ML *starts*; the hero (~8.7%) is the live behavior. The two guns fire at **different ticks and body states**, so the comparison is **unpaired** and the floor carries *selection bias* on top of aim bias — the feature-distribution panel shows how far apart the two fire-time samples are so the gap isn't over-read. Clearly labels autopilot as a not-in-game counterfactual. |
| C5 | What is the **achievable ceiling** — distinguish (a) the *quantization bound* (aim the realized GF bin every time: ≈100% minus bot-width misses, a sanity bound only, **not** a learnable target since aim = label) from (b) the *learnable ceiling* (best single fixed-GF per opponent, and a cross-validated GF model using only fire-time features)? | Ceiling table (quantization-bound vs best-static vs CV-model vs current) | The quantization bound just confirms the bins are fine enough; the **learnable ceiling** is the real headroom. If best-static ≈ CV-model, segmentation is the lever, not the gun. |
| C6 | How strongly does each fire-time feature relate to `our_break_gf`? | Correlation + mutual-information ranking | First-pass feature selection for the GF regressor. |
| C7 | How much GF variance is explained by candidate **segmentation axes** (distance, lateralV, advancingV, accel, wall) individually and jointly? | Variance-explained / group-spread table | Quantifies which segments are worth their data cost. |
| C8 | Is there **temporal structure** — does `our_break_gf` autocorrelate across consecutive fires within a round? | Lag-1..k autocorrelation table | Non-zero autocorrelation means pattern/temporal models (PIF, sequence) can beat memoryless VCS. |
| C9 | How does hit rate vary with distance / flight time? | Hit-rate vs distance curve (PNG) | Confirms "closer = easier"; informs power/fire-selection policy. |

## Section D — Movement (defensive surfing): avoiding being hit

Goal: characterize the dodge problem and the opponents' guns from the receiving end.

| ID | Question | Answered by | Why |
|----|----------|-------------|-----|
| D1 | What is **our dodge profile** — distribution of `their_break_gf` (where we end up on their waves), overall and per opponent? | Dodge-profile histograms (PNG) + flatness table | A flat profile = hard to hit; peaks reveal our movement's exploitable bias. |
| D2 | What is the **hit-us rate** per opponent and per bullet-power band, and which GF bins are most dangerous? | Hit-us table + danger-by-GF-bin | Prioritizes which opponents/situations the surfer must handle first. |
| D3 | Where do opponents **aim** relative to head-on (their `their_break_gf` bias / peak)? | Opponent-aim-bias table | If an opponent's gun is biased/predictable, movement can deterministically dodge it. |
| D4 | What is the incoming **bullet-power distribution and power-vs-distance strategy**, plus inactivity-zap prevalence? | Power×distance table + per-opponent zap% + zap∩fire-overlap line | Confirms bullets are real (data quality) and reveals threat level (power = damage & speed). The zap line is per-opponent and reports what fraction of logged incoming fires sit on a zap tick (must be ~0). |
| D5 | Under what own-state conditions (distance, velocity, wall proximity) are we hit vs not hit? | Conditional hit-us table | Identifies dangerous states to design movement constraints/penalties. |
| D6 | What is the **dodge time budget** — incoming flight time / distance distribution? | Incoming flight-time table | How many ticks the surfer has to react after detecting a wave. |

## Section E — Feature analysis for ML

Goal: hand the engineer a ready-to-use feature view.

**Data source:** the GF-prediction model (E3) is fit on **`dejavu-waves.csv` real waves**
(unbiased labels). E4's hit/no-hit model also uses dejavu real waves. Autopilot-waves are
not fit — their aim bias would distort importances.

| ID | Question | Answered by | Why |
|----|----------|-------------|-----|
| E1 | What is the full candidate **feature inventory** (name, file, dtype, range, %NaN, units)? | Feature catalog table | The menu of inputs for both models. |
| E2 | Which features are **redundant** (pairwise correlation) within the targeting feature set? | Correlation matrix (PNG) | Drop collinear inputs; smaller, stabler models. |
| E3 | What is the **feature importance for GF prediction** (quick tree model: impurity + permutation importance)? | Importance ranking table | Empirical, model-based feature selection beyond linear correlation. |
| E4 | What is the **feature importance for hit / no-hit** classification (canonical analytic hit; ~9% positive base rate ⇒ use class weights and score by **PR-AUC**, not accuracy)? | Importance ranking table (permutation importance scored on PR-AUC) + base-rate line | Different objective (fire-selection / power policy) may value different features; at ~11:1 imbalance, accuracy and impurity importance mislead, so the metric is fixed here. |
| E5 | What minimal, decorrelated, high-signal **feature set** is recommended for v1 models? | Recommended-set list | A concrete starting point so the engineer doesn't start from 30 raw columns. |

## Section F — Learning dynamics

Goal: characterize *when* and *how fast* the models can learn, and whether the target
moves.

| ID | Question | Answered by | Why |
|----|----------|-------------|-----|
| F1 | How fast does data **accumulate** (cumulative real waves vs tick/round)? | Cold-start curve (PNG) | Online models must perform at low data; sets the "cold start" regime to simulate. |
| F2 | Is the target **non-stationary** — does the per-opponent GF distribution shift across rounds (KL divergence round 0 → round 4, on the canonical smoothed GF bins, in bits)? | KL-by-opponent table (bootstrap CI; small-`N` rounds flagged) | Quantifies adaptation; high KL ⇒ recency-weighting / online updates matter. Fixed bins + smoothing are required or empty bins make KL inf/NaN and reports incomparable. |
| F3 | How **sparse** is a segmented VCS grid — data per (opponent × segment) cell for a stated grid (e.g. 5×5×3)? | Occupancy / sparsity table | Reveals how many segments are starved; guides KNN-vs-grid choice. |
| F4 | How much data is needed for a **stable GF estimate** (bootstrap variance of the peak bin vs N)? | Stability-vs-N curve (PNG) | Tells the engineer the per-segment sample size for trustworthy aim. |

## Section G — Baselines & benchmarks

Goal: a single scoreboard so any future model has numbers to beat.

| ID | Question | Answered by | Why |
|----|----------|-------------|-----|
| G1 | Per opponent: **autopilot-floor vs in-game-hero vs head-on vs best-static vs learnable-ceiling** hit rate — every cell using the **canonical analytic hit** (Scope), with a **separate engine-hit column** for floor and hero, and `N` + 95% CI per cell. | Scoreboard table | The canonical benchmark row set. Mixing engine hits and analytic hits in one column is invalid, so analytic is the comparison column and engine-hit sits beside it. Autopilot is the floor ML must beat; head-on/best-static/ceiling are derived from dejavu real waves; hero is the live result. |
| G2 | What were the actual **battle outcomes** (win/loss, `round_hit_rate`) per opponent? | Outcomes table | Ground-truth competitive result the statistics must ultimately move. |
| G3 | **Virtual-gun curve**: hit rate as a function of fixed aim-GF offset, per opponent. | Virtual-gun curves (PNG), derived analytically from **dejavu** break-GF; optional overlay of the **autopilot** pre-baked virtual fan as a cross-check | Shows whether a better static offset exists and how sharp the optimum is. Deriving it from dejavu's unbiased labels avoids the autopilot's aim bias; the fan overlay just validates the math. |

## Section H — Per-opponent profiles

Goal: a compact per-opponent fingerprint and a grouping.

| ID | Question | Answered by | Why |
|----|----------|-------------|-----|
| H1 | What is each opponent's **one-row fingerprint** (distance, GF peak, GF entropy, our hit rate, hit-us rate, adaptivity/KL, typical power)? | Opponent fingerprint table | At-a-glance opponent difficulty and style. |
| H2 | Which opponents are **statistically similar** (cluster by GF-distribution distance)? | Similarity/cluster table | Opponents in one cluster may share a model / warm-start prior. |

## Section I — ML readiness & recommendations

Goal: convert the statistics into modeling guidance. This section is prose +
a checklist, generated from the computed numbers (thresholded statements), not free text.

| ID | Question | Answered by | Why |
|----|----------|-------------|-----|
| I1 | What is the **label budget, noise, per-wave latency, and stationarity** summary for each task? | Readiness summary table | One place that states feasibility per task. |
| I2 | What **target formulation** does the data favor (GF regression vs bin classification vs hit/no-hit)? | Recommendation, justified by C1–C5 | Picks the learning objective. |
| I3 | What **train/test split** avoids leakage (use dejavu real waves as labels; if autopilot virtual rows are ever used, group by parent fire; split by battle/round; isolate self-play)? | Split recipe | Prevents the #1 silent metric inflation in this dataset. |
| I4 | What are the top **risks/traps** (autopilot is counterfactual/not-in-game, virtual-wave leakage, variable per-wave label latency, self-play double-counting, per-round adaptation, round-end censoring, floor selection bias, missing scans)? | Ranked risk list | The "read this first" warnings for the modeler. |

---

## Known traps this report must keep surfacing

1. **Autopilot is a counterfactual, not the game** — `autopilot-waves.csv` is the bullets
   the autopilot *would have* fired; a different hero was actually in the battle. Its aim
   is currently bad, so its `our_break_gf` sample is **biased** (mean ≈ −0.18 vs dejavu's
   ≈0). Use **`dejavu-waves.csv`** (the hero's real fires) for all opponent/targeting
   characterization; use autopilot only as the explicitly-labeled current floor.
2. **Virtual-wave leakage (autopilot only)** — the 162k autopilot virtual rows are not
   independent labels; ~11 share one `our_break_gf`. If they are ever used, group by
   `(matchup, round, our_fire_tick)`. `dejavu-waves.csv` has no virtual fan, so it is
   leakage-free.
3. **Label latency (variable, mean ~31 ticks)** — features are known at fire time but the
   label is the opponent's position when the wave breaks, a **per-wave** delay that scales
   with distance and bullet power (B4); online evaluation must respect each wave's own
   flight time, not a single global constant.
4. **Self-play** — `X__vs__X` matchups put the same bot on both sides; computed and shown
   individually but **excluded** from every cross-opponent aggregate and ranking (Scope's
   self-play rule), never double-weighted.
5. **Per-round adaptation** — several opponents shift their GF distribution between
   rounds; a single static model understates difficulty.
6. **Incoming bullet power** — treated as real; the inactivity-zap line is a data-quality
   check, reported (not filtered) so the assumption stays auditable each run. Verified on
   the current run: zap is 3.2% of scan *ticks* but those are 1.1-tick blips concentrated
   in 3 low-energy bots (6.9% / 5.1% / 4.2%) and ~0% for the rest, and only 0.47% of
   logged incoming fires sit on a zap tick — so it does not pollute the labels. The flag
   is coarse and will be refined in the pipeline later; the per-opponent A7/D4 measure
   keeps it honest meanwhile.
7. **Round-end censoring** — unresolved waves (A4) are dropped, but they are **not**
   missing at random: they cluster at round end, low energy, and near-death, so the
   surviving label distribution skews toward early/mid-round geometry. Treat A4's
   unresolved set as informative, not ignorable.
8. **Floor selection bias** — the autopilot floor (C4) fires at its own self-selected
   ticks and body states, so it is an **unpaired** baseline biased on fire-time features
   (distance, lateralV) as well as aim; never read the floor↔hero gap as pure aim quality
   (see C4's selection-bias panel).

---

## CLI / repeatability

```
python scripts/intuition.py [RUN_DIR] [--out wiki/ML-intuition.md] [--assets wiki/ml-intuition] [--max-model-rows 50000]
```

- `RUN_DIR` defaults to the newest directory under
  `pipeline/build/battle-csv-producer/`.
- The script is deterministic given a fixed `RUN_DIR` (fixed random seed for any
  sampling/model fit), uses only pandas / numpy / scikit-learn / matplotlib, and
  fully replaces the report and its PNG assets on every run.

---

## Performance & cost budget

Measured on the current 580 MB run (2.4M rows across 36 matchups): full load ≈ 4.3 s,
RandomForest GF fit ≈ 0.8 s, permutation importance (5 repeats) ≈ 6.2 s, all
groupby/aggregation steps < 0.1 s each. **End-to-end ≈ 15–25 s** (load + ~40 tables +
~15 PNG renders). Every dominant cost is **linear in row count**, so a 10× larger
dataset is ≈ 3–5 min — provided the guards below are honored.

The two sklearn-importance steps (Section E3/E4) are the only super-linear-feeling
costs in practice and the first thing to bound as data grows.

### Guards the implementation must apply

1. **Selective column loading** — `ticks.csv` (1.04M rows) and `scan.csv` (1.01M rows)
   are 2.0M of the 2.4M total rows but only feed the descriptive sections (B5/B6/B7
   energy curves, scan coverage, position heatmaps). Load them with an explicit
   `usecols=` (only the columns those sections consume) and downcast numeric dtypes
   (`float32`/`int32`). Wave files are small and hold the labels — load them fully.
   `autopilot-waves.csv` (178k rows, mostly the virtual fan) is only needed for the C4/G1
   floor line and the optional G3 cross-check; load only its real rows (or `usecols=` the
   few columns those two uses need) rather than the full virtual fan.
2. **Seeded sample cap on model fits only** — the RandomForest fit and permutation
   importance (E3/E4) operate on a deterministic subsample of **dejavu real-wave labels**
   capped at `--max-model-rows` (default 50,000). Importances are stable
   well before that size. **Descriptive statistics and all tables are never
   subsampled** — they always use the full data so every reported number stays exact.
3. **Bounded permutation importance** — fixed `n_repeats=5`; do not scale repeats with
   data size.
4. **Heatmaps render from 2D histograms, not scatter** — Section B7 bins tick rows into
   a 2D histogram per opponent (cost is the bin count, not the row count), so it stays
   flat as data grows.

### Tunable CLI flags (defaults keep the report exact)

```
--max-model-rows N    cap rows fed to the E3/E4 model fit + permutation importance
                      (default 50000; tables/statistics are unaffected)
```

These guards keep the script feeling instant (~20 s) at the current size and under a
few minutes at 10×, without ever subsampling a number that appears in a table.
