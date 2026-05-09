# robocode-autopilot — Project Plan (v3)

*Updated: 2026-05-09 · Previous plans: [archive/2026-05-04-plan.md](archive/2026-05-04-plan.md)*
*Sprint 7 result: BLOCKED — see [archive/2026-05-09-retrospective-7.md](archive/2026-05-09-retrospective-7.md)*

## Vision

Build a competitive Robocode 1v1 robot powered by offline-trained ML models
distilled to Java. The robot uses a multi-strategy architecture with virtual
guns, competing movement strategies, and energy-aware strategy layer.

## Project Status Summary

| Phase | Status | Key Deliverable |
|---|---|---|
| 1. Battle recording & rumble | **Done** | 50-bot rumble, CI, ~1944 battles |
| 2. Feature engineering pipeline | **Done** | 80+ features, ticks/waves/scores CSV |
| 3. Statistical exploration | **Done** | 16 notebooks, honest baselines |
| 4. GBM model training | **Done** | Fire power R²=0.960 |
| 5. Robot architecture | **Done** | Full decision wiring, all subsystems |
| 6. Wave stacking research | **Done** | Niche tactic; multi-wave defense priority |
| 7. Feature additions + path planning | **Done** | Multi-wave, envelope, VCS, energy strategy, persistence |
| 8. ML distillation to Java | **Done** | 3 GBM models, Base64 embedded, TickBudget |
| 9. Wire predictions + VCS persistence | **Done** | PredictiveGun, dodge urgency, per-opponent VCS |
| 10. Local pipeline + retrospective | **Done** | Build→battle→record→CSV→notebook loop |
| 11. Iterative improvement campaign | **Done** | Orbit-primary movement, gun reordering, TickBudget fix |
| **12. Online learning** | **Next** | Bayesian blending, adaptation detection |

### Current Performance (16-opponent eval, 2026-05-09)

| Metric | Previous (50 bots) | Current (16 bots) |
|---|---|---|
| Overall score % | 0.56% | **5.4%** |
| Battle win rate | 0.56% | 0.0% |
| Our hit rate | 8.1% | 8.0% |
| Opponent hit rate | 47.1% | 46.2% |
| Damage ratio | — | 0.099 (10:1 deficit) |

**Key finding:** TickBudget fix confirmed working (100–200 trees). Score
improved 10× but two critical issues remain:
- **Fire power model broken in-game** (R²=−0.61 vs offline 0.862) — feature mismatch
- **Gun selection bug** — HeadOnGun selected 54% despite being lowest priority

---

## Honest ML Baselines

All numbers post-leakage-fix. See [wiki/ml-results.md](wiki/ml-results.md) for details.

| Task | Model | Metric | Value | Notes |
|---|---|---|---|---|
| Fire power | XGBoost (compact 200t) | R² | **0.862** | Retrained on 50-opponent data |
| Movement N=5 | GBM-window (compact) | R² | **0.866** | Retrained on 50-opponent data |
| Fire timing (3-tick) | GBM-window (compact) | AUC | **0.855** | Retrained on 50-opponent data |
| Fire power | XGBoost (full 800t) | R² | **0.960** | Reference; not distilled |
| GF targeting | MLP [16→128²→64→61] | ±3 bins | **0.570** | Deferred (data-starved) |

**Key insight:** 20-tick sliding window features are the single most important
innovation. Without them, R² drops from 0.87 → 0.07.

---

## Next Milestones

### Phase 12: Fix Broken Systems (PRIORITY — blocks all downstream work)

1. **Fix Java/Python feature parity** — fire power model R²=−0.61 in-game
   due to feature mismatch. Diagnose sliding-window divergence between
   Java and Python. Target: in-game R² ≥ 0.5.
2. **Fix gun ordering in VGM** — HeadOnGun at 54% contradicts Decision #10.
   CircularGun must be first in list. Tune epsilon threshold.
3. **Fix movement velocity** — only 64% at max speed, 54.5% high lateral.
   Reduce unnecessary direction changes (currently 11.3% of ticks).

### Phase 13: Online Learning & Adaptation (deferred until Phase 12 complete)

- **Bayesian blending**: MLP prior + VCS online via λ = K/(K+n) mixing
- **Per-family GF priors** loaded from resource files after name-hash identification
- **Adaptation detector** (KS-distance) for mid-battle strategy switching
- **GF flattening**: intentionally randomize dodge direction to make our
  GF profile harder to learn (anti-profiling defense)

### Phase 14: Competition & Iteration

- Enter LiteRumble / submit to RoboRumble
- More battle seasons for training data
- Per-opponent policy tuning (currently ~2 battles per pair, need 10+)

---

## Architecture Reference

See [wiki/architecture.md](wiki/architecture.md) for the full architecture document
(boot sequence, tick flow, subsystems, feature map, ML models, known issues).

---

## Key Decisions Log

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Java 8 target | Required for Robocode engine classloader |
| 2 | 50-bot rumble | Broad competitive coverage |
| 3 | Stateless features | All inter-tick state in Whiteboard |
| 4 | Observable-only discipline | No god-view data in features |
| 5 | 20-tick sliding windows | Key innovation for all ML tasks |
| 6 | GBM-window as default model | Simpler distillation, strong baselines |
| 7 | Base64-embed models, no file I/O | Robocode sandbox blocks getResourceAsStream |
| 8 | Compact 200-tree models | 500KB budget; full 800-tree too slow |
| 9 | Orbit-primary movement | Constant wave surf oscillation hurts; orbit + imminent-wave dodge is better |
| 10 | CircularGun as primary | Best general-purpose gun; HeadOnGun demoted to lowest priority |
| 11 | TickBudget upward recovery | One-way ratchet previously crippled models to 5% capacity |
| 12 | Position advantage useless | R²=0.001 in nb16, dropped from robot |
| 13 | Fix broken systems before new features | Fire power R²=−0.61 in-game, gun selection bugged, movement too slow |

---

## Process Reference

See [sprint.md](sprint.md) for the sprint cycle
(plan → build → battle → diagnose → retrospective → revert/commit → repeat).

## Documentation Index

- **Architecture:** [wiki/architecture.md](wiki/architecture.md) — Full system documentation
- **Wiki:** [wiki/](wiki/) — Knowledge base (physics, features, leakage, ML results, pipeline, strategy)
- **Archive:** [archive/](archive/) — Historical planning documents with date prefixes
- **Intuition notebooks:** [intuition/](intuition/) — Jupyter notebooks with inline analysis
- **Retrospective notebooks:** [intuition/retrospective/](intuition/retrospective/) — Per-sprint analysis
- **Sprint process:** [sprint.md](sprint.md) — Sprint cycle rules and diagnostic checklist
- **Team:** [team.md](team.md) — Roles, skills, artifact ownership
- **Copilot instructions:** [.github/copilot-instructions.md](.github/copilot-instructions.md) — Coding conventions
