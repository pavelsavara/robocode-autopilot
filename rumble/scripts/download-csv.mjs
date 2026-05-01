#!/usr/bin/env node
// download-csv.mjs — Download CSV artifacts from a Process Recordings CI run.
//
// Usage:
//   node rumble/scripts/download-csv.mjs [--run-id <id>] [--out <dir>]
//
// If --run-id is omitted, finds the latest successful "Process Recordings" run.
// Downloads all csv-chunk-* artifacts and flattens into the output directory.
//
// Requires: gh CLI authenticated and on PATH.

import { execFileSync } from 'node:child_process';
import { mkdirSync, readdirSync, renameSync, rmSync, statSync, existsSync } from 'node:fs';
import { join, basename } from 'node:path';

const args = process.argv.slice(2);
let runId = null;
let outDir = join('output', 'csv');

for (let i = 0; i < args.length; i++) {
    if (args[i] === '--run-id' && args[i + 1]) runId = args[++i];
    else if (args[i] === '--out' && args[i + 1]) outDir = args[++i];
    else if (args[i] === '--help') {
        console.log('Usage: download-csv.mjs [--run-id <id>] [--out <dir>]');
        process.exit(0);
    }
}

const REPO = 'pavelsavara/robocode-autopilot';
const WORKFLOW = 'process-recordings.yml';

function gh(...args) {
    const result = execFileSync('gh', args, { encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024 });
    return result.trim();
}

function ghJson(...args) {
    return JSON.parse(gh(...args));
}

// ─── Find the run ───
if (!runId) {
    console.log('Finding latest successful Process Recordings run...');
    const runs = ghJson('api',
        `repos/${REPO}/actions/workflows/${WORKFLOW}/runs?status=success&per_page=1`,
        '--jq', '.workflow_runs');
    if (!runs.length) {
        console.error('No successful Process Recordings runs found.');
        process.exit(1);
    }
    runId = String(runs[0].id);
    console.log(`  Found run ${runId} (${runs[0].created_at})`);
} else {
    console.log(`Using specified run: ${runId}`);
}

// ─── List CSV artifacts ───
const artifacts = ghJson('api',
    `repos/${REPO}/actions/runs/${runId}/artifacts`,
    '--jq', '[.artifacts[] | select(.name | startswith("csv-chunk-")) | {name, size_in_bytes}]');

if (!artifacts.length) {
    console.error(`No csv-chunk-* artifacts found in run ${runId}`);
    process.exit(1);
}

const totalMB = (artifacts.reduce((s, a) => s + a.size_in_bytes, 0) / 1048576).toFixed(1);
console.log(`Found ${artifacts.length} CSV chunks (${totalMB} MB compressed)`);

// ─── Download ───
mkdirSync(outDir, { recursive: true });

// Use a temp directory for downloading, then flatten
const tmpDir = join(outDir, '.download-tmp');
mkdirSync(tmpDir, { recursive: true });

console.log(`Downloading to ${outDir} ...`);

try {
    execFileSync('gh', [
        'run', 'download', runId,
        '--repo', REPO,
        '--pattern', 'csv-chunk-*',
        '--dir', tmpDir
    ], { stdio: 'inherit', timeout: 600_000 });
} catch (e) {
    // gh run download may error on existing files — that's OK
    console.log('  (some artifacts may have already existed)');
}

// ─── Flatten: move battle dirs from chunk subdirs into outDir ───
let moved = 0;
let skipped = 0;

const chunkDirs = readdirSync(tmpDir).filter(d => {
    try { return statSync(join(tmpDir, d)).isDirectory(); } catch { return false; }
});

for (const chunkDir of chunkDirs) {
    const chunkPath = join(tmpDir, chunkDir);
    const battleDirs = readdirSync(chunkPath).filter(d => {
        try { return statSync(join(chunkPath, d)).isDirectory(); } catch { return false; }
    });

    for (const battleDir of battleDirs) {
        const src = join(chunkPath, battleDir);
        const dest = join(outDir, battleDir);
        if (existsSync(dest)) {
            // Merge: copy perspective subdirs
            const perspectives = readdirSync(src).filter(d => {
                try { return statSync(join(src, d)).isDirectory(); } catch { return false; }
            });
            for (const persp of perspectives) {
                const pSrc = join(src, persp);
                const pDest = join(dest, persp);
                if (!existsSync(pDest)) {
                    renameSync(pSrc, pDest);
                    moved++;
                } else {
                    skipped++;
                }
            }
        } else {
            renameSync(src, dest);
            moved++;
        }
    }
}

// Clean up temp dir
rmSync(tmpDir, { recursive: true, force: true });

// Count results
const battleCount = readdirSync(outDir).filter(d => {
    try { return d !== '.download-tmp' && statSync(join(outDir, d)).isDirectory(); } catch { return false; }
}).length;

console.log(`Done. ${battleCount} battles in ${outDir} (${moved} new, ${skipped} skipped)`);
