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
- Use `CsvRowWriter` for all CSV formatting — never raw `OutputStream` + `StringBuilder`.

## Build
- Gradle 9.4.1 Kotlin DSL, version catalog in `gradle/libs.versions.toml`
- Robocode 1.10.1 from Maven Central
