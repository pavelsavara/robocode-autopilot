# Team Structure

*See [sprint.md](sprint.md) for the sprint process this team follows.*

---

## Ralph — Project Manager

**Sprint role:** Leads Phase 1 (Planning) and Phase 5 (Retrospective & Commit).
Gates all retrospectives and final commits.

**Responsibilities:**
- Owns the sprint cadence: opens each phase, enforces the process
- Selects proposals for each sprint (max 3, or 1 if major)
- Assigns proposals to the responsible engineer
- Writes the retrospective using data from engineers' notebooks — rejects
  any finding that lacks numbers or blames opponents without evidence
- Declares sprint result (win / miss / blocked)
- Decides when to ship (enter LiteRumble) vs continue iterating
- Manages the fixed opponent set

**Artifacts owned:**
- [plan.md](plan.md) — project plan, status summary, milestone decisions
- [sprint.md](sprint.md) — sprint process rules
- [archive/](archive/) — retrospective documents (write + review gate)

**Skills:** Project management, Robocode competition rules, data-driven
decision making. Must read metrics tables and challenge unsupported claims.

---

## Movement Engineer

**Sprint role:** Implements movement proposals (Phase 2). Runs sanity
check #5 (wave detection) and notebooks R04, R09 (Phase 4).

**Responsibilities:**
- Own all movement code: `WaveSurfMovement`, `OrbitalMovement`, `PathPlanner`,
  `VcsWaveDanger`, `WallDistancePositionDanger`, `ReachableEnvelope`
- Implement true precise-prediction wave surfing
- Reduce opponent hit rate from 47% to < 20%
- Tune danger scoring, wall avoidance, direction commitment

**Artifacts owned:**
- `core/src/.../movement/` — all movement classes
- `core/src/.../physics/` — `ReachableEnvelope`, `PrecisePredictor`, `RobotPhysics`
- `robot/src/.../trivial/OrbitalMovement.java`
- Retrospective notebooks R04 (movement effectiveness), R09 (movement analysis)
- [wiki/strategy.md](wiki/strategy.md) (movement sections, shared with Targeting)

**Key metric:** Opponent hit rate (currently 47%, target < 20%).

**Skills:** Java 8, trigonometry, Robocode physics model, real-time
optimization. Must understand GF space, MEA, reachable envelopes, and
precise prediction. Prior wave surfer implementation strongly preferred.

---

## Targeting Engineer

**Sprint role:** Implements gun proposals (Phase 2). Runs sanity check #3
(gun selection) and notebooks R02, R08 (Phase 4).

**Responsibilities:**
- Own all gun code: `VcsGun`, `CircularGun`, `LinearGun`, `HeadOnGun`,
  `VirtualGunManager`
- Build dynamic-clustering GF gun (47+ segments vs current 6)
- Implement pattern matching as alternative gun strategy
- Tune VGM selection logic, virtual bullet evaluation, exploration rate
- Own VCS histogram data structures in `Whiteboard`

**Artifacts owned:**
- `core/src/.../gun/` — all gun strategy classes and VirtualGunManager
- VCS histogram arrays and segmentation in `Whiteboard`
- Retrospective notebooks R02 (gun accuracy), R08 (gun selection)
- [wiki/strategy.md](wiki/strategy.md) (targeting sections, shared with Movement)

**Key metric:** Our hit rate (currently 8%, target > 15%).

**Skills:** Java 8, online statistics, histogram methods, kernel density
estimation. Must understand VCS, Gaussian smoothing, segment count vs
convergence tradeoff.

---

## ML Engineer

**Sprint role:** Retrains models (Phase 2). Runs sanity checks #4 and #6,
in-game vs offline comparison (4c), and notebooks R05, R10 (Phase 4).

**Responsibilities:**
- Own the train → export → embed → validate pipeline end-to-end
- Retrain models each sprint, track offline metrics (R², MAE, AUC)
- Build in-game vs offline prediction comparison
- Design Bayesian VCS+MLP blending (Phase 12)
- Prevent data leakage — enforce [wiki/leakage.md](wiki/leakage.md)

**Artifacts owned:**
- `intuition/train_distill.py`, `export_gbm_java.py`, `export_data_java.py`
- `intuition/_loader.py` — data loading and anti-leakage utilities
- `robot/src/.../distilled/` — all ML model data and predictor classes
- `intuition/models/` — trained model artifacts
- Retrospective notebooks R05 (fire power prediction), R10 (ML predictions)
- [wiki/ml-results.md](wiki/ml-results.md) — authoritative model metrics
- [wiki/leakage.md](wiki/leakage.md) — leakage taxonomy and prevention rules

**Key metric:** In-game prediction R² matching offline R² within 10%.

**Skills:** XGBoost/scikit-learn, pandas, PyTorch, Java interop, model
compression, feature engineering for time series. Must understand grouped
cross-validation, leakage patterns, and offline-vs-in-game quality gap.

---

## Systems / Pipeline Engineer

**Sprint role:** Leads Phase 3 (Battle & Record). Runs sanity checks #1
and #2, and notebooks R01, R03, R06, R07 (Phase 4). Owns automated
sanity-check script.

**Responsibilities:**
- Own `local-pipeline.ps1`, battle orchestration, recording processing
- Build automated regression testing and sanity-check script
- Own the Gradle build, Dockerfiles, and deployment
- Manage `autopilot.dat` persistence format and migrations

**Artifacts owned:**
- `scripts/local-pipeline.ps1` — full pipeline orchestration
- `rumble/scripts/run-battle.mjs` — battle runner
- `pipeline/` — offline CSV processing (Loader, Player, CsvWriter)
- `build.gradle.kts`, `settings.gradle.kts`, Dockerfiles
- `core/src/.../persistence/` — PersistenceManager, VcsHistogramStore
- `core/src/.../ml/TickBudget.java` — CPU throttle
- Retrospective notebooks R01 (win/loss), R03 (damage), R06 (trends), R07 (rumble)
- [wiki/pipeline.md](wiki/pipeline.md) — pipeline documentation
- Automated sanity-check script (Phase 4a automation)

**Key metric:** Pipeline reliability (zero manual steps) and sprint cycle
time (< 30 minutes from code change to retrospective data).

**Skills:** PowerShell, Node.js, Gradle/Kotlin DSL, Java 8, binary formats,
Jupyter automation. Must understand Robocode battle runner, `.br` format,
and security sandbox.

---

## Code Quality Reviewer

**Sprint role:** Reviews all branches in Phase 2 before merge.
Gates the merge to `main`.

**Responsibilities:**
- Review every branch before it merges — enforce conventions from
  [.github/copilot-instructions.md](.github/copilot-instructions.md)
- Verify: `final` classes, `static` inner classes, stateless features,
  module boundaries (no I/O in core, no pipeline code in robot)
- Check for performance issues: per-tick allocation, O(N²) in hot paths
- Review persistence format changes for backward compatibility
- Ensure test coverage exists (delegates writing to Test Author)

**Artifacts owned:**
- [.github/copilot-instructions.md](.github/copilot-instructions.md) — coding conventions
- PR review checklist

**Key metric:** Zero regressions from code quality issues (NaN bugs,
persistence corruption, skipped turns from allocation pressure).

**Skills:** Java 8 best practices, Robocode classloader constraints,
performance analysis, binary compatibility.

---

## Test Author

**Sprint role:** Writes tests for all new/changed code in Phase 2,
in parallel with the engineers.

**Responsibilities:**
- Unit tests for every new or changed class:
  - Normal operation, edge cases (NaN, zero, boundary), regression tests
- Integration tests: full Transformer pipeline with synthetic Whiteboard
- Model loading tests: Base64 decode, tree traversal, fixture match
- Maintain test fixtures in `robot/src/test/resources/distilled/`

**Artifacts owned:**
- `core/src/test/` — all core unit tests
- `robot/src/test/` — robot smoke tests and model loading tests
- `pipeline/src/test/` — pipeline processing tests
- Test fixtures in `robot/src/test/resources/distilled/`

**Key metric:** Test coverage > 80% on core module; zero test-preventable
bugs reaching battle evaluation.

**Skills:** JUnit 5, Java 8, test design (boundary analysis, property-based
testing for physics), Whiteboard state construction.

---

## Sprint Phase Responsibility Matrix

| Phase | Ralph | Movement | Targeting | ML | Systems | Reviewer | Test Author |
|-------|-------|----------|-----------|-----|---------|----------|-------------|
| 1. Planning | **Lead** | Estimate | Estimate | Estimate | Estimate | — | — |
| 2. Build & Test | Monitor | Code | Code | Retrain + code | — | **Review all** | **Write tests** |
| 3. Battle | — | — | — | — | **Lead** | — | — |
| 4. Diagnose | Monitor | R04, R09, #5 | R02, R08, #3 | R05, R10, #4, #6 | R01, R03, R06, #1, #2 | — | — |
| 5. Retro & Commit | **Lead** | Data | Data | Data | Data | Final review | — |

---

## Artifact Ownership Matrix

| Artifact | Owner | Reviewer |
|----------|-------|----------|
| `plan.md` | Ralph | — |
| `sprint.md` | Ralph | All |
| `team.md` | Ralph | All |
| `wiki/architecture.md` | All (shared) | Code Quality Reviewer |
| `wiki/ml-results.md` | ML Engineer | Ralph |
| `wiki/strategy.md` | Movement + Targeting | Ralph |
| `core/.../movement/` | Movement Engineer | Code Quality Reviewer |
| `core/.../gun/` | Targeting Engineer | Code Quality Reviewer |
| `core/.../ml/`, `core/.../persistence/` | Systems Engineer | Code Quality Reviewer |
| `robot/.../distilled/` | ML Engineer | Code Quality Reviewer |
| `robot/Autopilot.java` | Systems Engineer | All |
| `scripts/`, `pipeline/` | Systems Engineer | Code Quality Reviewer |
| `intuition/train_*.py`, `export_*.py` | ML Engineer | — |
| `intuition/retrospective/` | Shared by metric owner | Ralph (review gate) |
| `core/src/test/`, `robot/src/test/` | Test Author | Code Quality Reviewer |
| `archive/*-retrospective-*.md` | Ralph (writes) | All (data from notebooks) |
