package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.strategy.VirtualBullet;
import cz.zamboch.autopilot.core.util.RoboMath;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Tracks multiple gun strategies via virtual bullets, selects the best
 * performer based on rolling hit rate, and controls gun aiming.
 */
public final class VirtualGunManager {

    private static final int WINDOW = 100;
    private static final double HIT_RATE_EPSILON = 0.02;
    private static final double AIM_THRESHOLD = 0.02; // ~1.1 degrees

    private final List<IGunStrategy> strategies;
    private final List<List<VirtualBullet>> virtualBullets;
    private final int[][] hitHistory; // [strategyIndex][ringIndex] = 0/1
    private final int[] historyIndex;
    private final int[] historyCount;
    private final int[] hitCounts;

    private double selectedAngle;
    private int selectedStrategyIndex;

    public VirtualGunManager(List<IGunStrategy> strategies) {
        this.strategies = strategies;
        int n = strategies.size();
        this.virtualBullets = new ArrayList<List<VirtualBullet>>(n);
        this.hitHistory = new int[n][WINDOW];
        this.historyIndex = new int[n];
        this.historyCount = new int[n];
        this.hitCounts = new int[n];
        for (int i = 0; i < n; i++) {
            virtualBullets.add(new ArrayList<VirtualBullet>());
        }
    }

    /**
     * Called on each scan tick. Records virtual bullets and checks hits.
     */
    public void onScan(Whiteboard wb, double firePower) {
        double ourX = wb.getOurX();
        double ourY = wb.getOurY();
        long tick = wb.getTick();
        double distance = wb.getFeature(Feature.DISTANCE);
        double bulletSpeed = 20.0 - 3.0 * firePower;

        // Check existing virtual bullets for hits/misses
        checkVirtualBullets(wb);

        // Record new virtual bullets for each strategy
        for (int i = 0; i < strategies.size(); i++) {
            double angle = strategies.get(i).getFireAngle(wb);
            if (!Double.isNaN(angle)) {
                VirtualBullet vb = new VirtualBullet(ourX, ourY, angle,
                        bulletSpeed, tick, distance);
                virtualBullets.get(i).add(vb);
            }
        }

        // Select best strategy
        selectBest(wb);
    }

    /**
     * Check all virtual bullets against current opponent position.
     */
    private void checkVirtualBullets(Whiteboard wb) {
        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        long tick = wb.getTick();

        for (int i = 0; i < strategies.size(); i++) {
            Iterator<VirtualBullet> it = virtualBullets.get(i).iterator();
            while (it.hasNext()) {
                VirtualBullet vb = it.next();
                if (vb.hasPassed(tick)) {
                    // Check if the bullet would have hit
                    double actualBearing = Math.atan2(oppX - vb.startX, oppY - vb.startY);
                    double angleDiff = Math.abs(RoboMath.normalRelativeAngle(vb.heading - actualBearing));
                    double hitAngle = Math.atan2(18, vb.fireDistance);
                    boolean hit = angleDiff <= hitAngle;
                    recordResult(i, hit ? 1 : 0);
                    it.remove();
                }
            }
        }
    }

    private void recordResult(int strategyIndex, int hit) {
        int idx = historyIndex[strategyIndex];
        // Subtract old value from count
        if (historyCount[strategyIndex] >= WINDOW) {
            hitCounts[strategyIndex] -= hitHistory[strategyIndex][idx];
        }
        hitHistory[strategyIndex][idx] = hit;
        hitCounts[strategyIndex] += hit;
        historyIndex[strategyIndex] = (idx + 1) % WINDOW;
        if (historyCount[strategyIndex] < WINDOW) {
            historyCount[strategyIndex]++;
        }
    }

    private double getHitRate(int strategyIndex) {
        if (historyCount[strategyIndex] == 0) return 0;
        return (double) hitCounts[strategyIndex] / historyCount[strategyIndex];
    }

    private void selectBest(Whiteboard wb) {
        double bestRate = -1;
        double bestConfidence = -1;
        int bestIdx = 0;

        for (int i = 0; i < strategies.size(); i++) {
            double rate = getHitRate(i);
            double conf = strategies.get(i).getConfidence(wb);
            if (rate > bestRate + HIT_RATE_EPSILON) {
                bestRate = rate;
                bestConfidence = conf;
                bestIdx = i;
            } else if (rate >= bestRate - HIT_RATE_EPSILON && conf > bestConfidence) {
                bestRate = rate;
                bestConfidence = conf;
                bestIdx = i;
            }
        }

        selectedStrategyIndex = bestIdx;
        selectedAngle = strategies.get(bestIdx).getFireAngle(wb);
    }

    /** Get the absolute angle the gun should turn toward. */
    public double getSelectedAngle() {
        return selectedAngle;
    }

    /** Get the currently selected strategy. */
    public IGunStrategy getSelectedStrategy() {
        return strategies.get(selectedStrategyIndex);
    }

    /** Compute the turn angle from current gun heading to target. */
    public double getGunTurnAngle(Whiteboard wb) {
        return RoboMath.normalRelativeAngle(selectedAngle - wb.getOurGunHeading());
    }

    /** Whether the gun is aimed close enough to fire. */
    public boolean isAimed(Whiteboard wb) {
        return Math.abs(getGunTurnAngle(wb)) < AIM_THRESHOLD;
    }

    /** Whether the gun can fire (aimed + cool). */
    public boolean shouldFire(Whiteboard wb) {
        return wb.getOurGunHeat() <= 0 && isAimed(wb);
    }

    /** Reset for a new round. */
    public void onRoundStart() {
        for (List<VirtualBullet> list : virtualBullets) {
            list.clear();
        }
        // Keep hit history across rounds
    }
}
