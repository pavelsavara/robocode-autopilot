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
