# Replay Experience Engine

Batch replay simulator that feeds recorded Robocode battle data to a live robot instance, allowing it to "observe" past battles as if it were any participant.

## Goal

- Load `.br` recordings (binary-zip format from Robocode's built-in recorder)
- Pick any robot index in the recording as the "viewpoint"
- Instantiate YOUR robot class in that robot's position
- Feed it the reconstructed events (ScannedRobotEvent, HitByBulletEvent, etc.) each turn
- Capture your robot's decisions (ExecCommands) for analysis
- Run thousands of replays programmatically in batch mode

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Per .br File                              │
│                                                             │
│  .br File → RecordingLoader → TurnSnapshot[]                │
│                                    │                        │
│                          ┌─────────┴─────────┐              │
│                          │ EventReconstructor │              │
│                          │  (turn N-1 → N)   │              │
│                          └─────────┬─────────┘              │
│                                    │                        │
│                          RobotStatus + List<Event>           │
│                                    │                        │
└────────────────────────────────────┼────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────┐
│                    Your Robot                                │
│                                                             │
│  ReplayPeer ──executeImpl()──→ BasicRobotProxy              │
│       ▲                              │                      │
│       │                    eventManager.processEvents()      │
│       │                              │                      │
│       │                              ▼                      │
│       │                      YourRobot.class                │
│       │                    (onScannedRobot, etc.)            │
│       │                              │                      │
│       └──── ExecCommands (discarded) ┘                      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
                              ResultCollector
                          (your extension point)
```

## Components

### 1. RecordingLoader

**Responsibility:** Read `.br` file → `BattleRecordInfo` + sequential `TurnSnapshot[]`

**Reuse:** Copy deserialization from `robocode.battle` `RecordManager`:
- `prepareRecording()` → open zip, read `BattleRecordInfo`
- `readSnapshot()` → deserialize each `ITurnSnapshot` from the ObjectInputStream

**Output:**
```java
record Recording(
    BattleRecordInfo info,       // metadata: rules, robot names, rounds
    TurnSnapshot[][] rounds      // [round][turn] indexed snapshots
)
```

### 2. EventReconstructor

**Responsibility:** Given consecutive snapshots + target robot index, produce the events that robot would have received.

**Input:** `TurnSnapshot prev, TurnSnapshot curr, int myRobotIndex`  
**Output:** `List<Event> events, RobotStatus status`

#### Event reconstruction logic (copy from engine, 100% fidelity):

| Event | Source | Engine code to copy |
|-------|--------|---------------------|
| `ScannedRobotEvent` | Scan arc intersection with opponent positions | `RobotPeer.scan()` — arc sweep geometry |
| `HitByBulletEvent` | `BulletSnapshot.victimIndex == myIndex` && state=HIT_VICTIM | `BulletPeer.checkRobotCollision()` |
| `BulletHitEvent` | My bullet's `victimIndex != -1` && state=HIT_VICTIM | Same |
| `BulletMissedEvent` | My bullet state → HIT_WALL | `BulletPeer.checkWallCollision()` |
| `BulletHitBulletEvent` | Bullet state → HIT_BULLET | `Battle.checkBulletCollision()` |
| `HitRobotEvent` | Robot-robot collision (energy delta + distance ≤ 36px) | `Battle.checkRobotCollision()` |
| `HitWallEvent` | Robot at battlefield edge + velocity dropped to 0 | `RobotPeer.checkWallCollision()` |
| `RobotDeathEvent` | Robot state ACTIVE → DEAD | State comparison |
| `StatusEvent` | Always — current RobotStatus | Constructed from snapshot |

#### RobotStatus construction (from RobotSnapshot):
```java
RobotStatus status = HiddenAccess.createStatus(
    snapshot.getEnergy(),
    snapshot.getX(), snapshot.getY(),
    snapshot.getBodyHeading(), snapshot.getGunHeading(), snapshot.getRadarHeading(),
    snapshot.getVelocity(),
    bodyTurnRemaining,    // 0 (not stored in recording)
    radarTurnRemaining,   // 0
    gunTurnRemaining,     // 0
    distanceRemaining,    // 0
    snapshot.getGunHeat(),
    othersCount, sentriesCount,
    roundNum, numRounds,
    turnNum
);
```

**Note:** `*TurnRemaining` and `distanceRemaining` are NOT in recordings. They'll be 0. This is acceptable for observation mode — your robot won't use them since its commands are discarded anyway.

### 3. ReplayPeer

**Responsibility:** Implement `IRobotPeer` interface. Return pre-built `ExecResults` on `executeImpl()`. Ignore incoming `ExecCommands`.

```java
public class ReplayPeer implements IRobotPeer {
    private ExecResults nextResults;
    
    public void setNextResults(ExecResults results) {
        this.nextResults = results;
    }
    
    public ExecResults executeImpl(ExecCommands commands) {
        // Optionally capture commands for analysis
        commandLog.add(commands);
        return nextResults;
    }
    
    // All other IRobotPeer methods: no-op or trivial
}
```

### 4. RobotRunner

**Responsibility:** Instantiate robot, wire proxy, drive the turn loop.

```java
public class RobotRunner {
    public void run(Class<? extends Robot> robotClass, Recording recording, int viewpointIndex) {
        Robot robot = robotClass.getDeclaredConstructor().newInstance();
        ReplayPeer peer = new ReplayPeer();
        BasicRobotProxy proxy = new BasicRobotProxy(/* ... */);
        
        // Wire robot to proxy (via HiddenAccess or reflection)
        HiddenAccess.initRobot(robot, proxy);
        
        // Drive turns
        for (int round = 0; round < recording.rounds.length; round++) {
            TurnSnapshot[] turns = recording.rounds[round];
            for (int t = 1; t < turns.length; t++) {
                ExecResults results = reconstructor.buildResults(turns[t-1], turns[t], viewpointIndex);
                peer.setNextResults(results);
                proxy.executeImpl();  // This pumps events into robot
            }
        }
    }
}
```

### 5. ResultCollector

**Responsibility:** Your extension point. Captures robot outputs per turn.

```java
public interface ResultCollector {
    void onTurn(int round, int turn, ExecCommands commands, RobotStatus status);
    void onBattleComplete(BattleRecordInfo info);
}
```

## What's NOT needed (disabled/bypassed)

| Robocode Feature | Why not needed |
|-----------------|----------------|
| Security Manager | You instantiate the robot yourself — no sandboxing |
| Custom ClassLoader | Direct `new MyRobot()` — no JAR loading |
| Thread synchronization | Single-threaded sequential turn pump |
| Robot timeout/skipped turns | No real-time constraint |
| Graphics/Paint | No UI |
| TPS timing | Batch mode — run as fast as possible |
| Physics simulation | Recording provides all positions |
| Bullet physics | Recording provides bullet states |

## Dependencies

```groovy
dependencies {
    // For event classes, RobotStatus, Robot base class
    implementation project(':robocode.api')
    // OR: implementation files('path/to/robocode.api.jar')
    
    // For HiddenAccess, ExecCommands, ExecResults, snapshot classes
    implementation project(':robocode.core')
    
    // For RecordManager deserialization (or copy the ~100 lines)
    implementation project(':robocode.battle')
}
```

## Key Engine Code to Copy (for event reconstruction fidelity)

### Scan arc intersection (~40 lines)
From `RobotPeer.scan()` — determines which opponents fall within the radar sweep arc between `prevRadarHeading` and `currRadarHeading`.

### Bullet damage formula
```java
double damage = 4 * power;
if (power > 1) damage += 2 * (power - 1);
// Energy gained by shooter on hit: 3 * power
```

### Robot collision detection
```java
double distance = Math.hypot(robot1.x - robot2.x, robot1.y - robot2.y);
boolean collided = distance < 36;  // ROBOT_SIZE = 36 (bounding box diagonal threshold)
// Energy loss on collision: max(0, 0.6 * abs(velocity))
```

### ScannedRobotEvent bearing math
```java
double dx = opponent.x - myX;
double dy = opponent.y - myY;
double angle = Math.atan2(dx, dy);  // absolute angle to opponent
double bearing = Utils.normalRelativeAngle(angle - myBodyHeading);
double distance = Math.hypot(dx, dy);
```

## Batch Usage (target API)

```java
public static void main(String[] args) {
    ReplayEngine engine = new ReplayEngine();
    
    // Load all .br files from a directory
    List<Recording> recordings = engine.loadAll(Path.of("battles/"));
    
    // Run my robot against each, observing from every viewpoint
    for (Recording rec : recordings) {
        for (int i = 0; i < rec.info.getRobotCount(); i++) {
            RunResult result = engine.replay(MyRobot.class, rec, i);
            // result contains: commands per turn, any captured state
        }
    }
}
```

## Open Questions / Future Enhancements

1. **Scan arc reconstruction** — The recording stores `scanArc` geometry per snapshot. Can we use that directly instead of recomputing the sweep? (Yes — but need to verify it matches what `RobotPeer.scan()` uses for event generation.)

2. **Multiple viewpoints per pass** — Could instantiate N robots simultaneously, each observing from a different viewpoint in the same recording, sharing the snapshot parsing.

3. **Delta commands** — Even though commands are discarded, capturing what your robot WOULD have done (fire power, turn angles) enables training signal extraction.

4. **Partial replay** — Start from turn T instead of turn 0, useful for debugging specific scenarios.

5. **Synthetic injection** — Modify a snapshot mid-replay (e.g. move an opponent) to test "what if" scenarios.
