# Ceremonies

> Sprint ceremonies mapped to [sprint.md](../sprint.md) 5-phase process.
> These are HARD GATES — the coordinator MUST enforce them in order.

## Phase 1: Sprint Planning

| Field | Value |
|-------|-------|
| **Trigger** | manual |
| **When** | before |
| **Condition** | user says "start sprint", "plan next sprint", or similar |
| **Facilitator** | Holden |
| **Participants** | all-relevant |
| **Time budget** | focused |
| **Enabled** | ✅ yes |

**Agenda:**
1. Holden selects max 3 proposals from previous retrospective (1 if major)
2. Holden assigns each proposal to responsible engineer
3. Confirm fixed opponent set (16 bots from sprint.md)
4. Each engineer confirms what they're building and target metric

**Exit:** Every engineer knows their task and metric target.

---

## Phase 2a: Build (parallel)

| Field | Value |
|-------|-------|
| **Trigger** | auto |
| **When** | after planning |
| **Condition** | sprint planning complete |
| **Facilitator** | coordinator |
| **Participants** | assigned engineers |
| **Time budget** | full |
| **Enabled** | ✅ yes |

**Rules:**
1. Each change on a separate branch (worktree if enabled)
2. Code must pass existing tests before requesting review
3. Engineers implement + write tests for their changes

---

## Phase 2b: Code Review Gate (HARD GATE)

| Field | Value |
|-------|-------|
| **Trigger** | auto |
| **When** | after build, BEFORE any merge |
| **Condition** | engineers report work complete |
| **Facilitator** | Holden |
| **Participants** | Holden reviews each branch |
| **Time budget** | focused |
| **Enabled** | ✅ yes |
| **Enforcement** | ⛔ BLOCKING — NO branch merges without Holden's approval |

**⚠️ THIS IS THE GATE THAT WAS SKIPPED IN SPRINT 8, CAUSING 2 OF 3 FIXES TO BACKFIRE.**

**Process:**
1. Coordinator spawns Holden to review EACH branch via `git diff main..HEAD`
2. Holden applies the review checklist from sprint.md Phase 2
3. For each branch: APPROVE, REJECT with fix instructions, or REJECT—revert
4. Only APPROVED branches are merged to main
5. Rejected branches go back to the engineer for revision

**Checklist (from sprint.md):**
- [ ] `final` classes unless designed for inheritance
- [ ] `static` inner classes unless outer reference needed
- [ ] No mutable state in feature classes
- [ ] No I/O in core module
- [ ] No per-tick heap allocation in hot paths
- [ ] Persistence format backward-compatible
- [ ] Tests present and meaningful
- [ ] Change is logically correct

**Exit:** All approved branches merged. Rejected branches fixed or dropped.

---

## Phase 3: Battle & Record

| Field | Value |
|-------|-------|
| **Trigger** | auto |
| **When** | after all approved merges |
| **Condition** | main has all approved changes, build passes |
| **Facilitator** | Amos |
| **Participants** | Amos |
| **Time budget** | full |
| **Enabled** | ✅ yes |

**Process:**
1. Build robot JAR
2. Deploy to robocode, delete stale autopilot.dat
3. Smoke test (3 opponents, 1 battle) — abort if dramatically worse
4. Full evaluation (16 opponents, 3 battles × 35 rounds)
5. Process recordings into CSVs
6. Run `scripts/sanity-check.ps1`

**Exit:** CSVs in `output/local/csv/`, sanity check results available.

---

## Phase 4: Diagnose & Analyse

| Field | Value |
|-------|-------|
| **Trigger** | auto |
| **When** | after battle |
| **Condition** | evaluation data available |
| **Facilitator** | coordinator |
| **Participants** | all engineers (each runs their checks) |
| **Time budget** | focused |
| **Enabled** | ✅ yes |

**Process:**
1. Run sanity-check.ps1 if not already run (Amos)
2. If ANY mandatory check fails → STOP, that's the sprint finding
3. Each engineer runs their assigned retrospective notebooks
4. ML Engineer runs in-game vs offline comparison

**Exit:** All 6 sanity checks reported. Performance metrics computed.

---

## Phase 5: Retrospective & Commit

| Field | Value |
|-------|-------|
| **Trigger** | auto |
| **When** | after diagnosis |
| **Condition** | all diagnostics complete |
| **Facilitator** | Holden |
| **Participants** | all |
| **Time budget** | focused |
| **Enabled** | ✅ yes |
| **Enforcement** | ⛔ BLOCKING — sprint MUST NOT close without an archived retrospective doc |

**⚠️ HARD GATE: The retrospective document MUST be written to `archive/YYYY-MM-DD-retrospective-N.md` before the sprint can close. No exceptions — even on blocked sprints, sanity-check failures, or aborted runs, a retro doc is written explaining what happened. The coordinator MUST verify the file exists on disk before declaring the sprint closed.**

**Process:**
1. Holden writes retrospective in `archive/YYYY-MM-DD-retrospective-N.md` using engineers' data — REQUIRED, never skipped
2. Coordinator verifies the file exists on disk (e.g. `Test-Path`); if missing, sprint cannot close — re-spawn Holden
3. Update `archive/index.md` with the new retro entry
4. Revert branches that caused measurable harm
5. Commit net-positive changes with sprint summary message
6. Holden declares sprint result: win / miss / blocked (in the doc and in chat)
7. Holden proposes max 3 items for next sprint (in the doc)

**Exit:** Retrospective archived (file verified on disk), plan.md updated, sprint closed.
2. What did not ship? (open issues, blockers)
3. Root cause on any failures
4. Action items -- each MUST become a GitHub Issue labeled retro-action

**Coordinator integration:**
At round start, call Test-RetroOverdue (see skill retro-enforcement). If overdue, run this ceremony before the work queue.

**Why GitHub Issues, not markdown:**
Production data: 0% completion across 6 retros using markdown checklists, 100% after switching to GitHub Issues.
