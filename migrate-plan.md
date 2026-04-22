# Migration Plan: Consolidate Rumble Infrastructure into robocode-autopilot

## Context

The rumble infrastructure (battle planning, execution, scoring, and static site) currently lives in a fork of robocode (`pavelsavara/robocode`, branches `actions-rumble` and `data/robots`). We've confirmed that **no custom robocode build or plugin is needed** — the built-in `-record` CLI flag captures full per-tick `.br` recordings. This means the fork can be eliminated: the rumble infrastructure moves to `robocode-autopilot`, and the robocode engine is consumed as a pre-built release.

### Decision Inputs (from discussion)
- **Engine source**: Download release JAR, package into Docker image
- **GitHub Pages**: New URL (`pavelsavara.github.io/robocode-autopilot/`) — OK
- **Bot count**: Catalog has top 150; season runs use top 50 (scale up later)
- **Recording**: Record ALL season battles, store as GitHub Actions artifacts (auto-expire 90 days)
- **Historical data**: Fresh start (no migration of old results)
- **CI architecture**: Keep Docker-based battles (reproducible env)
- **Fork cleanup**: Delete `actions-rumble` and `data/robots` branches after migration

---

## 1. Repository Structure After Migration

```
robocode-autopilot/
├── core/                          # (existing) In-game feature logic
├── pipeline/                      # (existing) Offline pipeline, CSV output
├── robot/                         # (existing) Competition robot
├── rumble/
│   ├── scripts/
│   │   ├── run-battle.mjs         # Battle executor (single + batch, with --record-dir)
│   │   ├── plan-battles.mjs       # Generate N*(N-1)/2 pairings, split into chunks
│   │   ├── compute-rankings.mjs   # APS/Survival/Vote/ANPP scoring engine
│   │   ├── generate-pages.mjs     # Static HTML site generator
│   │   ├── scrape-wiki.mjs        # Wiki parser + JAR downloader
│   │   └── build-catalog.mjs      # Robot metadata catalog builder
│   ├── flags/                     # Country flag GIFs + flags.json + logo
│   ├── top150.txt                 # Curated list of top 150 LiteRumble bots (catalog)
│   ├── top50.txt                  # Active participants for season runs
│   └── package.json               # ESM config, npm scripts (no external deps)
├── .github/
│   ├── workflows/
│   │   ├── run-season.yml         # Orchestrator: plan → battle (Docker matrix) → rank → publish
│   │   ├── scrape-wiki.yml        # Download/update robot JARs from RoboWiki
│   │   ├── build-docker.yml       # Build + push battle-runner Docker image to GHCR
│   │   └── bot-submit.yml         # Issue-based bot submission handler
│   └── ISSUE_TEMPLATE/
│       └── bot-submission.yml     # Bot submission issue template
├── Dockerfile.battle              # Battle runner: JRE + Node.js + robocode + run-battle.mjs
├── recordings/                    # (existing) Local .br files for pipeline dev
├── output/csv/                    # (existing) Pipeline CSV output
├── intuition/                     # (existing) Jupyter notebooks
├── build.gradle.kts               # (existing) Java build
├── settings.gradle.kts            # (existing) Gradle modules
└── gradle/libs.versions.toml      # (existing) Version catalog
```

### What Does NOT Move
- `test-recording.yml` — was a one-off validation, recording is now integrated into `run-battle.mjs`
- `assembly.yml` — that was the Java CI build for the fork itself, not needed
- Historical results from `data/robots` branch — fresh start

---

## 2. Migration Steps

### Phase A: Migrate Robots (orphan branch `robots`)

**Goal**: Create orphan branch `robots` in `robocode-autopilot` with top 150 bots.

1. **Create `top150.txt`** — curated list of 150 bot fully-qualified names from LiteRumble rankings.
   - Update version mismatches (e.g., `Saguaro 0.1` → `Saguaro 1.0`).
   - Add the ~120 new bots not in the current top 30.

2. **Run `scrape-wiki.mjs`** locally or via workflow:
   ```bash
   node rumble/scripts/scrape-wiki.mjs \
     --top-list rumble/top150.txt \
     --max-bots 150 \
     --out-dir /tmp/robots \
     --participants /tmp/participants.txt
   ```

3. **Create orphan branch `robots`** in `pavelsavara/robocode-autopilot`:
   ```bash
   git checkout --orphan robots
   git rm -rf .
   cp /tmp/robots/*.jar .
   cp /tmp/participants.txt .
   git add *.jar participants.txt
   git commit -m "Add top 150 robot JARs from LiteRumble"
   git push origin robots
   ```

4. **Run `build-catalog.mjs`** to generate `index.json`:
   ```bash
   node rumble/scripts/build-catalog.mjs \
     --top-list rumble/top150.txt \
     --robots-dir /tmp/robots \
     --out /tmp/index.json \
     --github-repo pavelsavara/robocode-autopilot \
     --github-branch robots
   ```
   Commit `index.json` to the `robots` branch.

### Phase B: Migrate Scripts & Assets

**Goal**: Copy rumble scripts, flags, and static assets into main branch.

1. **Copy from fork** (`pavelsavara/robocode` `master` branch — has `--record-dir` support):
   - `rumble/scripts/*.mjs` (6 files)
   - `rumble/flags/` (57 GIFs + `flags.json` + `robocode_logo_tanks.png`)
   - `rumble/package.json`
   - `rumble/.gitignore`
   - `rumble/top30.txt` → rename to `rumble/top150.txt` and expand
   - Create `rumble/top50.txt` — subset of top150.txt, first 50 entries (used for season runs)

2. **Script modifications needed** (minor):
   - `run-battle.mjs`: Already has `--record-dir`. No changes needed.
   - `build-catalog.mjs`: Update default `--github-repo` from `pavelsavara/robocode` to `pavelsavara/robocode-autopilot`.
   - `build-catalog.mjs`: Update default `--github-branch` from `data/robots` to `robots`.
   - `generate-pages.mjs`: Update footer link from `pavelsavara/robocode` to `pavelsavara/robocode-autopilot`.
   - `scrape-wiki.mjs`: No changes needed.
   - `compute-rankings.mjs`: No changes needed.
   - `plan-battles.mjs`: No changes needed.

### Phase C: Migrate CI Workflows

**Goal**: Port GitHub Actions workflows, adapting branch references and enabling recording.

#### C1: `build-docker.yml` — Build Docker Image

**Changes from fork version**:
- Remove Java build step (no `./gradlew build`). Instead, download the **robocode release JAR** (v1.10.1) from GitHub releases and extract it.
- Update `ref:` from `actions-rumble` to `main`.
- Update GHCR image tag from `ghcr.io/pavelsavara/robocode/battle-runner` to `ghcr.io/pavelsavara/robocode-autopilot/battle-runner`.

**New Docker build approach**:
```yaml
- name: Download Robocode release
  run: |
    curl -fSL -o robocode-setup.jar \
      "https://github.com/robo-code/robocode/releases/download/v1.10.1.0/robocode-1.10.1.0-setup.jar"
    mkdir -p robocode-home
    cd robocode-home
    unzip -o ../robocode-setup.jar \
      libs/* robots/* compilers/* battles/* robocode.bat robocode.sh \
      -x 'net/*' 'META-INF/*'
```

**Updated `Dockerfile.battle`**:
```dockerfile
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache nodejs
COPY robocode-home/ /opt/robocode/
COPY rumble/scripts/run-battle.mjs /opt/rumble/run-battle.mjs
WORKDIR /opt/robocode
ENV ROBOCODE_DIR=/opt/robocode
ENV NOSECURITY=true
ENTRYPOINT ["node", "/opt/rumble/run-battle.mjs"]
```
Unchanged from fork — the simplification is in the workflow, not the Dockerfile.

#### C2: `run-season.yml` — Run Rumble Season (Orchestrator)

**Key changes from fork**:
- Branch ref: `main` instead of `actions-rumble`
- Robot branch: `robots` instead of `data/robots`
- Docker image: `ghcr.io/pavelsavara/robocode-autopilot/battle-runner:latest`
- **Enable recording** in battle step: add `--record-dir recordings/`
- **Upload recordings as artifacts** (per chunk, auto-expire 90 days)
- GitHub Pages deployment: update worktree target
- Result storage: push to `robots` branch (sub-directory `results/`)
- **Participants file**: Use `top50.txt` (not full `participants.txt`) for season runs
- Scale: `max_parallel` default stays at `20` (GitHub's free-tier concurrent job limit)

**New recording step in battle job**:
```yaml
- name: Run battles (chunk ${{ matrix.chunk }})
  run: |
    node /opt/rumble/run-battle.mjs \
      --robocode-dir /opt/robocode \
      --battles chunk-battles.json \
      --out results/ \
      --rounds ${{ inputs.rounds || '35' }} \
      --record-dir recordings/

- uses: actions/upload-artifact@v7
  with:
    name: recordings-chunk-${{ matrix.chunk }}
    path: recordings/
    retention-days: 90
```

**50-bot scale considerations**:
- 50 bots → 1,225 battles (N*(N-1)/2)
- ~15s avg per battle → ~62 battles/chunk at 20 chunks → ~15 min wall clock
- Recording adds ~5-6MB per battle → ~7.4GB total → uploaded as artifacts (auto-expire 90 days)
- When scaling to 150 bots: 11,175 battles, ~2.3 hours, ~67GB recordings. Revisit `retention-days` then.

#### C3: `scrape-wiki.yml` — Wiki Scraper

**Changes**:
- Branch ref: `main` instead of `actions-rumble`
- Data branch: `robots` instead of `data/robots`
- Default `max_bots`: `150` instead of `30`

#### C4: `bot-submit.yml` — Bot Submission

**Changes**:
- Branch ref: `main` instead of `actions-rumble`
- Data branch: `robots` instead of `data/robots`

### Phase D: Build & Push Docker Image

1. Push the `Dockerfile.battle` and workflow files to `main`.
2. Trigger `build-docker.yml` (manual dispatch) to build and push the Docker image.
3. Verify the image at `ghcr.io/pavelsavara/robocode-autopilot/battle-runner:latest`.

### Phase E: Download Top 150 Robot JARs

1. Trigger `scrape-wiki.yml` with `max_bots=150`.
2. Verify `robots` branch has ~150 JARs + `participants.txt`.
3. Run `build-catalog.mjs` to create `index.json`.
4. Create `top50.txt` — first 50 entries from `top150.txt` — for active season runs.

### Phase F: First Season Run with Recording

1. Trigger `run-season.yml` with defaults (`max_parallel=20`, `rounds=35`).
   - Season uses `top50.txt` (50 bots, 1,225 battles, ~15 min).
2. Verify:
   - Rankings computed and published to GitHub Pages (`pavelsavara.github.io/robocode-autopilot/`)
   - Results saved to `robots` branch under `results/`
   - Recordings uploaded as artifacts (per-chunk, 90-day retention)
3. Scale to top 150 once pipeline integration is validated.

### Phase G: Fork Cleanup

1. Delete `actions-rumble` branch from `pavelsavara/robocode`.
2. Delete `data/robots` branch from `pavelsavara/robocode`.
3. Delete `gh-pages` branch from `pavelsavara/robocode` (or update to redirect to new URL).
4. Reset fork main branch to upstream hash.

---

## 3. Recording → Pipeline Integration

The recordings from CI feed directly into the existing Stage 2 pipeline:

```
CI Season Run → .br artifacts (90-day retention)
    ↓ download
recordings/ directory (local)
    ↓
Pipeline (pipeline module) → reads .br → replays through Whiteboard → CSV output
    ↓
intuition/ notebooks → statistical analysis
```

**Workflow for downloading recordings to local dev**:
```bash
# Download specific season's recordings from GitHub Actions
gh run download <run-id> --pattern 'recordings-chunk-*' --dir recordings/
```

This connects Stage 1 (battle execution + recording) to Stage 2 (feature engineering pipeline) without any code changes to the pipeline module.

---

## 4. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| RoboWiki JAR URLs broken for some bots | Can't download all 150 | `scrape-wiki.mjs` already handles failures gracefully; manually source missing JARs |
| Recording artifacts exceed GitHub storage | Cost ($0.25/GB/month) | Top 50 = ~7.4GB (manageable). Revisit retention-days when scaling to 150. |
| Season runtime too long at 150 bots | 2.3h per season | Start with top 50 (~15 min). Scale gradually. 6-hour job limit is not a risk. |
| Docker image cache invalidation | Slow CI | Pin robocode version in Dockerfile; only rebuild when version changes |
| Saguaro 0.1 vs 1.0 version mismatch | Wrong bot version | Update `top150.txt` to use LiteRumble's current versions |
| Some bots crash robocode or hang | Battle timeout | `run-battle.mjs` has 180s timeout per battle; errors are logged and skipped |

---

## 5. Estimated Effort

| Phase | Description | Effort |
|-------|-------------|--------|
| A | Create `robots` branch, download 150 JARs | Small — scripted |
| B | Copy scripts + assets, minor edits | Small |
| C | Port 4 workflows, adapt branch/image refs | Medium |
| D | Build & push Docker image | Small — one workflow trigger |
| E | Download top 150 bots | Small — one workflow trigger |
| F | First season run | Automated — ~2.3 hours |
| G | Fork cleanup | Small — branch deletion |

Total implementation: Phases A–D can be done in a single work session.

---

## 6. Summary of All Changes

### New files in `robocode-autopilot`:
- `rumble/scripts/*.mjs` (6 files, ~1616 lines total)
- `rumble/flags/` (57 GIFs + `flags.json` + logo PNG)
- `rumble/package.json`
- `rumble/.gitignore`
- `rumble/top150.txt` (catalog)
- `rumble/top50.txt` (active season participants)
- `.github/workflows/run-season.yml`
- `.github/workflows/build-docker.yml`
- `.github/workflows/scrape-wiki.yml`
- `.github/workflows/bot-submit.yml`
- `.github/ISSUE_TEMPLATE/bot-submission.yml`
- `Dockerfile.battle`

### Orphan branch `robots`:
- ~150 robot JARs (full catalog)
- `participants.txt` (all 150)
- `index.json`
- `rankings.json` (after first season)
- `results/` (after first season)

### Orphan branch `gh-pages`:
- Static HTML rankings site (generated by `generate-pages.mjs`)

### No changes to existing code:
- `core/`, `pipeline/`, `robot/` — untouched
- `build.gradle.kts`, `settings.gradle.kts` — untouched
- `recordings/`, `output/csv/`, `intuition/` — untouched
