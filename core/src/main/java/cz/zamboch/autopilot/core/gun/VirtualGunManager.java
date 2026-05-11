package cz.zamboch.autopilot.core.gun;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.persistence.IPersistable;
import cz.zamboch.autopilot.core.strategy.IGunStrategy;
import cz.zamboch.autopilot.core.strategy.VirtualBullet;
import cz.zamboch.autopilot.core.util.RoboMath;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

/**
 * Tracks multiple gun strategies via virtual bullets, selects the best
 * performer based on rolling hit rate, and controls gun aiming.
 *
 * <p>Uses pre-allocated bullet pools (no per-scan allocation).
 * Hit history persists across rounds and can be saved/loaded across battles.</p>
 */
public final class VirtualGunManager implements IPersistable {

    public static final int SECTION_ID = 1;

    private static final int WINDOW = 25;
    private static final double HIT_RATE_EPSILON = 0.03;
    private static final double AIM_THRESHOLD = 0.02; // ~1.15 degrees — slightly relaxed for more firing opportunities
    /** Exploration rate: fraction of shots fired with a random gun. */
    private static final double EXPLORE_RATE = 0.01;

    /** Max virtual bullets in flight per strategy. Longest flight: ~40 ticks. */
    private static final int BULLET_POOL_SIZE = 64;

    private final List<IGunStrategy> strategies;
    private final Random rng = new Random();
    /** Pre-allocated bullet pools: [strategy][slot]. */
    private final VirtualBullet[][] bulletPool;
    /** Number of active bullets per strategy. */
    private final int[] bulletCount;
    private final int[][] hitHistory;
    private final int[] historyIndex;
    private final int[] historyCount;
    private final int[] hitCounts;

    private double selectedAngle;
    private int selectedStrategyIndex;

    public VirtualGunManager(List<IGunStrategy> strategies) {
        this.strategies = strategies;
        int n = strategies.size();
        this.bulletPool = new VirtualBullet[n][BULLET_POOL_SIZE];
        this.bulletCount = new int[n];
        this.hitHistory = new int[n][WINDOW];
        this.historyIndex = new int[n];
        this.historyCount = new int[n];
        this.hitCounts = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < BULLET_POOL_SIZE; j++) {
                bulletPool[i][j] = new VirtualBullet();
            }
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

        checkVirtualBullets(wb);

        for (int i = 0; i < strategies.size(); i++) {
            double angle = strategies.get(i).getFireAngle(wb);
            if (!Double.isNaN(angle) && bulletCount[i] < BULLET_POOL_SIZE) {
                bulletPool[i][bulletCount[i]].set(
                        ourX, ourY, angle, bulletSpeed, tick, distance);
                bulletCount[i]++;
            }
        }

        selectBest(wb);
    }

    /**
     * Check all virtual bullets against current opponent position.
     * Removes passed bullets by swapping with the last active slot.
     */
    private void checkVirtualBullets(Whiteboard wb) {
        double oppX = wb.getOpponentX();
        double oppY = wb.getOpponentY();
        long tick = wb.getTick();

        for (int i = 0; i < strategies.size(); i++) {
            int j = 0;
            while (j < bulletCount[i]) {
                VirtualBullet vb = bulletPool[i][j];
                if (vb.hasPassed(tick)) {
                    // Euclidean hit check: simulate bullet position, check distance to opponent
                    double travelDist = vb.distanceTraveled(tick);
                    double bulletX = vb.startX + travelDist * Math.sin(vb.heading);
                    double bulletY = vb.startY + travelDist * Math.cos(vb.heading);
                    double dx = bulletX - oppX;
                    double dy = bulletY - oppY;
                    boolean hit = (dx * dx + dy * dy) <= 18.0 * 18.0;
                    recordResult(i, hit ? 1 : 0);
                    // Swap-remove: move last active bullet into this slot
                    bulletCount[i]--;
                    if (j < bulletCount[i]) {
                        VirtualBullet last = bulletPool[i][bulletCount[i]];
                        bulletPool[i][j] = last;
                        bulletPool[i][bulletCount[i]] = vb;
                    }
                    // Don't increment j — re-check the swapped-in bullet
                } else {
                    j++;
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
        double[] rates = new double[strategies.size()];
        for (int i = 0; i < strategies.size(); i++) {
            rates[i] = getHitRate(i);
        }

        int bestIdx = selectBestIndex(rates);

        // ε-greedy exploration: occasionally use a random gun to gather data
        // Only explore after initial convergence (first 30 data points)
        int totalData = 0;
        for (int i = 0; i < strategies.size(); i++) {
            totalData += historyCount[i];
        }
        if (totalData > 30 && rng.nextDouble() < EXPLORE_RATE) {
            selectedStrategyIndex = rng.nextInt(strategies.size());
        } else {
            selectedStrategyIndex = bestIdx;
        }
        selectedAngle = strategies.get(selectedStrategyIndex).getFireAngle(wb);
    }

    /** Compute the turn angle from current gun heading to target. */
    public double getGunTurnAngle(Whiteboard wb) {
        return RoboMath.normalRelativeAngle(selectedAngle - wb.getOurGunHeading());
    }

    /** Whether the gun can fire (aimed + cool + recent scan + not turning too fast). */
    public boolean shouldFire(Whiteboard wb) {
        if (wb.getOurGunHeat() > 0) return false;
        // Don't fire on stale scan data (>3 ticks old)
        if (wb.getTick() - wb.getLastScanTick() > 3) return false;
        double gunTurnRemaining = Math.abs(getGunTurnAngle(wb));
        return gunTurnRemaining < AIM_THRESHOLD;
    }

    /**
     * Pure selection logic: pick the best strategy index given hit rates.
     * Uses a two-pass approach:
     * <ol>
     *   <li>Find the maximum hit rate across all strategies.</li>
     *   <li>Among strategies within {@link #HIT_RATE_EPSILON} of the max,
     *       pick the lowest index (highest priority in the list).</li>
     * </ol>
     * The gun list order IS the priority: lower index = higher priority.
     * A gun must beat the current best by more than epsilon to override
     * the priority ordering.
     * Package-visible for testing.
     */
    static int selectBestIndex(double[] rates) {
        // Pass 1: find the maximum hit rate
        double maxRate = 0;
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] > maxRate) {
                maxRate = rates[i];
            }
        }

        // Pass 2: first gun within epsilon of max wins (lowest index = highest priority)
        double threshold = maxRate - HIT_RATE_EPSILON;
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] >= threshold) {
                return i;
            }
        }
        return 0; // unreachable — at least the max-rate gun qualifies
    }

    /** Reset for a new round. Virtual bullets cleared, hit history preserved. */
    public void onRoundStart() {
        for (int i = 0; i < strategies.size(); i++) {
            bulletCount[i] = 0;
        }
    }

    // === Debug accessors ===

    /** Index of the currently selected gun strategy. */
    public int getSelectedIndex() { return selectedStrategyIndex; }

    /** Name of the currently selected gun strategy. */
    public String getSelectedName() {
        return strategies.get(selectedStrategyIndex).getName();
    }

    /** Hit rate of a specific strategy. */
    public double getHitRateOf(int i) { return getHitRate(i); }

    /** Number of registered strategies. */
    public int getStrategyCount() { return strategies.size(); }

    /** Name of strategy at index i. */
    public String getStrategyName(int i) { return strategies.get(i).getName(); }

    // === IPersistable (cross-battle persistence) ===

    @Override
    public int getSectionId() { return SECTION_ID; }

    @Override
    public void writeTo(DataOutputStream out) throws IOException {
        int n = strategies.size();
        out.writeInt(n);
        for (int i = 0; i < n; i++) {
            out.writeInt(historyCount[i]);
            out.writeInt(historyIndex[i]);
            out.writeInt(hitCounts[i]);
            for (int j = 0; j < WINDOW; j++) {
                out.writeInt(hitHistory[i][j]);
            }
        }
    }

    @Override
    public void readFrom(DataInputStream in, int length) throws IOException {
        int n = in.readInt();
        int count = Math.min(n, strategies.size());
        for (int i = 0; i < count; i++) {
            historyCount[i] = in.readInt();
            historyIndex[i] = in.readInt();
            hitCounts[i] = in.readInt();
            for (int j = 0; j < WINDOW; j++) {
                hitHistory[i][j] = in.readInt();
            }
        }
        // Skip extra strategies if file has more than current config
        for (int i = count; i < n; i++) {
            in.readInt(); // historyCount
            in.readInt(); // historyIndex
            in.readInt(); // hitCounts
            for (int j = 0; j < WINDOW; j++) {
                in.readInt();
            }
        }
    }
}
