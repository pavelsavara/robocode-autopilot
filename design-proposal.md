# Design Proposal: Multi-Dimensional Whiteboard

## Problem Statement

The current Whiteboard is a **flat `double[Feature.COUNT]`** — one value per feature, overwritten every tick. This works for tick-level deterministic features but fails for:

1. **Waves** — multiple concurrent entities (5–15 in flight), each with fire-time + break-time features
2. **Virtual bullets** — hypothetical shots at alternative angles, evaluated many ticks later
3. **Speculative enemy waves** — pre-created when gunHeat=0 before energy drop confirms fire
4. **Movement candidates** — N path options per incoming wave, scored at wave break
5. **Validation** — no framework to evaluate predictions/estimates against delayed ground truth
6. **Offline enrichment** — no place to attach computed features (wall distance, accel, time-since-reverse) to wave records for ML

---

## Design Goals

- **Zero per-tick allocations** in the live robot — pre-allocated ring buffers
- **Unified data model** shared between robot (online) and pipeline (offline/validation)
- **Wave-centric ML** — each wave is a training example with fire-features (X) and break-features (y)
- **VCS fits naturally** as an online model that reads/writes the same wave table
- **Extensible** to future online models beyond VCS (e.g. small neural net, pattern matcher)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Whiteboard (per-perspective)         │
├─────────────────────────────────────────────────────────┤
│  TickRing          [2 rows]   current + previous tick   │
│  BattleRow         [1 row]    per-battle constants      │
│  OurWaveTable      [N slots]  real + virtual bullets    │
│  TheirWaveTable    [M slots]  detected + speculative    │
│  MoveCandidates    [per their-wave × K paths]           │
│  Models            VCS + future learners                │
└─────────────────────────────────────────────────────────┘
```

---

## Data Tables

### 1. TickRing (depth=2)

Ring buffer of 2 tick rows. Stores current and previous tick features.

| Column | Type | Notes |
|--------|------|-------|
| All current `FileType.TICKS` features | double | Same as today |

**Purpose:** Compute deltas (acceleration, heading change) and provide the "state at fire time minus gun-turn-delay" for wave creation.

**Why depth=2:** A bullet fires based on gun aim computed 1–2 ticks before the fire event. Recording previous tick state at wave creation captures the decision-time context. Deeper history has no current use case.

### 2. BattleRow (1 row)

Per-battle constants and accumulators.

| Column | Type | Notes |
|--------|------|-------|
| BATTLEFIELD_WIDTH | double | |
| BATTLEFIELD_HEIGHT | double | |
| OPPONENT_ID_HASH | int | |
| ROUND_INDEX | int | |
| OUR_TOTAL_HITS | int | running counter |
| OUR_TOTAL_FIRED | int | running counter |

### 3. OurWaveTable (ring buffer, N=64 slots)

Each row = one bullet we fired (real) or considered firing (virtual).

**Fire-time columns (frozen at fire tick):**

| Column | Type | Notes |
|--------|------|-------|
| slot_state | byte | FREE=0, ACTIVE=1, RESOLVED=2 |
| is_real | bool | true=actual bullet, false=virtual |
| bullet_id | int | Bullet.hashCode() for real; 0 for virtual |
| fire_tick | long | |
| fire_x, fire_y | double | our position |
| fire_bearing | double | absolute bearing to opponent |
| fire_power | double | |
| bullet_speed | double | 20 - 3×power |
| mea | double | asin(8/speed) |
| direction | int | ±1 lateral direction |
| fire_distance | double | |
| fire_lateral_velocity | double | |
| fire_advancing_velocity | double | |
| fire_opponent_x, fire_opponent_y | double | opponent position at fire |
| dist_segment | int | for VCS lookup |
| lat_vel_segment | int | for VCS lookup |
| aim_gf | double | GF we aimed at (from VCS peak or model) |
| fire_wall_distance | double | opponent's min distance to wall |
| fire_acceleration | double | opponent's velocity delta vs prev tick |
| fire_time_since_reverse | int | ticks since opponent changed lateral direction |

**Labels / outcomes (not available at fire time — filled later or by god view, exported to CSV for offline training only):**

| Column | Type | Source | Notes |
|--------|------|--------|-------|
| break_tick | long | later tick | tick wave reached opponent |
| break_opponent_x, break_opponent_y | double | later tick | opponent position at break |
| break_gf | double | later tick | **primary label** — actual GF at break |
| break_bearing_offset | double | later tick | angular offset from aim |
| hit | bool | event / god view | did our bullet actually hit |
| actual_distance_at_break | double | god view | precise distance (engine truth) |
| opponent_energy_at_fire | double | god view | exact energy (robot sees ±0.1) |

The robot fills `break_*` columns when the wave resolves (later tick), and `hit` from `onBulletHit` event. God-view columns are filled only by the pipeline's GodViewValidator. At inference time the robot uses only fire-time input features — labels are never read.

**Virtual bullet columns (for regret/accuracy evaluation):**

| Column | Type | Notes |
|--------|------|-------|
| virtual_aim_gf | double | the GF this virtual bullet was aimed at |
| virtual_would_hit | bool | computed at break: was opponent within bullet radius |

### 4. TheirWaveTable (ring buffer, M=32 slots)

Each row = one opponent bullet (confirmed or speculative).

**Detection columns:**

| Column | Type | Notes |
|--------|------|-------|
| slot_state | byte | FREE, SPECULATIVE, CONFIRMED, RESOLVED |
| detect_tick | long | tick we detected/predicted the fire |
| fire_tick | long | actual fire tick (may be detect_tick - 1) |
| fire_x, fire_y | double | opponent position at fire |
| fire_power | double | from energy drop |
| bullet_speed | double | |
| fire_bearing | double | bearing from them to us |
| speculative | bool | true until energy drop confirms |

**Labels / outcomes (filled at break time or by god view — CSV export for training only):**

| Column | Type | Source | Notes |
|--------|------|--------|-------|
| break_tick | long | later tick | tick their wave reached us |
| break_our_x, break_our_y | double | later tick | where we actually were |
| break_gf | double | later tick | GF where we were (their perspective) |
| hit_us | bool | event / god view | did their bullet hit us |
| danger_at_position | double | online | our VCS estimate at that GF |
| actual_bullet_power | double | god view | exact power (confirms our energy-drop estimate) |

The robot fills `break_*` at resolution time and `hit_us` from `onHitByBullet`. God-view columns are pipeline-only.

### 5. MoveCandidates (15 per re-plan: 5 GF × 3 distance)

Each candidate = a physically reachable target state we could arrive at. Candidates form
**pathways** — a sequence of tick-level states constrained by physics (accel ±1/−2, turn rate
= 10−0.75×|v|). Only some transitions between consecutive-tick states are valid.

**Multiple waves hit simultaneously:** A slow bullet (power 3, speed 11) fired earlier
can arrive at the same tick as a fast bullet (power 0.1, speed 19.7) fired later. Each
candidate must be scored against ALL active waves reaching us within the planning horizon
(next 3 waves).

**Candidate generation (per re-plan):**
1. Identify next 3 waves (by time to break)
2. Enumerate 15 target zones: 5 GF angles × 3 distance levels + random jitter
3. Check reachability via pre-calculated envelope
4. For reachable targets, simulate tick-by-tick path (get exact x, y, heading, velocity)
5. Compute GF and distance relative to each of the 3 waves at their respective break ticks
6. Pass full situation (candidate state + 3 wave feature vectors) to ML for danger score

**Re-planning frequency:** Every tick (scalable: skip ticks if CPU-constrained).

#### MovePlan (1 row per re-plan decision)

| Column | Type | Notes |
|--------|------|-------|
| plan_tick | long | when this plan was computed |
| our_x, our_y | double | our position at decision time |
| our_heading | double | |
| our_velocity | double | |
| wave0_slot | int | index into TheirWaveTable (most urgent) |
| wave1_slot | int | 2nd wave (or -1) |
| wave2_slot | int | 3rd wave (or -1) |
| wave0_ticks_to_break | int | |
| wave1_ticks_to_break | int | |
| wave2_ticks_to_break | int | |
| chosen_candidate_id | int | which of the 15 we picked |

#### MoveCandidate (15 rows per plan)

| Column | Type | Notes |
|--------|------|-------|
| candidate_id | int | 0..14 (5 GF × 3 dist) |
| target_x, target_y | double | target position at wave0 break |
| target_heading | double | heading at destination |
| target_velocity | double | velocity at destination |
| reachable | bool | envelope check passed |
| gf_at_wave0 | double | GF relative to wave 0 at its break tick |
| gf_at_wave1 | double | GF relative to wave 1 at its break tick |
| gf_at_wave2 | double | GF relative to wave 2 at its break tick |
| dist_to_wave0 | double | distance from wave 0 source at break |
| dist_to_wave1 | double | distance from wave 1 source at break |
| dist_to_wave2 | double | distance from wave 2 source at break |
| ml_danger_score | double | ML model output (combined risk) |
| chosen | bool | did we pick this candidate |

**Labels / outcomes (filled at wave break — CSV export for training):**

| Column | Type | Source | Notes |
|--------|------|--------|-------|
| actual_hit_by_wave0 | bool | event / god view | did wave 0 hit us at this path |
| actual_hit_by_wave1 | bool | event / god view | |
| actual_hit_by_wave2 | bool | event / god view | |
| actual_gf_at_wave0 | double | later tick | where we actually ended up (GF) |
| actual_x_at_wave0_break | double | later tick | actual position at wave 0 break |
| actual_y_at_wave0_break | double | later tick | |

---

### 6. ReachableEnvelope (pre-calculated offline, ~48KB)

For each (initial_velocity, ticks_available), stores the maximum reachable radius in
each direction relative to current heading. Used for O(1) feasibility checks.

**Offline computation:** Exhaustive BFS/simulation of all legal (accel, turn) sequences.
Stored as a static lookup table. The envelope represents the **robot center** reachable set.

**Storage format:**

```
// Boundary polygon per (velocity, ticks) slice
// Each boundary = ~32 points stored as (byte dx, byte dy) relative offsets
// byte = signed, 2px resolution → covers ±254 px (sufficient for 40 ticks at speed 8 = 320px max)
byte[17][40][32][2]  // [velocity_bin -8..+8][ticks 1..40][boundary_point 0..31][dx,dy]
// Total: 17 × 40 × 32 × 2 = 43,520 bytes ≈ 43 KB
// Actual position offset = byte_value × 2 (pixels), relative to current position
```

**Online use:**
1. Look up boundary polygon for current velocity_bin and ticks_to_break
2. Rotate polygon points by current heading (they're stored relative to heading=0)
3. Clip by walls: shrink polygon where boundary would exit the battlefield
4. For each candidate target: compute (dx, dy) relative to current position
5. Check: is (dx, dy) inside the boundary polygon → reachable

**Key insight:** The envelope of the robot CENTER differs from the bullet-hit boundary.
A bullet hits if it crosses the 36×36 axis-aligned box. From 45° absolute angle, the
effective target cross-section is 36√2 ≈ 50.9 px (41% larger than from cardinal angles).
MEA calculations should account for this angle-dependent hitbox width.

**Relationship to MEA:** Classical MEA = arcsin(8/bulletSpeed) assumes a point robot.
True MEA should add the angle-dependent half-width of the hitbox:
`effective_MEA = arcsin((8 + hitbox_half_width(angle)) / bulletSpeed)`
where `hitbox_half_width(angle) = 18 × (|cos(angle)| + |sin(angle)|)` (max 18√2 ≈ 25.5 at 45°).

---

## Models Table

Online-learned models that persist between rounds/battles.

### VCS (current)

- **Storage:** `int[5][5][31]` histogram — maps naturally to OurWaveTable rows
- **Update:** On wave break, `vcs.increment(wave.dist_segment, wave.lat_vel_segment, break_gf_bin)`
- **Query:** On fire, `vcs.getBestBin(dist_segment, lat_vel_segment)` → aim_gf
- **Persistence:** Binary file, keyed by opponent hash

### Future online models

| Model | Input | Output | Update trigger |
|-------|-------|--------|----------------|
| VCS | dist_seg, lat_vel_seg | GF histogram | our wave break |
| Anti-surfer | their_wave_break_gf history | predicted next GF | their wave break |
| Flattener | our GF profile as seen by them | movement bias | their wave break |
| Pattern matcher | tick sequence | predicted GF | our wave break |

All models read from the same wave tables. Each model writes its prediction into the wave row (`aim_gf` for gun, `danger_score` for movement).

---

## Validation Architecture

### Level 1: Deterministic (existing)

- **DebugValidator:** Robot debug properties == pipeline whiteboard (same inputs, same code → exact match)
- **GodViewValidator:** Pipeline whiteboard vs engine ground truth (validates feature formulas)

### Level 2: Wave-level cross-check (new)

For `OUR_FIRE_BULLET_ID`: pipeline's `WaveResolver` sees `IBulletSnapshot.getBulletId()`. Cross-check that the robot-side stored bullet_id matches the engine's bullet_id for the same fire event.

For `OUR_BREAK_HIT`: pipeline sees `BulletState.HIT_VICTIM`. Cross-check that robot-side `wave.hit` agrees with engine truth.

### Level 3: Prediction evaluation (new)

| What | Metric | Computed at |
|------|--------|-------------|
| Aim accuracy | `abs(aim_gf - break_gf)` per wave | wave break |
| Hit rate | `sum(hit) / count(real_waves)` per round | round end |
| Regret | `min_virtual(abs(v.aim_gf - break_gf)) - abs(aim_gf - break_gf)` | wave break |
| Movement danger calibration | correlation(danger_score, hit_us) | their wave break |
| Dodge success | `1 - hit_us` vs average | their wave break |
| Move-candidate ML calibration | correlation(ml_danger_score, actual_hit) | their wave break |
| Envelope accuracy | was chosen path actually reachable (god view) | their wave break |

Regret answers: "how much better would the best alternative have been?"

---

## Memory Layout (Robot, Zero-Alloc)

```java
public final class Whiteboard {
    // Tick ring (depth=2)
    private final double[][] tickRing = new double[2][TICK_FEATURE_COUNT];
    private int tickHead = 0;  // points to current tick

    // Battle constants
    private final double[] battleRow = new double[BATTLE_FEATURE_COUNT];

    // Our waves (ring buffer)
    private final double[][] ourWaves = new double[64][OUR_WAVE_COL_COUNT];
    private final byte[] ourWaveState = new byte[64]; // FREE/ACTIVE/RESOLVED
    private int ourWaveHead = 0;

    // Their waves (ring buffer)
    private final double[][] theirWaves = new double[32][THEIR_WAVE_COL_COUNT];
    private final byte[] theirWaveState = new byte[32];
    private int theirWaveHead = 0;

    // Move planning (re-planned every tick, only latest plan stored)
    private final double[] movePlan = new double[MOVE_PLAN_COL_COUNT];
    private final double[][] moveCandidates = new double[15][MOVE_CANDIDATE_COL_COUNT];

    // Reachable envelope (pre-calculated, loaded once)
    // [velocity_bin -8..+8][ticks 1..40][boundary_point 0..31][dx,dy] — 2px resolution
    private final byte[][][][] reachableEnvelope = new byte[17][40][32][2]; // 43 KB

    // Models
    private VcsStore vcsStore;
    // Future: IOnlineModel[] models;
}
```

All arrays pre-allocated at construction. Ring buffers overwrite oldest entries when full.
`moveCandidates` is overwritten each tick (latest plan only). The pipeline stores full
history of all plans for CSV export. Tick-level path simulation is ephemeral (compute →
score → discard; only the chosen candidate is stored).

---

## CSV Output Mapping

| Table | CSV file | Row trigger | Notes |
|-------|----------|-------------|-------|
| TickRing | ticks.csv | every tick | |
| OurWaveTable | our-waves.csv | at wave break | complete lifecycle (fire + break columns) |
| TheirWaveTable | their-waves.csv | at their wave break | |
| MovePlan + Candidates | move-plans.csv | at their wave break | 1 plan row + 15 candidate sub-rows per event |
| MoveCandidates (detail) | move-candidates.csv | at their wave break | one row per candidate with outcomes |
| BattleRow | scores.csv | at round end | |
| BattleRow | scores.csv | at round end |

The pipeline writes rows at break time (not fire time), so each CSV row has the complete lifecycle of one wave.

---

## Offline Enrichment Pipeline

```
battle.br → Player → Whiteboard tables → WaveResolver fills break columns
                                        → GodViewValidator checks all features
                                        → CsvWriter dumps complete rows
                                        → ML reads CSV
```

All features are online — the robot computes them from data it already has:
- `fire_wall_distance`: min(opponent_x, opponent_y, bf_width - opponent_x, bf_height - opponent_y)
- `fire_acceleration`: velocity[t] - velocity[t-1] from TickRing
- `fire_time_since_reverse`: counter reset on lateral velocity sign change

The pipeline's role is:
1. **Validation** — GodViewValidator checks robot-computed input features against engine truth
2. **Label generation** — fills god-view-only outcome columns (exact energy, exact distances) that the robot cannot observe
3. **CSV export** — dumps complete rows (input features + labels) for ML training

Input features are all online — the robot computes them from data it already has. Labels/outcomes come from later ticks (break-time resolution) or god view. At inference time the robot uses only input features; labels exist solely for offline training.

---

## Migration Path

### Phase 1: Structural (no behavior change)
1. Add column-index enums for each table (replace single `Feature` enum or extend it)
2. Replace `double[] features` with `double[][] tickRing` (depth=2 initially)
3. Replace `List<Wave> activeWaves` with pre-allocated `ourWaves[][]` ring buffer
4. WaveTracker reads/writes from wave table instead of flat features
5. CsvWriter reads from tables at appropriate trigger points
6. All existing tests still pass

### Phase 2: Their-wave resolution
1. Add TheirWaveTable with detection + break columns
2. Implement speculative wave creation (on gunHeat=0)
3. Confirm/discard speculative waves on energy drop
4. Resolve their waves → set THEIR_BREAK_* features
5. Add movement candidate scoring

### Phase 3: Virtual bullets
1. On each real fire, also fire K virtual bullets at alternative GFs
2. At wave break, evaluate all virtual bullets
3. Compute regret metric
4. Log to CSV for ML analysis

### Phase 4: Online model abstraction
1. Define `IOnlineModel` interface: `predict(wave)`, `update(wave)`
2. VcsStore becomes first `IOnlineModel` implementation
3. Model selection/ensemble: fire at the model with lowest recent regret

---

## Open Questions (Resolved)

1. **Column enum design:** Separate enums per table (`TickFeature`, `OurWaveColumn`, `TheirWaveColumn`, `MoveCandidateColumn`).
2. **Ring buffer overflow:** Throw exception. Re-implement with larger constant if it ever triggers in practice.
3. **Speculative wave lifecycle:** Discard once we confirm no fire — max 2 ticks after detection (energy drop didn't happen).
4. **Virtual bullet count K:** Start with 10 (evenly spaced across GF range). Tune later based on regret data.
5. **Pipeline-only features:** None. All features are online. The pipeline validates correctness but doesn't compute features the robot can't. New features are prototyped in robot code, validated by pipeline.
