# IDebugProperties Fidelity — Intent

> This document is self-contained. It defines **what** the IDebugProperties match
> is for and **what** "correct" means. It deliberately contains no implementation
> plan and no remediation steps — those live in a separate plan document so this
> intent can outlive any particular plan. This document does not depend on
> `replay-experience-engine.md` and remains valid if that file is deleted.

## 1. One-sentence intent

The **observer** must receive **exactly the same partial information** the
**in-game robot** had, and — because the robot is **100% deterministic** —
therefore compute the **exact same state**. The IDebugProperties match proves it.

## 2. The two roles

| Role | Description |
|------|-------------|
| **In-game robot** | The live `Autopilot` that actually fought the battle. As it plays, it publishes its entire internal state (every feature) as `IDebugProperty[]` on each tick. This is captured in the recording / snapshot stream. |
| **Observer (shadow)** | A second `Autopilot` instance that is *not* fighting. It is fed events reconstructed from the snapshot stream and re-runs its strategy, computing its own state independently. |

The observer is a **deterministic shadow** of the in-game robot. Given the same
partial information, it must reach the same decisions and the same numbers — not
"similar", not "good enough", but **identical**.

## 3. What this validation IS

A correctness check on the **replay → snapshot → observer** pipeline:

```
in-game robot ── plays ──▶ IDebugProperty[] per tick  (ground truth: what the robot actually computed)
                                   │
recording / snapshot stream ───────┤
                                   ▼
reconstructed events ──▶ observer ──▶ whiteboard per tick  (must equal the ground truth)
```

The only purpose of this validation is to **improve the quality of the
replay → snapshot → observer pipeline**. Any divergence between the in-game
robot's published debug properties and the observer's whiteboard is a **defect**
in one of:

- event reconstruction (wrong/missing/mis-ordered events from the snapshot),
- snapshot timing (the observer ran against a different tick's data),
- the rules/math the observer applied versus the in-game rules/math.

When the match is perfect, the pipeline is proven to deliver the in-game robot's
exact experience to the observer.

## 4. What this validation is NOT

- **Not** a god-view quality assessment. The in-game robot fundamentally cannot
  have god-view (exact opponent position, exact wave outcomes) — that is **by
  design of the game**. Measuring how close a robot-side estimate is to ground
  truth is a *different* concern and is explicitly **out of scope** here.
- **Not** a behavioral/strategy evaluation. We do not judge whether the robot
  plays well; only whether the shadow reproduces the robot's state.

Because of this, the IDebugProperties check must be **independent of any
god-view computation**. God-view must never mutate the whiteboard that the
observer is validated against. (God-view whiteboard mutation is only meaningful
when the observed robot is *not* an `Autopilot` and therefore publishes no debug
properties — that case is out of current scope.)

## 5. Layer naming

This fidelity check is the **foundational** layer and is renamed accordingly:

> **Layer 0 — IDebugProperty Fidelity (in-game robot vs observer)**

It precedes and is independent of the god-view quality layers (fire detection,
wave/GuessFactor precision). It does not read from, write to, or depend on any
god-view resolution.

## 6. Scope of features

**All features the in-game robot publishes must match — no exclusions.**

This is achievable because the `Autopilot` is 100% deterministic: identical
partial inputs produce identical outputs across the entire feature set,
including features that were previously excluded:

- wave features (`OUR_WAVES`, `THEIR_WAVES`),
- aim/decision features (`GUN_AIM_*`),
- score / cumulative features (`ROUND_*`).

The previous justification for excluding aim and wave features — "the observer
makes independent decisions" — is rejected. A deterministic shadow does **not**
make independent decisions; it re-derives the **same** decisions from the same
inputs. Any feature that diverges indicates the shadow did not receive, or did
not apply, the same partial information.

## 7. Perspective

The match is validated on the **perspective where the live `Autopilot` actually
fought** (the side whose snapshots carry `IDebugProperty[]`). The opposing
perspective has no in-game debug properties to compare against and is therefore
out of scope for this validation.

## 8. Comparison semantics

- Each tick, every published debug property is compared to the observer's
  whiteboard value for the same feature.
- Comparison is gated on **same tick**: the debug properties and the observer
  state must belong to the same turn before they are compared.
- `NaN` matches `NaN`; `NaN` versus a number is a mismatch (and vice versa).
- Values pass through a **string round-trip** between the two sides (the in-game
  robot serializes each feature to a string debug property; the observer value is
  numeric). The comparison tolerance must be **appropriate to that round-trip**:
  tight enough that it cannot mask a real divergence, loose enough that it does
  not flag pure string-formatting noise. Exact value equality is the target;
  the tolerance exists only to absorb serialization, not to forgive logic errors.

## 9. Acceptance criteria

The pipeline meets this intent when, for the perspective where the `Autopilot`
fought:

1. **Zero mismatches** across **all** features, on **every** tick, for **every**
   round of a battle.
2. Holds for **every opponent** in the integration matrix (deterministic robot ⇒
   no opponent-specific tolerance).
3. The check is **non-vacuous**: at least one feature is compared on a
   representative number of ticks (a battle that produced no comparisons is a
   failure, not a pass).
4. The check runs with **no god-view input** influencing the observer whiteboard.

Until criterion 1 holds across all features, the replay → snapshot → observer
pipeline is not yet a faithful reproduction of the in-game experience.
