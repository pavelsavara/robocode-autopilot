/*
 * Copyright (c) 2001-2025 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://robocode.sourceforge.io/license/epl-v10.html
 */
package net.sf.robocode.dejavu.model;

/**
 * Confidence / provenance flags attached to a reconstructed tick.
 * <p>
 * These mark ticks where reconstruction could not be made exactly certain so
 * the validation harness can triage mismatches. A flag is either
 * <em>tick-level</em> (it concerns the whole turn) or <em>event-level</em> (it
 * concerns a single reconstructed event and is attached to that event via
 * {@link TickEvents#flagsFor(robocode.Event)}); the event-level flags are also
 * folded into the tick-level set so a coarse consumer can still detect them.
 */
public enum Provenance {

    /**
     * A skipped turn is suspected (turn gap or physics deltas exceed single-tick
     * limits). Tick-level.
     */
    SKIPPED_TURN_SUSPECTED,

    /**
     * Two or more hero bullets struck the same victim on the same turn while the
     * victim still had recordable energy, so the engine's processing order
     * &mdash; and therefore the per-event victim energy carried on each
     * {@link robocode.BulletHitEvent} &mdash; is RNG-dependent and not
     * snapshot-recoverable. The final victim energy is exact; only the
     * intermediate per-event {@code getEnergy()} labels may be swapped.
     * Event-level: attached to each {@code BulletHitEvent} in the colliding
     * group.
     */
    DOUBLE_HIT,

    /**
     * A {@link robocode.HitWallEvent} was emitted on a {@code HIT_WALL} tick on
     * which the pre-collision movement direction could not be recovered (the
     * reconstructed impact velocity resolved to ~0, e.g. a robot that was already
     * stationary or moving parallel to the wall). The event is present and its
     * energy is exact, but its {@code getBearing()} is a best-effort estimate.
     * Event-level: attached to the {@code HitWallEvent}.
     */
    WALL_BEARING_UNRESOLVED,

    /**
     * A {@link robocode.HitRobotEvent} (robot ram) whose bearing could not be
     * recovered exactly. The engine resolves robot collisions inside
     * {@code Battle.updateRobots()}, iterating the robots in RNG-shuffled order
     * ({@code getRobotsAtRandom()}); the ramming robot reads the other robot's
     * position <em>as it stands at that moment</em>, and the ram bearing is
     * measured from the ramming robot's own pre-bounce (overlap) position. Two
     * inputs to that bearing may be estimates: (1) the opponent's mid-turn
     * position, which is not snapshot-recoverable when the opponent moved this
     * turn (only the final post-physics positions survive), and (2) the ramming
     * robot's pre-bounce position, which is rebuilt from its reconstructed
     * single-acceleration-step pre-collision velocity &mdash; itself an estimate
     * whenever that velocity is non-zero, since the realised velocity depends on
     * the unrecorded movement command. The event, its {@code isMyFault()} and its
     * {@code getEnergy()} are exact; only {@code getBearingRadians()} is a
     * best-effort estimate. Event-level: attached to the {@code HitRobotEvent}.
     */
    ROBOT_BEARING_UNRESOLVED,

    /**
     * A hero {@link robocode.BulletHitEvent} struck the opponent on the same turn
     * that an opponent bullet struck the hero. Because the opponent is both the
     * victim of the hero's bullet (taking {@link robocode.Rules#getBulletDamage}
     * damage) and the shooter of its own bullet (earning
     * {@link robocode.Rules#getBulletHitBonus} for hitting the hero), the
     * opponent's energy is written by two operations this turn. Both bullets are
     * resolved inside {@code Battle.updateBullets()} iterating in RNG-shuffled
     * order ({@code getBulletsAtRandom()}), so whether the opponent's hit bonus
     * has already been added when the hero's bullet records the victim energy is
     * RNG-dependent and not snapshot-recoverable. The opponent's final energy is
     * exact; only the per-event {@code getEnergy()} carried on the hero's
     * {@code BulletHitEvent} may differ by the bonus amount. The event, its
     * {@code getName()} and {@code isMyFault()} are exact. Event-level: attached
     * to the hero's {@code BulletHitEvent}.
     */
    SHOOTER_BONUS_UNRESOLVED,

    /**
     * A {@link robocode.ScannedRobotEvent} reconstructed on a turn whose radar
     * sweep was (near) zero-width &mdash; the radar heading did not change, so the
     * snapshot-pure scan gate cannot tell whether the engine actually delivered a
     * scan this turn. A robot may also trigger a scan by calling {@code scan()}
     * manually, which leaves no trace in the snapshots; on a degenerate
     * (zero-width) sweep the engine's exact line-intersection test against the
     * opponent's bounding box is not snapshot-recoverable, so the scan gate
     * over-produces here. The scan's fields (bearing, distance, energy, heading,
     * velocity) are exact when the scan did occur; only its <em>presence</em> is
     * uncertain. Event-level: attached to the {@code ScannedRobotEvent}.
     */
    SCAN_UNCERTAIN,

    /**
     * Two concurrently-live bullets were captured sharing one engine id, so the
     * reconstruction re-keyed the duplicate into a disjoint negative band to keep
     * the two separable (see {@code BulletIdCanonicalizer}). This happens only
     * against an engine that re-fires a persisted fire command (a robot that
     * stopped calling {@code execute()} with a fire pending); a fixed engine
     * consumes each command once and never produces it. All reconstructed fields
     * are exact &mdash; the synthetic id is internal bookkeeping only, never an
     * engine-visible value &mdash; this flag merely records that the remap
     * occurred. Tick-level.
     */
    DUPLICATE_ID,

    /**
     * The ground-truth realized-turn / move oracle (the per-tick decrement of the
     * captured {@code IExecCommands} {@code get*TurnRemaining()} /
     * {@code getDistanceRemaining()} counters) could not be used to corroborate the
     * reconstructed command on this tick, because the counter was reset by a fresh
     * actuator command issued the same tick (or the turn completed within a single
     * tick), so the {@code prev - cur} remaining delta no longer equals the realized
     * rotation/move. The reconstructed command is still cross-checked against the
     * snapshot-realized physical effect on these ticks; this flag records that the
     * stronger independent oracle was unavailable. Tick-level.
     */
    COMMAND_ORACLE_UNCERTAIN
}
