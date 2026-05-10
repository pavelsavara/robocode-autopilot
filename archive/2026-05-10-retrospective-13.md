# Retrospective 13 — Score 8.6% Record, First Positive R²

*Date: 2026-05-10 · Sprint: 13*
*Previous: [2026-05-10-retrospective-12.md](2026-05-10-retrospective-12.md)*

## Context

Sprint 12 delivered PrimitiveRollingBuffer (O(1) rolling stats), reducing
skipped turns from 12.6→1.8. Score was 7.9% with retrained R²=0.917 models
built into JAR but not yet battle-tested. Sprint 13 evaluates those models
plus additional optimizations.

**Changes:** Retrained models (FP R²=0.866, Mov R²=0.783, FT AUC=0.811) +
PrimitiveLongRingBuffer + wave pre-sizing + final classes.

**Evaluation:** 48 battles, 16 opponents, 3 battles × 35 rounds.

---

## 1. Diagnostic Health

| # | Check | Result | Detail |
|---|-------|--------|--------|
| 1 | TickBudget ≥ 100 | **PASS** | mean=200, min=200 |
| 2 | Skipped turns < 5 | **FAIL** | avg 1.7/battle, max 6, 4 battles ≥5 (improved from 1.8) |
| 3–6 | Gun/ML/Wave/Feature | **PASS** | all pass |
| B1 | Fire power R² (in-game) | **+0.20** | **FIRST POSITIVE R² EVER** (was −0.81) |
| B2 | Prediction calibration | 58% vs 4% | |
| B3 | Feature completeness | **PASS** | |

**Milestone: B1 fire power R² crossed zero.** Four consecutive sprints of
improvement: −3.67 → −1.44 → −1.12 → −0.81 → **+0.20**. The model is now
adding value in-game.

---

## 2. Metrics Table

| Metric | Sprint 12 | Sprint 13 | Delta |
|---|---|---|---|
| Overall score % | 7.9% | **8.6%** | **+0.7 pp** |
| Fire power R² (in-game) | −0.81 | **+0.20** | **+1.01** |
| Skipped turns (avg) | 1.8 | 1.7 | −0.1 |

**6th consecutive record.**

---

## 3. Per-Opponent Breakdown

| Opponent | Score % | Delta | Note |
|---|---|---|---|
| BlestPain | 24.0% | +3.0 | 28% single-battle record |
| ChocolateBar | 19.0% | +2.0 | |
| FloatingTadpole | 16.3% | +2.0 | |
| FourWD | 15.3% | +2.6 | |
| Gladiator | 12.0% | 0.0 | |
| GresSuffurd | 6.3% | +1.0 | |
| zapper | 6.0% | −0.7 | |
| DrussGT | 5.7% | +1.0 | |
| AgentSmith | 5.7% | +0.4 | |
| Help | 5.0% | −0.7 | |
| Cardigan | 4.7% | +0.4 | |
| Diamond | 4.0% | +1.3 | |
| Pris | 4.0% | 0.0 | |
| Shadow | 3.3% | 0.0 | |
| NewBGank | 3.3% | −1.7 | |
| BlackBox | 3.3% | +1.3 | |

**7/16 improved, 3 flat, 3 regressed (small), 3 unchanged.**

---

## 4. Retrained Models (end of pipeline)

| Model | Metric | Value |
|---|---|---|
| Fire power | R² | 0.866 |
| Movement N=5 | R² | 0.783 |
| Fire timing | AUC | 0.811 |

These are built into the JAR for Sprint 14 evaluation.

---

## 5. Sprint Result

**HIT** — Score 8.6% (project record, 6th consecutive), first positive
in-game fire power R² (+0.20), BlestPain 28% single-battle record.

**Key insight:** The R² trajectory (−3.67 → +0.20 over 5 sprints) confirms
that systematic Java/Python feature alignment is the highest-leverage work.
Each fix narrows the gap between offline and in-game performance.
