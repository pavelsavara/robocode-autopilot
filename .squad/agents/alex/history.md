# Alex — History

## Project Seed

- **Project:** robocode-autopilot — competitive Robocode 1v1 robot
- **User:** Pavel Savara
- **Stack:** Java 8, Python 3.10, Gradle 9.4.1
- **Current state:** Orbit-primary movement, 47% opponent hit rate
- **Key finding:** Constant wave surfing oscillation hurts — orbit + imminent dodge is better
- **TickBudget bug fixed 2026-05-09** — first real evaluation pending

## Learnings

### 2026-05-09 — Sprint 7
- Movement at max speed only 64% of ticks (should be ~90%+).
- Lateral velocity high only 54.5% — too many direction changes (11.3% of ticks).
- Opponent hit rate 46.2% — opponents adapt to our movement faster than we dodge.
- Orbit-primary strategy confirmed correct (Decision #9) but velocity implementation needs tuning.
- Next sprint priority: reduce unnecessary direction changes, increase time at max speed.
