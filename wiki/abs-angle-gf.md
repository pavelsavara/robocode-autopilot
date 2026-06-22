# Absolute bullet angle and GuessFactor hit geometry

_Generated 2026-06-22 13:30 UTC from `pipeline/build/intuition-run/1782133800396-1782133800396`._

## Verdict: CONFIRMED (engine mechanism)

The hypothesis is correct **by construction**, not by chance. Robocode resolves a hit with `robot.getBoundingBox().intersectsLine(bulletLine)` and that box is an **axis-aligned 36x36 square that does not rotate with heading** (`wiki/physics.md`, `BulletPeer.java`). A bullet at absolute angle `theta` therefore sees a target of half-width `18 * (|cos theta| + |sin theta|)` px - 18 px at the cardinals, `18*sqrt(2) ~= 25.46` px at the diagonals (a 41% wider target). The hittable GuessFactor half-band is `box_factor * 18 / (distance * MEA)`, so **the hittable GF range is `box_factor`x wider, up to sqrt(2)x at 45-degree absolute angles.**

## How much it matters across real battles

Measured over **146,314** resolved real hero waves (`dejavu-waves.csv`, self-play and the non-competitive `cz.zamboch.Autopilot` excluded). The absolute bullet angle is the recorded `our_fire_bearing_absolute` column; `box_factor = |cos theta| + |sin theta|`:

| quantity | value |
|---|---|
| mean box_factor (mean GF-band widening) | 1.251x |
| median box_factor | 1.270x |
| p90 box_factor | 1.407x |
| max box_factor | 1.414x |
| waves with box_factor >= 1.10 | 82.8% |
| waves with box_factor >= 1.20 | 64.2% |
| waves with box_factor >= 1.30 | 43.5% |
| mean hit-band the flat-18 model misses | 20.1% |
| worst-case (diagonal) under-count | 29.3% |

Per box_factor bin (median hittable GF half-band, flat-18 vs box model):

| box_factor bin | center | waves | share | median dist | flat GF tol | box GF tol |
|---|---|---|---|---|---|---|
| [1.000, 1.052] | 1.026 | 12,636 | 8.6% | 532 | 0.0783 | 0.0804 |
| [1.052, 1.104] | 1.078 | 13,393 | 9.2% | 531 | 0.0784 | 0.0845 |
| [1.104, 1.155] | 1.129 | 13,941 | 9.5% | 529 | 0.0787 | 0.0888 |
| [1.155, 1.207] | 1.181 | 14,321 | 9.8% | 526 | 0.0792 | 0.0936 |
| [1.207, 1.259] | 1.233 | 15,304 | 10.5% | 524 | 0.0795 | 0.0980 |
| [1.259, 1.311] | 1.285 | 16,566 | 11.3% | 520 | 0.0803 | 0.1031 |
| [1.311, 1.362] | 1.337 | 19,051 | 13.0% | 514 | 0.0810 | 0.1084 |
| [1.362, 1.414] | 1.388 | 41,102 | 28.1% | 509 | 0.0818 | 0.1142 |

**Actionable:** the analytic hit rule `canonical_hit` in `scripts/intuition.py` (and `gf_tol ~= (18/fire_distance)/mea` in `wiki/intuition-design.md`) uses a **flat 18 px** half-width. It therefore under-counts the true hittable GF band by ~20% on average and up to 29% at diagonal absolute angles. Replacing 18 with `18 * (|cos theta| + |sin theta|)` (theta = `our_fire_bearing_absolute`) makes the analytic hit baseline match the engine.

## Figures

![gf-band-widening](abs-angle-gf/gf-band-widening.png)

_Assets under `wiki/abs-angle-gf/`._
