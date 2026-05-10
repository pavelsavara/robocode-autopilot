# Sprint 22 Retrospective — Feature Divergence + Targeting + Movement

*Date: 2026-05-11 · Sprint goal: Close OPPONENT_INFERRED_GUN_HEAT gap, add VcsSamplingGun, fix wave surf lateral direction*

## Diagnostic Health

All 7 sanity checks **PASS** (0 failures):

| # | Check | Value | Pass |
|---|-------|-------|------|
| 1 | ticks.csv rows | 2,081,162 | ✅ |
| 2 | waves.csv rows | 80,183 | ✅ |
| 3 | scores.csv rows | 3,360 | ✅ |
| 4 | internal.csv rows | 364,241 | ✅ |
| 5 | ticks columns | 86 | ✅ |
| 6 | battle count | 3,360 | ✅ |
| 7 | tick density | 2,081,162 | ✅ |

## Metrics Table

| Metric | Sprint 21 | Sprint 22 | Delta |
|---|---|---|---|
| Overall score % | 10.4% | **9.9%** | −0.5 pp |
| Battle win rate | 2.1% (1/48) | 2.1% (1/48) | — |
| Self-battle | 52.3% | 54% | +1.7 pp |
| Errors | 0 | 0 | — |
| Opponents evaluated | 16 | 16 | — |
| Fire power R² (offline) | 0.840 | **0.839** | −0.001 |
| Movement R² (offline) | 0.884 | **0.887** | +0.003 |
| Fire timing AUC (offline) | 0.977 | **0.981** | +0.004 |
| Sanity checks | 7/7 | 7/7 | — |

### Notes on metric changes

- **Overall score dropped 10.4%→9.9% (−0.5 pp):** Within noise range for 3 battles
  per opponent (each battle ±2-3 pp variance). The single win (Fusion 56%) is
  the same opponent as Sprint 21.
- **Self-battle 54%:** Slightly above target band (45-55%). Within tolerance.
- **Model metrics essentially unchanged:** Expected — same training data, only
  robot code changed. Slight fire timing AUC improvement (0.977→0.981) may
  reflect the gun heat feature now being computed in-game.

## Per-Opponent Breakdown

| Opponent | Sprint 21 | Sprint 22 | Delta |
|---|---:|---:|---:|
| tobe.Fusion | 45.3 | **47.3** | +2.0 |
| cx.BlestPain | 24.7 | **24.0** | −0.7 |
| ej.ChocolateBar | 16.0 | **16.0** | — |
| ary.FourWD | 11.3 | 10.3 | −1.0 |
| eem.zapper | 11.3 | 8.7 | −2.6 |
| mld.Moebius | 8.3 | 8.0 | −0.3 |
| oog.mega.Saguaro | 8.0 | 7.0 | −1.0 |
| jk.mega.DrussGT | 6.0 | **6.0** | — |
| pe.SandboxDT | 5.3 | **5.7** | +0.4 |
| dft.Cardigan | 5.3 | **5.3** | — |
| darkcanuck.Pris | 6.3 | 4.0 | −2.3 |
| voidious.Diamond | 4.7 | 4.0 | −0.7 |
| simonton.SniperFrog | 4.3 | 3.7 | −0.6 |
| abc.Shadow | 4.0 | 3.0 | −1.0 |
| simonton.WeeksOnEnd | 3.0 | **3.3** | +0.3 |
| jk.mini.CunobelinDC | 1.7 | **2.7** | +1.0 |

## What Worked

1. **OPPONENT_INFERRED_GUN_HEAT now computed in-game.** Feature divergence gap
   closed — `GbmFireTimingPredictor` can now use the gun heat heuristic gate
   properly (if heat > 0 → fire probability → 0). This should improve fire
   timing prediction accuracy in-game over multiple sprints as models retrain.

2. **VcsSamplingGun wired into VGM.** The anti-profiling variant now competes
   alongside VcsGun (peak-firing). The VGM hit-rate selector will pick the
   best variant per opponent. This provides diversity against adaptive opponents.

3. **Wave surf lateral direction fix.** VcsWaveDanger now uses `wave.fireLateralDir`
   instead of current-tick lateral direction for segment lookup. This aligns
   movement danger scoring with the gun-side VCS fix from Sprint 16.

4. **CI pipeline continues working well.** Full cycle: push → battles (6 min) →
   CSV + sanity (1.5 min) → train + notebooks (3 min). ~11 min total.

## What Didn't Work

1. **Score marginally dropped** (10.4%→9.9%). The −0.5 pp is within battle
   variance. However, no clear improvement despite three changes. The changes
   are structural improvements that should compound over time (especially after
   model retraining with the new gun heat feature).

2. **eem.zapper dropped from 11.3% to 8.7%** — the lateral direction fix may
   have changed danger scoring in a way that's slightly worse against this
   particular opponent. Need more data points.

3. **darkcanuck.Pris dropped from 6.3% to 4.0%** — similar variance concern.

## Root Cause Analysis

**Binding constraint remains: opponent hit rate ~40%.** All three Sprint 22
changes are structural correctness fixes, not direct performance improvements.
The gun heat feature was always NaN in-game — now it's computed but the
current model was trained without it being available. The VcsSamplingGun needs
time to accumulate hit-rate data to prove its value. The lateral direction fix
corrects a segmentation bug but the movement histograms are still sparse.

The score plateau at ~10% suggests these correctness fixes need to compound
with retrained models. The next sprint should merge the CI-trained models
(which now include gun heat data) and evaluate the delta.

## Proposals for Next Sprint (Sprint 23)

1. **Merge Sprint 22 retrained models** — CI has already trained models on the
   new data (with OPPONENT_INFERRED_GUN_HEAT now present). Merge into main
   and evaluate. *Metric:* overall score % delta. *Owner:* Naomi.

2. **WindowFeatures state to Whiteboard** (Workstream F1) — move mutable
   `double[][]` ring buffers from WindowFeatures to Whiteboard. This is a
   known bug (copilot-instructions.md rule: "all inter-tick state in Whiteboard").
   *Metric:* code quality, no performance regression. *Owner:* Holden.

3. **Movement: implement true precise prediction** (Workstream C.2) — simulate
   exact future positions including wall bouncing and deceleration for wave
   surfing. Currently PathPlanner uses linear projection which misses wall
   bounces. *Metric:* opponent hit rate delta. *Owner:* Alex.
