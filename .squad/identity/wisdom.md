---
last_updated: 2026-05-09
---

# Team Wisdom

Reusable patterns and heuristics learned through work. NOT transcripts — each entry is a distilled, actionable insight.

## Patterns

<!-- Append entries below. Format: **Pattern:** description. **Context:** when it applies. -->

**Pattern:** Never merge branches without Holden reviewing each one first. **Context:** Sprint Phase 2 has a hard gate — "No branch merges to main without passing review and tests." Skipping this in Sprint 8 caused 2 of 3 fixes to backfire (fire power R² worsened -0.61 → -3.65, gun selection worsened 54% → 67%). The correct sequence is: engineers build → Holden reviews each branch → only approved branches merge → then battle.

**Pattern:** The sanity check script catches broken fixes immediately. **Context:** Always run `scripts/sanity-check.ps1` after every evaluation. It caught both failed fixes in Sprint 8 that manual inspection would have missed or delayed.

**Pattern:** Offline ML metrics don't guarantee in-game performance. **Context:** Naomi's fire power fix improved offline R² from 0.862 to 0.946, but in-game R² went from -0.61 to -3.65. The Java/Python feature computation gap is the real bottleneck — always validate with in-game R² via the sanity script before declaring a fix.

**Pattern:** "Finish the sprint" means follow all 5 phases in order, not go fast. **Context:** Coordinator collapsed Phase 2 (Build + Review) into one rush, bypassing the review gate. The sprint process exists to prevent shipping broken code.
