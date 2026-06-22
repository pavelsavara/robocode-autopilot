# ML Revelations

Short, evidence-backed findings from the statistical analysis of battle data. Each entry
states a hypothesis, the test, and the verdict.

## Diagonal absolute fire angles widen the hittable GuessFactor band

**Hypothesis.** The absolute bullet angle matters because the robot bounding box does not rotate
with heading, so the hittable GF range is wider when firing along absolute 45° angles.

**Evidence.** Proven *from the engine*: Robocode resolves a hit with
`robot.getBoundingBox().intersectsLine(bulletLine)`, and that box is an axis-aligned 36×36
square that never rotates ([physics.md](physics.md), `BulletPeer.java`). A bullet arriving at
absolute angle `θ` sees a target of half-width `18·(|cos θ| + |sin θ|)` px — 18 px at the
cardinals, `18·√2 ≈ 25.46` px at the diagonals (41 % wider). The hittable GF half-band is
`box_factor·18/(distance·MEA)` with `box_factor = |cos θ| + |sin θ| ∈ [1, √2]`. Measured over
**154,756** hero waves with `θ` read straight from `our_fire_bearing_absolute` (unchanged by
the frame fix), the mean band widening is **1.253×** (up to 1.414×), and the analytic flat-18
hit baseline under-counts the true hittable GF band by **~20 % on average**.

**Why it matters.** The `canonical_hit` baseline in [intuition.py](../scripts/intuition.py) and
the `gf_tol ≈ (18/fire_distance)/mea` in [intuition-design.md](intuition-design.md) assume a
flat 18 px half-width and therefore under-count. Replacing 18 with `18·(|cos θ| + |sin θ|)`
makes the baseline match the engine.

**Verdict: CONFIRMED (engine mechanism), reconfirmed on the corrected corpus.**
See [abs-angle-gf.md](abs-angle-gf.md).

