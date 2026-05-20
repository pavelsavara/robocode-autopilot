# Plan: GuessFactor Targeting Gun

## Goal
Replace HeadOnGunStrategy with a GuessFactor (GF) gun using Visit Count Stats (VCS).
Persist stats between battles. Measure score improvement. Establish testable baseline.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  Robot (Autopilot.java)                                         │
│  ┌─────────────────┐   ┌──────────────────────────┐             │
│  │ GFGunStrategy   │─> │ VcsStore (int[][][])     │             │
│  │  • aim at peak  │   │  • load/save per opponent│             │
│  │  • log our waves│   │  • segment[dist][latV]   │             │
│  └─────────────────┘   └──────────────────────────┘             │
│  ┌─────────────────┐                                            │
│  │ WaveTracker     │  Tracks our outgoing waves (fire_tick,     │
│  │  • create wave  │  origin, bearing, power, direction)        │
│  │  • resolve wave │  On resolution: compute GF → update VCS    │
│  └─────────────────┘                                            │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Pipeline (GodView wave resolution for validation/ML)           │
│  ┌─────────────────────┐   ┌─────────────────────────────┐      │
│  │ WaveResolver        │──>│ our-waves.csv (enriched)    │      │
│  │  • per-fire wave    │   │  our_fire_* + our_break_*   │      │
│  │  • resolve vs GT    │   │  + actual_gf (ground truth) │      │
│  └─────────────────────┘   └─────────────────────────────┘      │
└─────────────────────────────────────────────────────────────────┘
```

---

## Decisions (from discussion)

| Decision | Choice |
|----------|--------|
| GF bins | 31 (odd, GF=0 at index 15) |
| Segmentation | 2D: distance band × |lateralVelocity| band |
| Persistence | Single `getDataFile("vcs.dat")` — keyed by FNV-1a hash of opponent name |
| Score metric | Assert both win rate % AND score ratio in BattleLoopTest |
| Wave resolution | Both: robot-side for aiming; pipeline god-view for validation |
| Feature timing | Prefix convention: `fire_xxx` (known at fire), `break_xxx` (known at resolution) |
| Gun integration | Replace HeadOnGunStrategy immediately |
| CSV separation | `their-waves.csv` = opponent incoming waves; `our-waves.csv` = our outgoing waves |

---

## Feature Timing: The Posterior-Leak Problem

When features are computed for offline ML, we must know **when** each feature
was knowable:

| Timing | Features | Available at |
|--------|----------|--------------|
| `fire_*` | distance, lateral_velocity, advancing_velocity, our_x, our_y, opponent_bearing_abs, fire_power, direction | Tick we fire |
| `break_*` | actual_gf, opponent_x, opponent_y, bearing_offset | Tick wave reaches opponent |

The gun-wave CSV row is **created** at fire time (with `fire_*` columns), then
**updated** at break time (adding `break_*` columns). Offline ML must only
use `fire_*` features as inputs; `break_*` is the label.

---

## CSV File Separation

| File | Trigger | Perspective | Purpose |
|------|---------|-------------|--------|
| `ticks.csv` | Every tick | Per-robot | Full game state time series |
| `their-waves.csv` | Opponent fire detected (energy drop) | Per-robot | Incoming waves (future: wave surfing) |
| `our-waves.csv` | **We** fire a bullet | Per-robot | Outgoing waves (GF gun learning) |
| `scores.csv` | Round end | Per-robot | Round outcomes |

`their-waves.csv` (renamed from `waves.csv`) — opponent fires, `THEIR_FIRE_POWER` trigger.
`our-waves.csv` is new — triggered when our bullet is fired, resolved when
our wave reaches the opponent. Contains `OUR_FIRE_*` and `OUR_BREAK_*` columns.

---

## New Features (added to Feature enum / our-waves.csv)

### OUR_FIRE_* features (inputs for gun, known at fire tick)
| Feature | Formula | Purpose |
|---------|---------|---------|
| `OUR_FIRE_DISTANCE` | Distance at fire tick | Segmentation axis |
| `OUR_FIRE_LATERAL_VELOCITY` | Opponent lat. vel at fire tick | Segmentation axis |
| `OUR_FIRE_ADVANCING_VELOCITY` | Opponent adv. vel at fire tick | Context |
| `OUR_FIRE_BULLET_SPEED` | `20 - 3 * power` | MEA denominator |
| `OUR_FIRE_MEA` | `asin(8.0 / bulletSpeed)` | GF normalization |
| `OUR_FIRE_DIRECTION` | Sign of lateral velocity (+1 CW, -1 CCW) | GF sign |
| `OUR_FIRE_BEARING_ABSOLUTE` | Absolute bearing to opponent at fire | Wave origin angle |
| `OUR_FIRE_X` / `OUR_FIRE_Y` | Our position at fire | Wave origin |
| `OUR_FIRE_OPPONENT_X` / `OUR_FIRE_OPPONENT_Y` | Opponent estimated pos at fire | Resolution check |

### OUR_BREAK_* features (labels / validation, known at wave resolution)
| Feature | Formula | Purpose |
|---------|---------|---------|
| `OUR_BREAK_TICK` | Tick when wave reaches opponent | Timing |
| `OUR_BREAK_GF` | `clamp(angleOffset / MEA, -1, 1) * direction` | **The label** |
| `OUR_BREAK_BEARING_OFFSET` | Unclamped angular offset | Raw angle |
| `OUR_BREAK_OPPONENT_X` / `OUR_BREAK_OPPONENT_Y` | Actual opponent pos (god-view) | Ground truth |
| `OUR_BREAK_HIT` | 1 if our bullet hit, 0 otherwise | Success flag |

---

## Segmentation Design

### Distance bands (5 segments)
| Index | Range (px) |
|-------|-----------|
| 0 | 0–200 |
| 1 | 200–400 |
| 2 | 400–600 |
| 3 | 600–800 |
| 4 | 800+ |

### Lateral velocity bands (5 segments)
| Index | |latVel| range |
|-------|----------------|
| 0 | 0–1.5 (nearly still) |
| 1 | 1.5–4.0 (slow) |
| 2 | 4.0–6.0 (medium) |
| 3 | 6.0–7.5 (fast) |
| 4 | 7.5–8.0 (max) |

Total: 5 × 5 × 31 = 775 bins. Compact.

---

## Implementation Plan

### Phase 1: Core GF Infrastructure (core module, Java 8)

1. **`GuessFactor.java`** — utility class:
   - `maxEscapeAngle(double bulletSpeed)` → `asin(8.0 / bulletSpeed)`
   - `guessFactor(double angleOffset, double mea, int direction)` → `[-1, 1]`
   - `gfToBinIndex(double gf, int numBins)` → `int`
   - `binIndexToGf(int index, int numBins)` → `double`
   - `distanceSegment(double distance)` → `int [0-4]`
   - `lateralVelocitySegment(double latVel)` → `int [0-4]`
   - `bulletSpeed(double power)` → `20 - 3*power`

2. **`VcsStore.java`** — persistent VCS storage:
   - `int[5][5][31]` histogram (distance × latVel × bins)
   - `increment(int distSeg, int latVelSeg, int binIndex)`
   - `getBestBin(int distSeg, int latVelSeg)` → index of peak
   - `save(DataOutputStream)` / `load(DataInputStream)` — binary I/O (one section)
   - `decay()` — optional rolling limit (MAX=255, decrement others)
   - `clear()`

3. **`VcsFile.java`** — multi-opponent persistence in single `vcs.dat`:
   - File format: `[int opponentHash][int dataLength][byte[] vcsData]...`
   - `loadForOpponent(File dataFile, int opponentHash)` → `VcsStore` or empty
   - `saveForOpponent(File dataFile, int opponentHash, VcsStore store)` — upsert
   - Uses `getDataFile("vcs.dat")` in robot context

4. **`IdentityFeatures.java`** (IInGameFeatures) — opponent identification:
   - On first scan: parse opponent name → `opponentBotId` -> `OPPONENT_ID_HASH`
   - `fnv1a32(String)` → 32-bit hash of full opponent name
   - Outputs: `OPPONENT_ID_HASH` feature (used by VcsFile for lookup)
   - Parsing logic:
     ```java
     int sp = name.indexOf(' ');
     opponentBotId = (sp < 0) ? name : name.substring(0, sp);
     ```
   - FNV-1a constants: `OFFSET_BASIS = 0x811c9dc5`, `PRIME = 0x01000193`

5. **`Wave.java`** — data class for in-flight wave:
   - `fireX, fireY, fireTick, fireBearing, bulletSpeed, direction`
   - `distanceSegment, latVelSegment` (frozen at fire time)
   - `hasReached(double targetX, double targetY, long currentTick)` → boolean
   - `computeGuessFactor(double targetX, double targetY)` → double

6. **New Feature enum entries** (FileType.OUR_WAVES):
   - Add all `OUR_FIRE_*` and `OUR_BREAK_*` features listed above
   - Add `OPPONENT_ID_HASH` (FileType.TICKS) — set by IdentityFeatures
   - Rename existing `OPPONENT_FIRE_POWER` → `THEIR_FIRE_POWER` (FileType.THEIR_WAVES)

7. **`WaveFeatures.java`** (IInGameFeatures):
   - Computes `OUR_FIRE_MEA`, `OUR_FIRE_BULLET_SPEED`, `OUR_FIRE_DIRECTION` from existing features
   - Depends on: `DISTANCE`, `OPPONENT_LATERAL_VELOCITY`, `THEIR_FIRE_POWER`

### Phase 2: Robot Gun (robot module)

8. **`GFGunStrategy.java`** implements `IGunStrategy`:
   - Holds `VcsStore` + `List<Wave> activeWaves`
   - **On fire:** Create Wave, store in activeWaves
   - **On scan:** Resolve any waves that passed opponent position → update VCS
   - **getFireCommand():** Look up best GF bin → convert to absolute bearing offset
   - **Persistence:** Uses `VcsFile` to load/save from `getDataFile("vcs.dat")`
     keyed by `OPPONENT_ID_HASH` from IdentityFeatures

9. **`WaveTracker.java`** — manages wave lifecycle in robot:
   - `createWave(tick, x, y, bearing, power, latVel, distance)`
   - `resolveWaves(opponentX, opponentY, currentTick)` → updates VCS
   - Called from Autopilot.onScannedRobot()

10. **Update `Autopilot.java`:**
    - Replace `HeadOnGunStrategy` with `GFGunStrategy`
    - Register `IdentityFeatures` in whiteboard
    - Wire wave creation on fire, resolution on scan
    - Load VCS on first scan (once opponentHash known), save in onBattleEnded

### Phase 3: Pipeline Wave Resolution (god-view ground truth)

11. **`WaveResolver.java`** (pipeline module):
   - Tracks all fired bullets from both perspectives (using engine snapshots)
   - On wave break: records `OUR_BREAK_GF`, `OUR_BREAK_OPPONENT_X/Y`, `OUR_BREAK_HIT`
   - Writes enriched wave rows to our-waves.csv

12. **Enrich `our-waves.csv`**:
    - Fire-time columns written when fire detected (existing trigger)
    - Break-time columns appended when wave resolves (new)
    - Alternative: write break as separate row with same wave_id (join key)
    - **Decision:** Single row approach — buffer wave row, flush when resolved

13. **GodViewValidator extensions:**
    - Validate `BREAK_GF` computed by robot matches pipeline ground truth
    - Track hit rate prediction accuracy

### Phase 4: Testing

14. **Unit tests (core module):**
    - `GuessFactor` math: MEA, GF conversion, bin indexing, segments
    - `VcsStore`: increment, best bin, save/load roundtrip, decay
    - `VcsFile`: multi-opponent save/load, upsert existing key
    - `IdentityFeatures`: name parsing, fnv1a32 known vectors
    - `Wave`: hasReached geometry, computeGuessFactor edge cases

15. **Unit tests (pipeline module):**
    - `WaveResolver`: mock scenarios with known positions → verify BREAK_GF
    - Fire-time vs break-time column separation

16. **Integration tests (BattleLoopTest):**
    - Assert our-waves.csv has `our_fire_mea`, `our_break_gf` columns
    - Assert VCS file created in data/ after battle
    - **Baseline assertion:**
      - vs SittingDuck: win rate = 100%, score ratio > 5.0
      - vs Crazy: win rate ≥ 60%, score ratio > 1.2
      - vs sample.Fire: win rate ≥ 50%
      - vs sample.Walls: win rate ≥ 40%
    - Assert GF gun hit rate > head-on hit rate (measured via our-waves.csv OUR_BREAK_HIT)

17. **DebugValidator extensions:**
    - Validate robot-computed `OUR_FIRE_MEA` matches pipeline-computed value
    - Validate `OUR_FIRE_DIRECTION` sign consistency

18. **GodViewValidator extensions:**
    - Compare robot wave resolution GF vs god-view GF (should match within ε on scan ticks)
    - Report mean absolute GF error

### Phase 5: Score Reporting & Baseline

19. **Score extraction from BattleLoopTest:**
    - Parse scores.csv → compute win rate % and score ratio
    - Print summary to stdout (for CI visibility)
    - Assert minimum thresholds (start conservative, tighten as gun improves)

20. **Pipeline CSV enhancement:**
    - Add `hit_rate` column to scores.csv (bullets_hit / bullets_fired per round)
    - Track gun accuracy trend across rounds

---

## File Changes Summary

### New files
| File | Module |
|------|--------|
| `core/src/main/java/.../core/GuessFactor.java` | core |
| `core/src/main/java/.../core/VcsStore.java` | core |
| `core/src/main/java/.../core/VcsFile.java` | core |
| `core/src/main/java/.../core/Wave.java` | core |
| `core/src/main/java/.../core/features/IdentityFeatures.java` | core |
| `core/src/main/java/.../core/features/WaveFeatures.java` | core |
| `core/src/main/java/.../core/strategy/GFGunStrategy.java` | core |
| `robot/src/main/java/.../WaveTracker.java` | robot |
| `pipeline/src/main/java/.../pipeline/WaveResolver.java` | pipeline |
| `core/src/test/java/.../core/GuessFactorTest.java` | core |
| `core/src/test/java/.../core/VcsStoreTest.java` | core |
| `core/src/test/java/.../core/VcsFileTest.java` | core |
| `core/src/test/java/.../core/IdentityFeaturesTest.java` | core |
| `core/src/test/java/.../core/WaveTest.java` | core |
| `pipeline/src/test/java/.../pipeline/WaveResolverTest.java` | pipeline |

### Modified files
| File | Change |
|------|--------|
| `core/.../Feature.java` | Add FIRE_*, BREAK_*, OPPONENT_ID_HASH entries |
| `core/.../FileType.java` | Add `GUN_WAVES` entry |
| `robot/.../Autopilot.java` | Wire GFGunStrategy, IdentityFeatures, wave tracking, persistence |
| `pipeline/.../StreamingPipelineObserver.java` | Integrate WaveResolver |
| `pipeline/.../GodViewValidator.java` | Add GF validation |
| `pipeline/.../CsvWriter.java` | Handle deferred wave row completion |
| `pipeline/.../BattleLoopTest.java` | Add score assertions + GF validation checks |

---

## Maximum Escape Angle Reference

```
MEA = asin(8.0 / bulletSpeed)
bulletSpeed = 20 - 3 * power

Power 0.1 → speed 19.7 → MEA ≈ 0.407 rad (23.3°)
Power 1.0 → speed 17.0 → MEA ≈ 0.470 rad (27.0°)
Power 2.0 → speed 14.0 → MEA ≈ 0.571 rad (32.7°)
Power 3.0 → speed 11.0 → MEA ≈ 0.748 rad (42.8°)
```

---

## Risk & Open Questions

1. **First-battle cold start:** No VCS data → fire at GF=0 (head-on). Still better
   than pure head-on because we start learning immediately.
2. **Radar gaps:** If TICKS_SINCE_SCAN > bullet flight time, wave resolution uses
   stale opponent position. Accept inaccuracy for now; precise prediction later.
3. **Wall constraints:** Simple MEA (`asin(8/v)`) ignores walls. Acceptable for
   v1; precise MEA is a future improvement.
4. **Multi-fire between scans:** Current GodViewValidator already throws on this.
   For wave resolution, buffer and resolve on next scan.
5. **Persistence:** `getDataFile("vcs.dat")` stores all opponent data in one
   file, keyed by FNV-1a hash. Hash collisions (2^32 space) are negligible for
   Robocode opponent counts. File is append/upsert on battle end.

---

## Success Criteria

- [ ] GF gun replaces head-on, fires at peak VCS bin
- [ ] Waves resolve correctly (unit + integration tests pass)
- [ ] VCS persists between rounds within a battle, between battles via file
- [ ] our-waves.csv contains our_fire_* and our_break_* columns with correct timing
- [ ] BattleLoopTest asserts minimum win rate + score ratio per opponent
- [ ] GodViewValidator reports GF error < 0.1 mean absolute on scan ticks
- [ ] Hit rate vs SittingDuck ≈ 95%+ (validates correctness)
- [ ] Hit rate vs Crazy > head-on baseline (validates learning)
