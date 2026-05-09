# Retrospective 6 — Post-Fix Code Audit (Pre-Evaluation)

*Date: 2026-05-09 · Sprint: Pre-evaluation code audit*
*Previous: [2026-05-09-retrospective-5.md](2026-05-09-retrospective-5.md)*

## Context

This retrospective captures findings from a full team code audit conducted before
the first evaluation with the TickBudget fix deployed. All previous evaluations
(retrospectives 1-5) ran ML models at 5% capacity (10/200 trees). This evaluation
will be the first at full model capacity.

## Diagnostic Health

All previous metrics are suspect due to the TickBudget ratchet bug. This audit
focused on code quality, dead code, and wiki accuracy rather than performance numbers.

## Critical Findings

### 1. Dead Code: Pre-emptive Dodge (Alex — Movement)

`WaveSurfMovement.getDodgeScale()` and `preemptiveLateralMove()` are defined but
**never called** from `getCommand()`. The fire timing model (AUC=0.855, ~410 KB
Base64 data) runs every tick but its output is discarded by movement. Also:
`COMMIT_TICKS`, `committedAngle`, `commitTick` fields are declared but never used.

**Impact:** The robot cannot pre-emptively dodge before detecting an energy drop.
~2-3 ticks of reaction time wasted at close range.

### 2. VCS Lateral Direction Bug (Alex + Bobbie — Movement + Targeting)

**Movement VCS (`VcsWaveDanger`):** Uses `OPPONENT_LATERAL_DIRECTION` instead of
OUR lateral direction relative to the opponent. Uses current-tick value instead of
fire-time value. Corrupts dodge danger scoring.

**Gun VCS (`Whiteboard.prunePassedWaves`):** Uses `opponentVelocity >= 0 ? 1 : -1`
at wave-break time instead of storing lateral direction at fire time. Corrupts
targeting histograms by recording observations in wrong segments.

**Impact:** Both movement and targeting VCS histograms are systematically corrupted.
This may explain why VcsGun underperforms CircularGun despite having persistence data.

### 3. Virtual Bullet Hit Check (Bobbie — Targeting)

`VirtualGunManager` checks wave passage against fire-time distance, but the opponent
has moved since fire time. At 500px with 45-tick flight, the opponent could be 360px
away. VGM selection signal is systematically inaccurate.

### 4. Simplified Wall Physics (Alex — Movement)

`RobotPhysics.step()` clamps position to walls but does NOT zero velocity on wall
collision. `PrecisePredictor` and `ReachableEnvelope` overestimate movement range
near walls.

### 5. PredictiveGun Staleness (Bobbie — Targeting)

`PredictiveGun` applies a 5-tick ML prediction for the entire bullet flight (up to
50+ ticks). Beyond ~10 ticks the prediction is stale, limiting effectiveness at
medium-to-long range.

### 6. Stale Javadoc Metrics (Naomi — ML)

All three predictor Javadocs show metrics from an older training run:
- `GbmFirePowerPredictor`: says R²=0.906, actual 0.862
- `GbmMovementPredictor`: says R²=0.739, actual 0.866
- `GbmFireTimingPredictor`: says AUC=0.773, actual 0.855

### 7. Only 6 VCS Segments (Bobbie — Targeting)

Current: 3 distance × 2 lateral direction = 6 segments.
Top bots: 5-7 dimensions, 36+ segments. This is the single biggest targeting gap.

### 8. No Automated Sanity Script (Amos — Systems)

Sprint.md documents this as a Systems Engineer deliverable but it doesn't exist.
The TickBudget catastrophe persisted across 5 retrospectives partly because checks
were manual and incomplete.

### 9. Feature Order Coupling (Naomi — ML)

No cross-validation that Java and Python produce identical predictions on real tick
data. Window feature computation differs (Python pandas `rolling()` with group
boundaries vs Java `RingBuffer` carrying state across rounds).

## Wiki Accuracy Issues

| Page | Issue |
|---|---|
| architecture.md §4d | Describes pre-emptive dodge and commit ticks as working — they're dead code |
| architecture.md §5c | Says `PREDICTED_OPPONENT_FIRES_3` consumed by movement — it's not |
| strategy.md | Claims orbit-primary gives 28% opponent HR — current measured is 47% |
| ml-results.md | Fire power R² reported as 0.931 (full) in detail section, 0.862 (compact) in summary — confusing |
| pipeline.md | Missing local pipeline instructions (local-pipeline.ps1 path) |
| R08 notebook | Gun index mapping reflects OLD ordering before sprint 11 reordering |

## Proposals for Next Sprint (Priority Order)

### Immediate (this evaluation)
1. **Run clean post-fix evaluation** — first honest baseline at full model capacity

### Small/Quick Wins (next sprint, can combine)
2. **Wire pre-emptive dodge** — connect existing dead code, +5-10% opponent HR improvement expected
3. **Fix VCS lateral direction bugs** — both movement and gun VCS, +1-2% hit rate
4. **Fix predictor Javadoc metrics** — update to match current training results
5. **Build automated sanity script** — prevent systemic bugs from persisting

### Medium (dedicated sprint)
6. **Expand VCS segments from 6 to 24+** — +3-5% our hit rate expected
7. **Cap PredictiveGun extrapolation at ~10 ticks** — simple, low-risk improvement
8. **Add build manifests for traceability** — pipeline reliability

### Large (sole sprint item)
9. **True CW/CCW wave surfing** — replace random envelope sampling, target 25-30% opponent HR
10. **GF flattening defense** — requires #9 first, target <20% opponent HR

## Team Consensus

All five specialists independently identified the same #1 priority: **run a clean
evaluation before making any code changes.** The TickBudget fix alone may
significantly change the performance picture. All other proposals are premature
until we have real numbers at full model capacity.
