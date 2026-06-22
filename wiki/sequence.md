# Sequence hypothesis - does recent context predict a wave's aim (and outcome)?

_Generated 2026-06-22 13:30 UTC from `pipeline/build/intuition-run/1782133800396-1782133800396`. Deterministic (seed 1234567). `cz.zamboch.Autopilot` excluded as host and opponent._

## What is tested

Each wave is described by context known **at the fire tick only** (no look-ahead) and scored two ways, per opponent:

- **Primary - aim (continuous).** Predict the wave's eventual **break GuessFactor** (`own_break_gf`) - *where* on the escape envelope the victim ends up. This is the quantity hypothesis C8 says is auto-correlated; it is measured by Pearson `r` / `R^2`, not bits.
- **Secondary - outcome (binary, noisy).** Predict whether the wave **hits** (offense `our_break_hit`) or **hits us** (defense `their_hit_us`). At a ~10% base rate against top guns this label is dominated by irreducible residual dodge, so it is reported with hit-rate lift + Wilson 95% CIs, never as a sole verdict.

Anchors: offense = the perspective bot's real wave (`dejavu-waves.csv`); defense = the opponent's wave (`their-waves.csv`). This run is a **round-robin of competitive bots** (`cz.zamboch.Autopilot` excluded as host and opponent), so "we"/"our" is whichever competitive bot owns the perspective and "victim" is its opponent - there is no metronome gun in the analysed data.

**Leakage guard.** Measured fire cadence (~14 ticks) < bullet flight (~34 ticks), so when a wave is fired the previous wave has not broken yet - its break GF is a future value (leak). Two leakage-safe predictors replace it: `developing_gf` (the previous still-in-flight wave's GF re-evaluated at the current fire tick - a faithful lag-1 proxy) and `online_broken_mean` (running mean of every wave already broken, which is ~3 fires stale because cadence is much shorter than flight). The raw fire-order lag-1 break-GF autocorrelation is reported only as the C8 reference, clearly flagged as leaky.

**Reconstruction self-check.** `developing_gf` evaluated at the break tick reproduces the recorded break GF to max abs error **2.00e+00** over 142,754 offense waves.

## Scenarios tested (S1-S4 observational; S5 deferred)

These are sophisticated competitive guns (DrussGT, Diamond, Knight, BeepBoop, ScalarR), not a fixed metronome: fire cadence varies (gap std ~1.7 ticks) and bullet power varies (often low for energy management, but with a tail to full power), so `inter_fire_gap` and `power_delta` are genuine - if weak - axes, not degenerate ones. The dominant structured signal is the **wave-stacking GF** the primary test targets; the opponent's gun-heat clock and engagement geometry are secondary. Every per-opponent figure is the same 2x2 panel; the scenarios map onto it as follows.

- **S1 - Opponent gun-heat phase (sync hot vs cold).** Does firing when the victim's gun is cold (heat ~0, it can retaliate at once) versus hot (just fired, long until its next shot) change our hit / get-hit rate? Combines the original sync-to-gun-heat-HOT and sync-to-gun-heat-COLD ideas. _Graph: top-right_ - outcome vs victim gun-heat phase curve (dotted line = that opponent's marginal rate).
- **S2 - Clock x geometry.** Does the gun-heat-phase effect depend on range - is a cold victim only exploitable up close? _Graph: bottom-left_ - offense hit-rate heatmap, victim gun heat (x) by distance (y).
- **S3 - Wave-stacking / developing GF (primary).** With 2-3 waves always in flight, does how the victim is *already* dodging the previous in-flight wave (`developing_gf`, leakage-safe) predict the next wave's **break GF**? This is the observable, online face of C8's GF autocorrelation. _Graph: bottom-right_ - developing GF at fire (x) vs this wave's break GF (y), with the fit line and `r`.
- **S4 - Arrival rhythm.** Is there structure in the cadence of wave *arrivals* (ticks between consecutive breaks), and does our outgoing rhythm differ from the incoming one? _Graph: top-left_ - inter-arrival gap histogram (our waves vs theirs).
- **S5 - Fire-slow-dive-fire-fast (deferred, interventional).** Fire a slow bullet, close the range, then fire a fast one. The hero never executes this in the recorded data, so it is **not graphed**; it needs new battles with a power-varying gun (see Further scenarios below).

## Verdict

**The earlier "all scenarios disproved" conclusion was a measurement artifact, not a true null.** It scored every hypothesis against the noisy hit/miss label with mutual-information-in-bits, and blamed a metronome gun that is not in the data. Measured on the right target - the continuous break GuessFactor - the once-apparent lag-1 structure turns out to be a reference-frame artifact: once the GF is computed on the directioned, aim-time frame it collapses to near zero on BOTH the gun and the movement channels.

**The GUN target is NOT lag-1 predictable.** On the directioned, aim-time GF frame the fire-order lag-1 break-GF autocorrelation is only 0.01-0.11 across opponents (offense, `our_break_gf`) - near zero, and the leakage-safe predictors are flat (`developing_gf` -> GF r -0.02-0.01, `last_broken_gf` -> GF r -0.01-0.02). The ~0.64 that the original intuition report (C8) and earlier sequence passes saw was an artifact of a direction-less / mis-framed break GF (a slowly-varying geometric offset masquerading as a dodge).

**The MOVEMENT (defense) channel collapses the same way once its frame is corrected.** With the aim-time-direction fix now applied to incoming waves (TheirWaveTracker), the fire-order lag-1 autocorrelation of `their_break_gf` is only 0.05-0.08 and the leakage-safe `developing_gf` no longer predicts the next defense break GF (r -0.01-0.00, ~0). The earlier reading that the structure 'lived on the movement channel' was the residue of the same direction-less framing on incoming waves; correcting it removes it. **Net: neither channel carries exploitable wave-to-wave dodge memory on the corrected frame.**

**Hit/miss really is near-null - but that is expected, not a disproof.** At a ~10% base rate against world-class movement, whether one low-power bullet lands is dominated by irreducible residual dodge; conditioned on aim it carries almost no extra bits. That is why the outcome is judged by hit-rate **lift with CIs**, not MI: the heatmaps still expose real conditional cells - vs `rsalesc.mega.Knight` the best gun-heat x distance cell hits 16.0% versus a 10.4% marginal (1.7% of shots). `distance` stays the strongest single geometry axis (MI up to 0.0014 bits).

**Net.** On the corrected directioned frame the wave-to-wave GF predictors are flat on both channels, so sequence / pattern memory adds little; aiming gains come from the geometric segmentation (distance, lateral / advancing velocity), not from lag-1 dodge memory. The hit/miss tables are retained as a deliberately noisy secondary.

## Headline (primary): GF aiming predictability

Predicting the continuous break GuessFactor from fire-tick context. `lag-1 autocorr` is the C8 reference (leaky - uses the previous wave's *break* GF, a future value). `developing_gf -> GF` is the leakage-safe online predictor; `proxy` is how faithfully `developing_gf` tracks that previous break GF; `online_broken_mean -> GF` is the freshest *landed* feedback (stale).

| Opponent | side | N | lag-1 autocorr (C8 ref, leaky) | `developing_gf`->GF  r (R^2) | proxy r | `online_broken_mean`->GF  r |
|---|---|---|---|---|---|---|
| `aaa.r.ScalarR` | off | 32,303 | 0.006 | 0.002 (0.000) | 0.003 | 0.001 |
| `aaa.r.ScalarR` | def | 29,025 | 0.050 | -0.009 (0.000) | 0.033 | 0.003 |
| `jk.mega.DrussGT` | off | 25,013 | 0.035 | -0.003 (0.000) | 0.006 | -0.004 |
| `jk.mega.DrussGT` | def | 24,195 | 0.080 | -0.003 (0.000) | 0.038 | 0.075 |
| `kc.mega.BeepBoop` | off | 35,451 | 0.064 | 0.010 (0.000) | 0.015 | 0.006 |
| `kc.mega.BeepBoop` | def | 32,080 | 0.066 | -0.006 (0.000) | 0.028 | 0.009 |
| `rsalesc.mega.Knight` | off | 30,947 | 0.106 | -0.022 (0.000) | 0.068 | -0.008 |
| `rsalesc.mega.Knight` | def | 28,555 | 0.046 | 0.004 (0.000) | 0.013 | -0.007 |
| `voidious.Diamond` | off | 19,040 | 0.104 | -0.003 (0.000) | 0.063 | 0.084 |
| `voidious.Diamond` | def | 18,721 | 0.060 | 0.002 (0.000) | 0.033 | -0.001 |

> The leaky lag-1 column reproduces the corrected C8 reference (0.01-0.11); on the directioned aim-time frame the leakage-safe `developing_gf` no longer tracks the next break GF on either channel (r near zero), so the earlier 'sequences sharpen GF' reading does not survive frame correction.

## Secondary: gun-heat phase vs hit/miss (Wilson 95% CI + lift)

Noisy binary outcome. `cold` = victim gun heat ~0 (can retaliate at once); `hot` = hottest bin (just fired). `lift` = phase rate / that side's marginal; a CI bracketing 1.00x lift is not distinguishable from the marginal.

| Opponent | side | marginal | cold | cold lift | hot | hot lift |
|---|---|---|---|---|---|---|
| `aaa.r.ScalarR` | off | 8.3% [8.0, 8.6] (n=32,303) | 5.6% [3.9, 7.9] (n=537) | 0.67x | 8.4% [7.7, 9.1] (n=5,971) | 1.01x |
| `aaa.r.ScalarR` | def | 9.8% [9.4, 10.1] (n=29,025) | 9.8% [8.8, 10.9] (n=3,164) | 1.00x | 9.6% [8.6, 10.8] (n=2,753) | 0.99x |
| `jk.mega.DrussGT` | off | 9.4% [9.0, 9.8] (n=25,013) | 9.4% [7.0, 12.3] (n=470) | 1.00x | 9.4% [8.6, 10.3] (n=4,506) | 1.00x |
| `jk.mega.DrussGT` | def | 8.4% [8.1, 8.8] (n=24,195) | 7.6% [6.6, 8.7] (n=2,420) | 0.90x | 7.8% [6.9, 8.9] (n=2,777) | 0.93x |
| `kc.mega.BeepBoop` | off | 7.0% [6.7, 7.3] (n=35,451) | 7.3% [5.6, 9.3] (n=771) | 1.04x | 7.2% [6.6, 7.9] (n=6,388) | 1.04x |
| `kc.mega.BeepBoop` | def | 9.3% [9.0, 9.7] (n=32,080) | 9.5% [8.5, 10.5] (n=3,551) | 1.01x | 9.3% [8.4, 10.3] (n=3,288) | 1.00x |
| `rsalesc.mega.Knight` | off | 10.4% [10.1, 10.8] (n=30,947) | 11.2% [9.0, 13.8] (n=661) | 1.07x | 10.5% [9.8, 11.3] (n=5,843) | 1.01x |
| `rsalesc.mega.Knight` | def | 8.3% [7.9, 8.6] (n=28,555) | 7.6% [6.7, 8.5] (n=3,304) | 0.92x | 8.2% [7.2, 9.3] (n=2,831) | 0.99x |
| `voidious.Diamond` | off | 8.6% [8.2, 9.0] (n=19,040) | 8.6% [6.0, 12.3] (n=313) | 1.01x | 7.3% [6.6, 8.2] (n=4,042) | 0.86x |
| `voidious.Diamond` | def | 8.1% [7.7, 8.5] (n=18,721) | 7.2% [6.2, 8.5] (n=1,938) | 0.89x | 8.6% [7.4, 9.9] (n=1,895) | 1.06x |

† fewer than 200 events on one side.

## Secondary: hit/miss mutual information (noisy binary label)

Per feature, MI between the (quantile-binned) feature and the binary hit/miss outcome. Sequence features are the temporal-context axes; baseline features are the single-wave axes already in the intuition report. For a ~10% base-rate label even a useful predictor scores a tiny MI, so read this as a *relative* ranking among noisy axes, not as evidence for or against predictability - the aim signal lives in the GF headline above.

| Opponent | side | `target_gun_heat` | `developing_gf` | `last_broken_gf` | `arrival_gap` | `victim_energy_delta` | `distance` | `power_delta` | `inter_fire_gap` |
|---|---|---|---|---|---|---|---|---|---|
| `aaa.r.ScalarR` | off | 0.0002 | 0.0002 | 0.0001 | 0.0003 | 0.0002 | 0.0003 | 0.0001 | 0.0001 |
| `aaa.r.ScalarR` | def | 0.0002 | 0.0001 | 0.0002 | 0.0004 | 0.0011 | 0.0010 | 0.0001 | 0.0002 |
| `jk.mega.DrussGT` | off | 0.0001 | 0.0003 | 0.0002 | 0.0002 | 0.0001 | 0.0014 | 0.0000 | 0.0003 |
| `jk.mega.DrussGT` | def | 0.0002 | 0.0006 | 0.0001 | 0.0001 | 0.0004 | 0.0003 | 0.0000 | 0.0000 |
| `kc.mega.BeepBoop` | off | 0.0001 | 0.0002 | 0.0002 | 0.0003 | 0.0001 | 0.0005 | 0.0000 | 0.0003 |
| `kc.mega.BeepBoop` | def | 0.0003 | 0.0002 | 0.0000 | 0.0007 | 0.0003 | 0.0008 | 0.0000 | 0.0001 |
| `rsalesc.mega.Knight` | off | 0.0001 | 0.0001 | 0.0002 | 0.0000 | 0.0000 | 0.0007 | 0.0000 | 0.0002 |
| `rsalesc.mega.Knight` | def | 0.0001 | 0.0002 | 0.0002 | 0.0001 | 0.0001 | 0.0003 | 0.0000 | 0.0000 |
| `voidious.Diamond` | off | 0.0009 | 0.0004 | 0.0004 | 0.0001 | 0.0001 | 0.0008 | 0.0000 | 0.0002 |
| `voidious.Diamond` | def | 0.0004 | 0.0003 | 0.0002 | 0.0001 | 0.0008 | 0.0014 | 0.0002 | 0.0004 |

> Among these noisy axes `distance` is the strongest, matching the intuition report; the temporal `developing_gf`/`last_broken_gf` axes are near-flat *for hit/miss* specifically. That is expected: their structure is with the continuous break GF (headline above), which this binary-label MI cannot see.

## Per-opponent figures

### aaa.r.ScalarR

- Aim (primary): lag-1 GF autocorr 0.01 (C8 ref); leakage-safe `developing_gf` -> break GF r 0.00 (R^2 0.000).
- Offense: 32,303 shots, hit 8.3% (cold 5.6% → hot 8.4%).
- Best offense cell: heat 0.1-0.3, dist 350-450px → 11.0% (2.3% of shots).
- Defense: 29,025 incoming, get-hit 9.8% (cold 9.8% → hot 9.6%).

![sequence aaa.r.ScalarR](sequence/seq-aaa_r_ScalarR.png)

### jk.mega.DrussGT

- Aim (primary): lag-1 GF autocorr 0.03 (C8 ref); leakage-safe `developing_gf` -> break GF r -0.00 (R^2 0.000).
- Offense: 25,013 shots, hit 9.4% (cold 9.4% → hot 9.4%).
- Best offense cell: heat 0.1-0.3, dist 350-450px → 14.5% (1.4% of shots).
- Defense: 24,195 incoming, get-hit 8.4% (cold 7.6% → hot 7.8%).

![sequence jk.mega.DrussGT](sequence/seq-jk_mega_DrussGT.png)

### kc.mega.BeepBoop

- Aim (primary): lag-1 GF autocorr 0.06 (C8 ref); leakage-safe `developing_gf` -> break GF r 0.01 (R^2 0.000).
- Offense: 35,451 shots, hit 7.0% (cold 7.3% → hot 7.2%).
- Best offense cell: heat 0.1-0.3, dist 350-450px → 10.2% (1.1% of shots).
- Defense: 32,080 incoming, get-hit 9.3% (cold 9.5% → hot 9.3%).

![sequence kc.mega.BeepBoop](sequence/seq-kc_mega_BeepBoop.png)

### rsalesc.mega.Knight

- Aim (primary): lag-1 GF autocorr 0.11 (C8 ref); leakage-safe `developing_gf` -> break GF r -0.02 (R^2 0.000).
- Offense: 30,947 shots, hit 10.4% (cold 11.2% → hot 10.5%).
- Best offense cell: heat 0.1-0.3, dist 350-450px → 16.0% (1.7% of shots).
- Defense: 28,555 incoming, get-hit 8.3% (cold 7.6% → hot 8.2%).

![sequence rsalesc.mega.Knight](sequence/seq-rsalesc_mega_Knight.png)

### voidious.Diamond

- Aim (primary): lag-1 GF autocorr 0.10 (C8 ref); leakage-safe `developing_gf` -> break GF r -0.00 (R^2 0.000).
- Offense: 19,040 shots, hit 8.6% (cold 8.6% → hot 7.3%).
- Best offense cell: heat 0.3-0.6, dist 350-450px → 11.3% (1.6% of shots).
- Defense: 18,721 incoming, get-hit 8.1% (cold 7.2% → hot 8.6%).

![sequence voidious.Diamond](sequence/seq-voidious_Diamond.png)

_Assets under `wiki/sequence/`._

## Extended hit/miss scenario results (computed, noisy label)

Former proposal 1 (GF aiming) is now the **primary headline above**, measured the right way (leakage-safe `developing_gf` -> break GF). The remaining proposals 2-8 below stay on the noisy hit/miss label and are scored by `MI(feature, hit)` plus a hit-rate contrast across the natural split (offense label = our hit; defense = `their_hit_us`). As expected for a ~10% base-rate outcome, **every per-shot MI is < 0.005 bits**; the contrasts nudge the rate a few points in the plausible direction (cornered / recently-wobbling victims marginally easier) but none is a strong per-shot predictor. Read them as weak priors - the exploitable sequence signal is in the GF headline.

### Offense proposals (label = our hit; per-opponent marginal in parentheses)

| Opponent | Scenario | MI (bits) | Headline contrast |
|---|---|---|---|
| `aaa.r.ScalarR` (8.3%) | 2. Direction-reversal cadence | 0.0004 | 0 flips 7.6% vs >=2 flips 8.1% |
| `aaa.r.ScalarR` (8.3%) | 3. Post-hit adaptation | 0.0000 | prev miss 8.3% vs prev hit 8.3% |
| `aaa.r.ScalarR` (8.3%) | 4. Range trajectory (closing/opening) | 0.0006 | opening(<0) 8.3% vs closing(>0) 8.3% |
| `aaa.r.ScalarR` (8.3%) | 5. Wall-proximity / cornering | 0.0001 | cornered(<=50) 8.6% vs open(>=158) 7.9% |
| `aaa.r.ScalarR` (8.3%) | 8. Inactivity-zap phase | 0.0000 | n/a |
| `jk.mega.DrussGT` (9.4%) | 2. Direction-reversal cadence | 0.0004 | 0 flips 8.8% vs >=2 flips 8.8% |
| `jk.mega.DrussGT` (9.4%) | 3. Post-hit adaptation | 0.0000 | prev miss 9.2% vs prev hit 10.6% |
| `jk.mega.DrussGT` (9.4%) | 4. Range trajectory (closing/opening) | 0.0004 | opening(<0) 9.4% vs closing(>0) 9.0% |
| `jk.mega.DrussGT` (9.4%) | 5. Wall-proximity / cornering | 0.0004 | cornered(<=43) 9.1% vs open(>=124) 8.8% |
| `jk.mega.DrussGT` (9.4%) | 8. Inactivity-zap phase | 0.0000 | n/a |
| `kc.mega.BeepBoop` (7.0%) | 2. Direction-reversal cadence | 0.0000 | 0 flips 7.0% vs >=2 flips 7.0% |
| `kc.mega.BeepBoop` (7.0%) | 3. Post-hit adaptation | 0.0000 | prev miss 7.0% vs prev hit 6.9% |
| `kc.mega.BeepBoop` (7.0%) | 4. Range trajectory (closing/opening) | 0.0003 | opening(<0) 6.8% vs closing(>0) 7.4% |
| `kc.mega.BeepBoop` (7.0%) | 5. Wall-proximity / cornering | 0.0002 | cornered(<=0) 6.7% vs open(>=46) 6.7% |
| `kc.mega.BeepBoop` (7.0%) | 8. Inactivity-zap phase | 0.0000 | n/a |
| `rsalesc.mega.Knight` (10.4%) | 2. Direction-reversal cadence | 0.0000 | 0 flips 10.3% vs >=2 flips 9.9% |
| `rsalesc.mega.Knight` (10.4%) | 3. Post-hit adaptation | 0.0000 | prev miss 10.3% vs prev hit 11.2% |
| `rsalesc.mega.Knight` (10.4%) | 4. Range trajectory (closing/opening) | 0.0008 | opening(<0) 10.2% vs closing(>0) 10.9% |
| `rsalesc.mega.Knight` (10.4%) | 5. Wall-proximity / cornering | 0.0008 | cornered(<=47) 11.1% vs open(>=124) 9.0% |
| `rsalesc.mega.Knight` (10.4%) | 8. Inactivity-zap phase | 0.0000 | n/a |
| `voidious.Diamond` (8.6%) | 2. Direction-reversal cadence | 0.0001 | 0 flips 8.2% vs >=2 flips 7.8% |
| `voidious.Diamond` (8.6%) | 3. Post-hit adaptation | 0.0000 | prev miss 8.5% vs prev hit 9.3% |
| `voidious.Diamond` (8.6%) | 4. Range trajectory (closing/opening) | 0.0005 | opening(<0) 8.1% vs closing(>0) 8.9% |
| `voidious.Diamond` (8.6%) | 5. Wall-proximity / cornering | 0.0008 | cornered(<=30) 8.4% vs open(>=93) 7.6% |
| `voidious.Diamond` (8.6%) | 8. Inactivity-zap phase | 0.0000 | n/a |

### Defense proposals (label = their hit on us)

| Opponent | Scenario | MI (bits) | Headline contrast |
|---|---|---|---|
| `aaa.r.ScalarR` (9.8%) | 3. Post-hit adaptation | 0.0000 | prev miss 9.7% vs prev hit 10.3% |
| `aaa.r.ScalarR` (9.8%) | 6. Incoming fire rhythm | 0.0001 | regular(<=0) 9.9% vs irregular(>=0) 9.7% |
| `aaa.r.ScalarR` (9.8%) | 7. Incoming bullet-power ladder | 0.0001 | power down 11.7% vs power up 7.9% |
| `jk.mega.DrussGT` (8.4%) | 3. Post-hit adaptation | 0.0000 | prev miss 8.4% vs prev hit 9.3% |
| `jk.mega.DrussGT` (8.4%) | 6. Incoming fire rhythm | 0.0000 | regular(<=0) 8.4% vs irregular(>=0) 8.4% |
| `jk.mega.DrussGT` (8.4%) | 7. Incoming bullet-power ladder | 0.0000 | power down 9.5% vs power up 9.0% |
| `kc.mega.BeepBoop` (9.3%) | 3. Post-hit adaptation | 0.0000 | prev miss 9.3% vs prev hit 10.0% |
| `kc.mega.BeepBoop` (9.3%) | 6. Incoming fire rhythm | 0.0000 | regular(<=0) 9.3% vs irregular(>=0) 9.3% |
| `kc.mega.BeepBoop` (9.3%) | 7. Incoming bullet-power ladder | 0.0000 | power down 9.6% vs power up 8.7% |
| `rsalesc.mega.Knight` (8.3%) | 3. Post-hit adaptation | 0.0000 | prev miss 8.2% vs prev hit 8.9% |
| `rsalesc.mega.Knight` (8.3%) | 6. Incoming fire rhythm | 0.0000 | regular(<=0) 8.1% vs irregular(>=0) 8.2% |
| `rsalesc.mega.Knight` (8.3%) | 7. Incoming bullet-power ladder | 0.0000 | power down 8.5% vs power up 9.7% |
| `voidious.Diamond` (8.1%) | 3. Post-hit adaptation | 0.0000 | prev miss 8.0% vs prev hit 9.6% |
| `voidious.Diamond` (8.1%) | 6. Incoming fire rhythm | 0.0000 | regular(<=0) 8.1% vs irregular(>=0) 8.1% |
| `voidious.Diamond` (8.1%) | 7. Incoming bullet-power ladder | 0.0002 | power down 8.2% vs power up 8.5% |

_Largest offense per-shot MI across all extended hit/miss scenarios: **0.0008 bits** - consistent with a near-irreducible binary outcome. The wall-proximity and direction-reversal contrasts move the hit rate a few points in the expected direction, but are too small and too rare to lift aggregate predictability. The exploitable structure is in the GF headline, not here. Proposals 9-10 (Further scenarios) stay deferred - they require new interventional battles with a deliberately power/cadence-scripted gun._

## Further scenarios to explore (other dimensions)

Ten more sequence framings on dimensions not exercised by S1-S5. `[existing]` = minable from the current CSVs now; `[synthetic]` = needs new / interventional battles. Each names a new axis and the data channel that feeds it.

1. **GF sign-sequence aiming model** `[existing]`. Predict the *next* break GF (sign or bin) from the last 2-3 break GFs in the round. On the corrected frame the gun-side lag-1 autocorrelation is ~0, so this is now a MOVEMENT-side idea (surf the predictable structure in `their_break_gf`), not a gun one. Channel: `their_break_gf` ordered by `their_break_tick`.
2. **Opponent direction-reversal cadence** `[existing]`. Count lateral-velocity sign flips over the last K ticks; a recently-wobbling dodger may be more predictable on the next shot. Channel: `scan_opponent_lateral_velocity`.
3. **Post-hit adaptation (Markov)** `[existing]`. Condition the next wave's GF / hit on whether the *previous* wave hit - detects opponents that shift their dodge right after being tagged. Channel: `our_break_hit[n-1]` -> `our_break_gf[n]`.
4. **Range trajectory (closing vs opening)** `[existing]`. Sign and magnitude of d(distance)/dt over the last K ticks at fire time: are shots into a closing gap better than into an opening one? Channel: `our_fire_advancing_velocity`, `scan_distance` trajectory.
5. **Wall-proximity / cornering** `[existing]`. Track the victim's distance-to-wall over the last K ticks; a freshly-cornered victim has less room to dodge. Channel: `scan_opponent_x`/`scan_opponent_y` vs battlefield bounds.
6. **Incoming fire rhythm (defense)** `[existing]`. Reconstruct the opponent's fire cadence from adjusted energy drops; a regular incoming metronome means predictable incoming waves we can pre-dodge. Channel: `scan_their_energy_drop_adjusted` / `their_fire_tick` deltas vs `their_hit_us`.
7. **Incoming bullet-power ladder (defense)** `[existing]`. Track escalation / de-escalation of `their_fire_power` across consecutive incoming waves - power management signals a targeting lock or an energy-war swing that precedes our getting hit. Channel: `their_fire_power` deltas.
8. **Inactivity-zap phase** `[existing]`. Use ticks-until-inactivity-zap as a forced-motion clock: as the zap nears, both bots must move or fire, so dodge entropy may collapse. Channel: `scan_their_inactivity_zap_active`.
9. **Tempo dithering** `[synthetic]`. Deliberately jitter our gun's fire cadence (skip ticks) to decorrelate our wave arrivals and disrupt the opponent's wave-surfing sync. Pair it with the proper **S5** fire-slow-dive-fire-fast power ramp. Needs new battles with a cadence/power-scripted gun.
10. **Decoy / feint power waves** `[synthetic]`. Alternate fake vs real bullet power to poison the opponent's wave-surfing buffers, then exploit the corrupted stat. Needs new interventional battles with a power-scripted gun.
