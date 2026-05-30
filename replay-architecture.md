# Replay Architecture

High-level architecture of the **observer (replay) pipeline**. It re-runs
`Autopilot` instances against recorded or live battle data, feeding them
engine-faithful reconstructed events, and turns each observer's `Whiteboard`
into the pipeline's ground truth for CSV extraction, wave analysis, and layered
validation.

> Companion documents define the validation contracts in detail:
> `IDebugProperties-intent.md` (Layer 0) and `GodView-intent.md` (Layers 1–4).
> This document covers the **data flow and component responsibilities**.

## 1. Core idea

The live `Autopilot` plays a battle with only **partial information** (radar
scans, energy drops, collision events) and is **100% deterministic**. We can
therefore stand up a second `Autopilot` — an **observer shadow** — feed it the
*same* reconstructed events, and it must compute the *same* `Whiteboard`. That
shadow's whiteboard becomes the pipeline output, and comparing it against the
live robot's published `IDebugProperty[]` proves the reconstruction is faithful.

Two things ride on top of that faithful shadow:
- **Feature/CSV extraction** — the observer's whiteboard is the data we persist.
- **Two-tier wave analysis** — a robot-side resolver (partial info, like in-game)
  vs a god-view resolver (exact positions), whose gap measures perception quality.

## 2. Data flow

```
Data source                  Reconstruction            Shadow + analysis
───────────                  ──────────────            ─────────────────
Live battle (BattleRunner)
   │  or                     EventReconstructor        Observer[0] (ours)
.br replay (Loader / Main) ─▶  prev+curr snapshot ───▶   Autopilot shadow
   │                           per perspective           Whiteboard[0]
   └── ITurnSnapshot ──────▶                          Observer[1] (theirs)
                                                         Autopilot shadow
                                                         Whiteboard[1]
                                                              │
                              ┌───────────────────────────────┼───────────────┐
                              ▼               ▼               ▼               ▼
                          CSV writers   GodViewWaveResolver  Layer 0        Layers 1–4
                          (ticks,       + WavePrecision-     fidelity       god-view
                           waves,        Comparator          (in-game vs    quality
                           scores,      (robot-side vs       observer)      (god-view vs
                           debug dump)   god-view waves)                     robot-side)
```

`PipelineOrchestrator` (a Robocode `BattleAdaptor`) wires all of this together
and is driven one turn at a time by `onTurnEnded`.

## 3. Components (as implemented in `pipeline/src/.../pipeline/`)

| Component | Responsibility |
|-----------|----------------|
| `Main` | Batch CLI: replays every `.br` recording in an input dir through the orchestrator, writes CSV per battle/perspective. |
| `BattleRunner` | Headless live-battle runner; streams engine turn snapshots into the orchestrator and reports scores. |
| `Loader` | Reads `.br` recordings into `ITurnSnapshot` streams for replay. |
| `PipelineOrchestrator` | The hub. Per turn: reconstruct events → feed observers → god-view wave resolution → validate → write CSV. |
| `EventReconstructor` | Given `prev` + `curr` snapshots and a perspective, produces the exact Robocode events (status, scan, bullet, wall, ram, death) in engine order. |
| `ObserverContext` | Holds one observer `Autopilot`, its `ObserverRobotPeer`, the `EventReconstructor`, and a **separate god-view whiteboard** (independent `VcsStore` + `ModelSelector`). A pair = both perspectives. |
| `ObserverRobotPeer` | Mock robot peer: tracks gun heat, hands back bullets when heat hits 0, and no-ops movement/radar so the shadow's commands never affect anything. |
| `TickEvents` | Value object: the reconstructed `RobotStatus` + ordered `List<Event>` for one tick. |
| `GodViewWaveResolver` | Privileged wave resolver using exact opponent positions every tick (ground-truth fire detection + GF). |
| `WavePrecisionComparator` | Pairs robot-side waves against god-view waves to quantify detection/GF precision. |
| `Layer0DebugFidelityValidator` | **Layer 0**: in-game `IDebugProperty[]` vs observer whiteboard. Must be exact (zero mismatches). |
| `GodViewQualityValidator` | **Layers 1–4**: god-view ground truth vs robot-side estimate (spatial, fire detection, GF precision, energy accounting). |
| `CsvWriter` / `CsvRowWriter` | Tick/wave/score CSV output. |
| `DebugPropertyCsvWriter` | Optional long-format dump of in-game vs observer debug properties for diagnosis. |
| `SnapshotFixtureWriter` | Optional recorder of the raw snapshot stream for offline, engine-grounded unit-test replay. |

## 4. The two-whiteboard rule

Each `ObserverContext` keeps **two** whiteboards:

- **Robot-side whiteboard** — populated purely from reconstructed events, exactly
  what the in-game robot would know. This is the faithful shadow validated by
  Layer 0, and the source of CSV output.
- **God-view whiteboard** — seeded from the robot-side whiteboard each tick, then
  overlaid by `GodViewWaveResolver` using exact positions. It owns an
  **independent** `VcsStore` + `ModelSelector` so god-view wave resolution never
  trains the robot-side model.

**Hard constraint:** god-view never mutates the robot-side state. This keeps
Layer 0 a clean correctness check and Layers 1–4 a clean quality measurement.

## 5. Operating modes

- **Live battle** (`BattleRunner`): the real `Autopilot` fights and publishes
  `IDebugProperty[]`; the orchestrator runs as a `BattleAdaptor` alongside it.
  Layer 0 is available on the perspective the live robot occupies.
- **`.br` replay** (`Main` + `Loader`): no live robot. Two observers always
  process both perspectives; Layer 0 applies only if the recording was made with
  `Autopilot` fighting on that side.

## 6. Validation layers (summary)

| Layer | What vs what | Required result |
|-------|--------------|-----------------|
| **0 — IDebugProperty fidelity** | in-game robot vs observer shadow | **0 mismatches** (exact) — see `IDebugProperties-intent.md` |
| **1 — Spatial & kinematic** | god-view vs robot-side | 0 mismatches on covered features |
| **2 — Fire detection** | god-view fires vs energy-drop inference | rate ≈ 1.0; position/power MAE reported |
| **3 — Wave / GF precision** | god-view wave outcome vs robot-side GF | waveMatchRate ≈ 1.0; bounded GF error |
| **4 — Energy accounting** | predicted energy vs engine energy | ≥ 99.7% (residuals are observability limits) |

Layer 0 proves the pipeline is faithful; Layers 1–4 then measure how good the
blindfolded robot's perception is. Full definitions live in `GodView-intent.md`.

## 7. Scope

- **1v1 only** — no melee.
- **No `SkippedTurnEvent`** — impossible to reconstruct from recordings.
- Observer mode bypasses the security manager, custom class loader, threading,
  TPS timing, graphics, and physics — snapshots already provide every position,
  and commands from the shadow are discarded.
