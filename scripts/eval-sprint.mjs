#!/usr/bin/env node
// eval-sprint.mjs — Run evaluation battles and report results
import { execFileSync } from 'node:child_process';
import { rmSync } from 'node:fs';

const opponents = [
  'abc.Shadow', 'cx.BlestPain', 'ary.Help', 'eem.zapper',
  'ary.micro.Weak', 'dft.Cardigan', 'kid.Gladiator', 'da.NewBGank',
  'ary.FourWD', 'florent.FloatingTadpole', 'gh.GresSuffurd',
  'ab.DengerousRoBatra', 'axeBots.Musashi', 'ags.Glacier', 'jk.mega.DrussGT'
];

const BATTLES_PER = 3;
const ROUNDS = 35;
const results = [];
let totalBattleWins = 0, totalRoundWins = 0, totalBattles = 0, totalRounds = 0;

for (const opp of opponents) {
  // Clear opponent data before each opponent
  try { rmSync('c:\\robocode\\robots\\.data', { recursive: true, force: true }); } catch {}

  let oppRoundWins = 0, oppBattleWins = 0, oppScoreSum = 0;

  for (let i = 0; i < BATTLES_PER; i++) {
    try {
      const raw = execFileSync('node', [
        'rumble/scripts/run-battle.mjs',
        '--robocode-dir', 'c:\\robocode',
        '--bot-a', 'cz.zamboch.Autopilot',
        '--bot-b', opp,
        '--rounds', String(ROUNDS)
      ], { timeout: 120000, encoding: 'utf-8', cwd: 'd:\\robocode-autopilot' });

      const r = JSON.parse(raw);
      if (r.error) { console.error(`  ERROR ${opp} battle ${i+1}: ${r.message}`); continue; }

      const us = r.bot_a.name.includes('Autopilot') ? r.bot_a : r.bot_b;
      const them = r.bot_a.name.includes('Autopilot') ? r.bot_b : r.bot_a;

      oppRoundWins += us.firsts;
      if (us.firsts > ROUNDS / 2) oppBattleWins++;
      oppScoreSum += us.score_pct;
    } catch (e) {
      console.error(`  FAIL ${opp} battle ${i+1}: ${e.message?.slice(0,100)}`);
    }
  }

  const avgScore = (oppScoreSum / BATTLES_PER).toFixed(1);
  results.push({ opp, roundWins: oppRoundWins, battleWins: oppBattleWins, avgScore });
  totalBattleWins += oppBattleWins;
  totalRoundWins += oppRoundWins;
  totalBattles += BATTLES_PER;
  totalRounds += BATTLES_PER * ROUNDS;

  console.log(`${opp.padEnd(38)} avg=${avgScore.padStart(5)}%  bw=${oppBattleWins}/${BATTLES_PER}  rw=${oppRoundWins}/${BATTLES_PER * ROUNDS}`);
}

console.log('\n=== TOTALS ===');
console.log(`Battle wins: ${totalBattleWins} / ${totalBattles} (${(totalBattleWins/totalBattles*100).toFixed(1)}%)`);
console.log(`Round wins:  ${totalRoundWins} / ${totalRounds} (${(totalRoundWins/totalRounds*100).toFixed(1)}%)`);
