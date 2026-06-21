package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.FileType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast schema guard (runs in the normal {@code test} task, no battle required):
 * every {@code OUR_WAVES} wave feature must be consciously classified in
 * {@link WaveReconciliation} — either compared across the two outgoing-wave
 * producers or excluded with a documented reason. Adding a new wave feature fails
 * the build until it is classified. No more silent gaps.
 */
final class WaveReconciliationTest {

    @Test
    void everyOurWavesFeatureIsClassifiedForCrossPipelineReconciliation() {
        Set<String> classified = WaveReconciliation.classified();
        List<String> unclassified = new ArrayList<>();
        for (Feature f : Feature.values()) {
            if (f.getFileType() != FileType.OUR_WAVES) {
                continue;
            }
            String col = f.name().toLowerCase();
            if (!classified.contains(col)) {
                unclassified.add(col);
            }
        }
        assertTrue(unclassified.isEmpty(),
                "New OUR_WAVES feature(s) not classified for cross-pipeline wave reconciliation: " + unclassified
                        + "\nAdd each to WaveReconciliation.EXACT_COLS / ANGLE_COLS / BREAK_*_COLS (and make BOTH "
                        + "GodViewWaveResolver and RobotSideCsvObserver populate it consistently) or to "
                        + "WaveReconciliation.EXCLUDED_WITH_REASON with a justification. No more silent gaps.");
    }

    @Test
    void noClassifiedColumnRefersToARemovedFeature() {
        Set<String> ourWaveCols = new HashSet<>();
        for (Feature f : Feature.values()) {
            if (f.getFileType() == FileType.OUR_WAVES) {
                ourWaveCols.add(f.name().toLowerCase());
            }
        }
        List<String> stale = new ArrayList<>();
        for (String col : WaveReconciliation.classified()) {
            if (!ourWaveCols.contains(col)) {
                stale.add(col);
            }
        }
        assertTrue(stale.isEmpty(),
                "WaveReconciliation classifies columns that are no longer OUR_WAVES features (remove them): " + stale);
    }
}
