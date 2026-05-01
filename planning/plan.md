---
tags: [projects, robocode, ML]
created: 2026-04-12
updated: 2026-04-22
sources: 1
---

# GitHub Actions Rumble — Implementation Plan

## Overview

Build a self-hosted RoboRumble competition system using GitHub Actions as the compute platform, running in `pavelsavara/robocode` on the `actions-rumble` branch. Battles run via Robocode's headless engine in Docker containers. Rankings are computed and published as static HTML/JSON to GitHub Pages.

## Decisions Log

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Same repo (`pavelsavara/robocode`) | Leverage existing Dockerfile, build system, and branch structure |
| D2 | Start with top-30 by APS, scale to 100 later | Under 4 min with 20 parallel runners, iterate fast |
| D3 | Reimplement ranking/scoring from scratch | LiteRumble is Python 2.7/GAE — 90% is GAE Datastore/memcache/taskqueue plumbing. The portable scoring logic is ~200 lines of arithmetic. Faster to rewrite than port |
| D4 | Robot JARs stored via Git LFS in data branch | Full control, no broken URLs |
| D5 | Plain HTML/CSS + JSON data for pages | Simple, durable, like LiteRumble |
| D6 | eclipse-temurin:21 JDK | Current LTS, matches existing Dockerfile |
| D7 | Both PR and Issue for bot submissions | Open, flexible |
| D8 | **JS/TS (Node.js)** for all tooling | Pre-installed on GH runners (no setup step), native JSON, lighter Docker images, GH toolkit is JS/TS |
| D9 | Build Robocode from source in CI | `gradlew build` + `actions/cache` for Gradle deps. No pre-built artifacts to manage |
| D10 | 1v1 only — no melee/team/twinduel | Scope control for entire project |
| D11 | Manual trigger only (workflow_dispatch) | Measure battle times first, then decide on automation schedule |
| D12 | Keep all historical results | Reset only when data layout changes |
| D13 | Future plugin: IBattleListener JAR | Separate plugin JAR, not source fork. Affects Dockerfile: must support loading plugin JARs |
| D14 | Pages at `pavelsavara.github.io/robocode/` | Default GitHub Pages path |
| D15 | Top-30 from LiteRumble rankings | Scrape current APS rankings from literumble.appspot.com |
| D16 | `execFileSync` with 120s timeout | Some bots (DrussGT, Gilgalad) hang or crash the JVM. 2-minute timeout auto-fails them |
| D17 | Incremental result saves + resume | Write `results.json` after each battle. On restart, skip completed pairings via `completedPairs` Set |
| D18 | No Docker for local runs | Direct `java -cp libs/* robocode.Robocode` invocation. Docker only for CI |
| D19 | Mirror fallback for bot downloads | `robocode-archive.strangeautomata.com` as backup when primary URLs fail |
| D20 | Wiki `<pre>` block parser | Single-line `fullName,URL` format, not alternating 2-line. Some entries split across lines |

## Architecture

### Branch Layout

```
actions-rumble     ← workflows, Dockerfiles, scripts, scoring code (this branch)
gh-pages           ← generated static site (rankings HTML + JSON data)
data/robots        ← Git LFS: robot JARs, participants.txt, results JSON
data/battles       ← (future) CSV tick data from custom plugin
```

### Components

```
┌──────────────────────────────────────────────────────────────────┐
│                    GitHub Actions Workflows                       │
│                                                                   │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────┐  │
│  │ scrape-wiki  │  │ run-battles  │  │ compute-rankings       │  │
│  │ (cron daily) │  │ (matrix,     │  │ + publish-pages        │  │
│  │              │  │  parallel)   │  │ (after battles done)   │  │
│  └──────┬───────┘  └──────┬───────┘  └────────────┬───────────┘  │
│         │                 │                        │              │
│         ▼                 ▼                        ▼              │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐  │
│  │ data/robots  │  │ results/     │  │ gh-pages branch        │  │
│  │ branch (LFS) │  │ (artifacts)  │  │ (static HTML + JSON)   │  │
│  └──────────────┘  └──────────────┘  └────────────────────────┘  │
│                                                                   │
│  ┌──────────────┐  ┌──────────────┐                              │
│  │ bot-submit   │  │ season-      │                              │
│  │ (issue/PR    │  │ scheduler    │                              │
│  │  trigger)    │  │ (cron weekly)│                              │
│  └──────────────┘  └──────────────┘                              │
└──────────────────────────────────────────────────────────────────┘
```

### Docker Images

**1. `robocode-battle-runner`** — Runs headless battles
```
Base: eclipse-temurin:21-jre-alpine
Contents: Robocode headless install, battle execution script
Input: Bot A JAR, Bot B JAR, battle config
Output: Results JSON (scores, survival, bullet damage, etc.)
```

**2. `robocode-planner`** — Plans battles, computes rankings, generates pages
```
Base: node:22-alpine
Contents: Battle planner, APS/ANPP scorer, HTML generator (JS/TS)
Input: Participants list, previous results
Output: Battle pairs file, rankings JSON, static HTML
```

## Data Formats

### participants.txt
```
# Format: fully.qualified.BotName version,jar_filename
# One bot per two lines (matching wiki format)
# Lines starting with # are comments
abc.Shadow 3.83c,abc.Shadow_3.83c.jar
voidious.Diamond 1.8.22,voidious.Diamond_1.8.22.jar
jk.mega.DrussGT 3.1.7,jk.mega.DrussGT_3.1.7.jar
```

### Battle Result (per battle)
```json
{
  "battle_id": "abc.Shadow_3.83c-vs-voidious.Diamond_1.8.22-20260412-001",
  "timestamp": "2026-04-12T14:30:00Z",
  "season": "2026-S1",
  "config": {
    "rounds": 35,
    "field_width": 800,
    "field_height": 600
  },
  "bot_a": {
    "name": "abc.Shadow 3.83c",
    "total_score": 2847,
    "survival": 1750,
    "bullet_damage": 892,
    "bullet_bonus": 134,
    "ram_damage": 58,
    "ram_bonus": 13,
    "firsts": 25,
    "seconds": 10,
    "thirds": 0
  },
  "bot_b": {
    "name": "voidious.Diamond 1.8.22",
    "total_score": 3102,
    "survival": 1850,
    "bullet_damage": 1012,
    "bullet_bonus": 156,
    "ram_damage": 62,
    "ram_bonus": 22,
    "firsts": 27,
    "seconds": 8,
    "thirds": 0
  }
}
```

### Rankings JSON (aggregate)
```json
{
  "game": "roborumble",
  "generated": "2026-04-12T15:00:00Z",
  "season": "2026-S1",
  "total_battles": 435,
  "rankings": [
    {
      "rank": 1,
      "name": "voidious.Diamond 1.8.22",
      "aps": 87.42,
      "survival": 92.1,
      "pairings": 29,
      "battles": 145,
      "vote": 93.1,
      "last_battle": "2026-04-12T14:30:00Z"
    }
  ]
}
```

## Scoring System (Reimplemented from LiteRumble)

### APS (Average Percentage Score)
Per pairing: `APS = 100 * my_score / (my_score + opponent_score)`
Overall APS = average across all pairings.

### Survival %
Per pairing: `Survival = 100 * my_survival_score / max_possible_survival`
For 1v1 with 35 rounds: max = 50 * 35 = 1750 points.

### Vote %
`Vote = 100 * pairings_won / total_pairings`
A pairing is "won" if APS > 50 against that opponent.

### ANPP (Average Normalised Percentage Pairs)
LiteRumble's batch-computed metric. Per pairing: normalize APS relative to all other bots' APS against same opponent. Average across pairings.

## Workflow Specifications

### 1. `scrape-wiki.yml` — Sync Participants from RoboWiki

**Trigger:** `workflow_dispatch`

**Steps:**
1. Fetch `https://robowiki.net/wiki/RoboRumble/Participants?action=raw`
2. Parse participant entries (format: `package.Bot\nversion,url`)
3. Filter to configured subset (top-30 initially, by curated list)
4. For each bot not already in LFS:
   - Download JAR from URL
   - Validate: must be a valid JAR with `.properties` file
   - Store in `data/robots` branch via Git LFS
5. Update `participants.txt` in `data/robots` branch
6. Commit changes

**Outputs:** Updated `participants.txt`, new JARs in LFS

### 2. `plan-battles.yml` — Generate Battle Schedule

**Trigger:** `workflow_dispatch` + called by `run-season.yml`

**Steps:**
1. Read `participants.txt` from `data/robots` branch
2. Read existing results (if any) from `data/robots` branch
3. Generate all N×(N-1)/2 pairings (30 bots = 435 battles)
4. Split into chunks — **chunk size TBD after measuring single battle time**
5. Output battle matrix as JSON artifact

> [!note]
> GitHub Actions limit: **20 parallel jobs** (free account), each with **1 CPU**.
> With 30 bots (435 battles), if one battle takes ~10s, that's ~4,350s total.
> With 20 runners: ~218s per runner (~22 battles each) = **~4 min wall clock**.
> Need to verify actual battle time first — could be 5-30s per battle.

**Outputs:** `battle-matrix.json` artifact

### 3. `run-battles.yml` — Execute Battles (Parallel Matrix)

**Trigger:** Called by `run-season.yml` with matrix input

**Strategy:** `matrix` with chunks from planner, `max-parallel: 20`

**Per job steps:**
1. Pull `robocode-battle-runner` Docker image (or build from Dockerfile)
2. Checkout bot JARs from `data/robots` branch (LFS)
3. For each battle in chunk:
   - Copy bot JARs into Robocode's `robots/` directory
   - Run headless battle via Robocode Control API
   - Parse output → battle result JSON
4. Upload results as artifact

**Outputs:** Battle result JSON artifacts

### 4. `compute-rankings.yml` — Score & Publish

**Trigger:** Called after all battles complete

**Steps:**
1. Download all battle result artifacts
2. Merge with historical results in `data/robots` branch
3. Compute rankings (APS, Survival, Vote, ANPP)
4. Generate static HTML pages + JSON data files
5. Commit results to `data/robots` branch
6. Deploy HTML to `gh-pages` branch

**Outputs:** Updated `gh-pages`, updated historical results

### 5. `run-season.yml` — Orchestrator

**Trigger:** `workflow_dispatch` only (manual for now)

**Steps:**
1. Call `scrape-wiki.yml` (or assume already run)
2. Call `plan-battles.yml`
3. Call `run-battles.yml` with battle matrix
4. Call `compute-rankings.yml`

### 6. `bot-submit.yml` — Handle Bot Submissions

**Trigger:** `issues: opened` (with label `bot-submission`) + `pull_request` (to `data/robots` branch)

**For Issues:**
1. Parse issue body (template: bot name, version, JAR URL)
2. Download and validate JAR
3. Add to `participants.txt` and commit JAR to `data/robots` branch
4. Comment on issue with confirmation
5. Close issue

**For PRs:**
1. Validate that PR only modifies `participants.txt` and/or adds JARs
2. Validate JAR files
3. Auto-merge if valid (or request review)

## Directory Structure (on `actions-rumble` branch)

```
robocode/
├── .github/
│   ├── workflows/                  # (Phase 4 — not yet created)
│   └── ISSUE_TEMPLATE/             # (Phase 4)
├── rumble/
│   ├── scripts/
│   │   ├── scrape-wiki.mjs         # ✅ Wiki parser + JAR downloader
│   │   ├── plan-battles.mjs        # ✅ Battle planner (chunked pairings)
│   │   ├── run-battle.mjs          # ✅ Battle executor (single + batch, timeout, resume)
│   │   ├── compute-rankings.mjs    # ✅ Scoring engine (APS, ANPP, rolling avg)
│   │   └── generate-pages.mjs      # ✅ Static site generator (dark theme, flags, SVG)
│   ├── flags/
│   │   ├── flags.json              # ✅ 260 package→country mappings
│   │   └── *.gif                   # ✅ 57 country flag images
│   ├── robots/                     # .gitignored — JARs on data/robots branch
│   ├── results/
│   │   └── results.json            # ✅ Incremental battle results (293 battles)
│   ├── site/                       # .gitignored — deployed to gh-pages
│   ├── top30.txt                   # ✅ Curated bot list (30 bots)
│   ├── participants.txt            # ✅ Parsed from wiki (filtered to top 30)
│   ├── battles.json                # ✅ 435 battles in 20 chunks
│   ├── rankings.json               # ✅ Computed rankings
│   └── package.json                # ✅ No external dependencies
├── .sandbox/                       # Robocode headless installation
│   ├── libs/                       # Robocode JARs
│   ├── robots/                     # Bot JARs (copied from rumble/robots/)
│   └── battles/                    # Temp battle files
├── Dockerfile                      # Existing robocode Dockerfile
└── ... (existing robocode source)
```

## Implementation Phases

### Phase 1: Foundation (MVP) ✅ COMPLETE
**Goal:** Run a single battle locally, produce a result file.

1. ✅ **Java 21 installed** — Temurin JDK 21 on local machine
2. ✅ **Robocode built** — `./gradlew build` produces working install at `.sandbox/`
3. ✅ **run-battle.mjs** — Node.js script executes headless battles via `java -cp libs/* robocode.Robocode` with `-nodisplay -DNOSECURITY=true`
4. ✅ **Single battle tested** — BeepBoop vs Diamond, 5 rounds, 17.5s. Working.
5. ✅ **Result parsing** — Regex parses Robocode's text results into structured JSON (scores, survival, bullet damage, firsts/seconds)

### Phase 2: Battle Infrastructure ✅ COMPLETE
**Goal:** Run all battles for 30 bots, collect results.

1. ✅ **top30.txt** — Curated from LiteRumble rankings (1211 active bots scraped)
2. ✅ **scrape-wiki.mjs** — Parses RoboWiki `<pre>` block (1210 bots found), downloads JARs, supports `--top-list` filter
3. ✅ **plan-battles.mjs** — Generates N×(N-1)/2 pairings in chunks (30 bots = 435 battles, 20 chunks)
4. ✅ **run-battle.mjs** — Batch mode with incremental saves, 120s timeout, resume support
5. ✅ **30 bot JARs downloaded** — All from wiki URLs + 2 from strangeautomata mirror (5.6 MB total)
6. ✅ **Results accumulating** — `results/results.json` saves after each battle

### Phase 3: Scoring & Pages ✅ COMPLETE
**Goal:** Compute rankings, generate static site.

1. ✅ **compute-rankings.mjs** — APS, Survival, Vote, PWin, ANPP scoring with LiteRumble's rolling average + decay
2. ✅ **generate-pages.mjs** — Dark-theme HTML: index page, 29 bot detail pages, 268 compare pages
3. ✅ **Flags** — 57 country GIF flags, 260 package→country mappings in `flags.json`
4. ✅ **Inline SVG scatter plots** — Score distribution on bot pages
5. ✅ **Sortable columns** — Click to sort by APS, Survival, Vote, etc.
6. ✅ **Deploy to gh-pages** — Live at `pavelsavara.github.io/robocode/`
7. ✅ **Restyled** — Dark gray theme matching robo-code.blogspot.com, Robocode logo in header
8. ✅ **Robot catalog** — `data/robots` branch with 30 JARs + `index.json` metadata
9. ✅ **Bot metadata on detail pages** — Author, country, wiki link, JAR download, GitHub mirror

### Phase 4: Orchestration & Automation — NOT STARTED
**Goal:** GitHub Actions workflows for automated runs.

1. ⬜ **run-season.yml** — Orchestrator workflow
2. ⬜ **run-battles.yml** — Matrix workflow for parallel execution
3. ⬜ **compute-rankings.yml** — Score + deploy to gh-pages
4. ⬜ **scrape-wiki.yml** — Periodic bot sync
5. ⬜ **bot-submit.yml** — Issue/PR bot submission handler
6. ⬜ **Git LFS setup** — `data/robots` branch for JAR storage
7. ⬜ **Season history** — Track rankings over time

### Phase 5: Battle Data Recording ✅ COMPLETE
**Goal:** Per-tick data capture for ML pipeline.

1. ✅ **Built-in recording** — Robocode's existing `RecordManager` + `BattleRecorder` captures full per-tick state via `-record` CLI flag
2. ✅ **run-battle.mjs** — Added `--record-dir` option, passes `-record` to Java CLI
3. ✅ **Binary format** — `.br` (zipped binary) files, ~500KB/3-round battle, full precision
4. ✅ **Stage 2 reads directly** — `RecordManager.loadRecord()` + `provideTurns()` — no intermediate CSV needed
5. ⬜ **Data upload** — Commit `.br` files to `data/battles` branch or upload as artifacts

## Pre-requisites & Setup Steps

### On the machine (one-time)
```powershell
# Java SDK (needed to build Robocode locally)
winget install EclipseAdoptium.Temurin.21.JDK
# or: choco install temurin21

# Verify
java -version
./gradlew build
```

### On the GitHub repo
```bash
# Enable Git LFS
gh repo edit pavelsavara/robocode --enable-lfs  # if not already

# Create data branch (orphan)
git checkout --orphan data/robots
git rm -rf .
echo "# Robot JARs and participants data" > README.md
echo "*.jar filter=lfs diff=lfs merge=lfs -text" > .gitattributes
git add README.md .gitattributes
git commit -m "Initialize data/robots branch"
git push origin data/robots

# Enable GitHub Pages on gh-pages branch (already exists)
gh api repos/pavelsavara/robocode/pages -X PUT -f source.branch=gh-pages -f source.path=/
```

### GitHub Actions Secrets/Variables
| Name | Purpose |
|------|---------|
| `GITHUB_TOKEN` | Default, sufficient for commits and pages |
| (none extra needed for public repo) | |

## Robocode Build & Headless Execution

### Building Robocode
```bash
cd robocode
./gradlew build
# Produces: build/robocode-1.10.2-setup.jar
```

### Headless Battle Execution
```bash
# Install headless
java -jar build/robocode-1.10.2-setup.jar

# Run via roborumble CLI (existing mechanism)
cd robocode-install-dir
./roborumble.sh  # Uses roborumble.txt config

# Or via Control API (Java program)
# Use BattlesRunner with custom properties pointing to local files
```

### Custom Battle Runner (simplified headless)
Instead of the full RoboRumble@Home client (which downloads from network, uploads to server), we write a thin wrapper:

```java
// SimpleBattleRunner.java — runs one battle, outputs JSON
RobocodeEngine engine = new RobocodeEngine(new java.io.File("."));
engine.addBattleListener(new BattleAdaptor() {
    public void onBattleCompleted(BattleCompletedEvent e) {
        // Emit JSON with results
    }
});
BattlefieldSpecification field = new BattlefieldSpecification(800, 600);
RobotSpecification[] robots = engine.getLocalRepository("bot.A,bot.B");
BattleSpecification spec = new BattleSpecification(35, field, robots);
engine.runBattle(spec, true);
engine.close();
```

## Estimated Timeline

| Phase | Effort | When |
|-------|--------|------|
| Phase 1: Foundation | First working battle | — |
| Phase 2: Battle Infrastructure | Parallel battles for 30 bots | — |
| Phase 3: Scoring & Pages | Rankings site live | — |
| Phase 4: Orchestration | Fully automated | — |
| Phase 5: Custom Plugin | Per-tick data capture | Future |

## Open Questions (Resolved)

1. ~~Bot curation~~ → **LiteRumble top-30 by APS** (scrape current rankings)
2. ~~Seasons~~ → **Manual trigger** (workflow_dispatch), measure battle times first
3. ~~History~~ → **Keep all results**, reset on data layout changes
4. ~~Melee~~ → **1v1 only** for entire project scope
5. ~~Notifications~~ → **None for MVP**

## Current Status (2026-04-12)

### Battles Complete (partial season)
- **293/435 battles** (285 OK, 8 transient errors)
- Broken bots replaced: Gilgalad → mn.Combat #34, Roborio → CassiusClay #32
- All 30 bots now functional

### Measured Battle Times (local, Windows, JDK 21)
| Metric | Value |
|--------|-------|
| Average | **14.6s** per 35-round battle |
| Minimum | 3.2s (weak bots, quick kills) |
| Maximum | 116.7s (heavy bots like DrussGT, near timeout) |
| Timeout | 120s (auto-fail) |

### Rankings (top 5 of 30 bots)
| Rank | Bot | APS | Survival | Vote |
|------|-----|-----|----------|------|
| 1 | kc.mega.BeepBoop 2.0 | 79.97 | 95.13% | 100% |
| 2 | aaa.r.ScalarR 0.005h | 69.66 | 83.96% | 100% |
| 3 | jk.mega.DrussGT 3.1.7 | 68.29 | 80.82% | 95.24% |
| 4 | voidious.Diamond 1.8.22 | 66.21 | 78.32% | 88.24% |
| 5 | rsalesc.mega.Knight 0.6.28 | 62.98 | 77.98% | 94.12% |

### Deployed
- **Site**: `pavelsavara.github.io/robocode/` — 30 bot pages + 285 compare pages + index
- **Robot catalog**: `data/robots` branch — 30 JARs + `index.json` metadata
- **Theme**: Dark gray (robo-code.blogspot.com style), Robocode logo, green accents

### Scripts Implemented
| Script | Status | Description |
|--------|--------|-------------|
| `scrape-wiki.mjs` | ✅ Working | Parse wiki, download JARs, `--top-list` filter |
| `plan-battles.mjs` | ✅ Working | Generate N×(N-1)/2 pairings in chunks |
| `run-battle.mjs` | ✅ Working | Single + batch mode, timeout, incremental save, resume |
| `compute-rankings.mjs` | ✅ Working | APS, Survival, Vote, PWin, ANPP with rolling average |
| `generate-pages.mjs` | ✅ Working | Full static site with flags, compare pages, SVG plots |
| `build-catalog.mjs` | ✅ Working | Builds robots/index.json with metadata from wiki |

## Remaining Open Questions

1. ~~**Battle time**~~ → **ANSWERED: avg 14.6s** (range 3-117s). With 20 GH runners, 435 battles at ~15s = ~22 battles/runner = ~330s/runner = **~6 min wall clock**.
2. **Gradle caching:** How much does `actions/cache` help with Robocode build times?
3. ~~**LFS quota**~~ → Using `data/robots` branch (no LFS). 30 JARs (~5.6 MB) committed directly.
4. **Plugin loading:** Robocode's plugin mechanism — does `IBattleListener` work via external JAR in classpath, or needs registration?
5. ~~**Broken bots**~~ → **RESOLVED**: Gilgalad replaced with mn.Combat #34, Roborio replaced with CassiusClay #32.
6. **GH Actions runner battle time:** Local times measured, but GH runners have different CPU. Need to validate.
7. **Workflow architecture:** Single workflow with `java -cp` directly, or Docker container? Direct Java is simpler but less reproducible.
8. **Complete season:** 293/435 battles done. Need to run remaining 142 battles.

## References

- [RoboRumble Entry](https://robowiki.net/wiki/RoboRumble/Enter_The_Competition)
- [LiteRumble](https://literumble.appspot.com/)
- [LiteRumble Source](https://github.com/jkflying/literumble) — CC BY-NC-SA 3.0
- [Robocode Source](https://github.com/robo-code/robocode) — Eclipse Public License v1.0
- [roborumble.txt config](robocode.content/src/main/resources/roborumble/roborumble.txt)
- [Existing Dockerfile](Dockerfile) — eclipse-temurin:21-jre-alpine
- [RoboRumble Participants](https://robowiki.net/wiki/RoboRumble/Participants?action=raw)
- [Robocode Control API](https://robocode.sourceforge.io/docs/robocode/robocode/control/package-summary.html)

---

## Data Pipeline for ML

### Overview

A 4-stage pipeline that transforms raw battle data into inputs suitable for machine learning. The goal is twofold: **(1)** analyze how existing top bots behave, and **(2)** train a new ML-driven bot.

```
Stage 1              Stage 2                Stage 3              Stage 4
Battle Data    →    Feature           →    Statistical     →    Machine
Collection          Engineering             Analysis            Learning
(IBattleListener    (separate Java          (out of scope)      (out of scope)
 plugin, CSV)        program, CSV)
```

- **Stage 1** and **Stage 2** are in scope. Stages 3 and 4 are future work.
- All stages run in GitHub Actions as part of the rumble infrastructure.
- Both robot perspectives are captured per 1v1 battle (robot A's view and robot B's view).

### Stage 1: Battle Data Collection — Built-in Recording ✅ COMPLETE

Robocode already has a full battle recording system (`RecordManager` + `BattleRecorder`, originally written by Pavel). No custom plugin needed.

**How it works:**
- The existing `-record <filename>` CLI flag enables `enableCLIRecording`, which attaches the `BattleRecorder` to the `BattleEventDispatcher`.
- `BattleRecorder` captures every `TurnSnapshot` (god-view: all robots, all bullets, all scores) to a temp file via Java `ObjectOutputStream` during the battle.
- On `onBattleCompleted`, `RecordManager.saveRecord()` writes the temp file as a `BINARY_ZIP` `.br` file containing `BattleRecordInfo` + serialized `TurnSnapshot` objects.
- `SerializableOptions(false)` preserves full precision — no stripping.

**Data captured per tick in binary format:**
- **Per robot:** robotIndex, name, energy, x, y, bodyHeading, gunHeading, radarHeading, gunHeat, velocity, state (ACTIVE/HIT_WALL/HIT_ROBOT/DEAD), score snapshot (all fields), scanArc, outputStreamSnapshot, debugProperties
- **Per bullet:** bulletId, ownerIndex, state (FIRED/MOVING/HIT_VICTIM/HIT_BULLET/HIT_WALL/EXPLODED/INACTIVE), power, x, y, heading, victimIndex, isExplosion, frame

**Integration with `run-battle.mjs`:**
- Added `--record-dir <dir>` option to `run-battle.mjs`
- When specified, passes `-record <recordDir>/<battleId>.br` to the Java CLI
- The `.br` file path is included in the JSON result as `record_file`
- Tested: 3-round sample battle produces ~500KB `.br` file

**Stage 2 reads `.br` files directly:**
- `RecordManager.loadRecord(filename, BattleRecordFormat.BINARY_ZIP)` loads the file
- `RecordManager.provideTurns(consumer)` iterates all snapshots sequentially
- No intermediate CSV conversion needed — Stage 2 Java program works directly with `TurnSnapshot` objects
- Existing `generateCsvRecord()` can also export to CSV if needed (4 files: results, rounds, robots, bullets)

**Size estimates:**
- ~500KB per 3-round battle (sample bots, ~200 ticks/round)
- ~5-6MB per 35-round battle (top bots, ~500 ticks/round)
- ZIP compression is built-in

**Output location:** `data/battles` branch or GitHub Actions artifacts

### Stage 2: Feature Engineering

A **separate Java program** (not a plugin) that transforms the god-view `.br` binary recordings from Stage 1 into robot-perspective feature CSVs.

**Design:**
- Runs as a post-processing step after battles complete (in GitHub Actions).
- Reads `.br` files via `RecordManager.loadRecord()` + `provideTurns()` — direct access to `TurnSnapshot` objects with full `IRobotSnapshot`, `IBulletSnapshot`, `IScoreSnapshot` interfaces.
- Filters data to **observable-only** — each robot's feature set contains only what that robot could actually see/know at that tick.
- Replicates Robocode's internal math exactly (trigonometry, physics, coordinate transforms).
- Pre-calculates derived features useful for ML (distances, angles, wave-surfing geometry, energy-drop detection for bullet power estimation).
- Does **not** perform heuristics, learning, or prediction — only data preparation.
- Does **not** pre-compute rolling windows or temporal aggregations — leaves that to the ML stage.

**Robot observation delay** (strategically significant):
| Tick | What happens |
|------|-------------|
| Tick 1 | Opponent acts (moves, fires) |
| Tick 2 | We observe opponent's state and can issue our own commands |
| Tick 3 | Our action becomes visible in the game state |

This delay must be faithfully represented in the feature data — features are aligned to the tick the robot could have known them, not when the underlying action occurred.

**Scan events** are captured as a separate event CSV because robots only receive opponent data when their scan arc sweeps over the opponent — the observation is intermittent, not continuous.

### The 5 Learnable Domains

These are the dimensions that can be learned, optimized, or predicted from the collected data. Stage 2 prepares features relevant to each; Stages 3–4 do the actual learning.

| Domain | What it covers |
|--------|---------------|
| **A) Opponent movement** | Predicting where the opponent will be (pattern matching, movement profiling) |
| **B) Opponent firing** | Detecting when opponent fires (energy drop), estimating bullet power/angle/timing |
| **C) Our firing** | Choosing optimal fire angle, timing, and power given predicted opponent movement |
| **D) Our movement** | Dodging bullets (wave surfing), positioning, wall avoidance |
| **E) Strategy & tactics** | Adapting behavior based on energy balance, tick/time, battlefield position, opponent type |

### Key Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| ML-D1 | Both robot perspectives per 1v1 battle | Doubles training data; both sides of every engagement captured |
| ML-D2 | Full god-view ground truth in Stage 1 | Plugin has access to everything — capture it all, filter later |
| ML-D3 | Stage 2 filters to observable-only | Enforces the "robot's eye view" constraint; no cheating with hidden state |
| ML-D4 | Scan events as separate CSV | Scans are intermittent events, not per-tick — different row granularity |
| ML-D5 | No pre-computed rolling windows | Temporal windowing is ML-task-specific; let Stage 4 decide window sizes |
| ML-D6 | Values rounded: 3 dp positions/distances/velocities, 4 dp angles in radians | Sufficient precision for ML; 0.0001 rad gives 0.1px error at max range |
| ML-D7 | Built-in recording for Stage 1, separate Java program for Stage 2 | No custom plugin needed; Robocode's RecordManager already captures full per-tick state. Stage 2 reads `.br` files directly via `RecordManager.loadRecord()` + `provideTurns()` |
| ML-D8 | Replicate Robocode physics exactly | Robocode physics is non-standard; approximations would corrupt features |
| ML-D9 | Observation delay modeled faithfully | Tick 1→2→3 delay is strategically critical; features must reflect actual information availability |
| ML-D10 | God-view in unit tests | Unit tests for Stage 1 plugin and Stage 2 feature engineering may use god-view data for verification. Production Stage 2 code maintains observable-only discipline, but tests can cross-check computed features against ground truth |
| ML-D11 | Stage 2 code reusable in robot | Stage 2 feature engineering code designed for reuse inside a competition robot. May depend on `robocode.api` (IRobotSnapshot, etc.). Feature computation must be efficient for in-game real-time use |
| ML-D12 | Angle precision 4 decimal places | Angles in radians use 4 dp (0.0001 rad). At max battlefield diagonal (~1000px), this gives 0.1px error — 0.28% of robot width (36px). Positions and other values stay at 3 dp. Applies to all CSV output |
| ML-D13 | Kaggle dataset | TODO: Published battle data from Stage 2 could be donated to Kaggle as a public dataset for ML research on game AI |
| ML-D14 | Robot JAR size for learned data | Competition robots can encode learned data in their JAR. Reference: Saguaro_0.1.jar is 800KB. Learned model weights, lookup tables, or distilled parameters compiled into robot code, not external data files. Relaxes traditional 200KB RoboRumble data limit concern |
| ML-D15 | Transfer learning / distillation required | In-game prediction must use small/fast algorithms for real-time execution within per-tick time constraints. Approach: transfer learning or knowledge distillation from larger offline ML system (Stage 4). Offline and in-game models must be architecturally compatible |

### Stage 5: Competition Robot

Build the actual Robocode robot that uses the ML-derived knowledge in battle.

**Key constraints:**
- Must execute within Robocode's per-tick time budget (~10ms on typical hardware)
- Prediction/decision models must be small and fast — no heavyweight DNN inference in-game
- Approach: **transfer learning / knowledge distillation** from the large offline ML models (Stage 4)
  - Offline: train sophisticated models on the full dataset (neural nets, ensemble methods, etc.)
  - Distill: compress learned knowledge into compact representations (lookup tables, small decision trees, polynomial approximations, compressed weight matrices)
  - In-game: fast inference using the distilled model
- The offline system and in-game model must be architecturally compatible — the distillation pipeline connects them
- Robot JAR can encode learned data directly in compiled code (reference: Saguaro at 800KB JAR)
- Stage 2 feature computation code is reused inside the robot (ML-D11)

**Architecture:**
- Robot extends AdvancedRobot
- Feature computation: reused Stage 2 code, computing features from ScannedRobotEvent data in real-time
- Movement: distilled movement model (dodge bullets, maintain position)
- Targeting: distilled targeting model (predict opponent position, aim)
- Strategy: meta-model selecting behavior based on game state
- Cross-opponent learning: pre-loaded opponent profiles from prior battles

**Status:** Out of scope for current conversation — depends on Stages 3 and 4.

### Deliverables (Stage 1 & 2)

| Document | Purpose |
|----------|---------|
| `rules-and-known-ideas.md` | Stage 2 context: Robocode physics, known strategies, wave surfing, pattern matching |
| `features.md` | Stage 2 feature catalog: every pre-calculated feature with math/trigonometry |

> [!decision] Stage 1 plugin/event architecture page (`event-and-plugin-architecture.md`) removed — the goal is achievable with Robocode's built-in recording capabilities.

### References (ML Pipeline)

- [RoboWiki](https://robowiki.net/wiki/Main_Page) — Community knowledge base for Robocode strategies
- [Robocode Book](https://book.robocode.dev/) — Physics, strategies, and implementation patterns
- Note: This is for **Robocode** (original), not "Robocode Tank Royale" (different project, out of scope)

### Original Prompt

<details>
<summary>Full user prompt that initiated the ML pipeline design</summary>

```
Ok team I need your help with design

Stage 1) design a plugin for data collection about robocode battles.
- it needs to collect the data 1) as seen by the robot tick-by-tick 2) metadata about battle and round
- the plugin will inherit from BattleAdaptor which implements IBattleListener
- the data of the events are created from snapshots
- the robot-view data are transformed from it
- we would produce set of CSV files per battle, with different number of rows, some per battle, per round, per tick, per event
- the values should be rounded to 3 decimal places (disk space)
- this is kind of trivial component, we should only capture the ground truth during the battle
- summarize that into event-and-plugin-archietcture.md
- this plugin will run as part of robocode engine during robo rumble (battles). The whole process will run on github actions.

Stage 2) After the battle is done, we would run separate program to further transform the data from CSVs we captured into another set of CSVs
- this will be also java component. We would replicate some of the robocode internal math exactly.
- it will also run in github actions.
- we would produce synthetic "features" as they are called in machine learning
- we could run the trigonometry, measure distances of robots, walls, bullets, estimate bullet speed/power
- we should pre-calculate wave-surfing angles
- this is all described in the robocode wiki and book
- https://robowiki.net/wiki/Main_Page and https://book.robocode.dev/
- physics of robocode is bit strange and it matters. 
- Do not confuse "Robocode" with "Robocode Tank Royale" it's different and we don't care for the new thing.
- we should not try to do any heuristics at this point. Maybe with exception of energy drop detection (bullet power).
- in this process we will only use the data that is observable by the robot (we would not use data of the opponent or engine)
- note that robot reality is delayed: tick 1 - opponent acts, tick 2 - we observe it and can act, tick 3 - action is visible. This has strategical significance.
- summarize that into rules-and-known-ideas.md
- There are 5 things that could be learned/optimized/predicted
A) opponent movement
B) opponent firing energy/angle/timing
C) our firing angle/timing/energy
D) our movement
E) strategy vs tactics - change behavior based on current energy, tick/time, position
- in this component we are not doing any of the learning or prediction, we just prepare data, so that the learning could run in next component.
- propose which feature we pre-calculate - into features.md including the necessary math/trigonometry
- after that iterate on it as a team

Stage 3) 
- Once we have non-trivial amount of the data, we will use statistical tools to learn about the data.
- We will also write article about it.
- This stage is out of scope for current conversation.

Stage 4) 
- After that we would take the data to whatever machine learning we see fit.
- This stage is out of scope for current conversation.
```

</details>
