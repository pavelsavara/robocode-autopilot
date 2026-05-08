# Retrospective 3 — Movement & Energy Improvements (2026-05-08)

*Iteration series after retrospective 2. Multiple code changes to movement,
fire power, and wave danger model, followed by ML retrain.*

## Changes Since Retrospective 2

1. **Wave surf direction commitment** — target angle held for 5 ticks minimum,
   re-evaluated only when wave count changes. Eliminates per-tick oscillation
   that kept the robot below max velocity.
2. **Default lateral movement** — always orbit perpendicular to opponent when
   no waves are active (was: stop and wait).
3. **Max-speed ahead values** — all movement commands use |ahead|=150 to maintain
   8 px/tick velocity (was: clamped to target distance, avg 39 px).
4. **Gaussian wave danger prior restored** — flat prior was tested and proven
   worse (39% opp HR vs 33%). Gaussian at GF=0 is correct for dodging head-on.
5. **VCS gun histogram prior** — initialized with Gaussian at GF=0 (strength=3)
   so VCS gun has reasonable aim before observing actual hits.
6. **Distance-based fire power** — close range: 2.5, medium: 2.0, far: 1.5.
   Higher power maximizes damage per hit; distance adjustment preserves accuracy.
7. **Energy conservation** — never fire more than 1/3 of our energy; fire at
   0.5 when critically low (<5 energy).
8. **Gun ε-greedy exploration** — 3% random gun after 30 data points (was: 10%
   always). Reduces noise in gun selection.
9. **OUR_DIST_TO_WALL_MIN computed in-game** — was only in pipeline offline
   features. Now computed in core PositionFeatures for wall avoidance.
10. **OUR_LATERAL_VELOCITY computed in-game** — likewise moved to core.
11. **Wider opponent set** — 10 opponents from weak to strong (was: 5 elite bots).
12. **ML models retrained** on new battle data (3 rounds, 10 opponents).

## Test Setup

- **Robot version:** Autopilot 0.1.0 (iteration 10)
- **Opponents (10):** ary.micro.Weak, ary.Help, da.NewBGank, fromHell.BlackBox,
  darkcanuck.Holden, florent.FloatingTadpole, kid.Gladiator,
  rdt.AgentSmith.AgentSmith, ab.DengerousRoBatra, ags.Glacier
- **Battles:** 30 (3 per opponent), 35 rounds each
- **Total rounds analyzed:** 1,050

## Overall Results

| Metric | Retrospective 2 | This Run | Change |
|---|---|---|---|
| Win rate | 0.0% | **2.76%** | +2.76% |
| Our hit rate | 3.3% | **4.0%** | +0.7% |
| Opponent hit rate | 42.4% | **27.8%** | -14.6% |
| Dmg dealt/round | 3.3 | **8.7** | +5.4 |
| Dmg received/round | 83.8 | **65.2** | -18.6 |
| Avg ticks survived | 506 | **574** | +68 |

### Per-Opponent Breakdown

| Opponent | Rounds | Win% | Our HR | Opp HR | Dmg Dealt | Dmg Recv |
|---|---|---|---|---|---|---|
| FloatingTadpole 1.2.6 | 105 | **9.9%** | 9.7% | 24.9% | 22.4 | 68.2 |
| Gladiator .7.2 | 105 | **6.5%** | 5.1% | 22.7% | 13.9 | 56.7 |
| NewBGank 1.4 | 105 | **5.6%** | 5.0% | 25.6% | 8.9 | 72.3 |
| Glacier 0.3.2 | 105 | **5.2%** | 1.7% | 29.0% | 4.5 | 64.1 |
| Weak 1.2 | 105 | 0.4% | 2.6% | 27.0% | 6.4 | 59.3 |
| DengerousRoBatra 1.3 | 105 | 0% | 3.8% | 26.8% | 7.6 | 68.2 |
| Help 1.0 | 105 | 0% | 4.6% | 30.5% | 9.0 | 67.7 |
| Holden 1.13a | 105 | 0% | 3.6% | 34.0% | 6.2 | 65.9 |
| AgentSmith 0.5 | 105 | 0% | 2.4% | 26.8% | 5.6 | 61.5 |
| BlackBox 0.0.2 | 105 | 0% | 1.7% | 30.3% | 2.9 | 68.2 |

## Key Findings

### What Worked
1. **Direction commitment** — single biggest movement improvement. Opponent HR
   dropped from 33% to 28% by holding course for 5 ticks.
2. **Max-speed movement** — using ahead=150 instead of clamping to target
   distance ensures we always move at 8 px/tick.
3. **Higher fire power** — distance-based 1.5-2.5 power deals 2.6x more damage
   per round than power 1.0 (iteration 1).

### What Didn't Work
1. **Flat wave danger prior** — 39% opp HR vs 33% with Gaussian. Without
   directional bias, the wave surf doesn't dodge at all.
2. **Orbital movement** — 81% opponent HR (catastrophic). Pure circular paths
   are extremely predictable for any targeting system.
3. **Very high fire power (3.0 constant)** — reduced hit rate from 5.9% to
   4.3% because slower bullets are easier to dodge. Distance adjustment helps.
4. **10% gun exploration** — too aggressive, reduced hit rate without benefit.

### Iteration Progression
| # | Key Change | Win% | Opp HR | Dmg Dealt |
|---|---|---|---|---|
| 1 | Base (retro2 code + weak bots) | 0.5% | 45.4% | 5.6 |
| 2 | Max speed + energy conservation | 1.6% | 33.2% | 6.9 |
| 3 | Wall dist fix + 3% exploration | 0.6% | 32.8% | 7.1 |
| 4 | Orbital primary (reverted) | 0% | 80.9% | 4.4 |
| 5 | Flat wave danger (reverted) | 0.2% | 39.3% | 6.2 |
| 6 | VCS prior init | 0.5% | 34.9% | 6.8 |
| 7 | 5-tick direction commitment | 0.2% | 28.0% | 8.6 |
| 8 | Power 2.0+ base + retrain | 1.75% | 27.8% | 9.6 |
| 9 | Distance-based power + retrain | 1.2% | 27.8% | 7.3 |
| 10 | Balanced distance power | **2.76%** | 27.8% | 8.7 |

## Root Cause Analysis

The robot is still losing because:
1. **4% hit rate** — all opponents are competitive bots with effective dodge
   systems. Our targeting (linear, circular, VCS) isn't sophisticated enough.
2. **28% opponent HR** — wave surfing reduces hits significantly (from 45% at
   baseline) but 28% is still high. Top competitive wave surfers achieve 10-15%.
3. **3:1 damage imbalance** — at 8.7 dealt vs 65.2 received, we need 7.5x
   more damage or 7.5x better dodging to break even.

## Next Steps (Priority Order)

1. **Improve circular gun accuracy** — the circular gun simulation may have
   issues with deceleration near walls. Add wall-clamp to the simulation.
2. **Add stop-and-go movement** — randomly decelerating confuses timing-based
   targeting. Most effective against linear/circular guns.
3. **Increase wave surf commitment** — try 8-10 tick commitment periods.
4. **Retrain on combined rumble + local data** — the ML models trained only
   on local battles (10 opponents). Adding the full rumble dataset (50+ bots)
   should generalize better.
5. **Add truly weak opponents** — create a simple robot JAR (SittingDuck,
   SpinBot) to validate the robot can win against trivial opponents.
