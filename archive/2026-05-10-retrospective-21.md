# Sprint 21 Retrospective — Full CI Pipeline

*Date: 2026-05-10 · Sprint goal: Automate complete sprint cycle in CI*

## Diagnostic Health

All 7 sanity checks **PASS** (0 failures):

| # | Check | Value | Pass |
|---|-------|-------|------|
| 1 | ticks.csv rows | 2,011,142 | ✅ |
| 2 | waves.csv rows | 77,780 | ✅ |
| 3 | scores.csv rows | 3,360 | ✅ |
| 4 | internal.csv rows | 346,592 | ✅ |
| 5 | ticks columns | 86 | ✅ |
| 6 | battle count | 3,360 | ✅ |
| 7 | tick density | 2,011,142 | ✅ |

## Metrics Table

| Metric | Sprint 20 | Sprint 21 | Delta |
|---|---|---|---|
| Overall score % | 10.2% | **10.4%** | +0.2 pp |
| Battle win rate | 0% | 2.1% (1/48) | +2.1 pp |
| Self-battle | 52% | 52.3% | +0.3 pp |
| Errors | 0 | 0 | — |
| Opponents evaluated | 15 | 16 | +1 |
| Fire power R² (offline) | 0.913 | **0.840** | −0.073 |
| Movement R² (offline) | 0.760 | **0.884** | +0.124 |
| Fire timing AUC (offline) | 0.815 | **0.977** | +0.162 |
| Sanity checks | 6/6 | 7/7 | +1 check |
| Pipeline stages | manual | **2-stage CI** | automated |

### Notes on metric changes

- **Fire power R² dropped 0.913→0.840**: This is expected — the deterministic
  CSV column ordering fix changed which data the model trains on. Previously,
  misaligned CSVs produced artificially correlated noise. The 0.840 is a more
  honest baseline.
- **Fire timing AUC jumped 0.815→0.977**: Previously the model was crashing
  on misaligned data (780+ classes from column swap). Now training on properly
  aligned binary labels. The 0.977 is a legitimate improvement.
- **Movement R² improved 0.760→0.884**: Same root cause — column alignment fix.

## Per-Opponent Breakdown

| Opponent | Score % | Wins |
|---|---:|---:|
| tobe.Fusion | 45.3 | 1/3 |
| cx.BlestPain | 24.7 | 0/3 |
| ej.ChocolateBar | 16.0 | 0/3 |
| ary.FourWD | 11.3 | 0/3 |
| eem.zapper | 11.3 | 0/3 |
| mld.Moebius | 8.3 | 0/3 |
| oog.mega.saguaro.Saguaro | 8.0 | 0/3 |
| darkcanuck.Pris | 6.3 | 0/3 |
| jk.mega.DrussGT | 6.0 | 0/3 |
| dft.Cardigan | 5.3 | 0/3 |
| pe.SandboxDT | 5.3 | 0/3 |
| voidious.Diamond | 4.7 | 0/3 |
| simonton.mega.SniperFrog | 4.3 | 0/3 |
| abc.Shadow | 4.0 | 0/3 |
| simonton.mini.WeeksOnEnd | 3.0 | 0/3 |
| jk.mini.CunobelinDC | 1.7 | 0/3 |

## What Worked

1. **Full 2-stage CI pipeline operational.** Push to main → battles → CSV →
   sanity checks → model training → notebook HTML → sprint branch with new
   models. Total CI time: ~8 min (stage 1) + ~3 min (stage 2) = ~11 min.
2. **Deterministic CSV column ordering.** Transformer topological sort now
   sorts by class name, eliminating cross-JVM column misalignment.
3. **Column-aware CSV combine.** Python combiner with header remapping
   replaces naive `tail | cat` concatenation.
4. **internal.csv and debug.log now included in CI artifacts.** R08/R09/R10
   notebooks can run in CI. Sanity checks expanded from 6 to 7.
5. **Fire timing model now trains successfully** (was crashing on misaligned data).
   AUC 0.977 — best ever.

## What Didn't Work

1. **Score essentially flat** (10.2% → 10.4%). This sprint was infrastructure-only —
   no robot code changes, so no gameplay improvement expected.
2. **Fire power R² dropped** — the aligned data is harder to fit than the
   accidentally-correlated misaligned data.

## Root Cause Analysis

**Binding constraint: opponent hit rate ~40%.** The robot scores 10.4% against
16 strong opponents. The top opponent (Fusion, 45.3%) is a mid-tier bot.
Against elite bots (Shadow, Diamond, DrussGT, CunobelinDC), scores are 1.7–6%.

The movement system is the bottleneck. Wave surfing activates infrequently
and the orbital movement pattern is highly predictable to pattern-matching guns.

## CI Pipeline Architecture (delivered this sprint)

```
push to main
    │
[1] 1-sprint-battles.yml (~8 min)
    ├── build: compile robot JAR + pipeline
    ├── battles: 16 opponents × 3 battles (4 parallel chunks)
    │   + self-battle
    ├── combine-csv: Python column-aware merge (ticks, waves, scores, internal)
    │   + debug.log concatenation
    ├── sanity: 7 automated checks
    └── aggregate: summary.json + score table
         │ workflow_run [completed]
[2] 2-sprint-train.yml (~3 min)
    ├── train: 3 models in parallel (fire_power, fire_timing, movement)
    ├── notebooks: R01–R10 retrospective notebooks → HTML
    └── assemble: collect models + .dat → commit to sprint/N-models branch
```

### Bugs Fixed During Pipeline Development

| Bug | Root Cause | Fix |
|---|---|---|
| Notebooks can't find CSVs | Downloaded to `output/csv`, expected `output/local/csv` | Changed download path |
| Non-deterministic CSV columns | `HashMap` iteration order in topological sort | Sort by class name |
| `fatal: not in a git directory` | Container runs as root, checkout owned by uid 1001 | `safe.directory` |
| DtypeWarning on battle_id | Hex strings inferred as mixed types in chunked read | `low_memory=False` |
| R08/R09/R10 crash on empty df | `internal.csv` not in combined artifact | Added to combine list |
| `StopIteration` on empty CSV | Opponent `internal.csv` files are empty (no header) | Skip empty files |
| `cat` path splitting on spaces | `$LOG_FILES` split on whitespace in robot name | Null-delimited `find` |
| Training error hidden by `tail` | `2>&1 \| tail -30` swallowed the actual error | Removed pipe |
| Fire timing 780+ classes | Misaligned columns → `opponent_fired` had random values | Column alignment |

## Proposals for Next Sprint (Sprint 22)

1. **Merge sprint/21-models branch** — the retrained models with correct column
   alignment should improve in-game R². Target: fire power in-game R² > 0.5.
   *Metric:* in-game R² delta. *Owner:* Naomi.

2. **Add OPPONENT_INFERRED_GUN_HEAT in-game producer** (Workstream B.5) —
   the feature is declared and used in training but never set in-game.
   *Metric:* feature divergence count. *Owner:* Naomi.

3. **Movement: improve wave surf danger scoring** (Workstream C.1) —
   opponent HR ~40% is the binding constraint. Use per-opponent VCS profiles.
   *Metric:* opponent hit rate delta. *Owner:* Alex.
