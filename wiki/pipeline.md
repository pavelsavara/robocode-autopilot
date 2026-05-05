# Pipeline & CSV Schema

*Recording → feature extraction → CSV output workflow.*

## Pipeline Overview

```
run-season.yml (CI) → .br recording files (GitHub artifacts)
       ↓
process-recordings.yml (CI) → CSV chunks (GitHub artifacts)
       ↓
download-csv.mjs (local) → output/csv/{battleId}/{perspective}/
                              ├── ticks.csv
                              ├── waves.csv
                              └── scores.csv
```

## Data Flow

```
.br file (binary recording, Robocode format)
  → Loader (reads ZIP + ObjectInputStream)
  → Player (replays god-view snapshots → synthesized robot events)
  → Whiteboard (central state store, receives events)
  → Transformer (processes feature pipeline, dependency-sorted)
  → CsvWriter (writes ticks.csv, waves.csv, scores.csv)
```

**Two perspectives per battle:** Each `.br` file produces two output
directories: `<battleId>/RobotA/` and `<battleId>/RobotB/`. Each
perspective contains only what that robot could observe.

## CSV Files

### ticks.csv — One Row Per Tick

~3,000 rows per battle. Contains per-tick state-of-the-world features.

**Key columns:**
- Identity: `battle_id`, `observer_bot`, `opponent_bot`, `round`
- Position: `our_x`, `our_y`, `opponent_x`, `opponent_y`
- State: `distance`, `opponent_velocity`, `opponent_lateral_velocity`, `energy_ratio`
- Timing: `ticks_since_scan`, `ticks_since_opponent_fired`
- Targeting: `linear_target_angle`, `circular_target_angle`, `gf_current_at_power_2`
- Wave tracking: `opponent_wave_distance`, `opponent_wave_remaining`, `opponent_wave_eta`

**This is the INPUT file** — safe for ML training features. No outcomes.

### waves.csv — One Row Per Detected Opponent Fire

~50–200 rows per battle. Snapshot of state at the moment of fire detection.

**Key columns:**
- `wave_bullet_power`, `wave_bullet_speed`, `wave_fire_distance`
- `wave_mea`, `wave_flight_time`
- `wave_lateral_velocity_at_fire`

**This is an INPUT file** — snapshots at detection moment. No hit/miss column.
If a `wave_was_hit` column is ever added, it stays in waves.csv only —
never copy it to ticks.csv (that would be outcome leakage).

### scores.csv — One Row Per Round

~10–35 rows per battle. Contains round outcomes AND per-battle constants.

**Outcome columns (labels):**
- `score_damage_dealt`, `score_damage_received`, `score_net_damage`
- `score_our_hit_rate`, `score_opponent_hit_rate`, `score_win_rate`

**Per-battle constants (safe to merge into ticks/waves):**
- `opponent_name_hash`, `opponent_bot_id_hash`, `opponent_version_hash`
- `battlefield_width`, `battlefield_height`, `gun_cooling_rate`, `num_rounds_total`

**Merge rule:** Only `BATTLE_CONSTANT_COLS` (whitelisted in `_loader.py`)
may be merged into ticks/waves via `attach_battle_constants()`. Outcome
columns are deliberately excluded.

## Dataset Scale

| Metric | Value |
|---|---|
| Battles | ~1,944 |
| ticks.csv files | ~3,888 (2 per battle) |
| Total disk size | ~20 GB |
| Rows per battle | ~3,000 ticks, ~100 waves, ~10–35 scores |
| Bots | 50 (top LiteRumble competitors) |
| Rounds per battle | 35 |

## Loading Data (Python)

Use `intuition/_loader.py` for all data loading:

```python
from _loader import load_stratified, numeric_feature_cols, drop_redundant_features

# Load stratified sample: top-20 bots, 10 battles each, 10% row subsample
df = load_stratified(top_n=20, battles_per_robot=10, row_frac=0.1)

# Get clean numeric features (excludes string columns, leakage columns)
features = numeric_feature_cols(df, extra_exclude=SCAN_META_COLS)
features = drop_redundant_features(features)
```

**Memory targets:** ~1M tick rows, ~300 MB pandas memory. Above this,
KDE/boxplot/sklearn easily OOM the VS Code kernel.

## Gradle Modules

| Module | Role | Dependencies |
|---|---|---|
| `core` | In-game logic, features, interfaces | Robocode API |
| `pipeline` | Offline CSV processing, .br replay | core |
| `robot` | Competition robot JAR | core only |

**Build:** Gradle 9.4.1 Kotlin DSL. Version catalog in `gradle/libs.versions.toml`.
Target: Java 8 (Robocode engine requirement).

## CI Workflows

| Workflow | Trigger | Output |
|---|---|---|
| `run-season.yml` | Manual / scheduled | `.br` recording artifacts |
| `build-docker-pipeline.yml` | Push to core/pipeline | Docker image on GHCR |
| `process-recordings.yml` | After season run | `csv-chunk-*` artifacts |
