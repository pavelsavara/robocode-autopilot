# robocode-autopilot Coding Conventions

## Java Version
- Target Java 8 (to match the robocode engine)

## Class Design
- Prefer `final` classes. Every class that is not designed for inheritance must be `final`.
  This enables JIT devirtualization and inlining, reducing method call overhead.
- Inner classes must be `static` unless they genuinely need the enclosing instance reference.
  Non-static inner classes carry a hidden reference to the outer object, increasing allocation cost.
- Utility classes (all-static methods) must be `final` with a private constructor.
- Exception classes should be `static final` when nested.

## Module Boundaries
- **core** — In-game logic only. No CSV, no I/O beyond what the robot needs. Interfaces use `IInGameFeatures`.
- **pipeline** — Offline processing. CSV output, recording replay, `IOfflineFeatures`.
  Offline feature subclasses extend core base classes and add CSV support.
- **robot** — Competition robot. Depends on core only. Must stay small — no pipeline code.

## Features
- Base feature classes (e.g. `SpatialFeatures`) implement `IInGameFeatures` and live in core.
  They are NOT `final` — offline subclasses extend them.
- Offline subclasses (e.g. `SpatialOfflineFeatures`) are `final` and live in pipeline.
- Pipeline-only features (e.g. `MovementSegmentationOfflineFeatures`) implement `IOfflineFeatures`
  directly and live in pipeline. They do NOT have a core base class.
- Feature classes must be **stateless** — all inter-tick state lives in `Whiteboard`.
  Features read from `Whiteboard`, compute, and write back via `wb.setFeature()`.
  Never store mutable fields (e.g. `prevVelocity`, counters) in a feature class.
- All features — core and pipeline-only — use the `Feature` enum and `wb.setFeature()`/`wb.getFeature()`.
- String-valued data (e.g. opponent name) is stored as a `Whiteboard` field with a getter,
  and exposed to the feature system as a stable numeric hash (FNV-1a 32-bit) via `setFeature()`.
- Use `CsvRowWriter` for all CSV formatting — never raw `OutputStream` + `StringBuilder`.

## CSV layout — pick the right granularity
Three output files per `<battle>/<robot>/`:
- **ticks.csv** — one row per simulation tick (~3,000 rows/battle). Hot loop. Keep it lean —
  every column costs ~8 bytes × millions of rows across the dataset.
- **waves.csv** — one row per detected opponent fire (~50–200 rows/battle). Wave-relative
  snapshot of distance, bullet power/speed, MEA, lateral velocity at fire instant.
- **scores.csv** — one row per round (~10–35 rows/battle). Round outcomes AND per-battle constants.

### Slow-moving dimensions belong in scores.csv, NOT ticks.csv
Any value that is **constant for a whole battle** (or even a whole round) is wasteful as a
ticks.csv column — it gets duplicated 3,000× per battle for no information gain. Put it in
scores.csv where it costs ~10× per battle. Notebooks deduplicate via
`scores.drop_duplicates('battle_id')` or join into ticks via `pd.merge(ticks, scores_const, on='battle_id')`.

Examples of slow-moving dimensions (currently in scores.csv via `IdentityOfflineFeatures`):
- `opponent_name_hash` / `opponent_bot_id_hash` / `opponent_version_hash` — bot identity.
  The name is split on the first ASCII space (Robocode's `getName()` returns
  `"<class> <version>"`); `bot_id_hash` survives version bumps and is the primary
  fingerprint for ML segmentation. `version_hash` distinguishes patches.
- `battlefield_width` / `battlefield_height` / `gun_cooling_rate` / `num_rounds_total` —
  battle rules, identical for every tick of every round.

Per-round (not per-battle) values like `damage_dealt`, `win_rate`, `our_hit_rate` also
live in scores.csv — that's their natural granularity.

When adding a new feature, ask: **does it change every tick?** If not, route it to scores.csv.
Per-tick state-of-the-world (positions, velocities, distances, gun heat, wave countdowns)
stays in ticks.csv. Per-fire-event snapshots stay in waves.csv.

### Don't leak outcomes into learning features
The three CSVs are separated on purpose: **inputs and outcomes must not mix**.
- **ticks.csv** and **waves.csv** are *inputs* — state-of-world snapshots an in-game robot
  could observe at that moment. They are safe to use as training features.
- **scores.csv** holds *outcomes* (`damage_dealt`, `damage_received`, `our_hit_rate`,
  `opponent_hit_rate`, `win_rate`, …) AND per-battle constants. The constants are safe;
  the outcomes are the labels.
- `waves.csv` records each opponent fire at the **moment of detection** (one tick after
  the energy drop). It contains NO hit/miss column. If you ever add one (e.g. `wave_was_hit`,
  `realised_dodge_fraction`), it lives in waves.csv only — never copy it back into ticks.csv,
  that would let the model see the answer.
- `intuition/_loader.py::BATTLE_CONSTANT_COLS` whitelists exactly the columns that
  `attach_battle_constants()` will merge from scores into ticks/waves. Outcomes are
  deliberately excluded. Mirror that whitelist in any custom merge.
- `opponent_energy` is a soft outcome — fine for short-horizon movement prediction, but
  for round-outcome models it correlates almost perfectly with `win_rate` late in a round.
  Cap to early-round ticks or exclude it.
- Each battle writes two perspective directories (`<battle_id>/RobotA/`, `.../RobotB/`).
  A model trained on RobotA's perspective must never read RobotB's CSVs — that's perfect
  leakage. Pick a side and stick with it.

## Robot JARs
- Robot JARs are stored on the `robots` branch (Git LFS).
- Download missing robots from the **Robocode Archive**: https://robocode-archive.strangeautomata.com/robots/
  JAR naming: `<fully.qualified.ClassName>_<version>.jar`
- After downloading, validate with `unzip -t` before committing — corrupt JARs crash Robocode's scanner.

## Build
- Gradle 9.4.1 Kotlin DSL, version catalog in `gradle/libs.versions.toml`
- Robocode 1.10.1 from Maven Central

## Intuition / ML Exploration (Python)
- The `intuition/` folder contains Jupyter notebooks for statistical exploration of pipeline CSV output.
- **Explain all statistics and ML concepts at high-school math level.**
  Every notebook must define terms (mean, std, correlation, PCA, etc.) in plain language
  with intuitive analogies before using them. Assume the reader knows algebra but not
  university-level statistics or linear algebra.
- Use pandas, scikit-learn, matplotlib, seaborn. Python 3.10 venv in `intuition/.venv/`.
- Notebooks read data from `../output/csv/`. They are self-contained and produce inline plots.

### Data scale & sampling
- The full rumble dataset is **~1900 battles, ~3900 ticks.csv files, ~20 GB on disk**.
  Loading everything via `pd.concat` will OOM VS Code (the notebook host shares process limits with the editor).
- All notebooks must use **stratified per-robot sampling** via the shared helper
  `intuition/_loader.py` (`from _loader import load_stratified`). This:
  - Indexes the CSV tree once, groups files by `robot_name`.
  - Picks the top-N most-played robots and a few battles per robot.
  - Optionally row-subsamples each `ticks.csv` (typical: 10–20%).
  - Downcasts `float64 → float32` and `int64 → smallest int` to halve RAM.
- Targets: **~1M tick rows, ~300 MB pandas memory**. Below this, plotting and
  sklearn fits stay responsive; above it, KDE / boxplot / sklearn easily OOM.
- For plotting on the loaded frame, **subsample again** (50k rows for histograms,
  ~2k rows per robot for boxplots). The visual fidelity of a 50-bin histogram
  saturates around 50k points.

### Running notebooks headlessly (outside the VS Code extension host)
- The VS Code notebook host shares memory with the editor; a kernel OOM crashes
  the whole window. To avoid this, execute notebooks in a separate `python`
  subprocess via `nbconvert`. Outputs (plots, tables) are written back into the
  `.ipynb` on disk and VS Code's file watcher picks them up automatically.
- Close the notebook tab in VS Code first (so the editor isn't competing for the
  file lock), then run from the workspace root:

  ```powershell
  .\intuition\.venv\Scripts\python.exe -m jupyter nbconvert `
      --to notebook --execute intuition\<name>.ipynb `
      --inplace --ExecutePreprocessor.timeout=1200 --allow-errors
  ```

  Reopen the notebook to view results. The `--allow-errors` flag keeps execution
  going past a failing cell so you see all problems in one run; remove it once
  the notebook is clean.
- For batch runs across all notebooks, loop over `intuition/0*.ipynb`.
- `papermill` (`pip install papermill`) is a cleaner alternative with parameter
  injection via tagged cells; same isolation guarantee.
