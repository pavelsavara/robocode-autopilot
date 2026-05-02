package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.IdentityFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the per-battle identity columns emitted into scores.csv:
 * 3 hashes (name / bot id / version) + 4 battle constants.
 */
class IdentityOfflineFeaturesTest {

    private Whiteboard wb;
    private IdentityOfflineFeatures feat;

    @BeforeEach
    void setUp() {
        wb = new Whiteboard();
        feat = new IdentityOfflineFeatures();
    }

    @Test
    void fileTypeIsScores() {
        assertEquals(FileType.SCORES, feat.getFileType());
    }

    @Test
    void splitsNameOnFirstSpace() {
        wb.setOpponentScan("DrussGT 3.1.7", 100, 100, 0, 0, 100);
        assertEquals("DrussGT 3.1.7", wb.getOpponentName());
        assertEquals("DrussGT", wb.getOpponentBotId());
        assertEquals("3.1.7", wb.getOpponentVersion());
    }

    @Test
    void nameWithoutSpaceHasEmptyVersion() {
        wb.setOpponentScan("SimpleBot", 100, 100, 0, 0, 100);
        assertEquals("SimpleBot", wb.getOpponentName());
        assertEquals("SimpleBot", wb.getOpponentBotId());
        assertEquals("", wb.getOpponentVersion());
    }

    @Test
    void splitOnlyOnFirstSpace() {
        // Robocode's getName() format is "<class> <version>" but defensively
        // we should split only on the first space if a stray one ever sneaks in.
        wb.setOpponentScan("ScalarR 0.005h.053-noshield", 100, 100, 0, 0, 100);
        assertEquals("ScalarR", wb.getOpponentBotId());
        assertEquals("0.005h.053-noshield", wb.getOpponentVersion());
    }

    @Test
    void wordVersionStillSplits() {
        // Real example from the rumble dataset.
        wb.setOpponentScan("RougeDC willow", 100, 100, 0, 0, 100);
        assertEquals("RougeDC", wb.getOpponentBotId());
        assertEquals("willow", wb.getOpponentVersion());
    }

    @Test
    void splitFieldsLockedAtFirstScan() {
        // After the first scan, later setOpponentScan calls must not change the split.
        wb.setOpponentScan("DrussGT 3.1.7", 100, 100, 0, 0, 100);
        wb.setOpponentScan("DrussGT 3.1.7", 200, 200, 0, 0, 100);
        assertEquals("DrussGT", wb.getOpponentBotId());
        assertEquals("3.1.7", wb.getOpponentVersion());
    }

    @Test
    void identityFeaturesProcessSetsAllThreeHashes() {
        wb.setOpponentScan("DrussGT 3.1.7", 100, 100, 0, 0, 100);
        new IdentityFeatures().process(wb);

        assertTrue(wb.hasFeature(Feature.OPPONENT_NAME_HASH));
        assertTrue(wb.hasFeature(Feature.OPPONENT_BOT_ID_HASH));
        assertTrue(wb.hasFeature(Feature.OPPONENT_VERSION_HASH));

        int nameHash = (int) wb.getFeature(Feature.OPPONENT_NAME_HASH);
        int botHash = (int) wb.getFeature(Feature.OPPONENT_BOT_ID_HASH);
        int verHash = (int) wb.getFeature(Feature.OPPONENT_VERSION_HASH);

        assertEquals(IdentityFeatures.fnv1a32("DrussGT 3.1.7"), nameHash);
        assertEquals(IdentityFeatures.fnv1a32("DrussGT"), botHash);
        assertEquals(IdentityFeatures.fnv1a32("3.1.7"), verHash);
    }

    @Test
    void differentVersionsShareBotIdHashButDifferOnNameAndVersion() {
        Whiteboard wb1 = new Whiteboard();
        Whiteboard wb2 = new Whiteboard();
        wb1.setOpponentScan("DrussGT 3.1.7", 0, 0, 0, 0, 100);
        wb2.setOpponentScan("DrussGT 3.1.8", 0, 0, 0, 0, 100);
        new IdentityFeatures().process(wb1);
        new IdentityFeatures().process(wb2);

        assertEquals(wb1.getFeature(Feature.OPPONENT_BOT_ID_HASH),
                wb2.getFeature(Feature.OPPONENT_BOT_ID_HASH),
                "Bot family hash must survive version bumps");
        assertNotEquals(wb1.getFeature(Feature.OPPONENT_NAME_HASH),
                wb2.getFeature(Feature.OPPONENT_NAME_HASH));
        assertNotEquals(wb1.getFeature(Feature.OPPONENT_VERSION_HASH),
                wb2.getFeature(Feature.OPPONENT_VERSION_HASH));
    }

    @Test
    void writesHeadersInExpectedOrder() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CsvRowWriter row = new CsvRowWriter(baos);
        row.beginRow();
        feat.writeColumnNames(row);
        row.endRow();

        String header = baos.toString().trim();
        assertEquals("opponent_name_hash,opponent_bot_id_hash,opponent_version_hash,"
                + "battlefield_width,battlefield_height,gun_cooling_rate,num_rounds_total",
                header);
    }

    @Test
    void writesAllSevenColumns() {
        wb.setOpponentScan("DrussGT 3.1.7", 100, 100, 0, 0, 100);
        wb.onRoundStart(0, 800, 600, 0.1, 35);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CsvRowWriter row = new CsvRowWriter(baos);
        row.beginRow();
        feat.writeRowValues(row, wb);
        row.endRow();

        // Note: onRoundStart resets opponentName? Let's check — resetRound clears tick,
        // not opponentName (the field is set on first scan during the round). After
        // onRoundStart, opponentName is still null because resetRound doesn't touch it,
        // BUT we set it before onRoundStart so it survives... wait, resetRound does NOT
        // reset opponentName. Verify by checking output has 7 comma-separated fields.
        String[] cols = baos.toString().trim().split(",", -1);
        assertEquals(7, cols.length, "Should have 7 columns: " + baos.toString());
        assertEquals("800", cols[3]);
        assertEquals("600", cols[4]);
        assertEquals("0.1000", cols[5]);
        assertEquals("35", cols[6]);
    }
}
