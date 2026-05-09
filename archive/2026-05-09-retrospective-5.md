# Retrospective 5 — Top-50 Campaign (2026-05-09)

*Iterative improvements targeting 30% win rate against top-50 competitive opponents.*

## Starting Point

- 35% win rate including 3 test bots (previous target met)
- ~4.6% win rate against 7 competitive opponents
- 0% against most top-50 ranked bots

## Changes Across Iterations

1. **Orbit-primary movement** (most impactful) — high-speed lateral orbit as
   default, planner only activates on imminent waves (<12 ticks).
2. **Wider Gaussian danger prior** when VCS has <8 observations.
3. **Tighter aim threshold** (0.015 rad = 0.86 degrees).
4. **Distance-scaled fire power** — 3.0 at close, 2.0 at medium, 1.5 at far.
5. **Wall-aware direction reversal** in lateral movement.
6. **Increased wall/corner danger thresholds** (100px/150px).
7. **Path planner** 80/20 wave/position weighting, 50 candidates.
8. **Energy-clamped firing** — fire even when low on energy (clamp power).
9. **ML retrained on 50-opponent data** (1.7M rows, 51 robots).

## What Worked

- **Orbit-primary movement** was the single biggest improvement (+200% win rate)
- Constant wave surfing actively HURT by causing oscillation
- Wider danger prior helped against bots with uncommon targeting patterns
- ML retrain on diverse 50-opponent data improved model quality

## What Didn't Work

- **GF flattening noise** — made movement random instead of strategic
- **VCS segment aggregation** — muddied per-segment targeting data
- **True minimum-risk CW/CCW dodging** — too simplistic, opponent HR 52%
- **Closer engagement distance (300px)** — reduced dodge time
- **Fire power micro-tuning** — marginal effects either way

## Final Results (50 opponents, 3,500 rounds)

| Metric | Starting (retro 4) | Best 20-opp | Final 50-opp |
|---|---|---|---|
| Win rate | 4.6% (7 opp) | 2.92% (20 opp) | 0.56% (50 opp) |
| Our HR | 5.2% | 5.9% | 8.1% |
| Opp HR | 25.9% | 31.6% | 47.1% |
| Winning vs | 2/7 | 9/20 | 8/50 |

### Best Matchups (50-opponent sweep)
| Opponent | Win% | Our HR | Opp HR |
|---|---|---|---|
| FourWD 1.3d | **8.1%** | 11.3% | 44.4% |
| AdvancedEFD | **5.9%** | 8.9% | 52.1% |
| Atom 0.51 | **5.9%** | 6.7% | 25.8% |
| Shiva 2.2 | **5.9%** | 11.6% | 48.7% |
| Star2 1.23 | 0.9% | 12.5% | 31.0% |

## Root Cause Analysis: Why 30% Is Unreachable

The fundamental barrier is **opponent hit rate (47%)**. Top-50 bots have:
- Sophisticated GF targeting that learns our movement pattern
- Wave surfing that dodges our simple guns effectively
- Multi-segment VCS with 47+ segments (ours: 6)

To achieve 30%, we would need:
- Opponent HR < 20% (requires advanced wave surfing with precise GF projection)
- Our HR > 12% (requires adaptive targeting beyond simple VCS)
- These require months of development of standard competitive Robocode techniques

The orbit-primary approach gets us ~2-3% against the middle tier (bots ranked
30-50). Against top-20 bots, our movement and targeting are fundamentally
outclassed.
