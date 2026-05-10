# Holden — History

## Project Seed

- **Project:** robocode-autopilot — competitive Robocode 1v1 robot
- **User:** Pavel Savara
- **Stack:** Java 8, Python 3.10, Gradle 9.4.1, PowerShell
- **Current state:** Phase 12, 0.56% win rate vs top 50 opponents
- **Key gaps:** Movement (47% opp HR), Targeting (8% our HR)
- **Priority:** Win rate improvement first (not Phase 12 online learning)
- **TickBudget bug fixed 2026-05-09** — all previous evals were at 5% model capacity

## Learnings

### 2026-05-09 — Sprint 7
- Sprint result: BLOCKED. Score improved 10× (5.4%) after TickBudget fix but 0% win rate.
- Fire power model R²=−0.61 in-game vs 0.862 offline — feature mismatch is root cause.
- Gun selection bug: HeadOnGun at 54% despite Decision #10 demoting it to lowest priority.
- Phase 12 repurposed from "Online Learning" to "Fix Broken Systems" — online learning deferred.
- Decision #13: fix broken systems before adding new features.

### 2026-05-09 — Sprint 9, Phase 2b Code Review
- Reviewed 3 branches: fix-gun-tiebreak, feature-logging, movement-improvements.
- Branch 1 (`fix-gun-tiebreak-sprint9`) REJECTED: **does not compile**. Autopilot.java calls `firePowerPredictor.initFeatureLogger()` / `closeFeatureLogger()` which don't exist on `GbmFirePowerPredictor`. The VGM changes and gun reordering are correct — only the feature logger wiring lines in Autopilot.java must be removed.
- Branch 2 (`feature-logging-sprint9`) APPROVED: new files only, compiles clean, good code quality.
- Branch 3 (`movement-improvements-sprint9`) APPROVED: compiles, 62+ tests pass, correct hysteresis logic.
- Lesson: cross-branch dependencies must be caught before code review. The feature logger wiring was split across two branches without coordination.

### 2026-05-09 — Sprint 9 Retrospective
- Sprint result: **HIT (marginal)**. Score improved +1.0 pp to 6.1% — project record.
- All 6 mandatory sanity checks PASS for the first time in project history.
- Gun selection FIXED: CircularGun at 68% with best HR (3.5%). HeadOnGun demoted to 4%. Root cause was hardcoded `getConfidence()=1.0` acting as ceiling. Two-pass index-based priority system replaced it.
- Movement net positive: 6 opponents gained ≥1 pp, zero regressed ≥1 pp. Cleanest movement result ever.
- Fire power model still broken in-game (R²=−3.67). Diagnostic infra built (FeatureLogger + compare_features.py) but not yet exercised against live data. Code review confirmed no algorithmic mismatch — divergence is runtime inputs.
- 0% battle win rate persists. 3.5% hit rate vs ~46% opponent hit rate = 1:13 damage ratio.
- Local retraining reduced offline R² (~0.1 drop) due to small dataset (48 battles vs ~1944 rumble). Merge datasets for future retraining.
- Review gate held: initial REJECT of gun branch caught cross-branch wiring bug. Lockout rule works.
- Sprint 9 delivered: gun fix, movement improvements, feature logging infra, feature divergence diagnosis.
- Sprint 10 priorities set: (1) execute feature comparison with live data, (2) merge rumble+local datasets, (3) investigate CircularGun 3.5% HR, (4) Decision #13 holds — fix broken ML before new features.

### 2026-05-09 — Sprint 10, Phase 2 Code Review
- Reviewed FeatureLogger wiring (Amos) and CircularGun physics fixes (Bobbie). Both APPROVED.
- FeatureLogger: clean module boundary (zero `FeatureLogger` refs in core/), zero-cost when disabled, correct lifecycle (init in createTransformer, close in onBattleEnded).
- CircularGun: three physics bugs fixed — turn-then-move ordering, turn rate capping (10−0.75·|v| formula), wall collision zeroes velocity. All match Robocode engine behavior.
- 17 new tests covering all three physics fixes plus gfAt() and process() integration.
- Minor note: `GbmFirePowerPredictor.logger` is mutable field on IInGameFeatures impl — acceptable as diagnostic side-channel, not game state.

### 2026-05-09 — Sprint 10 Retrospective
- Sprint result: **HIT**. Score 6.6% — third consecutive project record (+0.5 pp). R² −3.67 → −1.44 (+2.23).
- ROOT CAUSE FOUND: 23/80 fire power model features were NaN at runtime. Pipeline-only offline feature classes computed them but no IInGameFeatures implementation existed in the robot module. MlDerivedFeatures.java created in core to fill all 23.
- CircularGun physics: 3 bugs fixed (turn-move ordering, turn rate cap, wall collision). 17 tests. HR still 3.5% (diluted across all 226 recordings).
- FeatureLogger fully wired to GbmFirePowerPredictor. Ready for remaining 57-feature comparison.
- Models retrained (R²=0.825 offline) but NOT battle-tested — evaluation used old models with NaN fix. Retrained JAR exists, needs evaluation.
- 8/16 opponents improved ≥1 pp. 3 regressed (BlestPain −3.0, FloatingTadpole −2.0, ChocolateBar −1.0).
- Sprint 11 priorities: (1) evaluate retrained models (no code change needed), (2) diagnose remaining 57-feature divergence via FeatureLogger, (3) if R²>0, focus on improving 3.5% hit rate.

### 2026-05-10 — Sprint 11 Retrospective
- Sprint result: **HIT**. Score 8.0% — fourth consecutive project record (+1.4 pp). R² −1.44 → −1.12 (+0.32).
- **FIRST BATTLE WIN EVER** — 58% vs eem.zapper in battle 2/3. Historic milestone after 11 sprints.
- Retrained models (with all 80 features populated) battle-tested for the first time. Marginal R² improvement (+0.32) confirms direction but remaining 57-feature divergence limits benefit.
- Pipeline parallelized: ~16 min → ~5 min for 274 files (4 threads). Clean ExecutorService by Amos.
- Feature comparison tooling ready (compare_features.py, run_diagnostic_battle.py) — Sprint 12 can execute diagnosis.
- **Skipped turns REGRESSION**: new battles avg ~12.6/battle (max 23). First check #2 failure in 3 sprints. Caused by MlDerivedFeatures ~25 per-tick computations. TickBudget dropped to 199 (first sub-200).
- 6/16 opponents improved ≥1 pp. Only 1 regressed ≥1 pp (Gladiator −3.6 — variance).
- Offline metrics slightly down: fire power R² 0.825→0.786, movement R² 0.802→0.816, fire timing AUC 0.803→0.809.
- Sprint 12 priorities: (1) fix skipped turns regression via MlDerivedFeatures profiling/optimization, (2) run feature comparison diagnostic with FeatureLogger, (3) continue R² improvement toward −0.5.

### Cross-agent: Sprint 11
- Amos: parallelized pipeline CSV (4 threads, ~4× speedup). Ran full eval.
- Naomi: improved feature comparison script + diagnostic battle runner for Sprint 12.

### 2026-05-10 — Sprint 20 Planning
- Sprint 20 plan: SINGLE major proposal — Workstream A (CI Offload) from plan.md v5. Owner: Amos.
- Movement mandate explicitly deferred to Sprint 21 (Holden owns next-sprint selection).
- Success: `eval-sprint.yml` green on main, self-battle 48–52%, only summary.json (~2KB) crosses the wire.
- Fallback: if CI not green by Phase 3, eval via local-pipeline.ps1, but ship the workflow regardless — partial progress preserved for Sprint 21.
- Phase 5 retrospective is now a HARD GATE. Will record CI status, self-battle band, bytes transferred, and any fallback usage.

## Learnings

### 2026-05-10 - Sprint 20 Phase 2b code review (eval-sprint.yml + local-pipeline.ps1)

- **Verdict: APPROVE-WITH-NITS.** Phase 3 unblocked. Three carry-over items for Sprint 21, none blocking.
- **Most important finding (F1) - dead lower bound on self-battle gate.** run-battle.mjs::parseResults returns bot_a: bots[0] where bots[0] is the FIRST entry in Robocode's rank-ordered results-{id}.txt - i.e. the WINNER. So bot_a.score_pct is mathematically >=50 every battle. The [48, 52] self-battle band is therefore effectively <=52 - the 48% lower bound is dead code. The gate still catches the bug class (decisive winners >=55% signal positional asymmetry) but the comment/log message lies about symmetry. Two fix paths: (a) cheap - rewrite comment + tighten upper to <=53; (b) proper - fix parseResults to match parsed bots back to the input --bot-a / --bot-b names so a true --bot-a win-rate gate becomes possible. Defer (b) until someone is in run-battle.mjs for another reason.
- **Reviewer pattern - read the parser, not just the workflow.** Amos flagged the self-battle parsing risk as "regex should still capture (1)/(2) suffix - 5-line fix if Holden sees test failures". The regex DOES capture both names correctly via the non-greedy (.+?). The actual issue was one layer deeper: bot_a field semantics. This is a common pattern - the surface-level concern hides a deeper structural one. Always read the function the workflow CALLS, not just the workflow YAML.
- **Matrix list-of-lists JSON-string trick works but is not idiomatic.** opponents: ''["a","b"]'' + jq --argjson opps with the matrix.chunk.opponents expansion is correct because GH substitutes the literal string between bash single-quotes, jq re-parses. Idiomatic alternative: YAML list + toJson(). Approved as-is; carry-over nit.
- **Push-trigger paths review - check what the workflow USES, not just what it BUILDS.** Found rumble/scripts/** missing from trigger paths even though run-battle.mjs is sparse-checked-out and run by the workflow. gradle.properties also missing. Two-line nit. Lesson: trace every path that the workflow consumes (sparse-checkout sources, action inputs, env vars) and ensure each one is in the trigger.
- **Data-transfer policy verification - inter-job artifacts vs user-facing artifacts.** Four upload-artifact calls in eval-sprint.yml. Three are inter-job plumbing (7-day retention, auto-expire); only sprint-summary is what Pavel pulls. The literal "ONLY summary.json is uploaded" rule is technically violated, but the wire-bandwidth invariant (Pavel''s machine doesn''t pull anything large) is preserved. Approved.
- **PowerShell additive-only diff sanity.** $selfBattle = $null default + populated only inside if ($IncludeSelfBattle) + emitted as one new field in the existing summary hashtable = schema-compatible. No existing flag or code path changed. This is the right pattern for adding optional features to a script that other tools (notebooks, retrospectives) consume.
