# IDebugProperties Fidelity — Plan

> This plan depends on [IDebugProperties-intent.md](IDebugProperties-intent.md).
> The intent document does **not** depend on this plan: this file may be deleted
> once the work is complete. Scope is **Layer 0 (IDebugProperty fidelity) only** —
> the god-view quality layers (fire detection, wave/GF precision, energy) are not
> in scope except where they must be decoupled from the validated whiteboard.

## Goal

Make the integration `battleTest` green with **zero IDebugProperty mismatches
across all features, every tick, every round, for all 6 opponents** — proving the
observer is a faithful deterministic shadow of the in-game `Autopilot`.

## Done definition

`:pipeline:battleTest` passes with the Layer-0 assertion checking **all** features
(no feature exclusions) and **0** mismatches for every opponent in the matrix
(test.SittingDuck, test.Aggressive, sample.Fire, sample.Walls, sample.Crazy,
kc.mega.BeepBoop).

---

## Ordered steps

Steps are ordered, but there is a **single verification at the end** (no
per-phase checkpoint). Diagnose before prescribing each fix.

### Step 1 — Renumber Layer 5 → Layer 0 (clean refactor, no behavior change)

Rename the IDebugProperty cross-check from "Layer 5" to "Layer 0" everywhere it
is referenced, to reflect that it is the foundational fidelity layer and is
independent of the god-view layers.

- `PipelineValidator.validateDebugProperties` and its section/comment headers.
- The class-level "5 Validation Layers" javadoc list.
- `printSummary()` output labels.
- Any references in `PipelineOrchestrator` and `BattleLoopTest`.

This step changes only names/labels and ordering of output, not logic. Verify it
compiles; do not run the full battle yet.

### Step 2 — Decouple god-view from the validated whiteboard

Per intent §4: god-view must never mutate the whiteboard that Layer 0 validates.
God-view whiteboard mutation is only meaningful when the observed robot is not an
`Autopilot` (out of current scope).

- Ensure `GodViewWaveResolver` no longer overwrites `OUR_FIRE_*` / `OUR_BREAK_*`
  (and `THEIR_*`) on the **observer** whiteboard that Layer 0 reads.
- If the god-view quality layers (fire detection / wave / GF) still need their
  inputs, route those through a separate path (separate whiteboard or captured
  copy) that does not touch the Layer-0-validated observer state.
- Remove the orchestrator's "capture before / restore after" dance for
  `OUR_FIRE_*` that only exists to work around god-view contamination, once the
  contamination source is gone.

### Step 3 — Diagnose each mismatch class (no fixes yet)

Run the battle once and bucket every mismatch by feature. For each bucket,
determine the **root cause** before writing any fix. Record the cause inline in
the plan (or scratch notes). Known hypotheses to confirm/refute:

- **`OPPONENT_ID_HASH` (dominant, 24–32 / battle)** — likely an
  **instance-lifetime / identity-preservation** problem:
  - The opponent name/id is unknown until the **first scan of the first round**.
  - The live `Autopilot` loads VCS and computes `opponentHash` **once per battle**
    (`vcsLoaded` gate) and must **preserve** identity across rounds of the same
    battle.
  - The observer clears its whiteboard each round (`resetRound` →
    `clearFeatures`), so `OPPONENT_ID` / `OPPONENT_ID_HASH` are lost at round
    boundaries and not re-established until the next scan, while the live robot
    still reports the carried-forward value.
  - Confirm: is the divergence concentrated at round starts / pre-first-scan
    ticks? Does the live robot keep `OPPONENT_ID_HASH` across rounds while the
    observer resets it?
- **Spatial/opponent features (`OPPONENT_X/Y`, `OPPONENT_LATERAL/ADVANCING_VELOCITY`,
  `TICKS_SINCE_SCAN`, etc., 1–4 each)** — likely **event timing / scan
  reconstruction**: confirm whether mismatches occur only on specific ticks
  (e.g., the tick a scan is or isn't reconstructed) and whether the observer sees
  the scan one tick early/late relative to the live robot.
- **Previously-excluded features** (waves, `GUN_AIM_*`, scores) — these are not
  yet checked. Diagnose them only after Step 5 expands coverage.

### Step 4 — Fix the dominant mismatch class first

Apply the fix for the largest bucket (expected: identity preservation across
rounds). Likely shape (to be confirmed by Step 3):

- Preserve opponent identity (`OPPONENT_ID` and derived `OPPONENT_ID_HASH`)
  across rounds of the same battle in the observer, matching the live
  `Autopilot`'s per-battle (not per-round) lifetime for identity.
- Ensure the observer's round-reset does not discard battle-scoped identity state
  that the live robot carries forward.

### Step 5 — Expand Layer 0 to ALL features (remove exclusions)

Per intent §6: drop the `isWaveFeature` / `isDecisionFeature` / `isScoreFeature`
skips so every published feature is compared. The robot is 100% deterministic, so
waves, `GUN_AIM_*`, and scores must match too.

- Remove the exclusion filters in `validateDebugProperties`.
- Update the assertion the test makes so it counts **all** feature mismatches
  (replace `getNonBreakDebugPropertyMismatches()` with an all-feature count, or
  redefine it to exclude nothing).
- Re-run diagnosis (Step 3 method) for the newly-surfaced feature buckets and fix
  each root cause until they match. Expected newly-exposed causes:
  - decision features diverging because the observer was previously allowed to
    "decide independently" — make it re-derive identical decisions from identical
    inputs (deterministic shadow);
  - wave features diverging due to god-view contamination (should already be gone
    after Step 2) or fire/break timing in the observer;
  - score/cumulative features diverging due to per-round reset vs battle
    accumulation.

### Step 6 — De-self-serve the supporting unit tests (intent goal #4)

Where unit tests merely re-assert the production code's own hand-computed
expectations on synthetic stubs, strengthen them so they are grounded in engine
behavior rather than restating the implementation:

- `EventReconstructorTest` and any stub-driven tests that assert geometry the
  production code also computes: anchor at least the key assertions to
  engine-derived ground truth (e.g., values taken from a real recorded snapshot)
  rather than author-recomputed formulas.
- Keep tests that already check real outcomes (score/hit-rate baselines) as-is.
- Do not weaken coverage; add engine-grounded assertions alongside or in place of
  self-referential ones.

### Step 7 — Single verification

Build and run the integration battle test once, all opponents:

```pwsh
.\gradlew.bat :pipeline:battleTest --rerun-tasks --console=plain
```

Pass criteria: 0 IDebugProperty mismatches across all features for every opponent;
non-vacuous (Layer 0 performed comparisons); god-view did not influence the
validated whiteboard.

---

## Out of scope (this plan)

- Tightening the comparison tolerance below the current `1e-4` (deferred to a
  later phase; intent §8 keeps an appropriate round-trip tolerance for now).
- God-view quality metrics (fire-detection rate, GF MAE, energy accounting) — not
  asserted here; only their coupling to the validated whiteboard is addressed
  (Step 2).
- Non-`Autopilot` observed robots (where god-view whiteboard mutation is the
  intended behavior).
