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

## Recent dodge history predicts the next dodge, and it is usable online

**Hypothesis.** The intuition report's C8 says consecutive break GuessFactors (GF) are
autocorrelated (lag-1 ≈ 0.643), so *where* an opponent dodged on recent waves should predict
where it dodges next. The open question is whether that signal is *usable*: with 2-3 of our
waves always in flight, the previous wave has not broken when the next is fired, so its break
GF is a future value (leak). If the autocorrelation is only visible after the fact, it cannot
drive a gun.

**Evidence.** Over **821,225** hero offense waves across five competitive opponents (ScalarR,
DrussGT, BeepBoop, Knight, Diamond; self-play and the non-competitive Autopilot excluded), the
fire-order lag-1 break-GF autocorrelation is **0.67-0.71** — reproducing C8. A leakage-safe
feature, `developing_gf` (the previous *still-in-flight* wave's GF re-evaluated at the current
fire tick), tracks that previous break GF at proxy `r = 0.74-0.79` and **still predicts the
current wave's break GF at fire time, `r = 0.17-0.24`, positive for every opponent on both the
gun and movement sides** (strongest vs `rsalesc.mega.Knight`, `r = 0.243`, R² 0.059). The
already-broken feedback a naive design reaches for instead (`online_broken_mean`, ~3 fires
stale because cadence ~14 ticks ≪ flight ~34 ticks) sits near zero (−0.05 to −0.12), which is
why earlier passes that used the "freshest broken wave" saw nothing.

**Why it matters.** The exploitable structure lives in the **continuous GF (aim), not in
hit/miss.** Correlating the same `developing_gf` against the binary hit label gives `r ≈ 0`,
because at a ~10% base rate against world-class movement whether one low-power bullet lands is
dominated by irreducible residual dodge — so a hit/miss-only analysis wrongly reads "no
signal". A memoryless VCS gun discards this lag-1 structure; a predictor that carries the recent
GF history recovers ~3-6% of break-GF variance for free, available at fire time.

**Verdict: CONFIRMED (usable online).** See [sequence.md](sequence.md) for the per-opponent
table, leakage analysis, and figures.

## Irregular incoming fire desyncs our wave-surfing, but only measurable against the simplest opponent

**Hypothesis.** If our movement surfs a regular incoming fire cadence well, then *irregular*
incoming timing should desynchronise the surf and raise how often we are hit; a steady
metronome of incoming waves should be the easiest to pre-dodge.

**Evidence.** This is the one place the noisy hit/miss label lights up — and only for
`aaa.r.ScalarR` (15,276 incoming waves). Splitting incoming waves by reconstructed fire-gap
regularity, we are hit **8.5% when the incoming cadence is regular vs 15.9% when it is
irregular** (mutual information 0.0072, the largest hit/miss MI anywhere in the run); a
de-escalating incoming power ladder is similarly worse for us (14.0% on falling power vs 9.9%
on rising). For all four mega-class opponents the same test is flat (MI ≈ 0.000).

**Why / caveat.** Treat this as a **ScalarR-specific exploitable, not a universal law.** ScalarR
is both the simplest mover and the hardest firer (mean bullet power 0.89 vs ~0.15-0.20 for
DrussGT/BeepBoop/Knight), so its fire timing reconstructs cleanly from energy drops, whereas the
low-power megabots' reconstructed fire-gap barely forms two buckets — their null is partly a
*measurement* limit, not proof of no effect. But `voidious.Diamond` also fires hard (mean 0.73)
and still shows no rhythm signal, so the effect does not obviously generalise; the safe reading
is a real surfing-desync lever against ScalarR that needs confirmation elsewhere before it
drives movement.

**Verdict: PARTIAL (strong for ScalarR, unconfirmed elsewhere).** See [sequence.md](sequence.md).
