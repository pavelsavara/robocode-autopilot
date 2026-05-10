# Squad Team

> robocode-autopilot — Competitive Robocode 1v1 robot powered by offline-trained ML models distilled to Java.

## Coordinator

| Name | Role | Notes |
|------|------|-------|
| Squad | Coordinator | Routes work, enforces handoffs and reviewer gates. |

## Members

| Name | Role | Charter | Status |
|------|------|---------|--------|
| Holden | Lead | [charter](agents/holden/charter.md) | 🟢 Active |
| Alex | Movement Engineer | [charter](agents/alex/charter.md) | 🟢 Active |
| Bobbie | Targeting Engineer | [charter](agents/bobbie/charter.md) | 🟢 Active |
| Naomi | ML Engineer | [charter](agents/naomi/charter.md) | 🟢 Active |
| Amos | Systems Engineer | [charter](agents/amos/charter.md) | 🟢 Active |
| Scribe | Scribe | [charter](agents/scribe/charter.md) | 🟢 Active |
| Ralph | Work Monitor | — | 🔄 Monitor |

## Project Context

- **Project:** robocode-autopilot
- **User:** Pavel Savara
- **Created:** 2026-05-09
- **Stack:** Java 8 (core/pipeline/robot), Python 3.10 (ML/intuition), Gradle 9.4.1, PowerShell
- **Priority:** Win rate improvement (currently 9.1% vs 16-opponent eval, target >50% vs top-50)
- **Key gaps:** Movement (40% opponent HR → target <20%), Targeting (3.6% our HR → target >10%)
- **Sprint process:** See [sprint.md](../sprint.md) and [team.md](../team.md) for roles
- **Planning/retro:** Coordinator leads (Ralph PM role folded in)
- **Naomi duties:** Statistical commentary + feature catalog research in every retro
- **Amos duties:** Pipeline + CI offload to GitHub Actions
- **Movement mandate:** Every 3rd sprint must include a movement proposal
- **Docs:** [plan.md](../plan.md), [wiki/](../wiki/), [archive/](../archive/)
