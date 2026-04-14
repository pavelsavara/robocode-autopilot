package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Pipeline-only combat state features — one row per round end.
 * Reads per-round Whiteboard counters directly (no process() computation needed).
 * Counters survive advanceTick() so values are valid at round-end emission time.
 */
public final class CombatStateOfflineFeatures implements IOfflineFeatures {

    private static final Feature[] OUTPUTS = {
            Feature.SCORE_DAMAGE_DEALT,
            Feature.SCORE_DAMAGE_RECEIVED,
            Feature.SCORE_NET_DAMAGE,
            Feature.SCORE_OUR_HIT_RATE,
            Feature.SCORE_OPPONENT_HIT_RATE,
            Feature.SCORE_WIN_RATE
    };

    private static final Feature[] DEPS = new Feature[0];

    public Feature[] getOutputFeatures() {
        return OUTPUTS;
    }

    public Feature[] getDependencies() {
        return DEPS;
    }

    /** No-op: values come from persistent Whiteboard counters, not the feature array. */
    public void process(Whiteboard wb) {
        // Score features are read directly from wb counters in writeRowValues().
        // They don't need per-tick computation.
    }

    public FileType getFileType() {
        return FileType.SCORES;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders(
                "damage_dealt", "damage_received", "net_damage",
                "our_hit_rate", "opponent_hit_rate", "win_rate");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        double dealt = wb.getDamageDealtThisRound();
        double received = wb.getDamageReceivedThisRound();
        double netDamage = dealt - received;

        int ourHits = wb.getOurBulletHitCountThisRound();
        int ourShots = wb.getOurShotsFiredThisRound();
        double ourHitRate = ourShots > 0 ? (double) ourHits / ourShots : 0.0;

        int oppHits = wb.getOpponentBulletHitCountThisRound();
        int oppShots = wb.getOpponentShotsDetectedThisRound();
        double oppHitRate = oppShots > 0 ? (double) oppHits / oppShots : 0.0;

        int won = wb.getRoundsWon();
        int lost = wb.getRoundsLost();
        int total = won + lost;
        double winRate = total > 0 ? (double) won / total : 0.0;

        row.writeRaw(String.format("%.2f", dealt));
        row.writeRaw(String.format("%.2f", received));
        row.writeRaw(String.format("%.2f", netDamage));
        row.writeRaw(String.format("%.4f", ourHitRate));
        row.writeRaw(String.format("%.4f", oppHitRate));
        row.writeRaw(String.format("%.4f", winRate));
    }
}
