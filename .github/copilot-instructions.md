# robocode-autopilot Coding Conventions

## Java Version
- Target Java 8 (to match the robocode engine)

## Class Design
- Prefer `final` classes. Every class that is not designed for inheritance must be `final`.
  This enables JIT devirtualization and inlining, reducing method call overhead.
- Inner classes must be `static` unless they genuinely need the enclosing instance reference.
  Non-static inner classes carry a hidden reference to the outer object, increasing allocation cost.
- Utility classes (all-static methods) must be `final` with a private constructor.
- Exception classes should be `static final` when nested.

## Module Boundaries
- **core** — In-game logic only. No CSV, no I/O beyond what the robot needs. Interfaces use `IInGameFeatures`.
- **pipeline** — Offline processing. CSV output, recording replay, `IOfflineFeatures`.
  Offline feature subclasses extend core base classes and add CSV support.
- **robot** — Competition robot. Depends on core only. Must stay small — no pipeline code.

## Features
- Base feature classes (e.g. `SpatialFeatures`) implement `IInGameFeatures` and live in core.
  They are NOT `final` — offline subclasses extend them.
- Offline subclasses (e.g. `SpatialOfflineFeatures`) are `final` and live in pipeline.
- Pipeline-only features (e.g. `MovementSegmentationOfflineFeatures`) implement `IOfflineFeatures`
  directly and live in pipeline. They do NOT have a core base class.
- Feature classes must be **stateless** — all inter-tick state lives in `Whiteboard`.
  Features read from `Whiteboard`, compute, and write back via `wb.setFeature()`.
  Never store mutable fields (e.g. `prevVelocity`, counters) in a feature class.
- All features — core and pipeline-only — use the `Feature` enum and `wb.setFeature()`/`wb.getFeature()`.
- Use `CsvRowWriter` for all CSV formatting — never raw `OutputStream` + `StringBuilder`.

## Build
- Gradle 9.4.1 Kotlin DSL, version catalog in `gradle/libs.versions.toml`
- Robocode 1.10.1 from Maven Central

## Intuition / ML Exploration (Python)
- The `intuition/` folder contains Jupyter notebooks for statistical exploration of pipeline CSV output.
- **Explain all statistics and ML concepts at high-school math level.**
  Every notebook must define terms (mean, std, correlation, PCA, etc.) in plain language
  with intuitive analogies before using them. Assume the reader knows algebra but not
  university-level statistics or linear algebra.
- Use pandas, scikit-learn, matplotlib, seaborn. Python 3.10 venv in `intuition/.venv/`.
- Notebooks read data from `../output/csv/`. They are self-contained and produce inline plots.
