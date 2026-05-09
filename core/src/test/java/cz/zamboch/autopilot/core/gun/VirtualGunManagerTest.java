package cz.zamboch.autopilot.core.gun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VirtualGunManager.selectBestIndex — the core gun selection logic.
 *
 * <p>Strategy order matches production (Decision #10):
 * CircularGun(0), VcsGun(1), PredictiveGun(2), LinearGun(3), HeadOnGun(4).
 * Lower index = higher priority. Within epsilon (3%), lowest index wins.</p>
 */
final class VirtualGunManagerTest {

    // Indices matching production gun list
    private static final int CIRCULAR = 0;
    private static final int VCS = 1;
    private static final int PREDICTIVE = 2;
    private static final int LINEAR = 3;
    private static final int HEADON = 4;

    // === Tie-break: all guns within epsilon ===

    @Test
    void allSameRate_selectsLowestIndex() {
        // All guns at 4% HR — all within epsilon → index 0 (CircularGun) wins
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.04};
        assertEquals(CIRCULAR, VirtualGunManager.selectBestIndex(rates),
                "All tied → CircularGun (lowest index) must win");
    }

    @Test
    void allZeroRate_selectsLowestIndex() {
        // Start of battle: all guns at 0% HR
        double[] rates = {0.0, 0.0, 0.0, 0.0, 0.0};
        assertEquals(CIRCULAR, VirtualGunManager.selectBestIndex(rates),
                "All zero → CircularGun (lowest index) must win");
    }

    @Test
    void headOnSlightlyBetter_withinEpsilon_selectsCircular() {
        // HeadOnGun at 6%, others at 4% — difference 2% < epsilon 3%
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.06};
        assertEquals(CIRCULAR, VirtualGunManager.selectBestIndex(rates),
                "HeadOn within epsilon → CircularGun (highest priority) must win");
    }

    @Test
    void headOnOneExtraHit_withinEpsilon_selectsCircular() {
        // Exactly the 50-window scenario: 1 extra hit = +0.02 rate
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.06};
        assertEquals(CIRCULAR, VirtualGunManager.selectBestIndex(rates),
                "One extra random hit must not override priority ordering");
    }

    // === Clear winner: one gun above epsilon ===

    @Test
    void oneClearlyBetter_selectsThatGun() {
        // VcsGun at 10%, others at 4% — difference 6% > epsilon 3%
        double[] rates = {0.04, 0.10, 0.04, 0.04, 0.04};
        assertEquals(VCS, VirtualGunManager.selectBestIndex(rates),
                "VcsGun clearly better → must be selected");
    }

    @Test
    void headOnClearlyBetter_selectedDespiteLowPriority() {
        // HeadOnGun at 12% vs 4% — must win despite being lowest priority
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.12};
        assertEquals(HEADON, VirtualGunManager.selectBestIndex(rates),
                "HeadOn clearly better by >epsilon → must be selected");
    }

    @Test
    void linearClearlyBetter_selectedOverHigherPriority() {
        // LinearGun at 10%, others at 3% — difference 7% > epsilon
        double[] rates = {0.03, 0.03, 0.03, 0.10, 0.03};
        assertEquals(LINEAR, VirtualGunManager.selectBestIndex(rates),
                "Linear clearly better → selected over higher priority guns");
    }

    // === Priority ordering within epsilon band ===

    @Test
    void twoGunsWithinEpsilon_higherPriorityWins() {
        // Circular and VCS both in epsilon band — Circular (lower index) wins
        double[] rates = {0.05, 0.05, 0.02, 0.02, 0.02};
        assertEquals(CIRCULAR, VirtualGunManager.selectBestIndex(rates),
                "Circular has higher priority than VCS → Circular wins tie");
    }

    @Test
    void vcsAndCircularWithinEpsilon_circularWins() {
        // VCS slightly ahead: 5% vs 4%, difference 1% < epsilon 3%
        double[] rates = {0.04, 0.05, 0.03, 0.03, 0.03};
        assertEquals(CIRCULAR, VirtualGunManager.selectBestIndex(rates),
                "VCS slightly ahead but within epsilon → CircularGun wins");
    }

    @Test
    void vcsAheadBeyondEpsilon_vcsWins() {
        // VCS at 8%, Circular at 4% — difference 4% > epsilon 3%
        double[] rates = {0.04, 0.08, 0.03, 0.03, 0.03};
        assertEquals(VCS, VirtualGunManager.selectBestIndex(rates),
                "VCS more than epsilon ahead → VCS wins");
    }

    @Test
    void priorityOrderPreserved_circularOverVcsOverPredictiveOverLinearOverHeadOn() {
        // All at exactly 4% — must select in priority order
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.04};
        assertEquals(CIRCULAR, VirtualGunManager.selectBestIndex(rates));

        // Remove Circular from epsilon band (drop it to 0%)
        double[] rates2 = {0.00, 0.04, 0.04, 0.04, 0.04};
        assertEquals(VCS, VirtualGunManager.selectBestIndex(rates2),
                "Without Circular in band → VCS wins");

        // Remove VCS too
        double[] rates3 = {0.00, 0.00, 0.04, 0.04, 0.04};
        assertEquals(PREDICTIVE, VirtualGunManager.selectBestIndex(rates3),
                "Without Circular+VCS in band → Predictive wins");

        // Remove Predictive too
        double[] rates4 = {0.00, 0.00, 0.00, 0.04, 0.04};
        assertEquals(LINEAR, VirtualGunManager.selectBestIndex(rates4),
                "Without Circular+VCS+Predictive → Linear wins");

        // Only HeadOn left
        double[] rates5 = {0.00, 0.00, 0.00, 0.00, 0.04};
        assertEquals(HEADON, VirtualGunManager.selectBestIndex(rates5),
                "Only HeadOn in band → HeadOn wins");
    }

    // === Edge cases ===

    @Test
    void singleStrategy_alwaysSelected() {
        double[] rates = {0.05};
        assertEquals(0, VirtualGunManager.selectBestIndex(rates));
    }

    @Test
    void withinEpsilon_higherPriorityWins() {
        // HeadOn at 6.9% vs Circular at 4% — difference 2.9% < epsilon 3%
        // max = 6.9%, threshold = 3.9%. Circular at 4% qualifies.
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.069};
        assertEquals(CIRCULAR, VirtualGunManager.selectBestIndex(rates),
                "Within epsilon → higher priority still wins");
    }

    @Test
    void justBeyondEpsilon_lowerPriorityWins() {
        // HeadOn at 7.1%, Circular at 4%. Difference 3.1% > epsilon 3%.
        // max = 7.1%, threshold = 4.1%. Circular at 4% does NOT qualify.
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.071};
        assertEquals(HEADON, VirtualGunManager.selectBestIndex(rates),
                "Just beyond epsilon → lower priority gun wins on merit");
    }

    @Test
    void noRatchetDown_lateGunCannotSneakThrough() {
        // Old ratchet-down bug scenario. Gun0 at 4%, gun4 at 6%.
        // max=6%, threshold=3%. Gun0 at 4% >= 3% → still qualifies.
        double[] rates = {0.04, 0.02, 0.02, 0.02, 0.06};
        assertEquals(CIRCULAR, VirtualGunManager.selectBestIndex(rates),
                "CircularGun within epsilon of max → wins by priority");
    }

    @Test
    void twoStrategies_tieGoesToFirst() {
        double[] rates = {0.03, 0.03};
        assertEquals(0, VirtualGunManager.selectBestIndex(rates));
    }

    @Test
    void allAtEpsilon_selectsFirst() {
        // All at exactly the epsilon value
        double[] rates = {0.03, 0.03, 0.03, 0.03, 0.03};
        assertEquals(CIRCULAR, VirtualGunManager.selectBestIndex(rates));
    }
}
