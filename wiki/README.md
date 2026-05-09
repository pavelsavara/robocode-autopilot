# Wiki — Knowledge Base

This folder contains the project's reference knowledge base, organized by topic.

## Pages

| Page | Description |
|---|---|
| [physics.md](physics.md) | Robocode game physics — coordinates, movement, bullets, energy, scanning |
| [features.md](features.md) | Feature catalog — all implemented and planned features with formulas |
| [leakage.md](leakage.md) | Data leakage patterns discovered and prevention rules |
| [ml-results.md](ml-results.md) | ML model results — honest baselines and training details |
| [architecture.md](architecture.md) | Robot architecture — decision system, data flow, subsystems, feature map, known issues |
| [pipeline.md](pipeline.md) | Pipeline & CSV schema — recording to CSV workflow |
| [strategy.md](strategy.md) | Competitive strategy — known ideas, top-bot analysis, ML prior art |

## Notebook Index

| # | Notebook | Status | Key Finding |
|---|---|---|---|
| 01 | Data overview | Current | |
| 02 | Correlations | Current | |
| 03 | Clustering | Current | |
| 04 | Simple ML (leakage audit) | Historical | Educational |
| 05 | Movement prediction | Stale outputs | |
| 06 | Bot fingerprinting | Current | |
| 07 | Round outcomes | Historical | Replaced by energy ratio |
| 08 | Wave analysis | Current | |
| 09 | Adaptation signal | Current | Negative result |
| 10 | Online fingerprint | Current | |
| 11 | Scan gap density | Current | |
| 12 | Wave stacking | Current | Niche tactic |
| 13 | Multi-wave GF | Current | |
| 14 | GBM model analysis | Current | Authoritative baselines |
| 15 | Opponent profiles | Current | Archetype clustering failed |
| 16 | Position advantage | Current | R²=0.001 — proven useless |

### Retrospective Notebooks (`intuition/retrospective/`)

| # | Notebook | Analysis |
|---|---|---|
| R01 | Win/loss rates | Per-opponent win rate, survival |
| R02 | Gun accuracy | Per-gun hit rates |
| R03 | Damage analysis | Damage dealt vs received |
| R04 | Movement effectiveness | Opponent hit rate (dodge quality) |
| R05 | Fire power prediction | In-game vs offline prediction comparison |
| R06 | Round trends | Adaptation speed across rounds |
| R07 | Rumble comparison | Local vs rumble metrics |
| R08 | Gun selection | Per-gun selection % |
| R09 | Movement analysis | Velocity, wave surf activation |
| R10 | ML predictions | Prediction ranges and calibration |

## Relationship to Other Docs

- **[plan.md](../plan.md)** — Current project plan (actionable, forward-looking)
- **[sprint.md](../sprint.md)** — Sprint cycle rules and diagnostic checklist
- **[team.md](../team.md)** — Roles, skills, artifact ownership
- **[archive/](../archive/)** — Historical planning documents (dated, immutable)
- **[intuition/](../intuition/)** — Jupyter notebooks (executable analysis)
- **[intuition/retrospective/](../intuition/retrospective/)** — Per-iteration analysis notebooks
- **[.github/copilot-instructions.md](../.github/copilot-instructions.md)** — Coding conventions for AI assistants
