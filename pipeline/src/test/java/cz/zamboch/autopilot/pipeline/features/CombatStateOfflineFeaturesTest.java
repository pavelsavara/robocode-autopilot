package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * God-view tests for CombatStateOfflineFeatures.
 */
class CombatStateOfflineFeaturesTest {

    private Whiteboard wb;
    private CombatStateOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new CombatStateOfflineFeatures();
        wb.onRoundStart(0, 800, 600, 0.1, 10);
    }

    @Test
    void fileTypeIsScores() {
        assertEquals(FileType.SCORES, feat.getFileType());
    }

    @Test
    void processIsNoOp() {
        // process() should not throw or change anything
        feat.process(wb);
        // Score features are written directly from wb counters
    }

    @Test
    void noDependencies() {
        assertEquals(0, feat.getDependencies().length);
    }

    @Test
    void outputsSixFeatures() {
        assertEquals(6, feat.getOutputFeatures().length);
    }

    @Test
    void writesDamageValues() {
        wb.addDamageDealt(35.5);
        wb.addDamageReceived(12.0);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CsvRowWriter row = new CsvRowWriter(baos);
        row.beginRow();
        feat.writeRowValues(row, wb);
        row.endRow();

        String output = baos.toString();
        assertTrue(output.contains("35.50"), "Should contain damage dealt: " + output);
        assertTrue(output.contains("12.00"), "Should contain damage received: " + output);
        assertTrue(output.contains("23.50"), "Should contain net damage: " + output);
    }

    @Test
    void writesHitRates() {
        wb.incrementOurShotsFired();
        wb.incrementOurShotsFired();
        wb.incrementOurShotsFired();
        wb.incrementOurShotsFired();
        wb.incrementOurBulletHitCount();

        wb.incrementOpponentShotsDetected();
        wb.incrementOpponentShotsDetected();
        wb.incrementOpponentBulletHitCount();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CsvRowWriter row = new CsvRowWriter(baos);
        row.beginRow();
        feat.writeRowValues(row, wb);
        row.endRow();

        String output = baos.toString();
        // our_hit_rate = 1/4 = 0.25
        assertTrue(output.contains("0.2500"), "Should contain our hit rate: " + output);
        // opponent_hit_rate = 1/2 = 0.5
        assertTrue(output.contains("0.5000"), "Should contain opponent hit rate: " + output);
    }

    @Test
    void hitRateZeroWithNoShots() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CsvRowWriter row = new CsvRowWriter(baos);
        row.beginRow();
        feat.writeRowValues(row, wb);
        row.endRow();

        String output = baos.toString();
        // With 0 shots, hit rates are 0.0
        // All values should be 0
        assertTrue(output.contains("0.0000"), "Zero rates expected: " + output);
    }

    @Test
    void writesWinRate() {
        wb.incrementRoundsWon();
        wb.incrementRoundsWon();
        wb.incrementRoundsLost();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CsvRowWriter row = new CsvRowWriter(baos);
        row.beginRow();
        feat.writeRowValues(row, wb);
        row.endRow();

        String output = baos.toString();
        // win_rate = 2/3 = 0.6667
        assertTrue(output.contains("0.6667"), "Should contain win rate: " + output);
    }

    @Test
    void winRateZeroNoRounds() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CsvRowWriter row = new CsvRowWriter(baos);
        row.beginRow();
        feat.writeRowValues(row, wb);
        row.endRow();

        String output = baos.toString();
        // 0 rounds → 0.0 win rate
        String[] parts = output.trim().split(",");
        assertEquals("0.0000", parts[parts.length - 1]);
    }

    @Test
    void writesColumnNames() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CsvRowWriter row = new CsvRowWriter(baos);
        row.beginRow();
        feat.writeColumnNames(row);
        row.endRow();

        String output = baos.toString().trim();
        assertEquals("damage_dealt,damage_received,net_damage,our_hit_rate,opponent_hit_rate,win_rate",
                output);
    }

    @Test
    void perRoundCountersUsed() {
        // Round 0 accumulation
        wb.addDamageDealt(20);
        wb.incrementOurShotsFired();

        // New round resets per-round counters
        wb.onRoundStart(1, 800, 600, 0.1, 10);
        wb.addDamageDealt(10);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CsvRowWriter row = new CsvRowWriter(baos);
        row.beginRow();
        feat.writeRowValues(row, wb);
        row.endRow();

        String output = baos.toString();
        // Should show round 1's damage (10), not cumulative (30)
        assertTrue(output.startsWith("10.00"), "Should use per-round counter: " + output);
    }
}
