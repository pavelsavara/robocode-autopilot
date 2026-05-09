# Team Structure

## Team Members

### Ralph — Project Manager

**Responsibilities:**
- Owns the iteration cycle: schedules cycles, runs standups, ensures the
  process in [iterative-improvements.md](iterative-improvements.md) is followed
- Reviews every retrospective before it's committed — rejects any that lack
  numbers or blame opponents without evidence
- Decides when to ship (enter LiteRumble) vs continue iterating
- Manages the fixed opponent set — adds/removes opponents based on ranking changes
- Tracks the win-rate target and declares cycle success/failure

**Artifacts owned:**
- [plan.md](plan.md) — project plan, status summary, milestone decisions
- [archive/](archive/) — retrospective documents (review gate)
- Release schedule and competition submissions

**Skills:** Project management, Robocode competition rules, data-driven
decision making. Must be able to read the retrospective metrics tables
and challenge unsupported conclusions.

---

### Movement Engineer

**Responsibilities:**
- Own all movement code: `WaveSurfMovement`, `OrbitalMovement`, `PathPlanner`,
  `VcsWaveDanger`, `WallDistancePositionDanger`, `ReachableEnvelope`
- Implement true precise-prediction wave surfing (tick-by-tick forward simulation)
- Reduce opponent hit rate from 47% to < 20%
- Tune danger scoring weights, wall avoidance, direction commitment logic
- Own the `MovementStrategyManager` round-level strategy selection

**Artifacts owned:**
- `core/src/.../movement/` — all movement classes
- `core/src/.../physics/` — `ReachableEnvelope`, `PrecisePredictor`, `RobotPhysics`
- `robot/src/.../trivial/OrbitalMovement.java`
- Retrospective notebook `R04` (movement effectiveness), `R09` (movement analysis)

**Skills:** Java 8, trigonometry, Robocode physics model (acceleration,
deceleration, wall sliding), real-time optimization. Must understand
GuessFactor space, Maximum Escape Angle, reachable envelopes, and precise
prediction. Prior competitive wave surfer implementation strongly preferred.

**Key metric:** Opponent hit rate (currently 47%, target < 20%).

---

### Targeting Engineer

**Responsibilities:**
- Own all gun code: `VcsGun`, `CircularGun`, `LinearGun`, `HeadOnGun`,
  `VirtualGunManager`
- Build dynamic-clustering GF gun (47+ segments vs current 6)
- Implement pattern matching as alternative gun strategy
- Tune VGM selection logic, virtual bullet evaluation, exploration rate
- Own VCS histogram data structures in `Whiteboard`

**Artifacts owned:**
- `core/src/.../gun/` — all gun strategy classes and `VirtualGunManager`
- VCS histogram arrays and segmentation in `Whiteboard`
- Retrospective notebooks `R02` (gun accuracy), `R08` (gun selection)

**Skills:** Java 8, online statistics, histogram methods, kernel density
estimation, feature selection for segmentation dimensions. Must understand
Visit Count Statistics, Gaussian smoothing, and the tradeoff between
segment count and convergence speed.

**Key metric:** Our hit rate (currently 8%, target > 15%).

---

### ML Engineer

**Responsibilities:**
- Own the train → export → embed → validate pipeline end-to-end
- Retrain models each cycle, track offline metrics (R², MAE, AUC)
- Build the in-game vs offline prediction comparison (sanity check 4c)
- Design Bayesian VCS+MLP blending system (Phase 12)
- Own `GbmFirePowerPredictor`, `GbmMovementPredictor`, `GbmFireTimingPredictor`,
  `PredictiveGun`, `MlpGfTargeting`
- Prevent data leakage — enforce rules in [wiki/leakage.md](wiki/leakage.md)

**Artifacts owned:**
- `intuition/train_distill.py`, `export_gbm_java.py`, `export_data_java.py`
- `intuition/_loader.py` — data loading and anti-leakage utilities
- `robot/src/.../distilled/` — all ML model data and predictor classes
- `intuition/models/` — trained model artifacts
- Retrospective notebooks `R05` (fire power prediction), `R10` (ML predictions)
- [wiki/ml-results.md](wiki/ml-results.md) — authoritative model metrics
- [wiki/leakage.md](wiki/leakage.md) — leakage taxonomy and prevention rules

**Skills:** XGBoost/scikit-learn, pandas, PyTorch (for MLP), Java interop,
model compression and distillation, feature engineering for time series.
Must understand cross-validation with grouped splits, leakage patterns,
and the difference between offline R² and in-game prediction quality.

**Key metric:** In-game prediction R² matching offline R² within 10%.

---

### Systems / Pipeline Engineer

**Responsibilities:**
- Own `local-pipeline.ps1`, battle orchestration, recording processing
- Build automated regression testing: run fixed-opponent sweep, compare
  with previous best, flag regressions, produce diff report
- Automate the diagnostic checklist from step 4a as a CI-like script
- Own the Gradle build, Dockerfile, and deployment to `c:\robocode\robots\`
- Manage the `autopilot.dat` persistence file format and migrations

**Artifacts owned:**
- `scripts/local-pipeline.ps1` — full pipeline orchestration
- `rumble/scripts/run-battle.mjs` — battle runner
- `pipeline/` — offline CSV processing (Loader, Player, CsvWriter)
- `build.gradle.kts`, `settings.gradle.kts`, Docker files
- `core/src/.../persistence/` — `PersistenceManager`, `VcsHistogramStore`
- `core/src/.../ml/TickBudget.java` — CPU throttle
- Retrospective notebooks `R01` (win/loss), `R03` (damage), `R06` (round trends),
  `R07` (rumble comparison)
- [wiki/pipeline.md](wiki/pipeline.md) — pipeline documentation

**Skills:** PowerShell, Node.js, Gradle/Kotlin DSL, Java 8, binary file
formats, Jupyter notebook automation (`nbconvert`, `papermill`). Must
understand Robocode's battle runner, `.br` recording format, and the
security sandbox constraints.

**Key metric:** Pipeline reliability (zero manual steps from code change to
retrospective data) and cycle time (< 30 minutes per iteration).

---

### Code Quality Reviewer

**Responsibilities:**
- Review every PR / commit before it reaches `main`
- Enforce coding conventions from [.github/copilot-instructions.md](.github/copilot-instructions.md):
  `final` classes, `static` inner classes, stateless features, module boundaries
- Verify that core has no I/O, robot has no pipeline code, features don't
  store mutable state
- Check for performance regressions: no per-tick allocation, no O(N²) in hot paths
- Review persistence format changes for backward compatibility
- Ensure test coverage for new code (delegates to Test Author for implementation)

**Artifacts owned:**
- [.github/copilot-instructions.md](.github/copilot-instructions.md) — coding conventions
- Code review checklist (maintained as part of PR template)

**Skills:** Java 8 best practices, Robocode classloader constraints, performance
analysis (allocation profiling, tick budget awareness), binary compatibility.
Must understand why `final` matters for JIT, why inner classes must be `static`,
and why features must be stateless.

**Key metric:** Zero regressions from code quality issues (NaN bugs, persistence
corruption, skipped turns from allocation pressure).

---

### Test Author

**Responsibilities:**
- Write and maintain unit tests for all core and robot modules
- Ensure every new feature class has tests for edge cases (NaN inputs,
  zero velocity, wall boundaries, empty histograms)
- Write integration tests: full Transformer pipeline with synthetic Whiteboard
  state, verify all features produce plausible values
- Write regression tests for known bugs (TickBudget ratchet, wall feature NaN,
  VCS segment indexing)
- Maintain model loading tests (`ModelLoadingTest`) — verify Base64 decode,
  tree traversal, fixture predictions match Python output

**Artifacts owned:**
- `core/src/test/` — all core unit tests
- `robot/src/test/` — robot smoke tests and model loading tests
- `pipeline/src/test/` — pipeline processing tests
- Test fixtures in `robot/src/test/resources/distilled/` — model prediction fixtures

**Skills:** JUnit 5, Java 8, test design (boundary analysis, property-based
testing for physics code), mocking (Whiteboard state construction). Must
understand Robocode physics well enough to write meaningful assertions
(e.g. circular gun angle must be within MEA of bearing).

**Key metric:** Test coverage > 80% on core module; zero test-preventable
bugs reaching battle evaluation.

---

## Artifact Ownership Matrix

| Artifact | Owner | Reviewer |
|----------|-------|----------|
| `plan.md` | Ralph | — |
| `iterative-improvements.md` | Ralph | All |
| `wiki/architecture.md` | All (shared) | Code Quality Reviewer |
| `wiki/ml-results.md` | ML Engineer | Ralph |
| `wiki/strategy.md` | Movement Eng + Targeting Eng | Ralph |
| `core/.../movement/` | Movement Engineer | Code Quality Reviewer |
| `core/.../gun/` | Targeting Engineer | Code Quality Reviewer |
| `core/.../ml/`, `core/.../persistence/` | Systems Engineer | Code Quality Reviewer |
| `robot/.../distilled/` | ML Engineer | Code Quality Reviewer |
| `robot/Autopilot.java` | Systems Engineer | All |
| `scripts/`, `pipeline/` | Systems Engineer | Code Quality Reviewer |
| `intuition/train_*.py`, `export_*.py` | ML Engineer | — |
| `intuition/retrospective/` | Shared by metric | Ralph (review gate) |
| `core/src/test/`, `robot/src/test/` | Test Author | Code Quality Reviewer |
| `archive/*-retrospective-*.md` | Ralph (writes) | All (data from notebooks) |
