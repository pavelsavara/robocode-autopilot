# Replay Experience Engine

Observer pipeline that runs **Autopilot instances** against recorded or live battle data, feeding them engine-faithful reconstructed events. Each observer's Whiteboard output becomes the pipeline's ground truth for CSV extraction, wave analysis, and three-way validation.

## Goal

- Run two **observer Autopilot instances** per battle (one per perspective)
- Feed them **all Robocode events** reconstructed with 100% engine fidelity from `TurnSnapshot` data
- Observers' Whiteboard values become the pipeline output (CSV, wave features)
- **Validation**: recording's IDebugProperty vs observer Whiteboard (only when Autopilot is fighting on that side)
- Support both **live battles** (via BattleAdaptor) and **`.br` replay** (from recordings)
- **Dual WaveResolver** per perspective: robot-side (from events only, same info as in-game) vs god-view (exact positions) — their comparison measures wave detection precision

## Architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│                         Data Sources                                   │
│                                                                       │
│   Live Battle (BattleAdaptor)    OR    .br File (Loader)              │
│            │                                  │                       │
│            └──────────── ITurnSnapshot ───────┘                       │
│                               │                                       │
└───────────────────────────────┼───────────────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────────────┐
│                      EventReconstructor                                │
│                                                                       │
│   TurnSnapshot[prev] + TurnSnapshot[curr] + perspectiveIndex          │
│                         │                                             │
│          ┌──────────────┼──────────────────────────┐                  │
│          ▼              ▼              ▼            ▼                  │
│   StatusEvent    ScannedRobot   HitByBullet   HitWall   ...          │
│   (exact engine order: bullets → move → scan → death → status)       │
│                         │                                             │
│          RobotStatus + List<Event> (priority-sorted)                  │
│                         │                                             │
└─────────────────────────┼─────────────────────────────────────────────┘
                          │
         ┌────────────────┴────────────────┐
         ▼                                 ▼
┌────────────────────┐          ┌────────────────────┐
│ Observer 0 (ours)  │          │ Observer 1 (theirs)│
│                    │          │                    │
│ Autopilot instance │          │ Autopilot instance │
│   onStatus()       │          │   onStatus()       │
│   onScannedRobot() │          │   onScannedRobot() │
│   onHitByBullet()  │          │   onHitByBullet()  │
│   ...              │          │   ...              │
│        │           │          │        │           │
│   Whiteboard[0]    │          │   Whiteboard[1]    │
│   (features)       │          │   (features)       │
│        │           │          │        │           │
└────────┼───────────┘          └────────┼───────────┘
         │                               │
         └───────────┬───────────────────┘
                     │
         ┌───────────┼───────────────────────────┐
         ▼           ▼                           ▼
┌─────────────┐  ┌───────────────────────────────┐  ┌──────────────────────┐
│  CsvWriter  │  │ Dual WaveResolver (per persp) │  │      Validator       │
│  (ticks,    │  │                               │  │                      │
│   waves,    │  │  Robot-side    God-view        │  │  IDebugProperty      │
│   scores)   │  │  (from events  (exact pos     │  │    vs                │
│             │  │   only, like    each tick)     │  │  observer whiteboard │
│             │  │   in-game)                     │  │  (our side only,     │
│             │  │       ↕ compare ↕              │  │   when Autopilot     │
│             │  │  = wave detection precision    │  │   is fighting)       │
└─────────────┘  └───────────────────────────────┘  └──────────────────────┘
```

## Operating Modes

### Mode 1: Live Battle (current BattleRunner path)

```
Robocode Engine
  ├── Autopilot (real, fighting) ── produces IDebugProperty[]
  └── BattleAdaptor (PipelineOrchestrator)
        ├── EventReconstructor → Observer[0] (our perspective)
        │     ├── Robot-side WaveResolver (events-only, like in-game)
        │     └── God-view WaveResolver (exact positions)
        ├── EventReconstructor → Observer[1] (opponent perspective)
        │     ├── Robot-side WaveResolver (events-only)
        │     └── God-view WaveResolver (exact positions)
        └── Validator (IDebugProperty vs Observer[0] — our side only)
```

The **live Autopilot** fights normally. Its **IDebugProperty** output is embedded in `IRobotSnapshot.getDebugProperties()`. The two **observer Autopilots** receive reconstructed events and compute their own Whiteboard values independently.

**IDebugProperty comparison is only valid for Observer[0] (our side)** — because debug properties come from the live Autopilot, which occupies that perspective. The opponent side has no debug properties to compare against. Any divergence between the live robot's debug properties and Observer[0]'s Whiteboard is a bug in either the robot or the event reconstruction.

### Mode 2: `.br` Replay (batch CLI)

```
.br File → Loader → TurnSnapshot[]
  ├── EventReconstructor → Observer[0]
  │     ├── Robot-side WaveResolver
  │     └── God-view WaveResolver
  ├── EventReconstructor → Observer[1]
  │     ├── Robot-side WaveResolver
  │     └── God-view WaveResolver
  └── Validator (IDebugProperty vs Observer — only if Autopilot was fighting on that side)
```

No live robot. The recording's `IDebugProperty[]` is only available if the recording was made with Autopilot fighting, and only for the perspective that matches the Autopilot's side. Two observers always process from both perspectives regardless.

## Components (replaces most of `pipeline/src/.../pipeline/`)

### Files to KEEP (unchanged)
| File | Reason |
|------|--------|
| `CsvWriter.java` | Output format unchanged |
| `CsvRowWriter.java` | Low-level CSV mechanics unchanged |
| `Loader.java` | .br reading unchanged |

### New outputs
| File | Content |
|------|---------|
| `ticks-*.csv` | Whiteboard features per tick (existing format) |
| `our-waves-*.csv` | Wave features at resolution (existing format) |
| `their-waves-*.csv` | Opponent wave features (existing format) |
| `decisions-*.csv` | Movement/gun/fire decisions per tick per perspective (new) |

### Files to REPLACE
| Old File | New Replacement |
|----------|----------------|
| `Player.java` | `EventReconstructor.java` |
| `ScanSynthesizer.java` | Merged into `EventReconstructor` (scan logic) |
| `DamageDetector.java` | Merged into `EventReconstructor` (collision logic) |
| `StreamingPipelineObserver.java` | `PipelineOrchestrator.java` |
| `BattleRunner.java` | `BattleRunner.java` (simplified, delegates to orchestrator) |
| `Perspective.java` | `ObserverContext.java` (holds observer + whiteboard + state) |
| `DebugValidator.java` | `PipelineValidator.java` |
| `GodViewValidator.java` | Merged into `PipelineValidator` |
| `WaveResolver.java` | `GodViewWaveResolver.java` (god-view layer) |
| `Main.java` | `Main.java` (simplified CLI) |

---

### 1. EventReconstructor

**Responsibility:** Given consecutive TurnSnapshots + perspective index, produce the exact events that Robocode's engine would have dispatched to that robot.

**Input:** `TurnSnapshot prev, TurnSnapshot curr, int perspectiveIndex, TickState state`  
**Output:** `TickEvents { RobotStatus status, List<Event> events }`

**Design principle:** Copy-paste logic from Robocode engine methods 1:1. Each event type has a dedicated private method mirroring the engine source.

#### Tick processing order (matches engine's `Battle.runTurn()`):
```
1. updateBullets()       → HitByBulletEvent, BulletHitEvent, BulletMissedEvent, BulletHitBulletEvent
2. performMove()         → HitWallEvent, HitRobotEvent
3. performScan()         → ScannedRobotEvent
4. handleDeadRobots()    → RobotDeathEvent, DeathEvent, WinEvent
5. publishStatus()       → StatusEvent (always first in robot's queue by priority)
```

#### Event reconstruction — exact engine logic:

##### ScannedRobotEvent (from `RobotPeer.scan()`)
```java
// Arc from prevRadarHeading → currRadarHeading
double scanRadians = Utils.normalRelativeAngle(currRadarHeading - prevRadarHeading);
// Build Arc2D.PIE centered on robot, radius=1200
// Test intersection with opponent's 36×36 bounding box
// If hit:
double dx = opponent.x - my.x;
double dy = opponent.y - my.y;
double angle = Math.atan2(dx, dy);  // Robocode's north-clockwise convention
double bearing = Utils.normalRelativeAngle(angle - myBodyHeading);
double distance = Math.hypot(dx, dy);
new ScannedRobotEvent(name, energy, bearing, distance, heading, velocity, isSentry);
```

##### HitByBulletEvent (from `BulletPeer.checkRobotCollision()`)
```java
// Bullet with victimIndex == myIndex AND state == HIT_VICTIM
double bearing = Utils.normalRelativeAngle(bulletHeading + Math.PI - myBodyHeading);
// damage = Rules.getBulletDamage(power)
new HitByBulletEvent(bearing, bullet);
```

##### BulletHitEvent (from same collision, owner's perspective)
```java
// My bullet's victimIndex != -1 AND state == HIT_VICTIM
// owner gains 3*power energy
new BulletHitEvent(victimName, victimEnergy, bullet);
```

##### BulletMissedEvent (from `BulletPeer.checkWallCollision()`)
```java
// My bullet state → HIT_WALL
new BulletMissedEvent(bullet);
```

##### BulletHitBulletEvent (from `Battle.checkBulletCollision()`)
```java
// Bullet state → HIT_BULLET
new BulletHitBulletEvent(myBullet, otherBullet);
```

##### HitWallEvent (from `RobotPeer.checkWallCollision()`)
```java
// Robot center ± 18px exceeds battlefield boundary
// Detect via: snapshot state == HIT_WALL AND prev state != HIT_WALL
// OR: position at boundary AND velocity dropped to 0
// Wall normal: left=3π/2, right=π/2, bottom=π, top=0
double bearing = Utils.normalRelativeAngle(wallNormal - myBodyHeading);
// damage = max(abs(prevVelocity)/2 - 1, 0)
new HitWallEvent(bearing);
```

##### HitRobotEvent (from `RobotPeer.checkRobotCollision()`)
```java
// Detect via: snapshot state == HIT_ROBOT for either robot
double angle = Math.atan2(other.x - my.x, other.y - my.y);
double bearing = Utils.normalRelativeAngle(angle - myBodyHeading);
// At-fault: was moving toward other robot
boolean atFault = (velocity > 0 && Math.abs(bearing) < Math.PI/2)
              || (velocity < 0 && Math.abs(bearing) > Math.PI/2);
new HitRobotEvent(otherName, bearing, otherEnergy, atFault);
```

##### RobotDeathEvent / DeathEvent / WinEvent
```java
// Detect state transitions: ACTIVE→DEAD in current snapshot
// DeathEvent → the dying robot
// RobotDeathEvent → all alive robots
// WinEvent → last robot standing (only in final tick of round)
```

##### StatusEvent (always generated)
```java
RobotStatus status = HiddenAccess.createStatus(
    energy, x, y,
    bodyHeading, gunHeading, radarHeading,
    velocity,
    0, 0, 0, 0,  // *Remaining fields (not in recordings, 0 is acceptable)
    gunHeat,
    othersCount, 0, // sentries
    roundNum, numRounds, turnNum
);
new StatusEvent(status);
```

#### State tracking (`TickState` — persists across ticks within a round):
```java
class TickState {
    double prevRadarHeading;        // for scan arc
    double prevVelocity;            // for wall hit damage
    double prevBodyHeading;         // for collision direction
    RobotState prevState;           // for transition detection
    Set<Integer> knownBulletIds;    // deduplication
    Set<Integer> hitBulletIds;      // track bullet hits
    double prevX, prevY;            // for collision detection
}
```

---

### 2. ObserverContext (replaces Perspective)

**Responsibility:** Holds one observer Autopilot instance + its Whiteboard + per-round state.

```java
public final class ObserverContext {
    private final int perspectiveIndex;    // 0 or 1
    private final Autopilot observer;      // Autopilot instance in observer mode
    private final Whiteboard whiteboard;   // observer's feature store
    private final TickState tickState;     // event reconstruction state
    private ObserverContext peer;          // the other perspective
    private CsvWriter csv;                // nullable output
    private boolean dead;
    private boolean isOurs;               // matches the live fighting robot
    private IRobotSnapshot lastRobot;     // last seen snapshot

    // Factory
    public static ObserverContext[] createPair(/* ... */);

    // Per-turn: feed events to the observer
    public void feedTick(TickEvents events);

    // Access whiteboard (for CSV, validation, wave resolver)
    public Whiteboard wb();
}
```

**Observer Autopilot instantiation:**
```java
// Refactored Autopilot: extract init() as public, make doTurn() public, no while(true).
// Observer mode uses a mock IBasicRobotPeer that:
//   - Tracks gunHeat from StatusEvent, returns Bullet when heat==0
//   - No-ops all movement/radar commands (but observer strategy still runs)
//   - Returns battlefield dimensions, data directory, etc. from config

Autopilot observer = new Autopilot();
observer.initForObserver(mockPeer, vcsStore, battleWidth, battleHeight, randomSeed);
// Then each tick: feed events → call doTurn() externally
```

**Key design:** The observer runs the **full Autopilot strategy** — radar, gun, movement, firing — not just event handlers. Commands go to mock peer (no-ops) but:
- `setFireBullet()` → mock tracks gunHeat, returns fake Bullet when heat==0 → triggers `snapshotFireFeatures()` → WaveTracker creates real waves
- Movement strategy runs → decisions logged to `decisions.csv` for regret analysis
- Gun strategy runs → aim decisions logged for debugging problematic situations

The observer is a full "shadow" Autopilot making identical decisions to the live robot (given same events + same random seed via `System.setProperty`). The only difference: its commands don't affect the battle.

**VCS store:** Observer shares the live robot's VCS store reference (live battle mode). For .br replay, the VCS store from the recording session must be provided externally.

**Random seed:** Both live robot and observer read the same seed from `System.setProperty`. This ensures deterministic PRNG alignment for firing/movement decisions.

---

### 3. PipelineOrchestrator (replaces StreamingPipelineObserver)

**Responsibility:** Wires EventReconstructor → Observers → WaveResolver → Validator → CSV.

```java
public final class PipelineOrchestrator extends BattleAdaptor implements Closeable {

    private ObserverContext[] observers;    // [0]=ours, [1]=theirs
    private EventReconstructor reconstructor;
    private GodViewWaveResolver godViewWaveResolver;
    private PipelineValidator validator;
    private CsvWriter decisionsCsv;        // movement/gun decisions per tick
    private ITurnSnapshot prevSnapshot;

    @Override
    public void onTurnEnded(TurnEndedEvent event) {
        ITurnSnapshot curr = event.getTurnSnapshot();
        IRobotSnapshot[] robots = curr.getRobots();

        // 1. Reconstruct events for each perspective
        for (ObserverContext ctx : observers) {
            TickEvents events = reconstructor.reconstruct(
                prevSnapshot, curr, ctx.perspectiveIndex(), ctx.tickState());
            
            // 2. Feed events to observer Autopilot (populates Whiteboard)
            ctx.feedEvents(events);
            
            // 3. Run full strategy: doTurn() computes features + makes decisions
            ctx.doTurn();
        }

        // 4. God-view wave resolution (uses exact positions from snapshot)
        boolean[] godViewResolved = godViewWaveResolver.processTick(observers, robots, curr);

        // 5. Validate event fidelity (only for our perspective, only if Autopilot is fighting)
        if (ourIndex >= 0) {
            IRobotSnapshot liveRobot = robots[ourIndex];
            if (liveRobot.getDebugProperties() != null)
                validator.validateEventFidelity(liveRobot, observers[ourIndex].wb());
        }

        // 6. Compare robot-side vs god-view wave precision (both perspectives)
        for (ObserverContext ctx : observers) {
            validator.validateWavePrecision(ctx.perspectiveIndex(),
                ctx.observer().getWaveTracker(), godViewWaveResolver);
        }

        // 7. Write CSV: features + wave data
        writeCsv(observers, godViewResolved, curr);

        // 8. Write decisions.csv: movement/gun/fire decisions per observer
        writeDecisions(observers, curr);

        prevSnapshot = curr;
    }
}
```

**decisions.csv columns** (per perspective, per tick):
- TICK, PERSPECTIVE
- MOVE_AHEAD, MOVE_TURN_RIGHT (movement command output)
- GUN_TURN, FIRE_POWER, FIRE_GF (gun/fire decisions)
- Used for: regret analysis, debugging problematic decisions, ML training signal

---

### 4. Dual WaveResolver Architecture

Each perspective has **two** WaveResolver instances running in parallel:

#### 4a. Robot-side WaveResolver (inside observer Autopilot)

**Responsibility:** Track waves using ONLY information available from events — exactly what the in-game Autopilot would know.

**Information sources (events-only, no leaking):**
- Fire detection: energy drop between scans (via `FireFeatures`)
- Fire position: opponent's last-scanned position (stale by radar gap)
- Wave resolution: opponent's position at next scan tick (stale)
- GF computation: from stale scan data

**This IS the existing `WaveTracker`** in the Autopilot robot code. It runs identically in observer mode — same code path, same stale data, same results.

**Outputs:** `OUR_BREAK_GF` (stale-data estimate), `THEIR_FIRE_POWER` (energy-drop detection)

#### 4b. God-view WaveResolver (external layer)

**Responsibility:** Ground-truth wave resolution using exact opponent positions every tick from snapshots.

**Information sources (privileged):**
- Fire detection: `IBulletSnapshot` with `FIRED` state (exact power, exact position)
- Wave resolution: exact opponent position every tick (no radar gap)
- GF computation: from true positions

**Logic unchanged from current `WaveResolver`** — detects fires via IBulletSnapshot, creates Waves with fire-time geometry, resolves when wavefront reaches exact opponent position, computes true GF.

**Outputs:** `OUR_FIRE_*` features (18 features at fire time), `OUR_BREAK_*` features (at resolution time), `THEIR_FIRE_*` / `THEIR_BREAK_*` (peer's waves reaching us)

#### Comparison: measuring wave detection precision

| Metric | Robot-side | God-view | What the diff tells us |
|--------|-----------|----------|------------------------|
| Fire detection | Energy drop (stale) | IBulletSnapshot (exact) | Missed fires, false positives |
| Fire tick | Scan tick (delayed) | Actual fire tick | Detection latency |
| Fire position | Last-scanned pos | Actual pos at fire | Position error |
| Break GF | Stale opponent pos | Exact opponent pos | GF accuracy loss from radar gap |
| Hit detection | BulletHitEvent | IBulletSnapshot | Should be identical (both exact) |

**Key constraint:** The robot-side WaveResolver must NOT access any god-view data. It sees only what the in-game robot would see through events. This ensures the comparison is meaningful — it measures the real-world precision penalty from limited information.

---

### 5. Validator (replaces DebugValidator + GodViewValidator)

**Responsibility:** Validate event reconstruction fidelity and measure wave detection precision.

#### Validation Layer 1: Event Fidelity (IDebugProperty vs Observer)

**Available only when:** Autopilot is the live fighter AND we're checking the matching perspective (our side).

| Source | What it provides |
|--------|------------------|
| **Recording IDebugProperty[]** | What the live Autopilot computed in-game |
| **Observer Whiteboard** | What the observer computed from reconstructed events |

**Checks:**
- All spatial features (OUR_X/Y/HEADING/VELOCITY/ENERGY, GUN_*)
- Scan features (DISTANCE, BEARING_RADIANS, OPPONENT_*)
- Fire detection (THEIR_FIRE_POWER)
- Timing (TICK, LAST_SCAN_TICK, TICKS_SINCE_SCAN)
- **Any mismatch = event reconstruction bug** (since both see the same events, they must compute the same values)

**NOT checked for opponent perspective** — no IDebugProperty available from opponent.

#### Validation Layer 2: Wave Detection Precision

Compares robot-side WaveResolver vs god-view WaveResolver for each perspective:

| Metric | Expected result |
|--------|----------------|
| Fire detection rate | Robot-side may miss fires (radar gap too large) |
| GF mean absolute error | Non-zero (stale scan data vs exact position) |
| Fire position error | Distance between estimated and actual fire origin |
| Detection latency | Ticks between actual fire and robot's detection |

This is NOT a pass/fail check — it's a **quality metric** that tells us how much information we lose from limited radar coverage.

#### Validation Layer 3: God-view Sanity (snapshot vs observer)

Simple sanity check that spatial features injected from `StatusEvent` match the snapshot directly:
- OUR_X/Y/HEADING/VELOCITY/ENERGY should exactly match `IRobotSnapshot` values
- DISTANCE/BEARING should match on scan ticks

```java
public final class PipelineValidator {
    private final EnumMap<Feature, ValidationStats> fidelityStats;
    private final WavePrecisionTracker[] wavePrecision;  // [0]=ours, [1]=theirs

    /**
     * Compare IDebugProperty against observer whiteboard.
     * Only called for the perspective where Autopilot is fighting.
     */
    public void validateEventFidelity(IRobotSnapshot liveRobot, Whiteboard observerWb);

    /**
     * Compare robot-side wave results vs god-view wave results.
     * Called for both perspectives every tick.
     */
    public void validateWavePrecision(int perspIndex,
                                      WaveResolver robotSide,
                                      GodViewWaveResolver godView);

    public int getEventFidelityMismatches();
    public double getGFMeanAbsoluteError(int perspIndex);
    public double getFireDetectionRate(int perspIndex);
    public void printSummary();
}
```

---

### 6. Observer Mode in Autopilot

**Responsibility:** Allow Autopilot to run in "observer mode" where it processes events and populates its Whiteboard but does not issue movement/gun commands.

**Changes to `robot/src/.../Autopilot.java`:**
```java
public class Autopilot extends AdvancedRobot {
    private boolean observerMode = false;

    public void setObserverMode(boolean mode) {
        this.observerMode = mode;
    }

    @Override
    public void run() {
        if (observerMode) return;  // Don't loop in observer mode
        // ... normal run loop ...
    }

    // Event handlers work identically in both modes:
    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        wb.setFeature(Feature.DISTANCE, e.getDistance());
        wb.setFeature(Feature.BEARING_RADIANS, e.getBearingRadians());
        // ... same code as live robot ...

        if (!observerMode) {
            // Execute strategies only when fighting
            executeStrategies();
        }
    }
}
```

**Event delivery to observer:**
```java
// In ObserverContext.feedTick():
public void feedTick(TickEvents events) {
    // Inject status features directly (StatusEvent equivalent)
    injectStatus(events.status());

    // Dispatch events via reflection/direct method call
    for (Event event : events.events()) {
        if (event instanceof ScannedRobotEvent sre)
            observer.onScannedRobot(sre);
        else if (event instanceof HitByBulletEvent hbe)
            observer.onHitByBullet(hbe);
        else if (event instanceof BulletHitEvent bhe)
            observer.onBulletHit(bhe);
        else if (event instanceof HitRobotEvent hre)
            observer.onHitRobot(hre);
        else if (event instanceof HitWallEvent hwe)
            observer.onHitWall(hwe);
        // ...
    }

    // Process derived features
    wb.process();
}
```

---

## Event Reconstruction — Complete Engine Parity

### Tick order guarantee

The EventReconstructor MUST produce events in the exact order the engine would deliver them. The engine processes in this order:

```
Phase 1: updateBullets (random order per bullet, but deterministic per snapshot)
  → BulletHitEvent / HitByBulletEvent (bullet hits robot)
  → BulletMissedEvent (bullet hits wall)
  → BulletHitBulletEvent (bullet hits bullet)

Phase 2: updateRobots (random order per robot)
  → HitWallEvent (robot hits wall after move)
  → HitRobotEvent (robot-robot collision after move)

Phase 3: performScan (same robot iteration)
  → ScannedRobotEvent (radar arc hits opponent)

Phase 4: handleDeadRobots
  → DeathEvent (to dying robot)
  → RobotDeathEvent (to all alive robots)
  → WinEvent (to last standing)

Phase 5: publishStatus (implicit)
  → StatusEvent (priority 99, always first in robot's event queue)
```

### Snapshot-based detection strategy

Since we observe snapshots AFTER the tick has completed, we detect events by comparing `prev` and `curr` states:

| Event | Detection from snapshots |
|-------|------------------------|
| HitByBullet | `IBulletSnapshot.getState() == HIT_VICTIM` AND `victimIndex == myIndex` AND bullet not in `knownHitBullets` |
| BulletHit | `IBulletSnapshot.getOwnerIndex() == myIndex` AND `state == HIT_VICTIM` |
| BulletMissed | `IBulletSnapshot.getOwnerIndex() == myIndex` AND `state == HIT_WALL` |
| BulletHitBullet | `state == HIT_BULLET` AND one bullet is mine |
| HitWall | `curr.state == HIT_WALL` AND `prev.state != HIT_WALL` |
| HitRobot | `curr.state == HIT_ROBOT` (either robot) |
| ScannedRobot | `IRobotSnapshot.getScanArc().intersects(opponentBoundingBox)` |
| RobotDeath | `curr.state == DEAD` AND `prev.state != DEAD` |
| Death | My `curr.state == DEAD` AND `prev.state != DEAD` |
| Win | I'm alive AND opponent just died AND it's last opponent |

### Key math to copy from engine (verbatim):

#### Scan arc — use `IRobotSnapshot.getScanArc()` directly
```java
// No need to recompute. The snapshot already stores the engine-computed arc.
// Just test: snapshot.getScanArc().intersects(opponentBoundingBox)
// where box is 36×36 centered on opponent position.
```

#### Bullet damage (from `Rules`)
```java
static double getBulletDamage(double power) {
    return (power > 1) ? 4*power + 2*(power-1) : 4*power;
}
static double getBulletHitBonus(double power) { return 3*power; }
```

#### Wall hit damage (from `Rules`)
```java
static double getWallHitDamage(double velocity) {
    return Math.max(Math.abs(velocity)/2 - 1, 0);
}
```

#### Robot collision (from `RobotPeer.checkRobotCollision()`)
```java
// Bounding boxes (36×36) overlap
boolean collided = Math.abs(r1.x - r2.x) < 36 && Math.abs(r1.y - r2.y) < 36;
// Damage: 0.6 to both robots
// At-fault: robot moving toward the other
```

---

## What's NOT needed (bypassed in observer mode)

| Robocode Feature | Why not needed |
|-----------------|----------------|
| Security Manager | Direct instantiation, no sandboxing |
| Custom ClassLoader | Same classpath, direct `new Autopilot()` |
| Thread synchronization | Single-threaded sequential event delivery |
| Robot timeout/skipped turns | No real-time constraint |
| Graphics/Paint | No UI |
| TPS timing | Batch mode — run as fast as possible |
| Physics simulation | Snapshots provide all positions |
| Movement commands | Observer mode — commands discarded |
| ExecCommands/ExecResults | Bypass proxy entirely, call event handlers directly |

---

## Dependencies (unchanged from current)

```kotlin
dependencies {
    implementation(project(":core"))
    implementation(libs.robocode.api)       // Event classes, RobotStatus, Rules
    implementation(libs.robocode.battle)    // BattleAdaptor, snapshot interfaces, RecordManager
    implementation(libs.robocode.core)      // HiddenAccess, snapshot implementations
    implementation(libs.robocode.host)      // BasicRobotProxy internals (for status creation)
    implementation(libs.robocode.repository)
}
```

---

## Batch Usage (target API)

```java
// CLI: replay all .br files, write CSV
public static void main(String[] args) {
    Path inputDir = Path.of(args[0]);
    Path outputDir = Path.of(args[1]);

    List<Path> recordings = Files.walk(inputDir)
        .filter(p -> p.toString().endsWith(".br"))
        .toList();

    for (Path br : recordings) {
        try (Loader loader = new Loader(br)) {
            PipelineOrchestrator pipeline = new PipelineOrchestrator(outputDir);
            loader.forEachTurn((round, turn) -> pipeline.onTurnEnded(turn));
            pipeline.finalize();
        }
    }
}

// Programmatic: live battle + observers
BattleRunner.runBattle(opponent, rounds, outputDir);
// → internally creates PipelineOrchestrator as BattleAdaptor
// → real Autopilot fights, two observer Autopilots process events
// → CSV + validation output
```

---

## Validation Acceptance Criteria

| Check | Threshold | Applies to |
|-------|-----------|------------|
| Spatial fidelity (Layer 1) | 0 mismatches | Both perspectives, every scan tick |
| Energy accounting (Layer 4) | 0 discrepancies > 1e-4 | Both perspectives, every tick |
| Event fidelity / IDebugProperty (Layer 5) | 0 non-break mismatches | Our perspective only (when Autopilot fighting) |
| Fire detection rate (Layer 2) | Measured ≥ 0.7 (warn if lower) | Both perspectives |
| GF mean absolute error (Layer 3) | Measured, report | Both perspectives (quality metric) |
| Fire position MAE (Layer 2) | Measured, report | Both perspectives (quality metric) |
| Detection latency (Layer 2) | Measured, report | Both perspectives (quality metric) |
| Wave match rate (Layer 3) | Measured ≥ 0.8 (warn if lower) | Both perspectives |
| Non-vacuous check | ALL layers must have > 0 checks | Entire validator |
| CSV output bit-identical to current pipeline | Yes (for tick rows) | Both perspectives |

---

## Design Decisions (resolved)

1. **Scan arc from snapshot** — Use `IRobotSnapshot.getScanArc()` directly. No need to recompute geometry.

2. **Event priority ordering** — Use default engine priority (StatusEvent=99, HitByBullet=20, ScannedRobot=10, etc.). Our robot doesn't reconfigure priorities.

3. **SkippedTurnEvent** — Impossible to reconstruct from recordings. Not supported.

4. **1v1 only** — No melee support.

5. **Wave precision metric purpose** — this is important for movement ML