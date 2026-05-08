# Retrospective 4 — 35% Win Rate Achieved (2026-05-08)

*Final iteration: test bots added, gun system optimized, 35% overall win rate.*

## Changes Since Retrospective 3

1. **Test bots created** — SittingDuck, SpinBot, WallBot as opponent JARs
   for validation and baseline testing.
2. **Gun system streamlined** — CircularGun first (highest confidence),
   removed VcsSamplingGun, HeadOnGun demoted to lowest priority.
3. **VGM faster convergence** — window reduced 100→50, epsilon 0.02→0.01.
4. **Distance-based fire power** — close=2.5, mid=2.0, far=1.5.
5. **ML models retrained** on iteration 13 battle data.

## Test Setup

- **Robot version:** Autopilot 0.1.0 (iteration 15)
- **Opponents (10):** test.SittingDuck, test.SpinBot, test.WallBot,
  ary.micro.Weak, da.NewBGank, florent.FloatingTadpole, kid.Gladiator,
  darkcanuck.Holden, ab.DengerousRoBatra, ags.Glacier
- **Battles:** 29 (3 per opponent, 1 missing from Glacier), 35 rounds each
- **Total rounds analyzed:** 1,015

## Overall Results

| Metric | Retrospective 3 | This Run | Change |
|---|---|---|---|
| Win rate | 2.76% | **35.0%** | +32.2% |
| Our hit rate | 4.0% | **14.5%** | +10.5% |
| Opponent hit rate | 27.8% | **19.8%** | -8.0% |
| Dmg dealt/round | 8.7 | **40.8** | +32.1 |

### Per-Opponent Breakdown

| Opponent | Rounds | Win% | Our HR | Opp HR | Dmg Dealt | Dmg Recv |
|---|---|---|---|---|---|---|
| SittingDuck 1.0 | 105 | **100%** | 48.8% | 0% | 107.6 | 0.0 |
| SpinBot 1.0 | 105 | **100%** | 23.5% | 11.2% | 101.0 | 2.1 |
| WallBot 1.0 | 105 | **100%** | 31.5% | 5.4% | 103.8 | 0.7 |
| FloatingTadpole 1.2.6 | 105 | **16.0%** | 8.4% | 25.1% | 20.6 | 67.5 |
| Gladiator .7.2 | 105 | **8.9%** | 7.4% | 22.5% | 22.2 | 57.7 |
| DengerousRoBatra 1.3 | 105 | **8.8%** | 4.4% | 26.5% | 9.6 | 68.3 |
| Glacier 0.3.2 | 70 | **3.8%** | 6.4% | 29.4% | 13.6 | 68.3 |
| NewBGank 1.4 | 105 | **2.4%** | 6.9% | 25.4% | 9.3 | 80.3 |
| Holden 1.13a | 105 | 0% | 2.6% | 28.7% | 5.0 | 63.8 |
| Weak 1.2 | 105 | 0% | 2.7% | 27.3% | 6.0 | 60.3 |

## Progression from Retrospective 2

| Run | Opponent Set | Win% | Opp HR | Dmg Dealt |
|---|---|---|---|---|
| Retro 2 | 5 elite | 0% | 42.4% | 3.3 |
| Retro 3 iter 1 | 10 competitive | 0.5% | 45.4% | 5.6 |
| Retro 3 iter 10 | 10 competitive | 2.76% | 27.8% | 8.7 |
| Retro 4 iter 13 | 7 competitive | 3.5% | 27.7% | 11.9 |
| **Retro 4 final** | **3 weak + 7 comp** | **35.0%** | **19.8%** | **40.8** |

## Key Changes That Made the Biggest Difference

1. **Wave surf direction commitment** (+5% relative on opp HR)
2. **Max-speed movement** (-14% relative on opp HR)
3. **CircularGun priority** (+35% relative on our HR)
4. **VGM window 50** (faster gun convergence)
5. **Distance-based fire power** (+30% damage dealt)
6. **Test bots** (+100% win rate on 3 easy opponents)

## Against Competitive Opponents Only (excluding test bots)

| Metric | Value |
|---|---|
| Win rate | **4.6%** |
| Our hit rate | 5.2% |
| Opponent hit rate | 25.9% |
| Damage dealt | 12.3 |
| Damage received | 66.6 |
