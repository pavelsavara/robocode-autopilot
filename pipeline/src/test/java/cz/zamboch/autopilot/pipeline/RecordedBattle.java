package cz.zamboch.autopilot.pipeline;

import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.snapshot.ITurnSnapshot;
import robocode.control.snapshot.RobotState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a recorded battle fixture (produced by {@link SnapshotFixtureWriter})
 * from the test classpath and exposes it for offline replay.
 * <p>
 * Each tick carries both the engine's authoritative {@link ITurnSnapshot}
 * (positions, kinematics, bullet states) and the live {@code Autopilot}'s
 * published debug properties for that turn. Because the live robot derived
 * those properties from the <i>real</i> Robocode events it received, replaying
 * the snapshots through {@link EventReconstructor} and comparing the result
 * against the live properties grounds the assertions in engine behavior rather
 * than in the production code's own re-stated formulas.
 */
final class RecordedBattle {

    /** A single recorded turn: engine snapshot + live engine-truth debug props. */
    record Tick(ITurnSnapshot snapshot, Map<String, Double> live) {
        int turn() {
            return snapshot.getTurn();
        }

        int round() {
            return snapshot.getRound();
        }

        /** Live debug value, or {@code NaN} when absent. */
        double liveValue(String key) {
            Double v = live.get(key);
            return v == null ? Double.NaN : v;
        }

        /** True when the live robot reported a scan this turn (engine truth). */
        boolean liveScannedThisTurn() {
            return live.get("TICKS_SINCE_SCAN") != null && live.get("TICKS_SINCE_SCAN") == 0.0;
        }
    }

    private final ITurnSnapshot spawn;
    private final int autopilotIndex;
    private final List<Tick> ticks;

    private RecordedBattle(ITurnSnapshot spawn, int autopilotIndex, List<Tick> ticks) {
        this.spawn = spawn;
        this.autopilotIndex = autopilotIndex;
        this.ticks = ticks;
    }

    /** The round-start snapshot used to seed event reconstruction. */
    ITurnSnapshot spawn() {
        return spawn;
    }

    /** Contestant index of the {@code Autopilot} (the side that publishes debug props). */
    int autopilotIndex() {
        return autopilotIndex;
    }

    /** Post-turn snapshots in order (spawn excluded). */
    List<Tick> ticks() {
        return Collections.unmodifiableList(ticks);
    }

    static RecordedBattle load(String resource) {
        try (InputStream in = RecordedBattle.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Recorded fixture not found on classpath: " + resource);
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RecordedBattle parse(BufferedReader r) throws IOException {
        ITurnSnapshot spawn = null;
        int autopilotIndex = 0;
        List<Tick> ticks = new ArrayList<>();

        String kind = null;
        int round = 0;
        int turn = 0;
        int apIndex = 0;
        IRobotSnapshot[] robots = new IRobotSnapshot[2];
        List<IBulletSnapshot> bullets = new ArrayList<>();
        Map<String, Double> live = new LinkedHashMap<>();

        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "SPAWN", "TICK" -> {
                    kind = f[0];
                    round = Integer.parseInt(f[1]);
                    turn = Integer.parseInt(f[2]);
                    apIndex = Integer.parseInt(f[3]);
                    robots = new IRobotSnapshot[2];
                    bullets = new ArrayList<>();
                    live = new LinkedHashMap<>();
                }
                case "ROBOT" -> {
                    int idx = Integer.parseInt(f[1]);
                    robots[idx] = parseRobot(idx, f);
                }
                case "BULLET" -> bullets.add(parseBullet(f));
                case "DEBUG" -> live.put(f[1], Double.parseDouble(f[2]));
                case "END" -> {
                    ITurnSnapshot snap = TestSnapshots.turn(round, turn, robots,
                            bullets.toArray(new IBulletSnapshot[0]));
                    if ("SPAWN".equals(kind)) {
                        spawn = snap;
                        autopilotIndex = apIndex;
                    } else {
                        ticks.add(new Tick(snap, live));
                    }
                }
                default -> throw new IllegalStateException("Unexpected fixture record: " + f[0]);
            }
        }
        if (spawn == null) {
            throw new IllegalStateException("Fixture has no SPAWN record");
        }
        return new RecordedBattle(spawn, autopilotIndex, ticks);
    }

    private static IRobotSnapshot parseRobot(int idx, String[] f) {
        // ROBOT idx x y bodyHeading velocity energy gunHeat gunHeading radarHeading state shortName name
        double x = Double.parseDouble(f[2]);
        double y = Double.parseDouble(f[3]);
        double bodyHeading = Double.parseDouble(f[4]);
        double velocity = Double.parseDouble(f[5]);
        double energy = Double.parseDouble(f[6]);
        double gunHeat = Double.parseDouble(f[7]);
        double gunHeading = Double.parseDouble(f[8]);
        double radarHeading = Double.parseDouble(f[9]);
        RobotState state = RobotState.valueOf(f[10]);
        String shortName = f[11];
        String fullName = f[12];
        return TestSnapshots.robot(x, y, bodyHeading, velocity, energy, gunHeat,
                gunHeading, radarHeading, idx, state, shortName, fullName, null);
    }

    private static IBulletSnapshot parseBullet(String[] f) {
        // BULLET id owner victim power state x y heading
        int id = Integer.parseInt(f[1]);
        int owner = Integer.parseInt(f[2]);
        int victim = Integer.parseInt(f[3]);
        double power = Double.parseDouble(f[4]);
        BulletState state = BulletState.valueOf(f[5]);
        double x = Double.parseDouble(f[6]);
        double y = Double.parseDouble(f[7]);
        double heading = Double.parseDouble(f[8]);
        return TestSnapshots.bullet(id, owner, victim, power, state, x, y, heading);
    }
}
