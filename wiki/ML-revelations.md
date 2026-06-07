# ML Revelations

Short, evidence-backed findings from the statistical analysis of battle data. Each entry
states a hypothesis, the test, and the verdict.

## No robot produces an interior GuessFactor comb

**Hypothesis.** A very granular GuessFactor (GF) histogram (2000 bins over [-1, 1]) should
expose *comb artifacts* — narrow, repeating spikes — caused by the Robocode engine's
discrete state: integer velocities/accelerations (-8..8, changing by ±1/±2 per tick) and
discrete bullet speeds (`speed = 20 - 3*power`, so `MEA = asin(8/speed)` is quantized).
Since `GF = bearing_offset / MEA` is assembled from these discrete ingredients over an
integer number of flight ticks, realized GF values should pile up at a repeating set of
rational positions rather than forming a smooth curve.

**Evidence.** Splitting the fine histogram into one line per robot (ScalarR, DrussGT,
BeepBoop, Knight, Diamond; the naive Autopilot excluded) and measuring the comb directly,
**no robot produces an interior comb** — *interior comb spikes = 0 for every robot in both
the gun (`our_break_gf`) and movement (`their_break_gf`) channels.* The interior of every
distribution is smooth sampling noise. The only sharp features are at the *fixed points*
GF=0 (head-on: zero net lateral offset) and GF=±1 (MEA saturation / max escape angle), and
even those are robot-specific behaviour, not an engine artifact — the GF=0 spike ranges
from 1.0× (flat) to 57.8× the median bin depending on the robot's targeting/surfing, while
the |GF|>0.98 saturation mass stays ~2% across all robots.

**Why.** Even though `bearing_offset` is built from discrete integers, `MEA` varies
continuously across waves (distance and chosen power differ shot to shot). Dividing a
quasi-discrete numerator by a continuously-varying denominator *smears* any interior comb
into a continuum. Only the two MEA-independent values survive the smearing: `offset=0 → GF=0`
and `saturation → GF=±1`. So the engine's discreteness is real but invisible as a comb —
disproving the comb form of the hypothesis.

**Verdict: comb hypothesis NOT SUPPORTED** (discreteness survives only at fixed points).
See [detailed-gf.md](detailed-gf.md) for the per-robot figures and metrics.
