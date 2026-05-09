# Decision: Close Sprint 8 — MISS

**Author:** Holden (Lead)
**Date:** 2026-05-09
**Sprint:** 8

## Decision

Sprint 8 is closed with result **MISS**. Target metrics not met.

## Rationale

**What was targeted:**
- Fire power in-game R² from −0.61 → ≥ 0.5
- Gun selection: HeadOnGun from 54% → < 20%
- Movement: max speed ticks from 64% → ≥ 85%

**What happened:**
- Fire power in-game R² degraded to −3.46 (offline improved 0.862→0.946)
- Gun selection worsened to 67%
- Movement results mixed (some opponents +4.7 pp, others −4.0 pp)
- Overall score 5.4%→5.1% (−0.3 pp)

**What was delivered (positive):**
- 4 code fixes merged (all reviewed and approved)
- 17 new unit tests
- Automated sanity-check script (6 mandatory + 3 bonus checks)
- Offline model quality improved
- Process gap (skipped review gate) identified and corrected
- Fire timing calibration improved (83%→59%)

## Key Finding

The binding constraint is Java/Python feature divergence at inference time.
The training pipeline fix made the offline model more accurate, but the
retrained model is now MORE sensitive to remaining in-game feature mismatches.
This is the classic "better model, worse deployment" pattern — the model
learned features the Java runtime doesn't reproduce faithfully.

## Next Sprint Direction

Sprint 9 must be a **diagnostic sprint** — no new features. Three proposals:
1. Feature-by-feature Java/Python comparison with logging (Naomi + Amos)
2. Gun tie-break priority fix (Bobbie)
3. Isolated movement evaluation to confirm net impact (Alex)

## Retrospective

Filed as [archive/2026-05-09-retrospective-8.md](../../archive/2026-05-09-retrospective-8.md).
