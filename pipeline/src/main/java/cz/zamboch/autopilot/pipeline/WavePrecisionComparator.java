package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;

/**
 * Compares robot-side wave detection (from observer's WaveTracker, using stale scan data)
 * with god-view wave detection (from GodViewWaveResolver, using exact positions).
 * <p>
 * Tracks:
 * <ul>
 * <li>God-view fires detected (IBulletSnapshot)</li>
 * <li>Robot-side fires detected (energy drop via FireFeatures)</li>
 * <li>Fire detection rate = robotSideFires / godViewFires</li>
 * <li>GF mean absolute error (when both resolve the same wave)</li>
 * </ul>
 */
final class WavePrecisionComparator {

    // Per-perspective stats
    private final int[] godViewFires = { 0, 0 };
    private final int[] robotSideFires = { 0, 0 };
    private final double[] gfErrorSum = { 0, 0 };
    private final int[] gfErrorCount = { 0, 0 };

    // Per-perspective last-seen break tick to detect new resolutions
    private final double[] lastRobotSideBreakTick = { Double.NaN, Double.NaN };
    private final double[] lastGodViewBreakTick = { Double.NaN, Double.NaN };

    // Per-perspective last-seen fire tick to detect new robot-side fires
    private final double[] lastRobotSideFireTick = { Double.NaN, Double.NaN };

    /**
     * Capture robot-side wave state BEFORE god-view overwrites the whiteboard.
     * Must be called after ctx.doTurn() but before godViewWaveResolver.processTick().
     *
     * @return robot-side OUR_BREAK_GF if a new break was detected this tick, else NaN
     */
    public double captureRobotSideBreak(int perspIndex, Whiteboard wb) {
        double breakTick = wb.getFeature(Feature.OUR_BREAK_TICK);
        if (!Double.isNaN(breakTick) && breakTick != lastRobotSideBreakTick[perspIndex]) {
            lastRobotSideBreakTick[perspIndex] = breakTick;
            return wb.getFeature(Feature.OUR_BREAK_GF);
        }
        return Double.NaN;
    }

    /**
     * Called after god-view has run. Checks if god-view resolved a wave this tick
     * and compares with the robot-side GF captured earlier.
     *
     * @param robotSideGf the GF captured by {@link #captureRobotSideBreak} (NaN if no robot-side break)
     * @param godViewResolved true if god-view resolved a wave this tick
     */
    public void compareTick(int perspIndex, Whiteboard wb, double robotSideGf, boolean godViewResolved) {
        if (godViewResolved) {
            double godViewGf = wb.getFeature(Feature.OUR_BREAK_GF);
            double breakTick = wb.getFeature(Feature.OUR_BREAK_TICK);
            lastGodViewBreakTick[perspIndex] = breakTick;

            // If robot-side also resolved this tick, compare GFs
            if (!Double.isNaN(robotSideGf)) {
                recordGfComparison(perspIndex, godViewGf, robotSideGf);
            }
        }
    }

    /**
     * Capture robot-side fire detection BEFORE god-view overwrites the whiteboard.
     * Detects a new fire if OUR_FIRE_TICK changed since last call.
     */
    public void captureRobotSideFire(int perspIndex, Whiteboard wb) {
        double fireTick = wb.getFeature(Feature.OUR_FIRE_TICK);
        if (!Double.isNaN(fireTick) && fireTick != lastRobotSideFireTick[perspIndex]) {
            lastRobotSideFireTick[perspIndex] = fireTick;
            robotSideFires[perspIndex]++;
        }
    }

    /** Record that god-view detected a new fire for this perspective. */
    public void recordGodViewFire(int perspIndex) {
        godViewFires[perspIndex]++;
    }

    /** Record that robot-side detected a fire (OUR_FIRE_POWER was set). */
    public void recordRobotSideFire(int perspIndex) {
        robotSideFires[perspIndex]++;
    }

    /**
     * Record a GF comparison when both sides resolved the same wave.
     *
     * @param godViewGf   the GF from GodViewWaveResolver (ground truth)
     * @param robotSideGf the GF from WaveTracker (stale-data estimate)
     */
    public void recordGfComparison(int perspIndex, double godViewGf, double robotSideGf) {
        gfErrorSum[perspIndex] += Math.abs(godViewGf - robotSideGf);
        gfErrorCount[perspIndex]++;
    }

    public void resetRound() {
        for (int i = 0; i < 2; i++) {
            godViewFires[i] = 0;
            robotSideFires[i] = 0;
            gfErrorSum[i] = 0;
            gfErrorCount[i] = 0;
            lastRobotSideBreakTick[i] = Double.NaN;
            lastGodViewBreakTick[i] = Double.NaN;
            lastRobotSideFireTick[i] = Double.NaN;
        }
    }

    /** Fire detection rate: robot-side fires / god-view fires. */
    public double getFireDetectionRate(int perspIndex) {
        return godViewFires[perspIndex] > 0
                ? (double) robotSideFires[perspIndex] / godViewFires[perspIndex]
                : Double.NaN;
    }

    /** Mean absolute GF error between god-view and robot-side. */
    public double getGfMeanAbsoluteError(int perspIndex) {
        return gfErrorCount[perspIndex] > 0
                ? gfErrorSum[perspIndex] / gfErrorCount[perspIndex]
                : Double.NaN;
    }

    public int getGodViewFires(int perspIndex) {
        return godViewFires[perspIndex];
    }

    public int getRobotSideFires(int perspIndex) {
        return robotSideFires[perspIndex];
    }

    public int getGfComparisonCount(int perspIndex) {
        return gfErrorCount[perspIndex];
    }

    /** Print a summary of comparison stats for both perspectives. */
    public void printSummary() {
        for (int i = 0; i < 2; i++) {
            System.out.printf("Perspective %d: godViewFires=%d, robotSideFires=%d, " +
                    "detectionRate=%.2f, gfMAE=%.4f (n=%d)%n",
                    i, godViewFires[i], robotSideFires[i],
                    getFireDetectionRate(i), getGfMeanAbsoluteError(i), gfErrorCount[i]);
        }
    }
}
