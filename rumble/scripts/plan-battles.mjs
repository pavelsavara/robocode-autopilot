#!/usr/bin/env node
// plan-battles.mjs — Generate prioritised 1v1 pairings from a participants list and split into chunks.
//
// Usage:
//   node plan-battles.mjs --participants participants.txt --chunks 20 --out battle-plan.json
//   node plan-battles.mjs --participants top50.txt --results results/ --min-battles 2 --out plan.json
//
// When --results is provided, battles are prioritised (LiteRumble-inspired):
//   1. Missing pairings (never fought) — highest priority, weighted by how many pairings the bot is missing
//   2. Low-battle pairings (below maxPerPair = 10000/numBots) — medium priority
//   3. Stabilised pairings — lowest priority (excluded when --min-battles is set)
//
// Output: JSON with { total_battles, unstabilised_pairs, chunks: [ [{bot_a, bot_b}, ...], ... ] }

import { readFileSync, writeFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { parseArgs } from 'node:util';

const { values: args } = parseArgs({
    options: {
        participants: { type: 'string', default: 'participants.txt' },
        chunks: { type: 'string', default: '20' },
        out: { type: 'string', default: 'battle-plan.json' },
        results: { type: 'string' },
        'min-battles': { type: 'string', default: '0' },
    },
});

// ── Load historical results ─────────────────────────────────────────────────

function loadResults(resultsPath) {
    const p = resolve(resultsPath);
    if (!existsSync(p)) return [];
    const s = statSync(p);
    if (s.isFile()) {
        const data = JSON.parse(readFileSync(p, 'utf-8'));
        return Array.isArray(data) ? data : [data];
    }
    const all = [];
    (function walk(dir) {
        for (const entry of readdirSync(dir, { withFileTypes: true })) {
            const full = join(dir, entry.name);
            if (entry.isDirectory()) walk(full);
            else if (entry.name.endsWith('.json')) {
                try {
                    const data = JSON.parse(readFileSync(full, 'utf-8'));
                    if (Array.isArray(data)) all.push(...data);
                    else all.push(data);
                } catch { /* skip malformed */ }
            }
        }
    })(p);
    return all.filter(r => r && !r.error && r.bot_a && r.bot_b);
}

// ── Parse participants ──────────────────────────────────────────────────────

const raw = readFileSync(resolve(args.participants), 'utf-8');
const bots = raw
    .split('\n')
    .map(l => l.trim())
    .filter(l => l && !l.startsWith('#'));

// ── Count existing battles per pair ─────────────────────────────────────────

const pairBattleCounts = new Map();
if (args.results) {
    for (const r of loadResults(args.results)) {
        const key = [r.bot_a.name, r.bot_b.name].sort().join('|');
        pairBattleCounts.set(key, (pairBattleCounts.get(key) || 0) + 1);
    }
}

const minBattles = parseInt(args['min-battles'], 10) || 0;
const maxPerPair = Math.max(1, Math.floor(10000 / bots.length));
const hasHistory = pairBattleCounts.size > 0;

// ── Compute per-bot missing-pairing counts (LiteRumble weighting) ───────────

const botMissingCount = new Map();
if (hasHistory) {
    for (const bot of bots) {
        const fought = new Set();
        for (const [key] of pairBattleCounts) {
            const [a, b] = key.split('|');
            if (a === bot) fought.add(b);
            else if (b === bot) fought.add(a);
        }
        botMissingCount.set(bot, bots.filter(b => b !== bot && !fought.has(b)).length);
    }
}

// ── Generate all N*(N-1)/2 unique pairings with priority ────────────────────

const allPairs = [];
for (let i = 0; i < bots.length; i++) {
    for (let j = i + 1; j < bots.length; j++) {
        const key = [bots[i], bots[j]].sort().join('|');
        const battles = pairBattleCounts.get(key) || 0;
        let priority;
        if (!hasHistory) {
            priority = 1;
        } else if (battles === 0) {
            // Missing pairing — weighted by both bots' missing counts
            const missingA = botMissingCount.get(bots[i]) || 0;
            const missingB = botMissingCount.get(bots[j]) || 0;
            priority = 2000 + missingA + missingB;
        } else if (battles < maxPerPair) {
            // Not stabilised — fewer battles = higher priority
            priority = 1000 + (maxPerPair - battles);
        } else {
            priority = 0;
        }
        allPairs.push({ bot_a: bots[i], bot_b: bots[j], _battles: battles, _priority: priority });
    }
}

// ── Filter and sort ─────────────────────────────────────────────────────────

let pairs = allPairs;
if (minBattles > 0 && hasHistory) {
    pairs = allPairs.filter(p => p._battles < minBattles);
}

const unstabilisedPairs = allPairs.filter(p => p._priority > 0).length;

// Shuffle for random tiebreaking, then stable-sort by priority descending
for (let i = pairs.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [pairs[i], pairs[j]] = [pairs[j], pairs[i]];
}
pairs.sort((a, b) => b._priority - a._priority);

// Strip internal fields
const cleanPairs = pairs.map(({ bot_a, bot_b }) => ({ bot_a, bot_b }));

// ── Split into chunks ───────────────────────────────────────────────────────

const numChunks = cleanPairs.length > 0
    ? Math.min(parseInt(args.chunks, 10), cleanPairs.length)
    : 0;
const chunks = Array.from({ length: numChunks }, () => []);
for (let i = 0; i < cleanPairs.length; i++) {
    chunks[i % numChunks].push(cleanPairs[i]);
}

const plan = {
    generated: new Date().toISOString(),
    total_bots: bots.length,
    total_battles: cleanPairs.length,
    unstabilised_pairs: unstabilisedPairs,
    num_chunks: numChunks,
    bots,
    chunks,
};

writeFileSync(resolve(args.out), JSON.stringify(plan, null, 2));
console.log(`Planned ${cleanPairs.length} battles for ${bots.length} bots in ${numChunks} chunks`);
if (hasHistory) {
    const totalHistorical = [...pairBattleCounts.values()].reduce((a, b) => a + b, 0);
    console.log(`Historical: ${totalHistorical} battles across ${pairBattleCounts.size} pairings`);
    console.log(`Unstabilised pairs: ${unstabilisedPairs}`);
}
if (numChunks > 0) {
    console.log(`Max battles per chunk: ${Math.max(...chunks.map(c => c.length))}`);
}
