# Bobbie — History

## Project Seed

- **Project:** robocode-autopilot — competitive Robocode 1v1 robot
- **User:** Pavel Savara
- **Stack:** Java 8, Python 3.10, Gradle 9.4.1
- **Current state:** 8% our hit rate, CircularGun primary, 4 gun strategies
- **VCS:** 61 bins × 12 distance segments, per-opponent LRU persistence
- **TickBudget bug fixed 2026-05-09** — first real evaluation pending

## Learnings

### 2026-05-09 — Sprint 7
- HeadOnGun selected 54% of ticks despite being lowest priority — gun ordering bug in VGM.
- All 4 guns have nearly identical virtual hit rates (3–4.5%) — no gun differentiation.
- Gun correctness (best gun selected) only 39% — worse than random among 4 guns.
- CircularGun should be primary (Decision #10) but selection logic not honoring priority.
- Next sprint priority: fix gun ordering in VirtualGunManager, tune epsilon threshold.

### 2026-05-09 — Sprint 9
- Gun selection FIXED. Replaced confidence-based tie-break with index-based two-pass priority system.
- Root cause: `getConfidence()=1.0` ceiling made it impossible for other guns to outprioritize CircularGun within epsilon — but epsilon (0.01) was below hit rate resolution (1/50=0.02), so random virtual hits bypassed tie-breaking entirely.
- New system: (1) find max hit rate, (2) among guns within 3% of max, pick lowest index. Gun order = priority.
- CircularGun now 68% selection with 3.5% HR (best). HeadOnGun demoted to 4%.
- Initial submission rejected in code review — had cross-branch dependency on FeatureLogger methods not yet merged. Lockout rule applied (Amos fixed the wiring lines).
- 17 tests rewritten covering tie-break, priority, edge cases.
- Next: investigate why CircularGun HR is only 3.5% — low for circular targeting.

### 2026-05-09 — Circular Targeting Physics Fix
- Found 3 bugs in `TargetingFeatures.circularTargetAngle()`:
  1. **Wrong physics ordering**: moved THEN turned (should be turn-then-move per Robocode engine). At distance 400, bullet speed 14, this causes ~9px position error (~1.3° aim error) for turning opponents.
  2. **No turn rate capping**: headingDelta wasn't constrained by `10 - 0.75·|vel|` deg/tick physics limit. Could produce impossible trajectories.
  3. **Wall collision didn't zero velocity**: position was clamped but velocity continued at full speed, making the simulation slide opponents along walls unrealistically.
- All 3 fixes applied. Added 17 unit tests for circular prediction, wall collision, turn rate capping, and process() integration.
- Key file: `core/src/main/java/cz/zamboch/autopilot/core/features/TargetingFeatures.java`
- Expected impact: Most significant for opponents near walls (majority of engagement ticks) and turning opponents (most bots except linear ones).

### Cross-agent: Sprint 10
- Naomi found root cause of R²=−3.67: 23/80 fire power features were NaN. MlDerivedFeatures.java created.
- Amos wired FeatureLogger into GbmFirePowerPredictor — enables per-tick CSV diagnostics.
- Sprint result: HIT. Score 6.6%, R² −3.67→−1.44.

### 2026-05-10 — VCS Segment Mismatch Fix
- **Root cause found**: Gun VCS was storing GF data under WRONG segments in `prunePassedWaves()`.
  Two bugs:
  1. **Lateral direction mismatch**: Update used `opponentVelocity >= 0 ? 1 : -1` (forward/backward),
     but VcsGun queries with `Feature.OPPONENT_LATERAL_DIRECTION` (left/right relative to bearing).
     Completely different semantics — data stored in segment A, queried from segment B.
  2. **Distance mismatch**: Update used wave-break-time `distanceToOpponent` for segment,
     but VcsGun queries with fire-time `Feature.DISTANCE`. When opponents move between fire and
     wave break (25+ ticks later), these can be in different distance bins (close/mid/far).
- **Fix**: Added `fireLateralDir` field to `WaveRecord`. Stores lateral direction at fire time.
  Both gun VCS and move VCS updates now use `w.fireDistance` + `w.fireLateralDir` for segmentation,
  matching VcsGun's query-time semantics.
- **Files changed**:
  - `WaveRecord.java` — added `fireLateralDir` field + backward-compat constructors
  - `Whiteboard.java` — `prunePassedWaves()` uses fire-time values for both gun and move VCS
  - `Autopilot.java` — passes `OPPONENT_LATERAL_DIRECTION` when creating our waves
  - `MultiWaveFeatures.java` — passes `prevLateralDirection` when creating opponent waves
- **Tests**: 8 new tests in `VcsGunTest.java` — segment consistency, fire-time distance,
  velocity sign vs lateral direction, backward compat, peak firing.
- **Expected impact**: HIGH. Every single VCS data point was going into the wrong segment.
  VcsGun was essentially reading from near-empty histograms. Fix should dramatically
  improve VCS targeting accuracy.
