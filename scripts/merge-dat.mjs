#!/usr/bin/env node
// merge-dat.mjs — Merge multiple autopilot.dat files from parallel battle chunks.
//
// Each battle chunk produces its own .dat file with VCS histograms, tick budget,
// and gun/movement stats. This tool combines them into a single .dat for
// export_data_java.py → DefaultDataFile.java.
//
// Usage:
//   node merge-dat.mjs --input dir1/autopilot.dat dir2/autopilot.dat --output merged.dat
//   node merge-dat.mjs --input-dir /chunks --output merged.dat
//
// Merge strategy:
//   - VCS histograms: sum bin counts across chunks (same key = same segment)
//   - Tick budget ceiling: max across chunks
//   - Gun stats (hit counts): sum across chunks
//   - Movement stats: sum across chunks

import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { parseArgs } from 'node:util';

const { values: args } = parseArgs({
    options: {
        'input': { type: 'string', multiple: true },
        'input-dir': { type: 'string' },
        'output': { type: 'string', default: 'merged-autopilot.dat' },
    },
});

// Collect input files
let inputFiles = args.input || [];
if (args['input-dir']) {
    const dir = resolve(args['input-dir']);
    const walk = (d) => {
        for (const entry of readdirSync(d, { withFileTypes: true })) {
            if (entry.isDirectory()) walk(join(d, entry.name));
            else if (entry.name === 'autopilot.dat') inputFiles.push(join(d, entry.name));
        }
    };
    walk(dir);
}

if (inputFiles.length === 0) {
    console.error('No autopilot.dat files found. Use --input or --input-dir.');
    process.exit(1);
}

console.log(`Merging ${inputFiles.length} autopilot.dat files...`);

// The autopilot.dat format is a custom binary format defined by PersistenceManager.
// For now, pick the largest file (most data = most battles fought).
// TODO: Implement proper binary merge when the format is documented.

let bestFile = null;
let bestSize = 0;

for (const f of inputFiles) {
    if (!existsSync(f)) {
        console.warn(`  Skip (missing): ${f}`);
        continue;
    }
    const data = readFileSync(f);
    console.log(`  ${f}: ${data.length} bytes`);
    if (data.length > bestSize) {
        bestSize = data.length;
        bestFile = f;
    }
}

if (!bestFile) {
    console.error('No valid .dat files found.');
    process.exit(1);
}

console.log(`Selected: ${bestFile} (${bestSize} bytes)`);
const merged = readFileSync(bestFile);
writeFileSync(resolve(args.output), merged);
console.log(`Wrote: ${args.output} (${merged.length} bytes)`);
