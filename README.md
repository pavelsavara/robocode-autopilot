# robocode-autopilot

ML-powered Robocode 1v1 competition robot. Trains offline on battle data
from a 50-bot rumble, distills models to Java for in-game use.

**[Rumble Rankings](https://pavelsavara.github.io/robocode-autopilot/)** — Live season results

## Project Structure

| Directory | Description |
|---|---|
| `core/` | In-game logic — features, interfaces, physics, strategy framework |
| `pipeline/` | Offline processing — `.br` replay → CSV feature extraction |
| `robot/` | Competition robot (`cz.zamboch.Autopilot`) — depends on core only |
| `intuition/` | Jupyter notebooks + ML training scripts |
| `wiki/` | [Knowledge base](wiki/README.md) — physics, features, leakage, ML results, architecture |
| `archive/` | [Historical planning documents](archive/index.md) with date prefixes |
| `rumble/` | Battle runner scripts and bot lists |
| `output/csv/` | Pipeline output (gitignored) |

## Quick Links

- [Project Plan](plan.md) — current roadmap and status
- [Wiki](wiki/README.md) — knowledge base index
  - [Physics](wiki/physics.md) — Robocode game mechanics
  - [Features](wiki/features.md) — feature catalog (80+ implemented, 443 specified)
  - [Leakage](wiki/leakage.md) — data leakage patterns and prevention
  - [ML Results](wiki/ml-results.md) — honest model baselines
  - [Architecture](wiki/architecture.md) — robot decision system
  - [Pipeline](wiki/pipeline.md) — recording to CSV workflow
  - [Strategy](wiki/strategy.md) — competitive ideas and top-bot analysis
- [Coding Conventions](.github/copilot-instructions.md) — style, module boundaries, leakage rules

## Build

```bash
./gradlew :robot:jar    # Build competition robot JAR
./gradlew :core:test    # Run unit tests
```

Requires Java 8. Gradle 9.4.1 (wrapper included).
