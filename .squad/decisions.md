# Squad Decisions

## Active Decisions

### 2026-05-09: Retrospective 6 — Post-Fix Code Audit
**By:** Holden (Lead)
**What:** Created retrospective-6 documenting full team code audit before first post-TickBudget-fix evaluation. Identified 9 critical findings including dead pre-emptive dodge code, VCS lateral direction bugs, and only 6 VCS segments. Team consensus: run evaluation first, then prioritize fixes.
**Why:** Team code review revealed multiple systemic issues that need baseline measurement before any changes are made.

### 2026-05-09: Sprint Close — Retrospective 7
**By:** Holden (Lead)
**Sprint result: BLOCKED**
Sanity check #3 failed (gun selection). Two of three ML models are broken in-game. The robot is running at ~5% of its designed targeting capability despite TickBudget fix confirming full model capacity.
**Evidence:**
- TickBudget fix WORKS: budget 100–200 trees, score 10× improved (0.56% → 5.4%)
- Fire power model: offline R²=0.862, in-game R²=−0.61
- Fire timing model: predicts fire 83% of ticks, actual fire rate 3%
- HeadOnGun selected 54% despite Decision #10 demoting it
- Movement at max speed only 64% of ticks
**Key decisions:**
1. Phase 12 repurposed to "Fix Broken Systems" — online learning deferred
2. Three blocking priorities: feature parity, gun ordering, movement velocity
3. Decision #13: fix broken systems before new features

## Governance

- All meaningful changes require team consensus
- Document architectural decisions here
- Keep history focused on work, decisions focused on direction
