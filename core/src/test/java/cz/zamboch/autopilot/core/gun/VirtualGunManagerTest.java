package cz.zamboch.autopilot.core.gun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VirtualGunManager.selectBestIndex — the core gun selection logic.
 *
 * <p>Strategy order matches production: CircularGun(0), LinearGun(1),
 * VcsGun(2), PredictiveGun(3), HeadOnGun(4). Confidence values mirror
 * the actual getConfidence() returns.</p>
 */
final class VirtualGunManagerTest {

    // Confidence values matching production guns
    private static final double CIRCULAR_CONF = 1.0;
    private static final double LINEAR_CONF = 0.7;
    private static final double VCS_CONF = 0.5; // varies 0-1.0, use typical mid-value
    private static final double PREDICTIVE_CONF = 0.5;
    private static final double HEADON_CONF = 0.3;

    private static final double[] CONFS = {
            CIRCULAR_CONF, LINEAR_CONF, VCS_CONF, PREDICTIVE_CONF, HEADON_CONF
    };

    @Test
    void allSameRate_selectsHighestConfidence() {
        // All guns at 4% HR → should select CircularGun (index 0, conf 1.0)
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.04};
        assertEquals(0, VirtualGunManager.selectBestIndex(rates, CONFS),
                "All tied → CircularGun (highest confidence) must win");
    }

    @Test
    void allZeroRate_selectsHighestConfidence() {
        // Start of battle: all guns at 0% HR
        double[] rates = {0.0, 0.0, 0.0, 0.0, 0.0};
        assertEquals(0, VirtualGunManager.selectBestIndex(rates, CONFS),
                "All zero → CircularGun must win");
    }

    @Test
    void oneClearlyBetter_selectsThatGun() {
        // VcsGun at 10%, others at 4% — difference 6% > epsilon 3%
        double[] rates = {0.04, 0.04, 0.10, 0.04, 0.04};
        assertEquals(2, VirtualGunManager.selectBestIndex(rates, CONFS),
                "VcsGun clearly better → must be selected");
    }

    @Test
    void headOnSlightlyBetter_withinEpsilon_selectsCircular() {
        // HeadOnGun at 6%, others at 4% — difference 2% < epsilon 3%
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.06};
        assertEquals(0, VirtualGunManager.selectBestIndex(rates, CONFS),
                "HeadOn within epsilon → CircularGun (higher conf) must win");
    }

    @Test
    void headOnOneExtraHit_withinEpsilon_selectsCircular() {
        // Exactly the 50-window scenario: 1 extra hit = +0.02 rate
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.06};
        assertEquals(0, VirtualGunManager.selectBestIndex(rates, CONFS),
                "One extra random hit must not override confidence");
    }

    @Test
    void clearWinner_despiteLowConfidence_selected() {
        // HeadOnGun clearly best at 12% vs 4% — must win despite low confidence
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.12};
        assertEquals(4, VirtualGunManager.selectBestIndex(rates, CONFS),
                "HeadOn clearly better by >epsilon → must be selected");
    }

    @Test
    void twoGunsWithinEpsilon_higherConfidenceWins() {
        // Circular and Linear both at 5%, others at 2%
        double[] rates = {0.05, 0.05, 0.02, 0.02, 0.02};
        assertEquals(0, VirtualGunManager.selectBestIndex(rates, CONFS),
                "Circular higher confidence than Linear → Circular wins");
    }

    @Test
    void vcsHighConfidence_winsOverCircularWhenTied() {
        // VcsGun with conf=1.0 ties CircularGun conf=1.0 — first-seen wins (index 0)
        double[] rates = {0.04, 0.04, 0.04, 0.04, 0.04};
        double[] confs = {1.0, 0.7, 1.0, 0.5, 0.3};
        // Both Circular (0) and VCS (2) have conf=1.0, Circular is first → wins
        assertEquals(0, VirtualGunManager.selectBestIndex(rates, confs),
                "Equal confidence → first in order wins (CircularGun)");
    }

    @Test
    void noRatchetDown_lateGunCannotSneakThrough() {
        // Scenario that triggered the ratchet-down bug:
        // Gun A (idx 0) at 0.04, gun B (idx 2) at 0.02 with high conf,
        // gun C (idx 4) at 0.06 should NOT win (only 2% above original best)
        double[] rates = {0.04, 0.02, 0.02, 0.02, 0.06};
        double[] confs = {0.3, 0.3, 1.0, 0.3, 0.3};
        // Gun 0 enters first with rate=0.04, conf=0.3
        // Gun 2 has higher conf=1.0, rate=0.02 within epsilon → takes over
        // But bestRate should stay at max(0.04, 0.02) = 0.04
        // Gun 4 at 0.06: 0.06 > 0.04 + 0.03 = 0.07? → NO
        // Gun 4 should NOT win
        int selected = VirtualGunManager.selectBestIndex(rates, confs);
        assertNotEquals(4, selected,
                "Late gun must not sneak through via ratchet-down");
        assertEquals(2, selected,
                "VcsGun (highest confidence within epsilon band) wins");
    }

    @Test
    void singleStrategy_alwaysSelected() {
        double[] rates = {0.05};
        double[] confs = {0.5};
        assertEquals(0, VirtualGunManager.selectBestIndex(rates, confs));
    }
}
