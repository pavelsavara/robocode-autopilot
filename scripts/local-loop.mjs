#!/usr/bin/env node
/**
 * Local test loop orchestrator.
 * 1. Compile robot + pipeline (Gradle incremental)
 * 2. Run battle vs SittingDuck with streaming CSV pipeline
 * 3. Run Python eval on produced CSV
 *
 * Usage: node scripts/local-loop.mjs [--rounds 5] [--opponent test.SittingDuck]
 */

import { execSync, spawn } from 'node:child_process';
import { existsSync, readdirSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { platform } from 'node:os';

const ROOT = resolve(import.meta.dirname, '..');
const isWin = platform() === 'win32';
const gradlew = isWin ? '.\\gradlew.bat' : './gradlew';

// Parse args
const args = process.argv.slice(2);
let rounds = '5';
let opponent = 'test.SittingDuck';
for (let i = 0; i < args.length; i++) {
    if (args[i] === '--rounds' && args[i + 1]) rounds = args[++i];
    if (args[i] === '--opponent' && args[i + 1]) opponent = args[++i];
}

const csvOutput = resolve(ROOT, 'pipeline', 'build', 'csv');

function run(cmd, label) {
    const start = Date.now();
    process.stdout.write(`[${label}] `);
    try {
        execSync(cmd, { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
        const elapsed = ((Date.now() - start) / 1000).toFixed(1);
        console.log(`OK (${elapsed}s)`);
        return true;
    } catch (e) {
        const elapsed = ((Date.now() - start) / 1000).toFixed(1);
        console.log(`FAILED (${elapsed}s)`);
        if (e.stdout) process.stdout.write(e.stdout.slice(-2000));
        if (e.stderr) process.stderr.write(e.stderr.slice(-2000));
        return false;
    }
}

function runVerbose(cmd, label) {
    const start = Date.now();
    console.log(`\n[${label}]`);
    try {
        const output = execSync(cmd, { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
        const elapsed = ((Date.now() - start) / 1000).toFixed(1);
        // Print last 30 lines
        const lines = output.trim().split('\n');
        console.log(lines.slice(-30).join('\n'));
        console.log(`  (${elapsed}s)\n`);
        return { ok: true, output };
    } catch (e) {
        const elapsed = ((Date.now() - start) / 1000).toFixed(1);
        console.log(`  FAILED (${elapsed}s)`);
        if (e.stdout) process.stdout.write(e.stdout.slice(-3000));
        if (e.stderr) process.stderr.write(e.stderr.slice(-3000));
        return { ok: false, output: e.stdout || '' };
    }
}

// ─── Step 1: Compile ────────────────────────────────────────────────────────
const compileOk = run(
    `${gradlew} :pipeline:classes :robot:jar :test-bots:jar`,
    'Compile'
);
if (!compileOk) {
    process.exit(1);
}

// ─── Step 2: Battle + Streaming CSV ─────────────────────────────────────────
// Clean previous CSV output
if (existsSync(csvOutput)) {
    rmSync(csvOutput, { recursive: true });
}

const battleResult = runVerbose(
    `${gradlew} :pipeline:runBattle -Prounds=${rounds} -Popponent=${opponent} -q`,
    'Battle + CSV'
);
if (!battleResult.ok) {
    process.exit(1);
}

// Find produced CSV directory
const battles = existsSync(csvOutput) ? readdirSync(csvOutput) : [];
if (battles.length === 0) {
    console.error('ERROR: No CSV output produced');
    process.exit(1);
}
const battleDir = join(csvOutput, battles[0]);
console.log(`CSV output: ${battleDir}`);

// ─── Step 3: Python Eval ────────────────────────────────────────────────────
const evalScript = resolve(ROOT, 'scripts', 'eval-csv.py');
const pythonCmd = isWin ? 'python' : 'python3';

const evalResult = runVerbose(
    `${pythonCmd} "${evalScript}" "${battleDir}"`,
    'Python Eval'
);

// --- Summary ---
console.log('=======================================');
console.log(`Compile: OK | Battle: ${battleResult.ok ? 'OK' : 'FAIL'} | Eval: ${evalResult.ok ? 'OK' : 'FAIL'}`);
if (!evalResult.ok) {
    process.exit(1);
}
