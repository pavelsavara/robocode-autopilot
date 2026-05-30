package cz.zamboch.autopilot.pipeline;

import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IDebugProperty;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;

/**
 * Records the raw {@link ITurnSnapshot} stream of a real battle to a compact,
 * dependency-free text fixture so it can be replayed offline in fast unit tests.
 * <p>
 * The point of the fixture is to <b>ground unit tests in engine behavior</b>:
 * the recorded snapshots are the engine's authoritative post-physics state, and
 * each tick also carries a small subset of the live {@code Autopilot}'s published
 * {@link IDebugProperty} values — which the live robot computed from the
 * <i>real</i> Robocode events it received. A replay of the snapshots through
 * {@link EventReconstructor} / the observer pipeline can therefore be checked
 * against two independent engine-truth sources (authoritative kinematics and the
 * live robot's event-derived debug properties) instead of re-asserting the
 * production code's own hand-computed formulas.
 * <p>
 * Off by default; enabled by setting the {@code record.fixture.dir} system
 * property (wired in {@link BattleRunner}). One battle = one fixture file, named
 * {@code <sanitized-opponent>.fixture}. Each line is tab-delimited and prefixed
 * with a record type; doubles are written with {@link Double#toString} so they
 * round-trip bit-exactly via {@link Double#parseDouble}.
 *
 * <pre>
 * SPAWN &lt;round&gt; &lt;turn&gt; &lt;autopilotIndex&gt;
 * TICK  &lt;round&gt; &lt;turn&gt; &lt;autopilotIndex&gt;
 * ROBOT  &lt;index&gt; &lt;x&gt; &lt;y&gt; &lt;bodyHeading&gt; &lt;velocity&gt; &lt;energy&gt; &lt;gunHeat&gt; &lt;gunHeading&gt; &lt;radarHeading&gt; &lt;state&gt; &lt;shortName&gt; &lt;name&gt;
 * BULLET &lt;id&gt; &lt;owner&gt; &lt;victim&gt; &lt;power&gt; &lt;state&gt; &lt;x&gt; &lt;y&gt; &lt;heading&gt;
 * DEBUG  &lt;key&gt; &lt;value&gt;
 * END
 * </pre>
 */
public final class SnapshotFixtureWriter implements Closeable {

    /**
     * The live debug properties recorded per tick. These are the features the live
     * {@code Autopilot} sets from the real {@code ScannedRobotEvent} it receives, so
     * they form an engine-grounded cross-check for the reconstructed scan.
     */
    static final Set<String> RECORDED_DEBUG_KEYS = Set.of(
            "DISTANCE",
            "BEARING_RADIANS",
            "OPPONENT_HEADING",
            "OPPONENT_VELOCITY",
            "OPPONENT_ENERGY",
            "TICKS_SINCE_SCAN");

    private final BufferedWriter out;

    public SnapshotFixtureWriter(File dir, String opponent) throws IOException {
        dir.mkdirs();
        String safe = opponent.replaceAll("[^A-Za-z0-9._-]", "_");
        // Overwrite: one battle produces one fixture.
        this.out = new BufferedWriter(new FileWriter(new File(dir, safe + ".fixture"), false));
    }

    /** Record the round-start (spawn) snapshot used to seed event reconstruction. */
    public void writeSpawn(ITurnSnapshot turn) {
        write("SPAWN", turn);
    }

    /** Record a post-turn snapshot. */
    public void writeTurn(ITurnSnapshot turn) {
        write("TICK", turn);
    }

    private void write(String kind, ITurnSnapshot turn) {
        if (turn == null) {
            return;
        }
        IRobotSnapshot[] robots = turn.getRobots();
        if (robots == null || robots.length < 2) {
            return;
        }
        int autopilotIndex = autopilotIndex(robots);
        try {
            out.write(kind);
            out.write('\t');
            out.write(Integer.toString(turn.getRound()));
            out.write('\t');
            out.write(Integer.toString(turn.getTurn()));
            out.write('\t');
            out.write(Integer.toString(autopilotIndex));
            out.write('\n');

            for (int i = 0; i < 2; i++) {
                writeRobot(i, robots[i]);
            }

            IBulletSnapshot[] bullets = turn.getBullets();
            if (bullets != null) {
                for (IBulletSnapshot b : bullets) {
                    writeBullet(b);
                }
            }

            IDebugProperty[] props = robots[autopilotIndex].getDebugProperties();
            if (props != null) {
                for (IDebugProperty p : props) {
                    if (RECORDED_DEBUG_KEYS.contains(p.getKey())) {
                        out.write("DEBUG\t");
                        out.write(p.getKey());
                        out.write('\t');
                        out.write(p.getValue() == null ? "" : p.getValue());
                        out.write('\n');
                    }
                }
            }

            out.write("END\n");
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeRobot(int index, IRobotSnapshot r) throws IOException {
        out.write("ROBOT\t");
        out.write(Integer.toString(index));
        out.write('\t');
        out.write(Double.toString(r.getX()));
        out.write('\t');
        out.write(Double.toString(r.getY()));
        out.write('\t');
        out.write(Double.toString(r.getBodyHeading()));
        out.write('\t');
        out.write(Double.toString(r.getVelocity()));
        out.write('\t');
        out.write(Double.toString(r.getEnergy()));
        out.write('\t');
        out.write(Double.toString(r.getGunHeat()));
        out.write('\t');
        out.write(Double.toString(r.getGunHeading()));
        out.write('\t');
        out.write(Double.toString(r.getRadarHeading()));
        out.write('\t');
        out.write(r.getState().name());
        out.write('\t');
        out.write(r.getShortName());
        out.write('\t');
        out.write(r.getName());
        out.write('\n');
    }

    private void writeBullet(IBulletSnapshot b) throws IOException {
        out.write("BULLET\t");
        out.write(Integer.toString(b.getBulletId()));
        out.write('\t');
        out.write(Integer.toString(b.getOwnerIndex()));
        out.write('\t');
        out.write(Integer.toString(b.getVictimIndex()));
        out.write('\t');
        out.write(Double.toString(b.getPower()));
        out.write('\t');
        out.write(b.getState().name());
        out.write('\t');
        out.write(Double.toString(b.getX()));
        out.write('\t');
        out.write(Double.toString(b.getY()));
        out.write('\t');
        out.write(Double.toString(b.getHeading()));
        out.write('\n');
    }

    /** The Autopilot side is the only robot that publishes debug properties. */
    private static int autopilotIndex(IRobotSnapshot[] robots) {
        for (int i = 0; i < robots.length; i++) {
            IDebugProperty[] props = robots[i].getDebugProperties();
            if (props != null && props.length > 0) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
