# Sprint 23 Retrospective — Movement: Always-PathPlanner (REVERTED)

*Date: 2026-05-11 · Sprint goal: Break plateau with movement improvement*

## Diagnostic Health

All 7 sanity checks **PASS** (0 failures). Self-battle 55.7% — above target band.

## Metrics Table

| Metric | Sprint 22 | Sprint 23 | Delta |
|---|---|---|---|
| Overall score % | 10.0% | **9.5%** | −0.5 pp |
| Battle win rate | 0/48 | 0/48 | — |
| Self-battle | 52.3% | **55.7%** | +3.4 pp ⚠️ |
| Errors | 0 | 0 | — |

## What Didn't Work

**Always-PathPlanner movement caused regression.** Changed WaveSurfMovement to
use PathPlanner for ALL active waves instead of only imminent ones (< 12 ticks).
Removed the VCS-guided orbital direction code.

**Root cause of failure:** The previous imminent-only approach was actually a form
of anti-profiling — the robot moved randomly/orbitally most of the time and only
activated precise dodging in the critical 12-tick window. The new approach made
the robot PREDICTABLE because it constantly positioned toward the "safest" spot
according to sparse VCS histograms. Pattern-matching guns exploit predictable
movement.

**Self-battle 55.7%** suggests the change made one side consistently better at
predicting the other — a sign of increased predictability.

## Decision

**REVERTED** per sprint process (revert what hurts). Sprint 23 code rolled back.

## Key Learning

Random unpredictable movement > pattern-optimized movement when:
1. VCS data is sparse (3 battles per opponent = ~150 wave observations)
2. Opponents have stronger pattern-matching guns than our danger model
3. The robot is the weaker player overall

The imminent-wave-only design is actually sound for our skill level. Future
movement improvements should focus on:
- Better wall avoidance (the robot sometimes gets cornered)
- More VCS segments (current 12 distance × 2 lateral = 24)
- Anti-profiling via occasional random direction choices during dodge

## Proposals for Sprint 24

1. **Targeting: histogram smoothing for VcsGun** — kernel density estimation
   on VCS histograms should improve aim accuracy over the current raw bin
   counts. *Metric:* our hit rate delta. *Owner:* Bobbie.

2. **Targeting: use predicted fire power in bullet speed** — VcsGun and
   CircularGun currently assume power 2.0 for wave speed. Using ML fire
   power prediction should improve wave matching. *Metric:* our hit rate.
   *Owner:* Bobbie.

3. **Code quality: FeatureMappingTest (T2 from backlog)** — pins the
   23-NaN-features bug class from Sprint 10. *Owner:* Test author.
