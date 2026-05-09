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
