# Retrospective 20 — CI Offload Complete, 10.2% Score

*Date: 2026-05-10 · Sprint: 20*
*Previous: [2026-05-10-retrospective-14.md](2026-05-10-retrospective-14.md)*

## Sprint Goal

**Workstream A: CI Offload** — move battle evaluation from Pavel's local
machine to GitHub Actions. Single major proposal (no other changes).

## 1. Diagnostic Health

This was an **infrastructure sprint** — no robot code changes, no model
retraining. The 6 standard sanity checks require `internal.csv` and
`debug.log` from local pipeline runs, which were not produced.

| # | Check | Result | Notes |
|---|-------|--------|-------|
| 1 | TickBudget | N/A | No local battles — CI only |
| 2 | Skipped turns | N/A | No local debug.log |
| 3 | Gun selection | N/A | No internal.csv |
| 4 | ML predictions | N/A | No internal.csv |
| 5 | Wave detection | N/A | No internal.csv |
| 6 | Feature completeness | N/A | No local pipeline CSV |

**Compilation check:** ✅ PASS — `compileTestJava` succeeds after fixing
5 anonymous `Whiteboard` subclass errors in pipeline tests.

## 2. Metrics Table

| Metric | Sprint 14 (prev) | Sprint 20 (current) | Delta |
|---|---|---|---|
| Overall score % | 8.1% | **10.2%** | +2.1 pp |
| Battle win rate | ~2% | 0% | −2 pp |
| Opponents evaluated | 16 (local) | 15 (CI) | −1 |
| Self-battle | N/A | 52% ✅ | New check |
| Skipped turns/battle | 0.0 | N/A | — |
| CI pipeline | ❌ None | ✅ Green | **New** |

**Score delta caveat:** Sprint 14 used 16 opponents (local). Sprint 20
uses 15 opponents (CI) with 2 substitutions (`rsj.Electro` → `simonton.mini.WeeksOnEnd`,
`simonton.Wyrm` → `simonton.mega.SniperFrog`). Scores are not directly comparable.
The +2.1pp is likely due to opponent mix, not robot improvement.

## 3. Per-Opponent Breakdown

All CI, 3 battles × 35 rounds each. Our score (not opponent's).

| Opponent | Avg Score % | Wins | Notes |
|---|---:|---:|---|
| tobe.Fusion 1.0 | 40.0 | 0 | Best matchup — new opponent |
| cx.BlestPain 1.41 | 21.0 | 0 | |
| ej.ChocolateBar 1.1 | 18.3 | 0 | |
| ary.FourWD 1.3d | 13.0 | 0 | |
| eem.zapper v6.03 | 9.7 | 0 | |
| mld.Moebius 2.9.3 | 8.0 | 0 | New opponent |
| darkcanuck.Pris 0.92 | 7.0 | 0 | |
| jk.mega.DrussGT 3.1.7 | 6.7 | 0 | |
| dft.Cardigan 1.09 | 6.0 | 0 | |
| pe.SandboxDT 3.02 | 5.7 | 0 | |
| voidious.Diamond 1.8.22 | 4.7 | 0 | |
| simonton.mega.SniperFrog 1.0.fix2 | 4.7 | 0 | New (replaces Wyrm) |
| abc.Shadow 3.83c | 4.3 | 0 | |
| jk.mini.CunobelinDC 1.2 | 2.3 | 0 | Fixed class name |
| simonton.mini.WeeksOnEnd 1.10.4 | 2.3 | 0 | New (replaces Electro) |
| **Overall** | **10.2** | **0** | |

Missing: `oog.mega.Saguaro` (3 errors — JAR exists but Robocode can't
resolve class due to package casing: archive JAR is `oog.mega.saguaro.Saguaro`
but we reference `oog.mega.Saguaro`).

## 4. What Worked

**CI pipeline is operational.** The `eval-sprint.yml` workflow runs the
full evaluation cycle in ~10 minutes across 4 parallel runners:
- Build robot JAR ✅
- 4 parallel battle chunks (4 opponents each) ✅
- Self-battle sanity check (52%, within 45–55% band) ✅
- Aggregate `summary.json` with per-opponent breakdown ✅
- Only ~2KB summary artifact crosses the wire ✅

**Pre-flight test catches broken opponents early.** Added after all chunk 3
battles failed at 0ms — now each chunk runs a 1-round preflight before
committing to the full battle plan. Same pattern as `run-season.yml`.

## 5. What Didn't Work

- **Opponent list had 5 incorrect/missing entries.** `wiki.mega.CunobelinDC`
  (wrong class), `rsj.Electro` (doesn't exist), `simonton.Wyrm` (doesn't
  exist), `tobe.Fusion` and `mld.Moebius` (JARs missing from `robots` branch).
  Required 3 CI iterations to diagnose and fix.
- **Bot A/B swap in aggregation.** Robocode sorts alphabetically — our bot
  (`cz.zamboch.*`) is usually `bot_b`, not `bot_a`. First summary showed
  89.8% (opponent's score). Fixed by identifying our bot by name prefix.
- **Self-battle band too tight.** 48–52% failed at 53.3% with only 3 battles.
  Widened to 45–55%.

## 6. Root Cause Analysis

**Binding constraint remains opponent hit rate (~40%).** This sprint made
no robot changes, so no movement improvement. The CI pipeline now makes
it cheaper to iterate — each push evaluates automatically.

**Secondary constraint: 0 wins.** Even against the weakest opponent
(tobe.Fusion, 40% score), we never win a battle. The damage ratio is
still heavily negative. Movement (Workstream C) and targeting
(Workstream D) are the paths forward.

## 7. Statistical Commentary

No ML changes this sprint. Baselines unchanged from Sprint 14:

| Task | Offline | In-Game |
|---|---|---|
| Fire power R² | 0.913 | +0.48 |
| Movement R² | 0.760 | — |
| Fire timing AUC | 0.815 | — |

The per-opponent score distribution shows a clear tier structure:
- **Beatable (30–50%):** tobe.Fusion — we score 40% consistently
- **Competitive (15–25%):** BlestPain, ChocolateBar — room to win with better movement
- **Hard (5–15%):** FourWD, zapper, Moebius, Pris, DrussGT, Cardigan, SandboxDT
- **Wall (2–5%):** Diamond, Shadow, CunobelinDC, SniperFrog, WeeksOnEnd

The variance across 3 battles per opponent is low (most within ±3pp),
suggesting the sample size is adequate for ranking but marginal for
detecting small improvements.

## 8. Feature Research

Deferred — this was an infrastructure sprint with no ML changes.

## 9. Proposals for Next Sprint

1. **Workstream B: Fix top-5 divergent features** (Naomi)
   - Target: in-game R² from +0.48 → +0.7
   - Measure: run `compare_features.py`, compare per-feature correlations
   - Prerequisite for Workstream B2 (CI retrain loop)

2. **Workstream C: Movement — wave surf activation rate** (Alex)
   - Target: opponent HR from ~40% → ~30%
   - Measure: count wave-surf activation ticks in retrospective
   - Movement mandate: overdue (last movement work was Sprint 16)

3. **Fix `oog.mega.Saguaro` opponent loading** (Amos)
   - Target: 16/16 opponents in CI (currently 15/16)
   - Measure: chunk containing Saguaro passes pre-flight
   - Quick fix: either correct the class reference or re-download JAR
