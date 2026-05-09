# Holden — History

## Project Seed

- **Project:** robocode-autopilot — competitive Robocode 1v1 robot
- **User:** Pavel Savara
- **Stack:** Java 8, Python 3.10, Gradle 9.4.1, PowerShell
- **Current state:** Phase 12, 0.56% win rate vs top 50 opponents
- **Key gaps:** Movement (47% opp HR), Targeting (8% our HR)
- **Priority:** Win rate improvement first (not Phase 12 online learning)
- **TickBudget bug fixed 2026-05-09** — all previous evals were at 5% model capacity

## Learnings

### 2026-05-09 — Sprint 7
- Sprint result: BLOCKED. Score improved 10× (5.4%) after TickBudget fix but 0% win rate.
- Fire power model R²=−0.61 in-game vs 0.862 offline — feature mismatch is root cause.
- Gun selection bug: HeadOnGun at 54% despite Decision #10 demoting it to lowest priority.
- Phase 12 repurposed from "Online Learning" to "Fix Broken Systems" — online learning deferred.
- Decision #13: fix broken systems before adding new features.
