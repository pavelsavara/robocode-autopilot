# Work Routing

How to decide who handles what.

## Routing Table

| Work Type | Route To | Examples |
|-----------|----------|----------|
| Movement, wave surfing, dodge, orbit, PathPlanner | Alex | Wall avoidance, direction commitment, WaveSurfMovement |
| Targeting, guns, VCS, GuessFactor, VirtualGunManager | Bobbie | Gun accuracy, VCS histograms, fire angle selection |
| ML models, training, export, distillation, leakage | Naomi | train_distill.py, export_gbm_java.py, model retraining |
| Build, pipeline, battles, recording, persistence | Amos | local-pipeline.ps1, Gradle, Dockerfiles, autopilot.dat |
| Sprint planning, retrospectives, decisions, code review | Holden | Sprint cadence, merge gates, proposal selection |
| Session logging | Scribe | Automatic — never needs routing |
| Work queue, backlog monitoring | Ralph | Automatic — tracks pending work |

## Domain Boundaries

| Module | Primary Owner | Reviewer |
|--------|--------------|----------|
| `core/.../movement/` | Alex | Holden |
| `core/.../gun/` | Bobbie | Holden |
| `core/.../physics/` | Alex | Holden |
| `core/.../ml/`, `core/.../persistence/` | Amos | Holden |
| `robot/.../distilled/` | Naomi | Holden |
| `intuition/train_*.py`, `export_*.py` | Naomi | — |
| `intuition/retrospective/` | Shared by metric owner | Holden |
| `scripts/`, `pipeline/` | Amos | Holden |
| `plan.md`, `sprint.md`, `archive/` | Holden | — |

## Issue Routing

| Label | Action | Who |
|-------|--------|-----|
| `squad` | Triage: analyze issue, assign `squad:{member}` label | Lead |
| `squad:{name}` | Pick up issue and complete the work | Named member |

### How Issue Assignment Works

1. When a GitHub issue gets the `squad` label, the **Lead** triages it — analyzing content, assigning the right `squad:{member}` label, and commenting with triage notes.
2. When a `squad:{member}` label is applied, that member picks up the issue in their next session.
3. Members can reassign by removing their label and adding another member's label.
4. The `squad` label is the "inbox" — untriaged issues waiting for Lead review.

## Rules

1. **Eager by default** — spawn all agents who could usefully start work, including anticipatory downstream work.
2. **Scribe always runs** after substantial work, always as `mode: "background"`. Never blocks.
3. **Quick facts → coordinator answers directly.** Don't spawn an agent for "what port does the server run on?"
4. **When two agents could handle it**, pick the one whose domain is the primary concern.
5. **"Team, ..." → fan-out.** Spawn all relevant agents in parallel as `mode: "background"`.
6. **Anticipate downstream work.** If a feature is being built, spawn the tester to write test cases from requirements simultaneously.
7. **Issue-labeled work** — when a `squad:{member}` label is applied to an issue, route to that member. The Lead handles all `squad` (base label) triage.
