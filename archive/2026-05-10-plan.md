# robocode-autopilot — Project Plan (v4)

*Updated: 2026-05-10 · Previous plans: [archive/2026-05-04-plan.md](archive/2026-05-04-plan.md)*
*Latest sprint: 18 · Score: 9.1% · In-game R²: +0.48*

## Vision

Build a competitive Robocode 1v1 robot powered by offline-trained ML models
distilled to Java. The robot uses a multi-strategy architecture with virtual
guns, competing movement strategies, and energy-aware strategy layer.

## Current Performance (16-opponent eval, 2026-05-10)

| Metric | Sprint 9 (baseline) | Sprint 18 (current) | Target |
|---|---|---|---|
| Overall score % | 6.1% | **9.1%** | >50% vs top-50 |
| Battle win rate | 0.0% | ~2% (occasional) | >30% |
| Our hit rate | 3.5% | 3.6% | >10% |
| Opponent hit rate | ~46% | ~40% | <20% |
| Fire power R² (in-game) | −3.67 | **+0.48** | >0.7 |
| Skipped turns/battle | 0.6 | 0.0 | 0 |

## Completed Phases (archived — see retrospectives in `archive/`)

Phases 1–8 (rumble, pipeline, notebooks, GBM models, architecture, distillation),
Phase 9–11 (wire predictions, local pipeline, iterative improvement),
Phase 12 (fix broken systems: 23 NaN features, O(1) rolling stats, process improvements),
Sprints 13–18 (sprint loop: score 6.1%→9.1%, R² −3.67→+0.48, VCS fixes, wave surf).

---

## Active Phase: Competitive Improvement Campaign

### Workstream A: CI Offload (Amos — HIGH PRIORITY)

Offload battles to GitHub Actions to unblock local development.
Existing infra: `run-season.yml`, `Dockerfile.battle`, `run-battle.mjs`.
**Constraint:** ~30 Mbps WiFi — minimize data transfer.

1. **Create `eval-sprint.yml` workflow** triggered on push to `main`.
   - Build robot JAR in CI
   - Run battles in matrix (4 runners × 8 opponents each = 32 opponents)
   - Compute `summary.json` in CI, download only that (~2 KB) — NOT recordings
   - Post per-opponent score table as commit status or PR comment
2. **Self-battle job** — add `cz.zamboch.Autopilot` as opponent.
   Sanity check: score must be 48–52%. Skew indicates position/init bug.
3. **Local fallback** — keep `local-pipeline.ps1` working. Use `-EvalOnly`
   locally while CI handles full pipeline.

### Workstream B: Feature Divergence Resolution (Naomi)

In-game R² is +0.48 but offline is 0.91. The remaining features with
Java/Python divergence cap targeting accuracy.

1. **Run FeatureLogger diagnostic** — scripts ready since Sprint 11.
   Execute `compare_features.py` against a diagnostic battle. Identify
   top-10 divergent features.
2. **Fix top divergent features** — target per-feature correlation ≥ 0.95.
3. **Statistical commentary in retrospectives** — Naomi writes a section
   in every retro analysing trends, variance, per-opponent anomalies.
4. **Feature catalog research** — each sprint, Naomi researches one
   unimplemented feature from [archive/2026-05-01-features.md](archive/2026-05-01-features.md)
   and writes an analysis of its potential value in the retrospective.

### Workstream C: Movement (Alex — EVERY 3RD SPRINT MINIMUM)

Opponent HR ~40% is the biggest gap. Movement received only 2 tasks
in 10 sprints despite being the binding constraint.

1. **Improve wave surf danger scoring** — use per-opponent VCS profiles
   for precise GF danger assessment.
2. **Implement true precise prediction** — simulate exact future positions
   including wall bouncing and deceleration.
3. **Anti-profiling (GF flattening)** — randomize dodge direction when no
   wave is imminent.

### Workstream D: Targeting (Bobbie)

Hit rate 3.6% is extremely low. VCS gun now has correct segments
(Sprint 16 fix) but hasn't outperformed CircularGun yet.

1. **Histogram smoothing** — kernel density estimation on VCS histograms.
2. **Use predicted fire power in bullet speed** — VCS and CircularGun
   should use ML fire power prediction for wave speed calculation.
3. **More VCS segments** — add velocity bucket and acceleration.

### Workstream E: Opponent Expansion (Amos)

Expand evaluation from 16 to 32 opponents for broader competitive coverage.
Download and validate 16 new opponent JARs from Robocode Archive.
Add archetype coverage: ram bots, surfers, spinners, nano bots.

---

## Honest ML Baselines (Sprint 18)

| Task | Model | Metric | Offline | In-Game |
|---|---|---|---|---|
| Fire power | XGBoost (200t) | R² | **0.913** | **+0.48** |
| Movement N=5 | GBM-window (200t) | R² | **0.760** | — |
| Fire timing | GBM-window (200t) | AUC | **0.815** | — |

---

## Key Decisions Log

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Java 8 target | Required for Robocode engine classloader |
| 2 | Stateless features | All inter-tick state in Whiteboard |
| 3 | 20-tick sliding windows | Key innovation for all ML tasks |
| 4 | Base64-embed models, no file I/O | Robocode sandbox blocks getResourceAsStream |
| 5 | Compact 200-tree models | 500KB budget; full 800-tree too slow |
| 6 | Orbit-primary movement | Constant wave surf oscillation hurts |
| 7 | CircularGun as primary | Best general-purpose; HeadOnGun demoted |
| 8 | Fix broken systems before features | Decision #13 — still applies |
| 9 | O(1) rolling stats | PrimitiveRollingBuffer aligns Java/Python |
| 10 | VCS segments at fire time | Lateral direction + distance captured correctly |
| 11 | VCS-guided orbital direction | Use wave danger histograms for non-imminent waves |
| 12 | Process improvements (Sprint 12) | Archive recordings, incremental CSV, sprint-only sanity |
| 13 | Coordinator leads planning/retro | Ralph PM role folded into coordinator |
| 14 | Naomi: statistical analysis + feature research | Per-sprint commentary + catalog research |
| 15 | Movement work every 3 sprints | Mandate — opponent HR is binding constraint |
| 16 | Amos owns CI pipeline | GH Actions offload, minimize data transfer |
| 17 | Expand to 32 opponents | Broader competitive coverage + archetype diversity |

---

## Documentation Index

- **Architecture:** [wiki/architecture.md](wiki/architecture.md)
- **Wiki:** [wiki/](wiki/) — physics, features, leakage, ML results, pipeline, strategy
- **Archive:** [archive/](archive/) — historical planning documents
- **Sprint process:** [sprint.md](sprint.md)
- **Team:** [team.md](team.md)
- **Copilot instructions:** [.github/copilot-instructions.md](.github/copilot-instructions.md)
