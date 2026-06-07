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

## Diagonal absolute fire angles widen the hittable GuessFactor band

**Hypothesis.** The absolute bullet angle matters because the robot bounding box does not
rotate with heading, so the GuessFactor (GF) range is bigger when firing along absolute
45-degree angles.

**Evidence.** The mechanism is *proven from the engine*, not inferred. Robocode resolves a
hit with `robot.getBoundingBox().intersectsLine(bulletLine)`, and that box is an
axis-aligned 36x36 square that never rotates ([physics.md](physics.md), `BulletPeer.java`).
A bullet arriving at absolute angle `theta` therefore sees a target of half-width
`18 * (|cos theta| + |sin theta|)` px — 18 px at the cardinals (0/90/180/270 deg) but
`18*sqrt(2) ≈ 25.46` px at the diagonals (45/135/…), a 41% wider target. Since the hittable
GF half-band is `box_factor * 18 / (distance * MEA)` with
`box_factor = |cos theta| + |sin theta| ∈ [1, sqrt(2)]`, the hittable GF range is
`box_factor`× wider — up to sqrt(2)× from diagonal angles. Measured over **679,080** real
hero waves (`dejavu-waves.csv`, self-play and the non-competitive Autopilot excluded), with
`theta` read straight from `our_fire_bearing_absolute`, the mean band widening is **1.25×**
(median 1.27×), 64% of waves sit at box_factor ≥ 1.20, and the top diagonal bin alone holds
28% of all waves.

**Why it matters.** The analytic hit baseline used elsewhere — `canonical_hit` in
[scripts/intuition.py](../scripts/intuition.py) and `gf_tol ≈ (18/fire_distance)/mea` in
[intuition-design.md](intuition-design.md) — assumes a **flat 18 px** half-width. It
therefore under-counts the true hittable GF band by **~20% on average and up to 29% at
diagonal angles**. Replacing the flat 18 with `18 * (|cos theta| + |sin theta|)` makes the
baseline match the engine.

**Verdict: CONFIRMED (engine mechanism).** See [abs-angle-gf.md](abs-angle-gf.md) for the
full distribution, per-bin table, and figure.
