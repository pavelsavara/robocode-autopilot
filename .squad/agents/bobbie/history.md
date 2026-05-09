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
