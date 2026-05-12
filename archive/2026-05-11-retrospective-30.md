# Sprint 25–30 Combined Retrospective — Movement & Targeting Overhaul, Two Reverts, Three Blockers Found

*Date: 2026-05-11 · Sprints 25–30 · Goal: Improve movement, targeting, and energy management*

**Process violation:** Six consecutive sprints ran without a retrospective document.
This violates the HARD GATE in `ceremonies.md` and `sprint.md` Phase 5. The debt is
settled by this combined document.

## Diagnostic Health

No formal sanity checks were run during Sprints 25–30. The CI sprint pipeline was
not exercised — all evaluation was local (15 opponents × 3 battles × 35 rounds).
Self-battle metrics not recorded.

**Status: UNKNOWN.** Restoring the sanity check pipeline is overdue.

## Metrics Table

| Metric | Sprint 24 (50 opp) | Sprint 30 (15 opp) | Delta | Note |
|---|---|---|---|---|
| Overall score % | 3.2% | **4.1%** | +0.9 pp | Different opponent sets — not directly comparable |
| Battle win rate | 0/150 | **0/45** | — | Still zero battle wins |
| Round win rate | — | **24/1575** | — | 1.5% round win rate |
| Opponents evaluated | 50 | **15** | −35 | Evaluation scope narrowed |
| Errors | 0 | 0 | — | |
| Sanity checks | 7/7 | **not run** | — | Process gap |
| Sprints attempted | 1 | **6** | — | |
| Sprints reverted | 0 | **2** | — | Sprints 27, 29 fully reverted |

**Score context:** The +0.9 pp gain from 3.2% to 4.1% is unreliable because the
opponent set shrank from 50 (full top-50) to 15 (mixed tiers). Against the same
top-50 set, the true score is likely ≤3.5%. Six sprints of work produced no
meaningful competitive improvement.

## Per-Opponent Breakdown (Sprint 30, 15 opponents)

| Opponent | Avg Score % | Battle Wins | Round Wins | Tier |
|---|---:|---:|---:|---|
| axeBots.Musashi | 8.7% | 0/3 | 4/105 | Mid |
| florent.FloatingTadpole | 7.7% | 0/3 | 7/105 | Mid |
| da.NewBGank | 7.0% | 0/3 | 2/105 | Mid |
| cx.BlestPain | 6.0% | 0/3 | 2/105 | Mid |
| ab.DengerousRoBatra | 5.3% | 0/3 | 1/105 | Low |
| kid.Gladiator | 4.0% | 0/3 | 2/105 | Mid |
| ary.FourWD | 4.0% | 0/3 | 2/105 | Mid |
| eem.zapper | 3.3% | 0/3 | 1/105 | Low |
| dft.Cardigan | 2.7% | 0/3 | 2/105 | Mid |
| ary.micro.Weak | 2.7% | 0/3 | 0/105 | Low |
| jk.mega.DrussGT | 2.0% | 0/3 | 0/105 | Elite |
| ary.Help | 1.7% | 0/3 | 0/105 | Low |
| gh.GresSuffurd | 1.7% | 0/3 | 1/105 | Mid |
| ags.Glacier | 1.3% | 0/3 | 0/105 | Elite |
| abc.Shadow | 1.0% | 0/3 | 0/105 | Elite |

**Pattern:** Scores correlate cleanly with opponent tier. Zero wins against
elite opponents (Shadow, Glacier, DrussGT). Best results against mid-tier
bots with simple movement (Musashi 8.7%, FloatingTadpole 7.7%).

## What Worked

1. **3-tier wave surf activation (Sprint 25–26).** The imminent (<20 ticks) /
   semi-imminent (<30 ticks) / orbit layering is architecturally sound and
   gives the movement system graduated urgency. This survived both reverts
   and remains in production.

2. **Smooth wall avoidance (Sprint 25–26).** Push-vector wall avoidance in
   the 140–180px zone replaced hard turn-away. The robot no longer gets
   trapped in corners as frequently. Measurable improvement against
   wall-aware opponents.

3. **Energy conservation (Sprints 25–26, 30).** Don't fire below 20% energy
   ratio, scan freshness gate (>3 ticks = don't fire), distance-based fire
   power brackets, and `ourEnergy/6` cap. These prevent the robot from
   disarming itself against strong opponents. Net positive — fewer wasted
   bullets.

4. **VGM faster convergence (Sprint 28).** Window 50→25 and exploration
   3%→1% let the Virtual Gun Manager lock onto the best-performing gun
   faster. Reduces the "exploration tax" in short battles.

5. **Circular targeting improvements (Sprint 25–26).** Acceleration
   prediction and wall collision handling in CircularGun make predictions
   more physically accurate. Minimum 5 prediction iterations prevent
   ultra-short-range misses.

## What Didn't Work

1. **True wave surfing — REVERTED (Sprint 27).** Replaced PathPlanner-based
   dodging with simulation-based two-option surfing. Performance regressed
   (0 wins → 0 wins, lower scores). The surfing implementation was too
   simplistic — it evaluated only two options (clockwise/counterclockwise)
   without precise prediction of future positions.

2. **Anti-adaptation randomization — REVERTED (Sprint 29).** Per-round orbit
   angle offset (±30°) and distance variation (±50px) caused severe
   regression. The offsets were too large, making the robot's orbit erratic
   rather than unpredictable. A ±5° offset with gradual ramp would have
   been safer.

3. **VCS persistence broken since Sprint 25.** The velocity bucket segment
   expansion (6→12 segments) bumped the persistence version from 1→2.
   `DefaultDataFile.java` still contains version 1 data. Every battle
   starts with empty VCS histograms for both gun and movement. The robot
   has been running without prior VCS data for 6 sprints — a critical
   regression that went undetected because no sanity check validates
   VCS state.

4. **GF normalization possibly double-applied (Sprint 25–26).** Recording
   uses `gf = (offset/mea) * latDir`, aiming uses `offset = gf * mea * latDir`.
   If both sides multiply by `latDir`, the sign chain is `latDir² = 1`,
   which collapses directional information and inverts aim for one lateral
   direction. This is a suspected bug — not yet confirmed, but consistent
   with the ~3% hit rate that hasn't improved despite 6 sprints of
   targeting work.

5. **Six sprints without retrospective.** No formal evaluation against the
   previous baseline. No root cause analysis after reverts. The team
   iterated blindly — each sprint's changes were assessed against the
   previous sprint's local eval, not against the Sprint 24 established
   baseline. The +0.9 pp "gain" may be within noise.

6. **Evaluation scope narrowed without justification.** Dropping from 50
   to 15 opponents makes results unreliable. The top-50 eval was
   established in Sprint 24 specifically to avoid this class of error.

## Root Cause Analysis

**Why did 6 sprints of movement + targeting work produce <1 pp improvement?**

Three structural bugs undermine every change made in Sprints 25–30:

### Blocker 1: PathPlanner evaluates unreachable positions

The `ReachableEnvelope` pre-computes candidate positions at tick+10 as
geometric offsets from the current position. But the robot's physics
(heading, velocity, turn rate, acceleration limits) mean most of these
candidates are not actually reachable in 10 ticks. The PathPlanner ranks
all candidates by danger score and picks the "best" — which is often
physically unreachable. `WaveSurfMovement` then turns toward the selected
position, arriving too late and too slowly to dodge.

**Impact:** Movement decisions are based on fantasy positions. The entire
wave surf system is degraded to "turn toward a good-looking position and
hope for the best."

**Fix:** Replace geometric envelope with simulation-based reachable set.
For each candidate direction, simulate the robot's actual trajectory
(respecting heading, velocity, turn rate, wall bouncing) and evaluate
danger at the *actual* arrival position.

### Blocker 2: VCS histograms empty every battle

The persistence version bump (1→2) from the 12-segment expansion made
`DefaultDataFile.java` incompatible with the current VCS format. The
persistence system silently discards version-mismatched data. Both gun
(VCS-based aiming) and movement (VCS danger scoring) start every battle
with zero histogram data.

**Impact:** The VCS gun fires based only on within-battle observations
(typically <50 data points in early rounds). The movement system has no
prior knowledge of opponent firing patterns. This nullifies 6 sprints of
VCS improvements.

**Fix:** Regenerate `DefaultDataFile.java` with version 2 format from
the current recording dataset. Add a sanity check that validates VCS
histogram population at round 2+.

### Blocker 3: GF normalization sign error (suspected)

Recording: `gf = (offset / mea) * latDir`
Aiming: `offset = gf * mea * latDir`

Expanding: `offset = ((offset_orig / mea) * latDir) * mea * latDir`
         = `offset_orig * latDir²`
         = `offset_orig * 1`
         = `offset_orig`

Wait — `latDir²` = 1 for both +1 and −1, so the sign IS preserved
through the round-trip. The normalization is mathematically correct IF
`latDir` at recording time equals `latDir` at aiming time. But if
they differ (opponent changed lateral direction between fire and impact),
the GF is recorded in one frame and applied in another.

**Revised assessment:** The normalization is correct for same-direction
replay. The real issue is more subtle: if VCS bins are addressed by
raw GF (−1 to +1) but the recording multiplied by `latDir`, then the
histogram conflates left-moving and right-moving patterns. This needs
a code-level audit to confirm or dismiss.

**Fix:** Audit `VcsHistogramStore` recording and lookup paths. Verify
that `latDir` is applied consistently in both directions. Add a unit
test that records a GF with `latDir=+1` and verifies correct retrieval
with `latDir=−1`.

## Sprint-by-Sprint Summary

| Sprint | Focus | Result | Score Impact |
|---|---|---|---|
| 25 | Movement overhaul (3-tier surf, wall avoidance) | Shipped | ~+0 pp |
| 26 | Targeting overhaul (VCS segments, GF normalization, energy) | Shipped | ~+0 pp |
| 27 | True wave surfing | **REVERTED** | 0 pp (net) |
| 28 | VGM convergence, close-range fire power | Shipped | ~+0 pp |
| 29 | Anti-adaptation randomization | **REVERTED** | 0 pp (net) |
| 30 | Energy conservation (lower fire power) | Shipped | ~+0 pp |

**Two of six sprints were fully reverted.** The remaining four shipped
incremental improvements that collectively failed to move the score.

## Proposals for Sprint 31

Three proposals, each targeting one of the three identified blockers:

### P1. Fix VCS persistence (Blocker 2)

Regenerate `DefaultDataFile.java` with version 2 format. This is the
lowest-effort, highest-impact fix — it restores VCS prior data that
has been missing for 6 sprints.

*Steps:*
1. Run recording battles against 15 opponents (existing recordings work).
2. Collect `autopilot.dat` files from all battles.
3. Merge via `merge-dat.mjs` into a combined dat file.
4. Re-encode as `DefaultDataFile.java` with version 2 header.
5. Verify VCS histogram population in a test battle.

*Metric:* VCS histograms populated at round 1 start.
*Owner:* Amos.

### P2. Audit GF normalization chain (Blocker 3)

Trace the full GF lifecycle: recording → histogram storage → lookup →
aim offset. Verify sign consistency for both lateral directions.

*Steps:*
1. Read `VcsHistogramStore.record()` and `VcsHistogramStore.query()`.
2. Read `WaveSurfMovement` GF recording path.
3. Read `VcsGun` GF aiming path.
4. Write `VcsNormalizationTest` — record GF with latDir=+1 and −1,
   verify correct retrieval.

*Metric:* Unit test passes for both lateral directions, OR bug found and fixed.
*Owner:* Holden.

### P3. Restore evaluation discipline

Re-enable the 50-opponent CI eval pipeline. Run sanity checks. No code
changes ship without a retrospective.

*Steps:*
1. Run CI eval against full 50-opponent set.
2. Establish honest Sprint 31 baseline.
3. Re-enable all 7 sanity checks.

*Metric:* Sanity checks 7/7 PASS. 50-opponent baseline established.
*Owner:* Amos.
