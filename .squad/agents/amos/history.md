# Amos — History

## Project Seed

- **Project:** robocode-autopilot — competitive Robocode 1v1 robot
- **User:** Pavel Savara
- **Stack:** Java 8, Gradle 9.4.1, PowerShell, Node.js
- **Current state:** Pipeline working end-to-end, ~1944 battles recorded
- **TickBudget bug fixed 2026-05-09** — ceiling now recovers upward
- **Persistence:** 4 sections, ~88 KB VCS data, binary format v1

## Learnings

### 2026-05-09 — Sprint 7
- TickBudget PASS: budget 100–200 trees (full capacity confirmed after fix).
- Skipped turns PASS: only 0.2 per battle (negligible).
- Score improved 10× (0.56% → 5.4%) confirming TickBudget fix value.
- Sanity check scripts still ad-hoc — need standardized reusable diagnostic tooling.
- Next sprint: build persistent sanity check script for automated pre/post evaluation.
