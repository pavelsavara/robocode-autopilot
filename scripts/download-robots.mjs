#!/usr/bin/env node
// download-robots.mjs — Download robot JARs from the Robocode Archive and validate them.
//
// Usage:
//   node download-robots.mjs --top-list rumble/top50.txt --dest c:\robocode\robots
//   node download-robots.mjs --top-list rumble/top50.txt --dest c:\robocode\robots --force
//
// Steps:
//   1. Parse the RoboWiki participants page for JAR URLs
//   2. Filter to bots listed in --top-list
//   3. Download each JAR to --dest
//   4. Validate every JAR is a valid ZIP file (check for End-of-Central-Directory)
//   5. Delete and retry corrupt JARs (up to --retries times)
//   6. Report summary

import { readFileSync, writeFileSync, mkdirSync, existsSync, unlinkSync, statSync, openSync, readSync, closeSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { parseArgs } from 'node:util';
import { get as httpsGet } from 'node:https';
import { get as httpGet } from 'node:http';
import { createWriteStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';

const { values: args } = parseArgs({
    options: {
        'wiki-url': {
            type: 'string',
            default: 'https://robowiki.net/wiki/RoboRumble/Participants?action=raw',
        },
        'archive-url': {
            type: 'string',
            default: 'https://robocode-archive.strangeautomata.com/robots/',
        },
        'top-list': { type: 'string', default: 'rumble/top50.txt' },
        dest: { type: 'string', default: 'c:\\robocode\\robots' },
        force: { type: 'boolean', default: false },
        retries: { type: 'string', default: '2' },
    },
});

const destDir = resolve(args.dest);
const maxRetries = parseInt(args.retries, 10);

// ── HTTP helpers ────────────────────────────────────────────────────

function fetchText(url, maxRedirects = 5) {
    return new Promise((resolve, reject) => {
        if (maxRedirects <= 0) return reject(new Error('Too many redirects'));
        const getter = url.startsWith('https') ? httpsGet : httpGet;
        const req = getter(url, {
            headers: { 'User-Agent': 'Robocode-Robot-Downloader/1.0' },
            timeout: 30_000,
        }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                return fetchText(res.headers.location, maxRedirects - 1).then(resolve, reject);
            }
            if (res.statusCode !== 200) return reject(new Error(`HTTP ${res.statusCode}`));
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve(data));
            res.on('error', reject);
        });
        req.on('error', reject);
        req.on('timeout', () => { req.destroy(); reject(new Error('Timeout')); });
    });
}

function downloadFile(url, dest, maxRedirects = 5) {
    return new Promise((resolve, reject) => {
        if (maxRedirects <= 0) return reject(new Error('Too many redirects'));
        const getter = url.startsWith('https') ? httpsGet : httpGet;
        const req = getter(url, {
            headers: { 'User-Agent': 'Robocode-Robot-Downloader/1.0' },
            timeout: 60_000,
        }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                return downloadFile(res.headers.location, dest, maxRedirects - 1).then(resolve, reject);
            }
            if (res.statusCode !== 200) {
                return reject(new Error(`HTTP ${res.statusCode}`));
            }
            const ws = createWriteStream(dest);
            pipeline(res, ws).then(resolve).catch(reject);
        });
        req.on('error', reject);
        req.on('timeout', () => { req.destroy(); reject(new Error('Timeout')); });
    });
}

// ── ZIP validation ──────────────────────────────────────────────────
// A valid ZIP file has an End-of-Central-Directory record (EOCD).
// The EOCD signature is 0x06054b50. It must appear in the last 65557 bytes.
// This catches truncated downloads, HTML error pages, and empty files.

function isValidZip(filePath) {
    try {
        const stat = statSync(filePath);
        if (stat.size < 22) return false; // minimum ZIP size

        const fd = openSync(filePath, 'r');
        try {
            // Read the last min(65557, fileSize) bytes
            const tailSize = Math.min(65557, stat.size);
            const buf = Buffer.alloc(tailSize);
            readSync(fd, buf, 0, tailSize, stat.size - tailSize);

            // Search for EOCD signature: 50 4B 05 06
            for (let i = tailSize - 22; i >= 0; i--) {
                if (buf[i] === 0x50 && buf[i + 1] === 0x4B &&
                    buf[i + 2] === 0x05 && buf[i + 3] === 0x06) {
                    return true;
                }
            }
            return false;
        } finally {
            closeSync(fd);
        }
    } catch {
        return false;
    }
}

// ── Wiki parser ─────────────────────────────────────────────────────

function parseParticipants(text) {
    const preMatch = text.match(/<pre>([\s\S]*?)<\/pre>/);
    const content = preMatch ? preMatch[1] : text;
    const rawLines = content.split('\n').map(l => l.trim()).filter(l => l.length > 0);
    const bots = [];
    let i = 0;

    while (i < rawLines.length) {
        const line = rawLines[i];
        if (line.startsWith('#') || line.startsWith('//')) { i++; continue; }

        // Single-line: "name version,URL"
        const commaHttpIdx = line.search(/,https?:\/\//);
        if (commaHttpIdx >= 0) {
            addBot(bots, line.substring(0, commaHttpIdx).trim(), line.substring(commaHttpIdx + 1).trim());
            i++; continue;
        }

        // "name version," then URL on next line
        if (line.endsWith(',') && i + 1 < rawLines.length && rawLines[i + 1].match(/^https?:\/\//)) {
            addBot(bots, line.slice(0, -1).trim(), rawLines[i + 1].trim());
            i += 2; continue;
        }

        if (i + 1 < rawLines.length) {
            const nextLine = rawLines[i + 1];
            // "name" then "version,URL"
            const nextIdx = nextLine.search(/,https?:\/\//);
            if (nextIdx >= 0) {
                addBot(bots, (line + ' ' + nextLine.substring(0, nextIdx)).trim(), nextLine.substring(nextIdx + 1).trim());
                i += 2; continue;
            }
            // "name version" then bare URL
            if (nextLine.match(/^https?:\/\//)) {
                addBot(bots, line.trim(), nextLine.trim());
                i += 2; continue;
            }
        }
        i++;
    }
    return bots;
}

function addBot(bots, fullName, jarUrl) {
    // JAR filename convention: spaces -> underscores, append .jar
    const jarFile = fullName.replace(/ /g, '_') + '.jar';
    const nameMatch = fullName.match(/^(\S+)\s*(.*)$/);
    bots.push({
        name: nameMatch ? nameMatch[1] : fullName,
        version: nameMatch ? nameMatch[2] : '',
        fullName,
        jarFile,
        jarUrl,
    });
}

// ── Main ────────────────────────────────────────────────────────────

async function main() {
    // Load top-list
    const topPath = resolve(args['top-list']);
    if (!existsSync(topPath)) {
        console.error(`Top-list not found: ${topPath}`);
        process.exit(1);
    }
    const topNames = new Set(
        readFileSync(topPath, 'utf-8')
            .split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'))
    );
    console.log(`Loaded ${topNames.size} bots from ${args['top-list']}`);

    // Fetch wiki participants
    console.log(`Fetching participants from ${args['wiki-url']}...`);
    const wikiText = await fetchText(args['wiki-url']);
    const allBots = parseParticipants(wikiText);
    console.log(`Parsed ${allBots.length} bots from wiki`);

    // Filter to top-list
    const bots = allBots.filter(b => topNames.has(b.fullName) || topNames.has(b.name));
    console.log(`Matched ${bots.length} bots from top-list`);

    // Report bots from top-list that weren't found on wiki
    const foundNames = new Set(bots.map(b => b.fullName));
    for (const name of topNames) {
        if (!foundNames.has(name) && !bots.some(b => b.name === name.split(' ')[0])) {
            console.warn(`  MISSING from wiki: ${name}`);
        }
    }

    // Also construct fallback archive URLs for any bot that has no wiki URL
    // or whose wiki URL fails. The Robocode Archive naming is:
    // https://robocode-archive.strangeautomata.com/robots/<JarFile>
    const archiveBase = args['archive-url'];

    mkdirSync(destDir, { recursive: true });

    let downloaded = 0, skipped = 0, failed = 0, validated = 0, corrupt = 0;

    for (const bot of bots) {
        const dest = join(destDir, bot.jarFile);

        // Skip if already exists and valid (unless --force)
        if (!args.force && existsSync(dest) && isValidZip(dest)) {
            const size = statSync(dest).size;
            console.log(`  OK   ${bot.jarFile} (${(size / 1024).toFixed(0)} KB, valid)`);
            skipped++;
            validated++;
            continue;
        }

        // If file exists but is corrupt, delete it
        if (existsSync(dest)) {
            if (!isValidZip(dest)) {
                console.log(`  DEL  ${bot.jarFile} (corrupt, re-downloading)`);
                try { unlinkSync(dest); } catch { /* ignore */ }
            } else if (args.force) {
                console.log(`  DEL  ${bot.jarFile} (--force)`);
                try { unlinkSync(dest); } catch { /* ignore */ }
            }
        }

        // Build URL list: try wiki URL first, then archive fallback
        const urls = [];
        if (bot.jarUrl) urls.push(bot.jarUrl);
        urls.push(archiveBase + bot.jarFile);

        let success = false;
        for (const url of urls) {
            for (let attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    process.stdout.write(`  GET  ${bot.jarFile} (attempt ${attempt}) from ${url.length > 80 ? url.substring(0, 77) + '...' : url}\r\n`);
                    await downloadFile(url, dest);

                    // Validate
                    if (isValidZip(dest)) {
                        const size = statSync(dest).size;
                        console.log(`  OK   ${bot.jarFile} (${(size / 1024).toFixed(0)} KB)`);
                        downloaded++;
                        validated++;
                        success = true;
                        break;
                    } else {
                        console.log(`  BAD  ${bot.jarFile} (invalid ZIP, retrying...)`);
                        try { unlinkSync(dest); } catch { /* ignore */ }
                    }
                } catch (err) {
                    console.log(`  ERR  ${bot.jarFile}: ${err.message}`);
                    try { unlinkSync(dest); } catch { /* ignore */ }
                }
            }
            if (success) break;
        }

        if (!success) {
            console.error(`  FAIL ${bot.jarFile} — all download attempts exhausted`);
            failed++;
            corrupt++;
        }
    }

    // Final validation pass: check ALL JARs in dest directory
    console.log('\n--- Validation pass ---');
    const { readdirSync } = await import('node:fs');
    const allJars = readdirSync(destDir).filter(f => f.endsWith('.jar'));
    let totalValid = 0, totalCorrupt = 0;
    for (const jarName of allJars) {
        const jarPath = join(destDir, jarName);
        if (isValidZip(jarPath)) {
            totalValid++;
        } else {
            totalCorrupt++;
            console.log(`  CORRUPT: ${jarName}`);
        }
    }

    console.log(`\n=== Summary ===`);
    console.log(`  Requested:  ${bots.length} bots`);
    console.log(`  Downloaded: ${downloaded}`);
    console.log(`  Skipped:    ${skipped} (already valid)`);
    console.log(`  Failed:     ${failed}`);
    console.log(`  Dest dir:   ${destDir}`);
    console.log(`  Total JARs: ${allJars.length} (${totalValid} valid, ${totalCorrupt} corrupt)`);

    if (totalCorrupt > 0) {
        console.log(`\nTo remove corrupt JARs, re-run with --force`);
    }
}

main().catch(err => {
    console.error(err);
    process.exit(1);
});
