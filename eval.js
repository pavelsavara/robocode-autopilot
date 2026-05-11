const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const opponents = [
    'abc.Shadow', 'cx.BlestPain', 'ary.Help', 'eem.zapper', 'ary.micro.Weak',
    'dft.Cardigan', 'kid.Gladiator', 'da.NewBGank', 'ary.FourWD', 'florent.FloatingTadpole',
    'gh.GresSuffurd', 'ab.DengerousRoBatra', 'axeBots.Musashi', 'ags.Glacier', 'jk.mega.DrussGT'
];

const robocodeDir = 'c:\\robocode';
const dataDir = path.join(robocodeDir, 'robots', '.data');

let totalBattleWins = 0;
let totalRoundWins = 0;
const results = [];

for (const opp of opponents) {
    if (fs.existsSync(dataDir)) {
        fs.rmSync(dataDir, { recursive: true, force: true });
    }

    let oppRoundWins = 0;
    let oppBattleWins = 0;
    let oppScores = [];

    console.log(`Evaluating ${opp}...`);

    for (let i = 0; i < 3; i++) {
        const proc = spawnSync('node', [
            'rumble/scripts/run-battle.mjs',
            '--robocode-dir', robocodeDir,
            '--bot-a', 'cz.zamboch.Autopilot',
            '--bot-b', opp,
            '--rounds', '35'
        ], { encoding: 'utf8' });

        try {
            const jsonMatch = proc.stdout.match(/\{[\s\S]*\}/);
            if (jsonMatch) {
                const data = JSON.parse(jsonMatch[0]);
                const auto = data.results.find(r => r.name.includes('Autopilot'));
                const other = data.results.find(r => !r.name.includes('Autopilot'));

                oppRoundWins += auto.rounds;
                if (auto.rounds > 17) oppBattleWins++;
                if (auto.score + other.score > 0) {
                    oppScores.push((auto.score / (auto.score + other.score)) * 100);
                }
            }
        } catch (e) {
            console.error(`Failed to parse result for ${opp} in battle ${i+1}`);
        }
    }

    const avgScore = oppScores.length > 0 ? oppScores.reduce((a, b) => a + b, 0) / oppScores.length : 0;
    results.push({
        opponent: opp,
        roundWins: oppRoundWins,
        battleWins: oppBattleWins,
        avgScore: avgScore.toFixed(2)
    });
    totalBattleWins += oppBattleWins;
    totalRoundWins += oppRoundWins;
    console.log(`Results for ${opp}: ${oppRoundWins} rounds, ${oppBattleWins} battles, ${avgScore.toFixed(2)}%`);
}

console.log('\n| Opponent | Round Wins (Sum) | Battle Wins | Avg Score % |');
console.log('| --- | --- | --- | --- |');
results.forEach(r => {
    console.log(`| ${r.opponent} | ${r.roundWins} | ${r.battleWins} | ${r.avgScore}% |`);
});

console.log(`\nTotal: ${totalBattleWins}/45 battle wins (${(totalBattleWins / 45 * 100).toFixed(2)}%)`);
console.log(`Total: ${totalRoundWins}/1575 round wins (${(totalRoundWins / 1575 * 100).toFixed(2)}%)`);
