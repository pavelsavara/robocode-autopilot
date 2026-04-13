# Stage 2: Feature Engineering Pipeline — Implementation Plan

## 1. Project Overview

**Repository:** `robocode-autopilot` (standalone, separate from the robocode engine repo)
**Language:** Java 21
**Build:** Gradle Kotlin DSL
**Dependencies:** Robocode JARs from Maven Central (`net.sf.robocode`, v1.10.2)

Two main deliverables:
- **A) Stage 2 Pipeline** — Reads `.br` battle recordings, replays them through a synthesized robot API, computes features, writes CSV.
- **B) Stage 5 Robot** — `cz.zamboch.Autopilot` — competition robot skeleton. No ML yet, just wiring: receives events via real Robocode engine, computes features via shared Whiteboard/Transformer code, delegates decisions to a future OnlineML module.

---

## 2. Architecture

### 2.1 Component Diagram

```
                          ┌──────────────────────────────────────────────────┐
                          │              Stage 2 Pipeline (A)               │
                          │                                                │
  .br files ──▶ [Loader] ──▶ [Player] ──▶ [Autopilot] ──▶ [Whiteboard] ──▶ [Transformer] ──▶ [CsvWriter]
                  │              │            │                │                │
                  │         synthesizes    extends          implements     IInGameFeatures
                  │         robot events  AdvancedRobot    IAdvancedEvents implementations
                  │         from snapshots forwards to     IBasicEvents    compute features
                  │                       Whiteboard       IBasicEvents2   into Whiteboard
                  reads ZIP+OIS                            IBasicEvents3
                  directly                                 accumulates
                                                           tick state +
                                                           lookback
                          ┌──────────────────────────────────────────────────┐
                          │              Stage 5 Robot (B)                  │
                          │                                                │
  Robocode Engine ──▶ [cz.zamboch.Autopilot] ──▶ [Whiteboard] ──▶ [Transformer] ──▶ [OnlineML]
                        extends AdvancedRobot       │                                  (future)
                        receives real events    same code
                        forwards to Whiteboard  as pipeline
```

### 2.2 Component Responsibilities

| # | Component | Responsibility |
|---|-----------|---------------|
| 1 | **Loader** | Reads `.br` recording files. Opens ZIP entry, deserializes `BattleRecordInfo` header + `TurnSnapshot` objects via `ObjectInputStream`. No dependency on `RecordManager` — we own the reading code for future parallelism. |
| 2 | **Player** | Replays `TurnSnapshot` sequence through synthesized robot API. For each tick, it creates `ScannedRobotEvent`, `HitByBulletEvent`, `BulletHitEvent`, `HitRobotEvent`, `HitWallEvent`, `StatusEvent`, `BulletMissedEvent`, `BulletHitBulletEvent`, `RobotDeathEvent` etc. — the same events the real engine would deliver to an `AdvancedRobot`. This layer enforces the **observation boundary**: the robot only sees what the real API exposes. Delivers events by calling the Whiteboard's event interface methods directly. |
| 3 | **Autopilot** | The robot class (`cz.zamboch.Autopilot extends AdvancedRobot`). In the pipeline, an instance receives Player-synthesized events and forwards them to the Whiteboard. In Stage 5, the same class receives real engine events and forwards them to the Whiteboard. The Autopilot delegates all event handling to the Whiteboard — it is a thin forwarder. |
| 4 | **Whiteboard** | Central state store per robot perspective. **Implements `IAdvancedEvents`, `IBasicEvents`, `IBasicEvents2`, `IBasicEvents3`** — the Autopilot forwards all robocode events directly to the Whiteboard's event handler methods. Maintains: (a) current tick state, (b) lookback history (ring buffers for recent N ticks), (c) cumulative counters (shots fired, damage dealt, etc.). Features are keyed by a **numeric `Feature` enum** (not strings) for performance. Implements persistence between rounds via `getDataFile()` API (honoring `getDataQuotaAvailable()` size limit). Resets between battles. |
| 5 | **Transformer** | After all events for a tick are collected, takes data from Whiteboard and computes ML features. Each feature is an `IInGameFeatures` implementation that reads Whiteboard state and writes computed value(s) back to the Whiteboard. Features declare dependencies on each other for ordering. Features are pure computations — no prediction, no heuristics (except energy-drop detection for bullet power inference). |
| 6 | **CsvWriter** | Produces CSV output files per battle per robot perspective. Reads computed feature values from Whiteboard after Transformer runs. |
| 7 | **OnlineML** | In-game predictor consuming Whiteboard state. **Out of scope** — placeholder interface only. |

### 2.3 Data Flow Per Tick (Pipeline)

```
1. Loader reads TurnSnapshot[tick] from .br file
2. Player extracts god-view data from TurnSnapshot
3. Player determines what THIS robot can observe:
   a. Own state: position, heading, velocity, energy, gun heat (always visible)
   b. Scan: did radar sweep over opponent? → synthesize ScannedRobotEvent
   c. Bullet hits: did any bullet hit this tick? → synthesize hit events
   d. Collisions: robot-robot, robot-wall → synthesize collision events
4. Player delivers events to Whiteboard (via IBasicEvents/IAdvancedEvents interface methods)
5. Autopilot forwards to Whiteboard (same interface in live robot)
6. Whiteboard stores current state, updates ring buffers
7. Transformer.process(whiteboard):
   a. Execute pre-sorted features (dependency order resolved once at battle start)
   b. Each feature: reads whiteboard → computes → writes to whiteboard
8. CsvWriter delegates to each IOfflineFeatures to write its columns
9. Whiteboard.advanceTick() — shift current → history
```

### 2.4 Two Robot Perspectives

For each 1v1 battle, the pipeline runs **two independent flows** — one for each robot perspective. Each flow has its own Robot, Whiteboard, and Transformer instance. The Player iterates snapshots once and feeds both flows (robot A's perspective and robot B's perspective).

```
                     ┌── Autopilot A ──▶ Whiteboard A ──▶ Transformer A ──▶ CsvWriter A
Loader ──▶ Player ──┤
                     └── Autopilot B ──▶ Whiteboard B ──▶ Transformer B ──▶ CsvWriter B
```

---

## 3. Dependencies

### 3.1 Maven Dependencies

```kotlin
dependencies {
    // Robocode API — robot interfaces, event classes, Rules, BattleResults
    implementation("net.sf.robocode:robocode.api:1.10.2")

    // Robocode Battle — TurnSnapshot, RobotSnapshot, BulletSnapshot,
    //   ScoreSnapshot, BattleRecordInfo (serialized objects in .br files)
    implementation("net.sf.robocode:robocode.battle:1.10.2")

    // Robocode Core — shared utilities, serialization helpers
    implementation("net.sf.robocode:robocode.core:1.10.2")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}
```

> **Note:** We depend on `robocode.battle` because the `.br` files contain serialized `TurnSnapshot`/`RobotSnapshot`/`BulletSnapshot` classes from that module. `robocode.api` is needed for event classes, `Rules.java`, and the `AdvancedRobot` base class for Stage 5.

### 3.2 Gradle Module Structure

```
robocode-autopilot/
├── build.gradle.kts              # Root build
├── settings.gradle.kts
├── gradle.properties             # Version constants
├── gradle/
│   └── wrapper/
├── pipeline/                     # Stage 2 pipeline (executable)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/cz/zamboch/autopilot/pipeline/
│       │   ├── Main.java                    # CLI entry point
│       │   ├── Loader.java                  # .br file reader
│       │   ├── Player.java                  # Snapshot → robot event synthesis
│       │   └── CsvWriter.java               # CSV output
│       └── test/java/cz/zamboch/autopilot/pipeline/
├── core/                         # Shared library (Robot, Whiteboard, Transformer)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/cz/zamboch/autopilot/core/
│       │   ├── Feature.java                 # Numeric enum of all feature keys
│       │   ├── FileType.java                # Enum: TICKS, WAVES, SCORES
│       │   ├── Whiteboard.java              # State store + ring buffers + event interfaces
│       │   ├── Transformer.java             # Feature orchestrator
│       │   ├── IInGameFeatures.java         # In-game feature interface (core)
│       │   ├── features/                    # Feature implementations
│       │   │   ├── SpatialFeatures.java
│       │   │   ├── MovementFeatures.java
│       │   │   ├── EnergyFeatures.java
│       │   │   └── ...
│       │   └── util/
│       │       ├── RoboMath.java            # normalAbsoluteAngle, normalRelativeAngle, etc.
│       │       └── RingBuffer.java          # Fixed-size circular buffer
│       └── test/java/cz/zamboch/autopilot/core/
├── robot/                        # Stage 5 competition robot
│   ├── build.gradle.kts          # Produces robot JAR
│   └── src/
│       └── main/java/cz/zamboch/
│           └── Autopilot.java    # extends AdvancedRobot
├── recordings/                   # .br files from CI
│   └── 24360294214/
│       └── *.br
└── stage-2-plan.md               # This file
```

---

## 4. Component Details

### 4.1 Loader

Reads `.br` files directly — thin wrapper around ZIP + `ObjectInputStream`.

```java
public class Loader implements AutoCloseable {
    // Opens .br file (BINARY_ZIP format):
    // 1. FileInputStream → BufferedInputStream → ZipInputStream
    // 2. ZipInputStream.getNextEntry()
    // 3. ObjectInputStream wrapping ZipInputStream
    // 4. First object: BattleRecordInfo (metadata: turnsInRounds[], battleRules, robotCount)
    // 5. Subsequent objects: TurnSnapshot (one per tick per round)

    public Loader(Path brFile);
    public BattleRecordInfo getRecordInfo();

    // Iterates all snapshots sequentially, invoking consumer per tick
    public void forEachTurn(BiConsumer<Integer, ITurnSnapshot> roundAndTurnConsumer);

    // Future: parallel chunk reading
    public void close();
}
```

**Why not RecordManager?** RecordManager creates a temp file, copies all data into it, then reads from the temp file. We skip the temp file — read directly from the ZIP stream. This is simpler and opens the door to future streaming/parallel processing.

**Serialization format:** Standard Java `ObjectInputStream`. The `.br` file is a ZIP archive containing one entry. The entry contains:
1. `BattleRecordInfo` object (header)
2. Sequence of `TurnSnapshot` objects (one per tick, ordered by round then tick)

Since `TurnSnapshot`, `RobotSnapshot`, `BulletSnapshot` implement `java.io.Serializable`, we need `robocode.battle` on the classpath for deserialization.

### 4.2 Player

Replays TurnSnapshot data through synthesized robot events. This is the **honesty layer** — it converts god-view snapshots into what a real robot would observe.

```java
public class Player {
    // For each tick, for each robot perspective:
    // 1. Extract own robot state from IRobotSnapshot → synthesize StatusEvent
    // 2. Determine radar sweep arc (previous vs current radar heading)
    // 3. If radar arc intersects opponent's bounding box → synthesize ScannedRobotEvent:
    //    - distance: hypot(dx, dy)
    //    - bearing: relative to our heading
    //    - heading: opponent's absolute heading
    //    - velocity: opponent's signed velocity
    //    - energy: opponent's energy
    //    - name: opponent's name
    // 4. Check bullet states for transitions:
    //    - FIRED → new bullet (associate with owner)
    //    - HIT_VICTIM where victim=us → synthesize HitByBulletEvent
    //    - HIT_VICTIM where owner=us → synthesize BulletHitEvent
    //    - HIT_WALL where owner=us → synthesize BulletMissedEvent
    //    - HIT_BULLET where owner=us → synthesize BulletHitBulletEvent
    // 5. Check robot state transitions:
    //    - HIT_WALL → synthesize HitWallEvent
    //    - HIT_ROBOT → synthesize HitRobotEvent
    //    - DEAD (opponent) → synthesize RobotDeathEvent
    // 6. Deliver events to Robot instance

    public void replay(Loader loader, Whiteboard whiteboardA, Whiteboard whiteboardB);
}
```

**Scan synthesis:** The Player must replicate the engine's scan logic. The radar sweeps a sector between its previous heading and current heading. If the opponent's 36×36 bounding box intersects this arc (within 1200px), a `ScannedRobotEvent` is generated. We use the same `Arc2D` intersection logic as `RobotPeer.scan()`.

**Bullet tracking:** The Player maintains a bullet registry. Each tick, it reads `IBulletSnapshot[]` from the `TurnSnapshot` and tracks state transitions (FIRED→MOVING, MOVING→HIT_VICTIM, etc.). State transitions generate the appropriate events.

### 4.3 Autopilot (Robot)

The robot class — thin forwarder that delegates all events to the Whiteboard.

```java
public class Autopilot extends AdvancedRobot {
    private final Whiteboard whiteboard;
    private final Transformer transformer;

    // In Stage 5 (live robot): events arrive from the Robocode engine.
    // In pipeline: Player calls Whiteboard's event methods directly (same interfaces).

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        whiteboard.onScannedRobot(e);  // Whiteboard implements IBasicEvents
        // ... trigger transformer, decisions
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        whiteboard.onHitByBullet(e);
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        whiteboard.onBulletHit(e);
    }

    // ... all other event handlers forward to whiteboard
}
```

In the pipeline, the Player bypasses the Autopilot and calls `whiteboard.onScannedRobot(event)` etc. directly — same interface, same code path.

### 4.4 Whiteboard

Central state store. **Implements `IAdvancedEvents`, `IBasicEvents`, `IBasicEvents2`, `IBasicEvents3`** — receives robocode events directly. Accumulates per-tick data and provides lookback history.

```java
public class Whiteboard implements IAdvancedEvents, IBasicEvents, IBasicEvents2, IBasicEvents3 {

    // === Event interface implementations ===
    // Whiteboard receives events directly from Autopilot (live) or Player (pipeline).
    // Each event handler extracts relevant data and stores it in the current tick state.
    @Override
    public void onScannedRobot(ScannedRobotEvent e) { /* extract & store opponent state */ }
    @Override
    public void onHitByBullet(HitByBulletEvent e)   { /* record damage received */ }
    @Override
    public void onBulletHit(BulletHitEvent e)        { /* record damage dealt */ }
    @Override
    public void onHitWall(HitWallEvent e)            { /* record wall collision */ }
    @Override
    public void onHitRobot(HitRobotEvent e)          { /* record robot collision */ }
    @Override
    public void onStatus(StatusEvent e)              { /* update own state each tick */ }
    @Override
    public void onRobotDeath(RobotDeathEvent e)      { /* mark opponent dead */ }
    @Override
    public void onBulletMissed(BulletMissedEvent e)  { /* track bullet miss */ }
    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) { /* track bullet-bullet */ }
    // ... other event methods from IBasicEvents2, IBasicEvents3, IAdvancedEvents

    // === Current tick state ===
    // Own state (always available)
    double ourX, ourY, ourHeading, ourGunHeading, ourRadarHeading;
    double ourVelocity, ourEnergy, ourGunHeat;
    long tick;
    int round;
    String battleId;               // ASCII-compressed GUID from recording filename

    // Opponent state (from last scan — may be stale)
    double opponentX, opponentY, opponentHeading, opponentVelocity, opponentEnergy;
    long lastScanTick;
    boolean scanAvailableThisTick;

    // Battle constants
    int battlefieldWidth, battlefieldHeight;
    double gunCoolingRate;
    int numRounds;

    // === Lookback ring buffers ===
    RingBuffer<TickState> history;          // Last N ticks of own state
    RingBuffer<ScanState> scanHistory;      // Last N scan events
    RingBuffer<WaveInfo> ourWaves;          // Our active/completed waves
    RingBuffer<WaveInfo> enemyWaves;        // Detected enemy waves

    // === Cumulative counters ===
    int ourShotsFired, opponentShotsDetected;
    double damageDealt, damageReceived;
    int roundsWon, roundsLost;
    int ourBulletHitCount, opponentBulletHitCount;

    // === Computed features (written by Transformer) ===
    // Keyed by numeric Feature enum for performance (array-backed, not HashMap)
    double[] features;                     // Indexed by Feature.ordinal()
    boolean[] featureSet;                  // Whether feature[i] has been set this tick

    // === API ===
    void setFeature(Feature key, double value);
    double getFeature(Feature key);
    boolean hasFeature(Feature key);

    // Lifecycle
    void advanceTick();          // Shift current → history, clear feature flags
    void resetRound();           // Clear per-round state, keep battle-level
    void resetBattle();          // Full reset

    // Persistence (mirrors robot's getDataFile API)
    void saveRoundData(byte[] data);    // Persist between rounds
    byte[] loadRoundData();             // Restore at round start
    int getDataQuotaAvailable();        // Honors 200KB limit
}
```

### 4.4a Feature Enum

All features are identified by a numeric enum. This avoids string hashing overhead and enables array-backed storage in the Whiteboard.

```java
public enum Feature {
    DISTANCE,                        // #1
    BEARING_TO_OPPONENT_ABS,         // #3
    OPPONENT_VELOCITY,               // #28
    OPPONENT_LATERAL_VELOCITY,       // #31
    OPPONENT_ADVANCING_VELOCITY,     // #32
    OPPONENT_HEADING_DELTA,          // #35
    OPPONENT_ENERGY,                 // #44
    OPPONENT_FIRED,                  // #49
    OPPONENT_FIRE_POWER,             // #50
    OUR_GUN_HEAT,                    // #76
    TICKS_SINCE_SCAN,                // #73
    OPPONENT_DIST_TO_WALL_MIN,       // #25
    // ... new features added here as the catalog grows
}
```

### 4.4b FileType Enum

```java
public enum FileType {
    TICKS,     // ticks.csv — one row per game tick
    WAVES,     // waves.csv — one row per wave event
    SCORES     // scores.csv — one row per round end
}
```

### 4.5 Transformer & IInGameFeatures / IOfflineFeatures

```java
// core — shipped with the robot
public interface IInGameFeatures {
    /** Feature enum values this feature produces. */
    Feature[] getOutputFeatures();

    /** Feature enum values this feature depends on (must run first). */
    Feature[] getDependencies();

    /** Compute feature value(s) and write to whiteboard. */
    void process(Whiteboard wb);
}

// pipeline — offline only, extends IInGameFeatures with CSV support
public interface IOfflineFeatures extends IInGameFeatures {
    /** Which CSV file this feature contributes to. */
    FileType getFileType();

    /** Write CSV column header(s) for this feature's outputs. */
    void writeColumnNames(CsvRowWriter row);

    /** Write CSV column value(s) for the current tick/wave/round. */
    void writeRowValues(CsvRowWriter row, Whiteboard wb);
}

public class Transformer {
    private List<IInGameFeatures> sortedFeatures;  // Pre-sorted at battle start

    /** Register a feature. */
    public void register(IInGameFeatures feature);

    /** Resolve dependency order. Called once at battle start, not every tick. */
    public void resolveDependencies();

    /** Execute all features in pre-sorted order. */
    public void process(Whiteboard wb);

    /** Get all registered features (in dependency order if resolved). */
    public List<IInGameFeatures> getFeatures();
}
```

**IoC pattern for features:** New features are added by implementing `IInGameFeatures` (core) or `IOfflineFeatures` (pipeline) and registering with the Transformer. No modifications to existing code needed.

**Dependency resolution happens once** at battle start via `resolveDependencies()` — topological sort is performed once, then the sorted list is reused for every tick. This avoids re-sorting on every `process()` call.

**CSV writing is delegated to offline features:** Each `IOfflineFeatures` knows how to write its own column names and values. The `CsvWriter` iterates over all features for a given `FileType` and calls `writeColumnNames()`/`writeRowValues()` — it doesn't need to know about individual features.

### 4.6 CsvWriter

Produces CSV files per battle per robot perspective. **Does not know about individual features** — delegates column writing to `IOfflineFeatures` instances obtained from the Transformer.

```java
public class CsvWriter {
    private final Transformer transformer;

    // Output files per battle per robot:
    //   {battleId}/{robotName}/ticks.csv    — one row per tick
    //   {battleId}/{robotName}/waves.csv    — one row per wave (our + enemy)
    //   {battleId}/{robotName}/scores.csv   — one row per round (final scores)

    public CsvWriter(Path outputDir, String battleId, String robotName, Transformer transformer);

    // Writes header row for each file:
    //   Fixed columns first, then iterates transformer.getFeatures()
    //   calling feature.writeColumnNames(row) for each matching FileType
    public void writeHeaders();

    // Writes data rows by calling feature.writeRowValues(row, wb) for each
    public void writeTickRow(Whiteboard wb);
    public void writeWaveRow(WaveInfo wave, Whiteboard wb);
    public void writeScoreRow(int round, IScoreSnapshot score, Whiteboard wb);
    public void close();
}
```

**ticks.csv columns:** `battle_id`, `round`, `tick`, `scan_available`, + columns from each `IOfflineFeatures` with `FileType.TICKS` (via `writeColumnNames()`/`writeRowValues()`).

**waves.csv columns:** `battle_id`, `round`, `tick`, `wave_type` (our/enemy), `origin_x`, `origin_y`, `bullet_power`, `bullet_speed`, `bearing_at_fire`, `tick_broke`, `gf_at_break`, `hit` (bool), + columns from each `IOfflineFeatures` with `FileType.WAVES`.

**scores.csv columns:** `battle_id`, `round`, `tick_count`, `total_score`, `survival_score`, `bullet_damage_score`, `bullet_kill_bonus`, `ramming_damage_score`, `ramming_kill_bonus`, `firsts`, `seconds`, + columns from each `IOfflineFeatures` with `FileType.SCORES`.

> **`battle_id`** is the ASCII-compressed GUID from the `.br` filename (e.g., `0a24decd`). Present in all three CSV files for cross-referencing.

---

## 5. Initial Feature Set (Core 12)

Start with the features that top bots (DrussGT, Diamond, BeepBoop) rely on most heavily for their primary targeting and movement systems. These form the minimum viable feature set for initial ML experiments.

| # | Feature | From features.md | Domain | Rationale |
|---|---------|-------------------|--------|-----------|
| 1 | `distance` | #1 | A,C | Primary segmentation dimension for all top bots |
| 2 | `bearing_to_opponent_abs` | #3 | A,C | Foundation for all targeting geometry |
| 3 | `opponent_velocity` | #28 | A,C | Direct from scan — core movement signal |
| 4 | `opponent_lateral_velocity` | #31 | A,C | #1 targeting dimension (GF targeting) |
| 5 | `opponent_advancing_velocity` | #32 | A,D | Approach/retreat detection |
| 6 | `opponent_heading_delta` | #35 | A,C | Turn rate — circular targeting input |
| 7 | `opponent_energy` | #44 | B,E | Energy drop detection for fire inference |
| 8 | `opponent_fired` | #49 | B,D | Energy drop detection flag |
| 9 | `opponent_fire_power` | #50 | B,D | Inferred bullet power |
| 10 | `our_gun_heat` | #76 | C | Fire timing decisions |
| 11 | `ticks_since_scan` | #73 | A,B | Data staleness signal |
| 12 | `opponent_dist_to_wall_min` | #25 | A,C | Wall proximity — key segmentation dim |

All 12 are **[RT]** (real-time computable) — usable both in pipeline and in competition robot.

**Registration example:**
```java
transformer.register(new SpatialFeatures());     // distance, bearing, wall distances
transformer.register(new MovementFeatures());    // velocity, lateral_velocity, heading_delta
transformer.register(new EnergyFeatures());      // energy, energy_drop, fired detection
transformer.register(new TimingFeatures());      // gun_heat, ticks_since_scan
```

---

## 6. Scan Synthesis Logic

The Player must replicate the engine's scan detection. In 1v1, max 1 scan per tick.

### 6.1 Radar Sweep Detection

```
prevRadarHeading = radarHeading[tick-1]
currRadarHeading = radarHeading[tick]
sweepAngle = normalRelativeAngle(currRadarHeading - prevRadarHeading)

If abs(sweepAngle) > 0:
    Create arc from prevRadarHeading to currRadarHeading, radius 1200px
    Test if opponent's 36×36 bounding box intersects this arc
    If yes → generate ScannedRobotEvent with:
        distance = hypot(opponentX - ourX, opponentY - ourY)
        bearing = normalRelativeAngle(atan2(opponentX - ourX, opponentY - ourY) - ourHeading)
        heading = opponentHeading (absolute)
        velocity = opponentVelocity (signed)
        energy = opponentEnergy
```

### 6.2 First Tick of Round

On tick 0 of each round, the engine fires an initial scan for all robots. The Player should synthesize a `ScannedRobotEvent` on tick 0 regardless of radar sweep.

---

## 7. Energy Drop Detection

Universally used heuristic — not considered "cheating."

```java
double energyDrop = prevOpponentEnergy - currentOpponentEnergy;

boolean opponentFired = false;
double inferredPower = 0;

if (energyDrop >= 0.1              // Rules.MIN_BULLET_POWER
    && energyDrop <= 3.0            // Rules.MAX_BULLET_POWER
    && ticksSinceScan == 1          // Consecutive scans (no data gap)
    && !weHitOpponentThisTick       // Our bullet didn't cause the drop
    && !opponentHitWallThisTick     // Wall hit didn't cause the drop
    ) {
    opponentFired = true;
    inferredPower = energyDrop;
}
```

When fire detected, create a **wave** (origin, speed, tick) tracked in `Whiteboard.enemyWaves`.

---

## 8. CSV Output Details

### 8.1 Files Per Battle

Given a battle between `BotA` and `BotB` in recording `abc123.br`:

```
output/
└── abc123/
    ├── BotA/
    │   ├── ticks.csv       # One row per tick from BotA's perspective
    │   ├── waves.csv       # Wave events (our fired + enemy detected)
    │   └── scores.csv      # Per-round score summary
    └── BotB/
        ├── ticks.csv
        ├── waves.csv
        └── scores.csv
```

### 8.2 Precision

- Positions, distances, velocities: **3 decimal places**
- Angles (radians): **4 decimal places**
- Booleans: `0` / `1`
- Missing/stale data: `NaN`

### 8.3 Row Granularity

- **ticks.csv:** One row per game tick. Fixed columns: `battle_id`, `round`, `tick`, `scan_available`. If no scan available, opponent features carry forward from last scan; `ticks_since_scan` increments; delta features are `NaN`. Feature columns are written by each `IOfflineFeatures` with `FileType.TICKS`.
- **waves.csv:** One row per wave event (fire detected or wave breaks). Fixed columns: `battle_id`, `round`, `tick`. Includes wave origin, bullet power/speed, GF at break. Feature columns from `FileType.WAVES` features.
- **scores.csv:** One row per round end. Fixed columns: `battle_id`, `round`, `tick_count`, cumulative scores. Feature columns from `FileType.SCORES` features.

---

## 9. Testing Strategy

### 9.1 Unit Tests

| Test Area | Approach |
|-----------|----------|
| **Loader** | Load a known `.br` file, verify `BattleRecordInfo` fields (round count, robot names, battlefield dims). Verify tick count matches `turnsInRounds[]`. |
| **RoboMath** | Test `normalAbsoluteAngle`, `normalRelativeAngle`, `wallAheadDistance`, bearing calculations against known values. Edge cases: 0°, 90°, 180°, 270°, wrap-around. |
| **Features** | Hand-craft `Whiteboard` with known state. Verify each `IInGameFeatures` computes expected output. E.g., set opponent at (300,400) with us at (100,100) → verify `distance = 360.555`, `bearing_to_opponent_abs = 0.9828 rad`. |
| **Energy drop detection** | Craft sequence: opponent energy drops from 100→98 → verify `opponent_fired=true`, `opponent_fire_power=2.0`, `opponent_bullet_speed=14.0`. Test false positives: wall hits, our bullet hits. |
| **Ring buffers** | Verify capacity, FIFO ordering, lookback access. |
| **Dependency ordering** | Register features with circular dependency → verify error. Register with valid DAG → verify topological order. |

### 9.2 Integration Tests (God-View Validation)

**Key principle:** We can use the god-view data from `TurnSnapshot` to validate our observable-only feature computations.

| Test | How |
|------|-----|
| **Position validation** | Compute `opponent_x`, `opponent_y` from scan (distance + bearing). Compare against god-view `IRobotSnapshot.getX()`, `getY()`. Tolerance: 0.1 px. |
| **Velocity validation** | Compare scan-derived `opponent_velocity` to god-view `IRobotSnapshot.getVelocity()`. Should be exact. |
| **Heading validation** | Compare scan-derived `opponent_heading` to god-view. Should be exact. |
| **Scan timing validation** | Verify that scans occur only when radar sweep intersects opponent. Compare against god-view: on ticks where we don't generate a scan, verify the radar arc truly didn't sweep over opponent's bbox. |
| **Bullet detection validation** | When energy drop detection flags `opponent_fired=true`, verify against god-view bullet data: a bullet with `state=FIRED` and `ownerIndex=opponent` exists that tick. Check inferred power matches actual `IBulletSnapshot.getPower()`. |
| **Wave break validation** | When a wave breaks (reaches opponent distance), verify the opponent's actual GF position against the god-view position. |
| **End-to-end** | Process a full `.br` file. Verify CSV has expected row count (= total ticks across all rounds). Verify no `NaN` in features that should always have values (distance on scan ticks, own position every tick). |

### 9.3 Test Data

Use the 10 `.br` recordings in `recordings/24360294214/` as test fixtures. For unit tests, also create synthetic minimal recordings (a few ticks of controlled data).

### 9.4 Testing the God-View / Observable Boundary

The Player is the critical honesty layer. Integration tests should verify:

1. **No leakage:** Features computed from Player-synthesized events must NOT contain god-view data not available to a real robot. Specifically:
   - Opponent gun heading, radar heading, gun heat: NEVER accessible
   - Opponent position: ONLY when scan fires (not every tick)
   - Bullet positions in flight: NEVER accessible (only on impact events)

2. **Completeness:** All events a real robot would receive ARE synthesized:
   - `ScannedRobotEvent` on correct ticks
   - `HitByBulletEvent` with correct power, bearing, heading
   - `BulletHitEvent` when our bullet hits
   - `HitWallEvent`, `HitRobotEvent`, `RobotDeathEvent`

---

## 10. Stage 5 Robot Skeleton — `cz.zamboch.Autopilot`

```java
package cz.zamboch;

import robocode.AdvancedRobot;
import robocode.ScannedRobotEvent;
import robocode.HitByBulletEvent;
import robocode.BulletHitEvent;
import robocode.StatusEvent;
// ...

public class Autopilot extends AdvancedRobot {
    private Whiteboard whiteboard;
    private Transformer transformer;
    // private OnlineML ml;  // future

    @Override
    public void run() {
        whiteboard = new Whiteboard();
        transformer = new Transformer();
        registerFeatures(transformer);
        transformer.resolveDependencies();  // Once at battle start

        whiteboard.setBattleId(computeBattleId());
        whiteboard.onRoundStart(getRoundNum(), (int) getBattleFieldWidth(),
                                (int) getBattleFieldHeight(), getGunCoolingRate(),
                                getNumRounds());

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY); // Infinite radar lock
            execute();
        }
    }

    @Override
    public void onStatus(StatusEvent e) {
        whiteboard.onStatus(e);  // Whiteboard implements IBasicEvents
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        whiteboard.onScannedRobot(e);
        transformer.process(whiteboard);
        // ml.decide(whiteboard);  // future
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        whiteboard.onHitByBullet(e);
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        whiteboard.onBulletHit(e);
    }

    // ... all other event handlers forward to whiteboard

    private void registerFeatures(Transformer t) {
        t.register(new SpatialFeatures());
        t.register(new MovementFeatures());
        t.register(new EnergyFeatures());
        t.register(new TimingFeatures());
    }
}
```

---

## 11. Pipeline CLI

```
java -jar pipeline.jar --input recordings/24360294214/ --output output/ [--features all|core]
```

- Scans `--input` directory for `.br` files
- For each `.br` file, runs dual-perspective feature extraction
- Writes CSVs to `--output` directory structure
- `--features core` = initial 12 features; `--features all` = all registered features

---

## 12. Implementation Phases

### Phase A: Project Scaffolding
1. Gradle multi-module setup (root, `core`, `pipeline`, `robot`)
2. Maven dependencies configured
3. Basic project compiles

### Phase B: Loader + Basic Player
1. Loader reads `.br` files, iterates TurnSnapshots
2. Player extracts own-robot state per tick
3. Integration test: load real `.br`, verify tick counts
4. Unit test: verify BattleRecordInfo parsing

### Phase C: Whiteboard + Scan Synthesis
1. Whiteboard with current state + ring buffers
2. Player synthesizes ScannedRobotEvent (radar arc intersection)
3. God-view validation test: compare synthesized scan positions to snapshot positions

### Phase D: Core Features (Initial 12)
1. IInGameFeatures / IOfflineFeatures interfaces + Transformer with dependency ordering
2. Implement 12 core features
3. Unit tests per feature with hand-crafted Whiteboard state
4. God-view integration test: validate computed features against snapshot

### Phase E: CsvWriter + End-to-End
1. CsvWriter produces ticks.csv, waves.csv, scores.csv
2. End-to-end test: `.br` → CSV with expected structure
3. Process all 10 recordings

### Phase F: Bullet Events + Energy Detection
1. Player synthesizes HitByBulletEvent, BulletHitEvent, BulletMissedEvent
2. Energy drop detection for opponent fire inference
3. Wave tracking in Whiteboard
4. God-view validation: verify inferred fires match actual bullet data

### Phase G: Robot Skeleton
1. `cz.zamboch.Autopilot` extending AdvancedRobot
2. Wired to shared core (Whiteboard, Transformer)
3. Robot JAR builds

### Phase H: Additional Features (Incremental)
- Add features in batches
- Each batch: implement, unit test, god-view validate
- Priority order: Wall interaction → Targeting geometry → Movement patterns → Wave surfing

---

## 13. Key Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| S2-D1 | Own Loader, skip RecordManager | No temp file overhead. Future parallelism across .br files (one thread per file). Direct ZIP+OIS reading is simpler. |
| S2-D2 | Player delivers events to Whiteboard's event interfaces | Whiteboard implements IBasicEvents/IAdvancedEvents. Same code path in pipeline and live robot. No intermediate Robot class needed. |
| S2-D3 | Whiteboard is the single source of state + event receiver | All features read/write Whiteboard. No feature-to-feature direct coupling. Event handling is co-located with state storage. |
| S2-D4 | IInGameFeatures IoC with dependency declaration | New features added by registration, no code changes. Dependencies resolved once at battle start via `resolveDependencies()`, not on every tick. |
| S2-D5 | Core 12 features first | Minimum viable for ML experiments. IoC pattern makes adding more trivial. |
| S2-D6 | Dual perspective per battle | Doubles training data. Independent Whiteboard instances. |
| S2-D7 | Gradle multi-module | `core` shared between `pipeline` and `robot`. Clean separation. |
| S2-D8 | Robot JAR reuses core code (ML-D11) | Feature computation identical in offline pipeline and live robot. |
| S2-D9 | Persistence via getDataFile() API | Between rounds: save/restore Whiteboard summary. Between battles: full reset. Honors 200KB quota. |
| S2-D10 | Ticks.csv = every tick, waves.csv = per wave | Different granularities for different ML tasks. Tick-level for movement prediction; wave-level for targeting. |
| S2-D11 | God-view validation in tests only | Production code maintains observable-only discipline. Tests cross-check against snapshots for math correctness. |
| S2-D12 | Feature keys are numeric enum, not strings | `Feature` enum enables array-backed storage in Whiteboard. O(1) access, no hashing overhead. |
| S2-D13 | IOfflineFeatures writes its own CSV columns | Each feature implements `writeColumnNames()` and `writeRowValues()`. CsvWriter doesn't hardcode features — iterates features by `FileType`. |
| S2-D14 | All CSV files include battle_id, round, tick | Enables cross-referencing between ticks.csv, waves.csv, and scores.csv. battle_id is ASCII-compressed GUID. |

---

## 14. Open Questions

1. **Scan arc intersection precision:** The engine uses `Arc2D.intersects(Rectangle2D)` which is an approximation. Should we replicate this exactly, or use a simpler angular check? (Simpler is likely fine for 1v1 where radar locks are tight.)

2. **Bullet tracking ID stability:** `IBulletSnapshot.getBulletId()` — is this stable across ticks for the same bullet? Need to verify for wave tracking.

3. **HitWallEvent detection from snapshots:** `RobotState.HIT_WALL` in the snapshot tells us a wall collision happened, but `HitByBulletEvent` needs `bearing`. Can we reliably compute the wall bearing from position + heading?

4. **Round persistence format:** What to persist between rounds? Opponent model parameters, wave hit distributions, cumulative stats? Needs to fit in 200KB budget.

5. **Robot JAR packaging:** Should `core` classes be shaded into the robot JAR, or can we depend on Robocode's classloading to handle multi-JAR robots?

---

## 15. Recording Inventory

| File | Size | Location |
|------|------|----------|
| `0a24decd.br` | TBD | `recordings/24360294214/` |
| `177be6be.br` | TBD | `recordings/24360294214/` |
| `5de4e839.br` | TBD | `recordings/24360294214/` |
| `6b711acc.br` | TBD | `recordings/24360294214/` |
| `6e34bccf.br` | TBD | `recordings/24360294214/` |
| `72f9c320.br` | TBD | `recordings/24360294214/` |
| `88da46d6.br` | TBD | `recordings/24360294214/` |
| `af39e2b1.br` | TBD | `recordings/24360294214/` |
| `e0dc346c.br` | TBD | `recordings/24360294214/` |
| `eb7809f6.br` | TBD | `recordings/24360294214/` |

10 recordings from CI run `24360294214`. These are 3-round sample battles (~500KB each).
