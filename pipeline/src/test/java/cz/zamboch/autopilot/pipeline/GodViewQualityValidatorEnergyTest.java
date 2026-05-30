package cz.zamboch.autopilot.pipeline;

import static cz.zamboch.autopilot.pipeline.TestSnapshots.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.RobotState;

/**
 * Unit tests for {@link GodViewQualityValidator#accountEnergy}.
 *
 * <p>
 * These tests pin down the bullet/wall/ram energy-accounting lifecycle:
 * god-view
 * snapshot states linger across ticks (a hit bullet stays {@code HIT_VICTIM}
 * during
 * the explosion animation; a robot stays {@code HIT_WALL}/{@code HIT_ROBOT}
 * while
 * pinned), so each one-time energy event must be charged exactly once. The
 * model
 * always drives perspective 0; perspective 1 is the opponent.
 */
class GodViewQualityValidatorEnergyTest {

    private static final double FIELD = 800.0;

    private GodViewQualityValidator newValidator() {
        return new GodViewQualityValidator(FIELD, FIELD);
    }

    /** Build the two-robot array (perspective 0 = self, 1 = opponent). */
    private static IRobotSnapshot[] robots(double selfEnergy, double selfVelocity,
            RobotState selfState, RobotState oppState) {
        IRobotSnapshot self = robot(100, 100, 0, selfVelocity, selfEnergy, 0, 0, 0,
                0, selfState, "self");
        IRobotSnapshot opp = robot(200, 200, 0, 0, 100, 0, 0, 0,
                1, oppState, "opp");
        return new IRobotSnapshot[] { self, opp };
    }

    /** Drive one tick from perspective 0. */
    private static void tick(GodViewQualityValidator v, double selfEnergy, double selfVelocity,
            RobotState selfState, RobotState oppState, IBulletSnapshot... bullets) {
        v.accountEnergy(0, robots(selfEnergy, selfVelocity, selfState, oppState), bullets);
    }

    @Test
    @DisplayName("Fire cost is charged once even though the bullet snapshot lingers for many ticks")
    void fireCostChargedOnce() {
        GodViewQualityValidator v = newValidator();
        // Tick 0: initialization, no check.
        tick(v, 100, 0, RobotState.ACTIVE, RobotState.ACTIVE);
        // Tick 1: our bullet (power 2) enters FIRED state -> fire cost -2 -> energy 98.
        tick(v, 98, 0, RobotState.ACTIVE, RobotState.ACTIVE,
                bullet(1, 0, 1, 2.0, BulletState.FIRED));
        // Ticks 2-3: same bullet id still in flight (MOVING) -> NO additional charge.
        IBulletSnapshot moving = bullet(1, 0, 1, 2.0, BulletState.MOVING);
        tick(v, 98, 0, RobotState.ACTIVE, RobotState.ACTIVE, moving);
        tick(v, 98, 0, RobotState.ACTIVE, RobotState.ACTIVE, moving);

        assertEquals(3, v.getEnergyChecks(0));
        assertEquals(0, v.getEnergyDiscrepancies(0), "fire cost must be charged exactly once");
    }

    @Test
    @DisplayName("Hit bonus is credited once across a lingering HIT_VICTIM bullet")
    void hitBonusCreditedOnce() {
        GodViewQualityValidator v = newValidator();
        tick(v, 100, 0, RobotState.ACTIVE, RobotState.ACTIVE);
        // Tick 1: bullet fired (power 2) -> -2 -> 98.
        tick(v, 98, 0, RobotState.ACTIVE, RobotState.ACTIVE,
                bullet(1, 0, 1, 2.0, BulletState.FIRED));
        // Tick 2: bullet hits -> +hitEnergyBonus(2) = +6 -> 104.
        IBulletSnapshot hit = bullet(1, 0, 1, 2.0, BulletState.HIT_VICTIM);
        tick(v, 104, 0, RobotState.ACTIVE, RobotState.ACTIVE, hit);
        // Ticks 3-4: HIT_VICTIM lingers during explosion animation -> NO further bonus.
        tick(v, 104, 0, RobotState.ACTIVE, RobotState.ACTIVE, hit);
        tick(v, 104, 0, RobotState.ACTIVE, RobotState.ACTIVE, hit);

        assertEquals(0, v.getEnergyDiscrepancies(0), "hit bonus must be credited exactly once");
    }

    @Test
    @DisplayName("Bullet damage received is charged once for a lingering opponent hit")
    void bulletDamageReceivedOnce() {
        GodViewQualityValidator v = newValidator();
        tick(v, 100, 0, RobotState.ACTIVE, RobotState.ACTIVE);
        // Opponent bullet (owner 1, victim 0, power 3) hits us:
        // bulletDamage(3) = 4*3 + 2*(3-1) = 16 -> energy 84.
        IBulletSnapshot incoming = bullet(5, 1, 0, 3.0, BulletState.HIT_VICTIM);
        tick(v, 84, 0, RobotState.ACTIVE, RobotState.ACTIVE, incoming);
        // Lingering HIT_VICTIM -> no further damage.
        tick(v, 84, 0, RobotState.ACTIVE, RobotState.ACTIVE, incoming);

        assertEquals(0, v.getEnergyDiscrepancies(0), "received damage must be charged exactly once");
    }

    @Test
    @DisplayName("Wall damage is applied once on the HIT_WALL transition using pre-impact velocity")
    void wallDamageOnTransition() {
        GodViewQualityValidator v = newValidator();
        // Tick 0: moving at velocity 8.
        tick(v, 100, 8, RobotState.ACTIVE, RobotState.ACTIVE);
        // Tick 1: transition into HIT_WALL. wallDamage(8) = 8/2 - 1 = 3 -> energy 97.
        tick(v, 97, 0, RobotState.HIT_WALL, RobotState.ACTIVE);
        // Tick 2: still pinned to wall -> NO further wall damage.
        tick(v, 97, 0, RobotState.HIT_WALL, RobotState.ACTIVE);

        assertEquals(0, v.getEnergyDiscrepancies(0), "wall damage must be applied once on transition");
    }

    @Test
    @DisplayName("Ram damage is charged every contact tick, detected via the opponent's HIT_ROBOT state")
    void ramDamageWhilePinnedToWall() {
        GodViewQualityValidator v = newValidator();
        // Tick 0: stationary against wall (velocity 0 so no wall damage on later
        // ticks).
        tick(v, 100, 0, RobotState.HIT_WALL, RobotState.ACTIVE);
        // Self reports HIT_WALL (wall takes priority in the state enum) while being
        // rammed; the opponent reports HIT_ROBOT. Ram damage 0.6 charged each tick.
        tick(v, 99.4, 0, RobotState.HIT_WALL, RobotState.HIT_ROBOT);
        tick(v, 98.8, 0, RobotState.HIT_WALL, RobotState.HIT_ROBOT);

        assertEquals(0, v.getEnergyDiscrepancies(0),
                "ram damage must be charged each contact tick even when self reports HIT_WALL");
    }

    @Test
    @DisplayName("A self-inflicted ram (own HIT_ROBOT state) is charged once per tick")
    void ramDamageBySelfState() {
        GodViewQualityValidator v = newValidator();
        tick(v, 100, 0, RobotState.ACTIVE, RobotState.ACTIVE);
        tick(v, 99.4, 0, RobotState.HIT_ROBOT, RobotState.ACTIVE);
        tick(v, 98.8, 0, RobotState.HIT_ROBOT, RobotState.ACTIVE);

        assertEquals(0, v.getEnergyDiscrepancies(0));
    }

    @Test
    @DisplayName("An energy change that violates the model is flagged as a discrepancy")
    void mismatchIsFlagged() {
        GodViewQualityValidator v = newValidator();
        tick(v, 100, 0, RobotState.ACTIVE, RobotState.ACTIVE);
        // No event explains a 5-point drop -> discrepancy.
        tick(v, 95, 0, RobotState.ACTIVE, RobotState.ACTIVE);

        assertEquals(1, v.getEnergyChecks(0));
        assertEquals(1, v.getEnergyDiscrepancies(0), "unexplained energy loss must be flagged");
    }

    @Test
    @DisplayName("resetRound clears per-round bullet ids so a reused id re-charges its fire cost")
    void resetRoundClearsBulletIds() {
        GodViewQualityValidator v = newValidator();
        // Round 1: bullet id 1 fired.
        tick(v, 100, 0, RobotState.ACTIVE, RobotState.ACTIVE);
        tick(v, 98, 0, RobotState.ACTIVE, RobotState.ACTIVE,
                bullet(1, 0, 1, 2.0, BulletState.FIRED));
        assertEquals(0, v.getEnergyDiscrepancies(0));

        // New round: bullet ids restart at 1. Without a reset the id would be seen
        // as already-fired and the cost would be skipped, producing a discrepancy.
        v.resetRound();
        tick(v, 100, 0, RobotState.ACTIVE, RobotState.ACTIVE);
        tick(v, 98, 0, RobotState.ACTIVE, RobotState.ACTIVE,
                bullet(1, 0, 1, 2.0, BulletState.FIRED));

        assertEquals(0, v.getEnergyDiscrepancies(0),
                "reused bullet id in a new round must re-charge the fire cost");
    }
}
