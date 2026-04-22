#!/usr/bin/env node
// run-battle.mjs — Execute a single Robocode 1v1 battle headlessly, output JSON results.
//
// Usage:
//   node run-battle.mjs --robocode-dir /path/to/robocode --bot-a "sample.Fire" --bot-b "sample.Crazy"
//   node run-battle.mjs --robocode-dir /path/to/robocode --battles battles.json --out results/
//
// Single battle mode: prints JSON result to stdout.
// Batch mode (--battles): reads a JSON array of {bot_a, bot_b} pairs, writes results to --out directory.

import { execFileSync, spawnSync } from 'node:child_process';
import { writeFileSync, readFileSync, mkdirSync, existsSync, unlinkSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { randomUUID } from 'node:crypto';

const { values: args } = parseArgs({
    options: {
        'robocode-dir': { type: 'string', default: '' },
        'bot-a': { type: 'string' },
        'bot-b': { type: 'string' },
        'battles': { type: 'string' },
        'out': { type: 'string', default: 'results' },
        'rounds': { type: 'string', default: '35' },
        'field-width': { type: 'string', default: '800' },
        'field-height': { type: 'string', default: '600' },
        'record-dir': { type: 'string' },
    },
});

const robocodeDir = resolve(args['robocode-dir'] || process.env.ROBOCODE_DIR || '/root/robocode');
const rounds = parseInt(args.rounds, 10);
const fieldWidth = parseInt(args['field-width'], 10);
const fieldHeight = parseInt(args['field-height'], 10);

const recordDir = args['record-dir'] ? resolve(args['record-dir']) : null;

function runSingleBattle(botA, botB) {
    const battleId = randomUUID().slice(0, 8);
    const battleFile = join(robocodeDir, 'battles', `rumble-${battleId}.battle`);
    const resultsFile = join(robocodeDir, `results-${battleId}.txt`);

    // Write .battle file
    // Strip version from bot names — selectedRobots uses class name only.
    // Robocode resolves versions from the JAR metadata.
    const classA = botA.replace(/\s+\S+$/, '');
    const classB = botB.replace(/\s+\S+$/, '');

    // Debug: log first battle details
    if (!runSingleBattle._logged) {
        runSingleBattle._logged = true;
        console.error(`[DEBUG] robocodeDir=${robocodeDir}`);
        console.error(`[DEBUG] selectedRobots=${classA},${classB}`);
        console.error(`[DEBUG] battleFile=${battleFile}`);
        console.error(`[DEBUG] resultsFile=${resultsFile}`);
    }

    const battleContent = [
        '#Battle Properties',
        `robocode.battleField.width=${fieldWidth}`,
        `robocode.battleField.height=${fieldHeight}`,
        `robocode.battle.numRounds=${rounds}`,
        'robocode.battle.gunCoolingRate=0.1',
        'robocode.battle.rules.inactivityTime=450',
        'robocode.battle.hideEnemyNames=true',
        `robocode.battle.selectedRobots=${classA},${classB}`,
        '',
    ].join('\n');
    writeFileSync(battleFile, battleContent);

    // Build Java command args
    const javaArgs = [
        '-cp', join(robocodeDir, 'libs', '*'),
        '-Xmx512M',
        '-XX:+IgnoreUnrecognizedVMOptions',
        '--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED',
        '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED',
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        '--add-opens=java.base/java.io=ALL-UNNAMED',
        '--add-opens=java.base/java.net=ALL-UNNAMED',
        '--add-opens=java.base/java.util=ALL-UNNAMED',
        '-DNOSECURITY=true',
        '-Djava.awt.headless=true',
        'robocode.Robocode',
        '-cwd', robocodeDir,
        '-battle', battleFile,
        '-results', resultsFile,
        '-nodisplay',
    ];

    // Add battle recording if --record-dir is specified
    let recordFile = null;
    if (recordDir) {
        mkdirSync(recordDir, { recursive: true });
        recordFile = join(recordDir, `${battleId}.br`);
        javaArgs.push('-record', recordFile);
    }

    const startTime = Date.now();
    let javaStdout = '';
    let javaStderr = '';
    try {
        const proc = spawnSync('java', javaArgs, {
            cwd: robocodeDir,
            timeout: 180_000, // 3 min max per battle
            stdio: ['pipe', 'pipe', 'pipe'],
            maxBuffer: 10 * 1024 * 1024,
        });
        javaStdout = proc.stdout?.toString() || '';
        javaStderr = proc.stderr?.toString() || '';
        if (proc.status !== 0) {
            return {
                error: true,
                bot_a: botA,
                bot_b: botB,
                message: `Battle failed: exit code ${proc.status}`,
                stderr: javaStderr.slice(0, 1000),
                stdout: javaStdout.slice(0, 500),
            };
        }
    } catch (err) {
        return {
            error: true,
            bot_a: botA,
            bot_b: botB,
            message: `Battle failed: ${err.message}`,
        };
    } finally {
        // Cleanup battle file
        try { unlinkSync(battleFile); } catch { /* ignore */ }
    }
    const elapsed = Date.now() - startTime;

    // Parse results file
    if (!existsSync(resultsFile)) {
        return { error: true, bot_a: botA, bot_b: botB, message: 'No results file produced', stderr: javaStderr.slice(0, 1000) };
    }

    const raw = readFileSync(resultsFile, 'utf-8');
    try { unlinkSync(resultsFile); } catch { /* ignore */ }

    const result = parseResults(raw, botA, botB, elapsed, javaStderr);
    if (result.error && javaStdout) {
        result.stdout = javaStdout.slice(0, 1000);
    }
    if (recordFile && existsSync(recordFile)) {
        result.record_file = recordFile;
    }
    return result;
}

function parseResults(raw, botA, botB, elapsedMs, javaStderr) {
    // Robocode results file comes in two formats:
    //
    // Space-separated with percentage (older Robocode):
    //   1st: sample.Crazy   3615 (55%)  800  160   2295  215  104  41   17  18  0
    //
    // Tab-separated without percentage (Robocode 1.10.x):
    //   1st: sample.Crazy\t3615\t800\t160\t2295\t215\t104\t41\t17\t18\t0
    const lines = raw.trim().split('\n');
    const roundsLine = lines[0]; // "Results for 35 rounds"
    const numRounds = parseInt(roundsLine.match(/(\d+) rounds/)?.[1] || '0', 10);

    const bots = [];
    for (const line of lines) {
        // Try tab-separated format first (Robocode 1.10.x)
        const tabMatch = line.match(/^\s*\d+\w+:\s+(.+?)\t(\d+)\t(\d+)\t(\d+)\t(\d+)\t(\d+)\t(\d+)\t(\d+)\t(\d+)\t(\d+)\t(\d+)/);
        if (tabMatch) {
            const totalScore = parseInt(tabMatch[2], 10);
            bots.push({
                name: tabMatch[1].trim(),
                total_score: totalScore,
                score_pct: 0, // computed after both bots parsed
                survival: parseInt(tabMatch[3], 10),
                survival_bonus: parseInt(tabMatch[4], 10),
                bullet_damage: parseInt(tabMatch[5], 10),
                bullet_bonus: parseInt(tabMatch[6], 10),
                ram_damage: parseInt(tabMatch[7], 10),
                ram_bonus: parseInt(tabMatch[8], 10),
                firsts: parseInt(tabMatch[9], 10),
                seconds: parseInt(tabMatch[10], 10),
                thirds: parseInt(tabMatch[11], 10),
            });
            continue;
        }
        // Fall back to space-separated format with percentage
        const m = line.match(
            /^\s*\d+\w+:\s+(.+?)\s+(\d+)\s+\((\d+)%\)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)/
        );
        if (m) {
            bots.push({
                name: m[1].trim(),
                total_score: parseInt(m[2], 10),
                score_pct: parseInt(m[3], 10),
                survival: parseInt(m[4], 10),
                survival_bonus: parseInt(m[5], 10),
                bullet_damage: parseInt(m[6], 10),
                bullet_bonus: parseInt(m[7], 10),
                ram_damage: parseInt(m[8], 10),
                ram_bonus: parseInt(m[9], 10),
                firsts: parseInt(m[10], 10),
                seconds: parseInt(m[11], 10),
                thirds: parseInt(m[12], 10),
            });
        }
    }

    // Compute score_pct if not available (tab format)
    if (bots.length >= 2) {
        const totalAll = bots[0].total_score + bots[1].total_score;
        if (totalAll > 0) {
            bots[0].score_pct = Math.round(100 * bots[0].total_score / totalAll);
            bots[1].score_pct = Math.round(100 * bots[1].total_score / totalAll);
        }
    }

    if (bots.length < 2) {
        return { error: true, bot_a: botA, bot_b: botB, message: 'Could not parse results', raw, stderr: (javaStderr || '').slice(0, 1000) };
    }

    return {
        timestamp: new Date().toISOString(),
        rounds: numRounds,
        field: `${fieldWidth}x${fieldHeight}`,
        elapsed_ms: elapsedMs,
        bot_a: bots[0],
        bot_b: bots[1],
    };
}

// Main
if (args.battles) {
    // Batch mode
    const raw = JSON.parse(readFileSync(args.battles, 'utf-8'));
    // Support both flat array [{bot_a,bot_b},...] and chunked {chunks:[[{bot_a,bot_b},...],...]}
    let pairs;
    if (Array.isArray(raw)) {
        pairs = raw;
    } else if (raw.chunks) {
        pairs = raw.chunks.flat();
    } else {
        console.error('Unrecognised battles.json format');
        process.exit(1);
    }
    const outDir = resolve(args.out);
    mkdirSync(outDir, { recursive: true });

    const resultsFile = join(outDir, 'results.json');

    // Load existing results (for resuming interrupted runs)
    let results = [];
    const completedPairs = new Set();
    if (existsSync(resultsFile)) {
        try {
            results = JSON.parse(readFileSync(resultsFile, 'utf-8'));
            for (const r of results) {
                const key = [r.bot_a?.name || r.bot_a, r.bot_b?.name || r.bot_b].sort().join(' vs ');
                completedPairs.add(key);
            }
            process.stderr.write(`Resuming: ${results.length} existing results loaded\n`);
        } catch { /* start fresh */ }
    }

    for (const pair of pairs) {
        const key = [pair.bot_a, pair.bot_b].sort().join(' vs ');
        if (completedPairs.has(key)) {
            continue; // skip already completed
        }
        const result = runSingleBattle(pair.bot_a, pair.bot_b);
        results.push(result);
        // Save after each battle for crash resilience
        writeFileSync(resultsFile, JSON.stringify(results, null, 2));
        process.stderr.write(`${result.error ? 'FAIL' : 'OK'}: ${pair.bot_a} vs ${pair.bot_b} (${result.elapsed_ms || 0}ms)\n`);
    }

    process.stderr.write(`\nWrote ${results.length} results to ${resultsFile}\n`);
} else if (args['bot-a'] && args['bot-b']) {
    // Single battle mode
    const result = runSingleBattle(args['bot-a'], args['bot-b']);
    process.stdout.write(JSON.stringify(result, null, 2) + '\n');
    process.exit(result.error ? 1 : 0);
} else {
    console.error('Usage: node run-battle.mjs --robocode-dir <path> --bot-a <name> --bot-b <name> [--record-dir <dir>]');
    console.error('   or: node run-battle.mjs --robocode-dir <path> --battles <file.json> --out <dir> [--record-dir <dir>]');
    process.exit(1);
}
