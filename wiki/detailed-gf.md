# Detailed GuessFactor Histogram — discreteness-artifact analysis

- **Generated (UTC):** 2026-06-22 09:43:07Z
- **Run directory:** `pipeline/build/intuition-run/1782120123832-1782120123832`
- **Fine binning:** 2000 bins over [-1, 1] (reference grid: 47 bins)
- **Self-play matchups excluded** from the pooled distributions.
- **Per robot:** BeepBoop, Diamond, DrussGT, Knight, ScalarR (one line per robot; excluded as too naive: Autopilot).

## Hypothesis

> A very granular GF histogram should expose *comb artifacts* — narrow, repeating spikes — produced by the engine's discrete state (integer velocities/accelerations -8..8, discrete bullet speeds `20 - 3*power` driving a quantized MEA). Because `GF = bearing_offset / MEA` is assembled from these discrete ingredients over an integer number of flight ticks, realized GF values should pile up at repeating rational positions instead of forming a smooth curve. Tested separately for the **gun** (`our_break_gf`, dejavu real waves) and **movement** (`their_break_gf`, their-waves).

## Verdict

- **Gun (`our_break_gf`):** FIXED-POINT only (GF=0 / +-1)
- **Movement (`their_break_gf`):** FIXED-POINT only (GF=0 / +-1)

**Verdict: PARTIALLY SUPPORTED — discreteness is real, but it is not an intermediate comb.** The only artifacts are sharp spikes at the *fixed points* GF=0 (head-on: zero net lateral offset) and GF=+-1 (MEA saturation / max escape angle). The interior of the distribution is smooth noise, **not** a repeating comb.

Mechanism: `GF = bearing_offset / MEA`. Even though `bearing_offset` is built from discrete integer velocities/accelerations, `MEA = asin(8 / bullet_speed)` varies continuously across waves (distance and chosen power differ shot to shot). Dividing a quasi-discrete numerator by a continuously-varying denominator *smears* any interior comb into a continuum. The two GF values that survive this smearing are exactly the ones independent of MEA: offset=0 -> GF=0, and saturation -> GF=+-1. So the engine's discreteness is visible, but only as these three fixed-point spikes, disproving the *comb* form of the hypothesis.

Decision rule: an *interior comb* requires >=10 spikes (excluding GF=0 and |GF|>=0.98) at >=5x their local smooth baseline, holding >=5% of all mass, with the tallest bin >=8x the median occupied bin. A *fixed-point* result is recorded when the GF=0 bin alone is >=8x the median occupied bin or the extremes hold >=1.5% of mass.

## Gun vs movement overlay

![gun vs movement](detailed-gf/gun-vs-movement-2000bins.png)

## Gun channel — `our_break_gf` (dejavu real waves)

![gun fine histogram](detailed-gf/gun-gf-2000bins.png)

Per-robot comb metrics (each robot's own gun):

| Robot | N | Spikiness | GF=0 spike | Interior comb spikes | Interior mass | Mass @\|GF\|>0.98 |
|---|---|---|---|---|---|---|
| ScalarR | 32,198 | 50.8x | 3.7x | 0 | 0.00% | 3.96% |
| DrussGT | 25,885 | 38.0x | 1.7x | 1 | 0.02% | 3.05% |
| BeepBoop | 37,437 | 37.4x | 12.9x | 0 | 0.00% | 3.10% |
| Knight | 30,704 | 34.8x | 20.1x | 0 | 0.00% | 2.85% |
| Diamond | 18,945 | 45.6x | 45.6x | 0 | 0.00% | 3.07% |

Pooled across all robots:

| Metric | Value |
|---|---|
| Resolved waves (N) | 145,169 |
| Fine bins | 2,000 (width 0.0010 GF) |
| Occupied bins | 1,918 / 2,000 (95.9%) |
| Tallest bin | 3,072 waves at GF=-0.9995 (2.12% of mass) |
| Spikiness (tallest / median occupied) | 38.9x |
| GF=0 spike height (/ median occupied) | 14.2x |
| Comb spikes (>=5x local baseline) | 2 |
| Mass inside comb spikes | 2.89% |
| Interior comb spikes (excl. GF=0, +-1) | 0 |
| Mass in interior comb spikes | 0.00% |
| Mass at GF=0 (+-1 bin) | 0.86% |
| Mass at extremes (|GF|>0.98) | 3.23% |

Top spike bins (engine-favored GF positions):

| Rank | GF center | Waves | % of mass |
|---|---|---|---|
| 1 | -0.9995 | 3,072 | 2.116% |
| 2 | +0.0005 | 1,124 | 0.774% |
| 3 | -0.1475 | 131 | 0.090% |
| 4 | -0.1535 | 128 | 0.088% |
| 5 | -0.1375 | 123 | 0.085% |
| 6 | -0.1465 | 122 | 0.084% |
| 7 | -0.0005 | 122 | 0.084% |
| 8 | -0.0465 | 120 | 0.083% |
| 9 | -0.5505 | 120 | 0.083% |
| 10 | -0.3775 | 117 | 0.081% |

## Movement channel — `their_break_gf` (their-waves)

![movement fine histogram](detailed-gf/movement-gf-2000bins.png)

Per-robot comb metrics (each robot's own movement):

| Robot | N | Spikiness | GF=0 spike | Interior comb spikes | Interior mass | Mass @\|GF\|>0.98 |
|---|---|---|---|---|---|---|
| ScalarR | 28,617 | 35.1x | 1.7x | 0 | 0.00% | 2.88% |
| DrussGT | 24,422 | 39.1x | 8.6x | 0 | 0.00% | 3.28% |
| BeepBoop | 33,450 | 29.9x | 2.1x | 1 | 0.01% | 2.52% |
| Knight | 28,575 | 59.9x | 1.1x | 0 | 0.00% | 4.38% |
| Diamond | 18,377 | 28.3x | 1.7x | 0 | 0.00% | 2.55% |

Pooled across all robots:

| Metric | Value |
|---|---|
| Resolved waves (N) | 133,441 |
| Fine bins | 2,000 (width 0.0010 GF) |
| Occupied bins | 1,917 / 2,000 (95.8%) |
| Tallest bin | 2,755 waves at GF=-0.9995 (2.06% of mass) |
| Spikiness (tallest / median occupied) | 37.7x |
| GF=0 spike height (/ median occupied) | 2.6x |
| Comb spikes (>=5x local baseline) | 1 |
| Mass inside comb spikes | 2.06% |
| Interior comb spikes (excl. GF=0, +-1) | 0 |
| Mass in interior comb spikes | 0.00% |
| Mass at GF=0 (+-1 bin) | 0.23% |
| Mass at extremes (|GF|>0.98) | 3.14% |

Top spike bins (engine-favored GF positions):

| Rank | GF center | Waves | % of mass |
|---|---|---|---|
| 1 | -0.9995 | 2,755 | 2.065% |
| 2 | +0.0005 | 191 | 0.143% |
| 3 | -0.1475 | 119 | 0.089% |
| 4 | -0.1535 | 118 | 0.088% |
| 5 | -0.1375 | 117 | 0.088% |
| 6 | -0.3785 | 114 | 0.085% |
| 7 | -0.0465 | 113 | 0.085% |
| 8 | -0.3775 | 112 | 0.084% |
| 9 | -0.5505 | 111 | 0.083% |
| 10 | -0.0005 | 111 | 0.083% |

## How to read this

- Each per-channel figure draws **one normalized-density line per robot** (Autopilot excluded). A genuine comb shows as repeating spikes at the *same* GF for every line; a per-robot quirk shows on a single line only.
- A spike exactly at `GF=0` is head-on accumulation (zero net lateral drift); spikes at `|GF|~=1` are max-escape-angle saturation. Intermediate repeating spikes come from integer lateral-velocity sequences over discrete flight ticks.
- `Spikiness` and `Comb spikes` quantify the effect; the smooth 47-bin view used elsewhere (e.g. `ML-intuition.md` C1) averages the comb away, which is why it looks continuous there.

