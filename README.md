# robocode-autopilot

`robocode-autopilot` is a Robocode research robot whose goal is to become competitive through precise offline machine learning: collect engine-faithful battle data, prove the collected features match what the robot could really know in-game, train/seed compact targeting models per opponent, and ship those learned models back into the live robot.

## Robot Architecture

The live robot is `cz.zamboch.Autopilot`, an `AdvancedRobot` packaged by the `robot` module with the reusable `core` module shaded into the Robocode JAR. The robot is intentionally deterministic: event handlers write raw observations into a `Whiteboard`, feature processors derive higher-level state, and strategy objects read only that whiteboard to issue commands.

Per tick, `Autopilot` follows this path:

1. Robocode events/status update raw features: our pose/energy/gun/radar state, scan data, bullet damage, hit-by-bullet energy gain, ram damage, and opponent identity.
2. `Whiteboard.process()` runs registered processors in dependency order: spatial reconstruction, movement decomposition, scan timing, wall-hit estimation, enemy-fire inference, identity hashing, our-wave feature staging, outgoing wave tracking, and incoming wave tracking.
3. Strategies consume the processed state: `NarrowLockRadar` keeps a tight 1v1 radar lock, `OrbitMovementStrategy` provides baseline movement, and `GFGunStrategy` aims from `GUN_AIM_*` features computed by `OurWaveFeatures`.
4. `WaveTracker` creates real and virtual outgoing waves, resolves them into GuessFactors, and updates the active model. The current model is `VcsStore`: a compact visit-count histogram over distance segment x lateral-velocity segment x GF bin, selected through `ModelSelector` so future model families can compete by recent regret.
5. Live battles load and persist `robot/data/vcs.dat` per opponent hash. Observer/replay instances can load the same model but never persist changes.

## Learning Pipeline

The learning pipeline exists to make offline data trustworthy before it is used for model work. It can run live headless battles (`BattleRunner`) or replay `.br` recordings (`Main`/`Loader`). In both modes, `PipelineOrchestrator` receives engine turn snapshots and runs two shadow `Autopilot` observers, one from each perspective.

The critical invariant is Layer 0 fidelity: the in-game robot publishes every internal feature through `IDebugProperty[]`, while an observer reconstructs the same partial-information events from snapshots and must reproduce the same whiteboard. A zero-mismatch Layer 0 result proves the replay pipeline gave the observer the exact experience the robot had, not god-view. Only then do the god-view layers matter: a separate god-view whiteboard is seeded from truth snapshots to measure spatial drift, damage-observation drift, enemy-fire inference quality, and robot-side vs god-view wave/GF precision. God-view state is diagnostic only and never mutates the robot-side whiteboard.

CSV data collection writes one directory per battle and perspective. `BattleCSVProducer`
writes robot-side observer `Whiteboard` CSVs for the hardcoded top-five robot set
plus `cz.zamboch.Autopilot`; `BattleLoopTest` keeps its quality validation focus
and only writes its existing feature CSVs when `-PcsvDir` is supplied.

- `ticks.csv`: per-tick state and derived features.
- `autopilot-waves.csv`: Autopilot observer fired/resolved waves, aim-time geometry, break GF, hit labels, and virtual/real wave fields.
- `dejavu-waves.csv`: DeJaVu reconstructed real outgoing bullet waves, using the same outgoing-wave schema.
- `their-waves.csv`: inferred incoming-fire waves, aim/fire/break geometry, hit-us labels.
- `scores.csv`: per-round result and hit-rate summary.
- Optional diagnostics: `in-game.csv`, `observer.csv`, `their-fires.csv`, `damage-events.csv`, and generated drift reports under `build/reports/`.

The practical loop today is: stage robot/opponents, run deterministic or seeded headless battles, validate Layer 0 and god-view quality summaries, inspect drift reports, evaluate CSV sanity, and promote improved `vcs.dat` with `:pipeline:updateVcsData` when battle learning is worth keeping. The intended next step is to replace or augment VCS with stronger offline-trained models while preserving the same feature/validation contract.

## Directory Structure

| Path | Purpose |
| --- | --- |
| `core/` | Shared robot brain: `Whiteboard`, `Feature` schema, wave rings, GuessFactor math, VCS/model interfaces, feature processors, radar/gun/movement strategies, and unit tests. Java 8 target so it can be shaded into the Robocode robot. |
| `robot/` | Live `cz.zamboch.Autopilot` Robocode robot, JAR packaging, and persisted learned data under `robot/data/`. |
| `pipeline/` | Java 21 headless Robocode runner and replay pipeline: event reconstruction, observer shadows, Layer 0 fidelity, god-view validators, CSV/trace writers, fixtures, and integration tests. |
| `test-bots/` | Small opponent set for repeatable evaluation: `SittingDuck`, `Aggressive`, and sample `Fire`, `Walls`, `Crazy`. |
| `scripts/` | Local orchestration and analysis helpers: compile/battle/eval loop, CSV sanity checks, and drift-report generation. |
| `wiki/` | Robocode domain notes for physics, radar, movement, targeting, strategy, and terminology. |
| `build/` | Local generated battle outputs, traces, reports, and ad-hoc analysis artifacts. Not source of truth. |
| `IDebugProperties-intent.md`, `GodView-intent.md`, `replay-architecture.md` | Detailed design contracts for replay fidelity, god-view quality layers, and observer data flow. |

## Useful Commands

```powershell
.\gradlew.bat :robot:jar
.\gradlew.bat :pipeline:runBattle -Prounds=5 -Popponent=test.SittingDuck
.\gradlew.bat :pipeline:battleTest -Prounds=35 -Popponent=sample.Crazy
.\gradlew.bat :pipeline:battleTest -Prounds=1 -Popponent=test.SittingDuck -PcsvDir=build/csv-check
.\gradlew.bat :pipeline:battleCsvProducer -Prounds=1 -Pseed=12345
.\gradlew.bat :pipeline:battleCsvProducer -Prounds=1 -ProbotA=cz.zamboch.Autopilot -ProbotB=kc.mega.BeepBoop
node scripts/local-loop.mjs --rounds 5 --opponent test.SittingDuck
.\gradlew.bat :pipeline:updateVcsData
```
