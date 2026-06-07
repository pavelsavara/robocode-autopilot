# Detailed GuessFactor Histogram — discreteness-artifact analysis

- **Generated (UTC):** 2026-06-07 12:05:02Z
- **Run directory:** `pipeline/build/battle-csv-producer/1780763098676-1780763098676`
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
| ScalarR | 13,760 | 14.3x | 2.9x | 0 | 0.00% | 2.70% |
| DrussGT | 11,128 | 11.4x | 3.2x | 0 | 0.00% | 2.19% |
| BeepBoop | 17,419 | 17.8x | 17.8x | 0 | 0.00% | 2.21% |
| Knight | 13,652 | 28.2x | 28.2x | 0 | 0.00% | 1.67% |
| Diamond | 9,494 | 57.8x | 57.8x | 0 | 0.00% | 2.04% |

Pooled across all robots:

| Metric | Value |
|---|---|
| Resolved waves (N) | 65,453 |
| Fine bins | 2,000 (width 0.0010 GF) |
| Occupied bins | 2,000 / 2,000 (100.0%) |
| Tallest bin | 578 waves at GF=+0.0005 (0.88% of mass) |
| Spikiness (tallest / median occupied) | 17.5x |
| GF=0 spike height (/ median occupied) | 17.5x |
| Comb spikes (>=5x local baseline) | 3 |
| Mass inside comb spikes | 1.80% |
| Interior comb spikes (excl. GF=0, +-1) | 0 |
| Mass in interior comb spikes | 0.00% |
| Mass at GF=0 (+-1 bin) | 0.98% |
| Mass at extremes (|GF|>0.98) | 2.17% |

Top spike bins (engine-favored GF positions):

| Rank | GF center | Waves | % of mass |
|---|---|---|---|
| 1 | +0.0005 | 578 | 0.883% |
| 2 | +0.9995 | 304 | 0.464% |
| 3 | -0.9995 | 294 | 0.449% |
| 4 | -0.0005 | 61 | 0.093% |
| 5 | -0.1235 | 58 | 0.089% |
| 6 | +0.1155 | 58 | 0.089% |
| 7 | -0.0145 | 57 | 0.087% |
| 8 | -0.2045 | 56 | 0.086% |
| 9 | -0.1055 | 56 | 0.086% |
| 10 | -0.1675 | 56 | 0.086% |

## Movement channel — `their_break_gf` (their-waves)

![movement fine histogram](detailed-gf/movement-gf-2000bins.png)

Per-robot comb metrics (each robot's own movement):

| Robot | N | Spikiness | GF=0 spike | Interior comb spikes | Interior mass | Mass @\|GF\|>0.98 |
|---|---|---|---|---|---|---|
| ScalarR | 7,989 | 9.0x | 2.0x | 0 | 0.00% | 1.81% |
| DrussGT | 8,216 | 41.5x | 41.5x | 0 | 0.00% | 1.98% |
| BeepBoop | 10,485 | 6.0x | 1.6x | 0 | 0.00% | 1.68% |
| Knight | 9,465 | 12.4x | 1.8x | 0 | 0.00% | 3.03% |
| Diamond | 7,499 | 4.5x | 1.0x | 0 | 0.00% | 1.39% |

Pooled across all robots:

| Metric | Value |
|---|---|
| Resolved waves (N) | 43,654 |
| Fine bins | 2,000 (width 0.0010 GF) |
| Occupied bins | 2,000 / 2,000 (100.0%) |
| Tallest bin | 188 waves at GF=+0.0005 (0.43% of mass) |
| Spikiness (tallest / median occupied) | 8.6x |
| GF=0 spike height (/ median occupied) | 8.6x |
| Comb spikes (>=5x local baseline) | 3 |
| Mass inside comb spikes | 1.23% |
| Interior comb spikes (excl. GF=0, +-1) | 0 |
| Mass in interior comb spikes | 0.00% |
| Mass at GF=0 (+-1 bin) | 0.51% |
| Mass at extremes (|GF|>0.98) | 2.00% |

Top spike bins (engine-favored GF positions):

| Rank | GF center | Waves | % of mass |
|---|---|---|---|
| 1 | +0.0005 | 188 | 0.431% |
| 2 | -0.9995 | 182 | 0.417% |
| 3 | +0.9995 | 166 | 0.380% |
| 4 | +0.1725 | 44 | 0.101% |
| 5 | -0.2155 | 42 | 0.096% |
| 6 | -0.1235 | 42 | 0.096% |
| 7 | +0.1155 | 42 | 0.096% |
| 8 | +0.0395 | 41 | 0.094% |
| 9 | -0.0945 | 41 | 0.094% |
| 10 | -0.1845 | 41 | 0.094% |

## How to read this

- Each per-channel figure draws **one normalized-density line per robot** (Autopilot excluded). A genuine comb shows as repeating spikes at the *same* GF for every line; a per-robot quirk shows on a single line only.
- A spike exactly at `GF=0` is head-on accumulation (zero net lateral drift); spikes at `|GF|~=1` are max-escape-angle saturation. Intermediate repeating spikes come from integer lateral-velocity sequences over discrete flight ticks.
- `Spikiness` and `Comb spikes` quantify the effect; the smooth 47-bin view used elsewhere (e.g. `ML-intuition.md` C1) averages the comb away, which is why it looks continuous there.

