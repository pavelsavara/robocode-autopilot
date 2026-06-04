package cz.zamboch.autopilot.pipeline;

import cz.zamboch.autopilot.core.Feature;
import cz.zamboch.autopilot.core.GuessFactor;
import cz.zamboch.autopilot.core.OurWaveColumn;
import cz.zamboch.autopilot.core.RoboMath;
import cz.zamboch.autopilot.core.TheirWaveColumn;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.TheirWaveTracker;
import net.sf.robocode.dejavu.core.CommandReconstructor;
import net.sf.robocode.dejavu.model.BattleEndEvent;
import net.sf.robocode.dejavu.model.TickCommands;
import robocode.control.snapshot.BulletState;
import robocode.control.snapshot.IBulletSnapshot;
import robocode.control.snapshot.IRobotSnapshot;
import robocode.control.events.BattleAdaptor;
import robocode.control.events.BattleCompletedEvent;
import robocode.control.events.BattleStartedEvent;
import robocode.control.events.RoundStartedEvent;
import robocode.control.events.TurnEndedEvent;
import robocode.control.snapshot.ITurnSnapshot;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Writes robot-side observer Whiteboard CSVs for both perspectives of a 1v1 battle. */
public final class RobotSideCsvObserver extends BattleAdaptor implements Closeable {
    private final ObserverContext[] observers;
    private final CsvWriter[] csvWriters;
        private final CommandReconstructor[] commandReconstructors = {
            new CommandReconstructor(0), new CommandReconstructor(1)
        };
        private final List<DejavuWave>[] dejavuWaves = newWaveLists();
            private final List<IncomingBulletId>[] pendingIncomingBulletIds = newIncomingBulletLists();
    private final String battleId;
    private final Set<Long>[] writtenOurWaveIds = newIdSets();
    private final Set<Integer>[] writtenTheirWaveSlots = newSlotSets();
    private final int[] realOurBreaks = { 0, 0 };
    private final int[] realOurHits = { 0, 0 };
    private int currentRound = -1;
    private ITurnSnapshot prevCommandSnapshot;
    private ITurnSnapshot prevPrevCommandSnapshot;
    private BattleEndEvent battleEndEvent;

    public RobotSideCsvObserver(double bfWidth, double bfHeight, double gunCoolingRate,
            String battleId, File pairOutputDir, String robotA, String robotB) throws IOException {
        this.observers = ObserverContext.createPair(bfWidth, bfHeight, gunCoolingRate);
        this.battleId = battleId;
        this.csvWriters = new CsvWriter[] {
                new CsvWriter(new File(pairOutputDir, "Perspective0-" + sanitize(robotA))),
                new CsvWriter(new File(pairOutputDir, "Perspective1-" + sanitize(robotB)))
        };
        csvWriters[0].writeHeaders(battleId);
        csvWriters[1].writeHeaders(battleId);
    }

    public void setObserverDataDir(File dataDir) {
        for (ObserverContext ctx : observers) {
            ctx.setDataDir(dataDir);
        }
    }

    public BattleEndEvent battleEndEvent() {
        return battleEndEvent;
    }

    @Override
    public void onBattleStarted(BattleStartedEvent event) {
        int rounds = event.getBattleRules() != null ? event.getBattleRules().getNumRounds() : 1;
        for (ObserverContext ctx : observers) {
            ctx.setNumRounds(rounds);
        }
    }

    @Override
    public void onRoundStarted(RoundStartedEvent event) {
        int round = event.getRound();
        if (round != currentRound) {
            resetRound(round);
            currentRound = round;
        }
        ITurnSnapshot start = event.getStartSnapshot();
        if (start != null) {
            for (ObserverContext ctx : observers) {
                ctx.seedRoundStart(start);
            }
            prevPrevCommandSnapshot = null;
            prevCommandSnapshot = start;
        }
    }

    @Override
    public void onTurnEnded(TurnEndedEvent event) {
        processTurn(event.getTurnSnapshot());
    }

    @Override
    public void onBattleCompleted(BattleCompletedEvent event) {
        battleEndEvent = BattleEndEvent.from(event);
        flushOpenWaveRows();
        writeScoreRows(battleEndEvent);
    }

    public void processTurn(ITurnSnapshot curr) {
        int round = curr.getRound();
        if (round != currentRound) {
            resetRound(round);
            currentRound = round;
        }

        processDejavuCommands(curr);

        for (ObserverContext ctx : observers) {
            ctx.processTickEvents(curr);
        }
        for (ObserverContext ctx : observers) {
            ctx.doTurn();
        }
        assignPendingIncomingBulletIds(curr.getTurn());
        for (ObserverContext ctx : observers) {
            writeRows(ctx);
        }

        prevPrevCommandSnapshot = prevCommandSnapshot;
        prevCommandSnapshot = curr;
    }

    private void processDejavuCommands(ITurnSnapshot curr) {
        if (prevCommandSnapshot == null) {
            prevCommandSnapshot = curr;
            return;
        }
        for (int pi = 0; pi < 2; pi++) {
            TickCommands commands = commandReconstructors[pi].reconstruct(prevCommandSnapshot, curr);
            if (commands.isFired()) {
                IBulletSnapshot bullet = freshBullet(prevCommandSnapshot, curr, pi);
                if (bullet != null) {
                    dejavuWaves[pi].add(DejavuWave.create(pi, commands, bullet,
                            prevCommandSnapshot, prevPrevCommandSnapshot));
                    pendingIncomingBulletIds[1 - pi].add(new IncomingBulletId(bullet));
                }
            }
            updateDejavuBulletStates(pi, curr.getBullets());
            writeResolvedDejavuWaves(pi, curr);
        }
    }

    private void writeRows(ObserverContext ctx) {
        if (ctx.isDead()) {
            return;
        }
        int pi = ctx.perspectiveIndex();
        Whiteboard wb = ctx.wb();
        try {
            csvWriters[pi].writeTickRow(wb, battleId, currentRound);
            if (wb.hasCurrentScan()) {
                csvWriters[pi].writeScanRow(wb, battleId, currentRound);
            }
            writeResolvedRealOurWaves(pi, wb);
            writeResolvedTheirWaves(pi, wb);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write robot-side CSV for perspective " + pi, e);
        }
    }

    private void writeResolvedRealOurWaves(int pi, Whiteboard wb) throws IOException {
        double tick = wb.getFeature(Feature.TICK);
        if (Double.isNaN(tick)) {
            return;
        }
        for (int slot = 0; slot < Whiteboard.OUR_WAVE_CAPACITY; slot++) {
            if (wb.getOurWaveState(slot) != Whiteboard.WAVE_RESOLVED) {
                continue;
            }
            double breakTick = wb.getOurWave(slot, OurWaveColumn.BREAK_TICK);
            if (Double.isNaN(breakTick) || Math.abs(breakTick - tick) > 1e-4) {
                continue;
            }
            long waveId = (long) wb.getOurWave(slot, OurWaveColumn.WAVE_ID);
            if (!writtenOurWaveIds[pi].add(waveId)) {
                continue;
            }
            csvWriters[pi].writeOurWaveRow(wb, slot, battleId, currentRound);
            if (wb.getOurWave(slot, OurWaveColumn.IS_REAL) == 1.0) {
                recordOurWaveStats(pi, slot, wb);
            }
        }
    }

    private void writeResolvedDejavuWaves(int pi, ITurnSnapshot curr) {
        IRobotSnapshot opponent = curr.getRobots()[1 - pi];
        long tick = curr.getTurn();
        for (DejavuWave wave : dejavuWaves[pi]) {
            if (wave.resolved) {
                continue;
            }
            if (!wave.hasReached(opponent, tick)) {
                continue;
            }
            wave.resolve(opponent, curr.getBullets(), tick);
            try {
                csvWriters[pi].writeDejavuWaveRow(wave.values, battleId, currentRound, tick);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write DeJaVu wave CSV for perspective " + pi, e);
            }
        }
    }

    private void updateDejavuBulletStates(int pi, IBulletSnapshot[] bullets) {
        for (DejavuWave wave : dejavuWaves[pi]) {
            if (!wave.resolved) {
                wave.observeBullets(bullets);
            }
        }
    }

    private void assignPendingIncomingBulletIds(long tick) {
        for (int pi = 0; pi < 2; pi++) {
            Whiteboard wb = observers[pi].wb();
            for (IncomingBulletId bullet : pendingIncomingBulletIds[pi]) {
                TheirWaveTracker.assignBulletId(wb, bullet.bulletId, bullet.power,
                        bullet.x, bullet.y, bullet.heading, tick);
            }
            pendingIncomingBulletIds[pi].clear();
        }
    }

    private void flushOpenWaveRows() {
        long tick = lastKnownTick();
        for (ObserverContext ctx : observers) {
            int pi = ctx.perspectiveIndex();
            Whiteboard wb = ctx.wb();
            try {
                flushOpenAutopilotWaves(pi, wb);
                flushOpenTheirWaves(pi, wb);
                flushOpenDejavuWaves(pi, tick);
            } catch (IOException e) {
                throw new RuntimeException("Failed to flush open wave CSV rows for perspective " + pi, e);
            }
        }
    }

    private void flushOpenAutopilotWaves(int pi, Whiteboard wb) throws IOException {
        for (int slot = 0; slot < Whiteboard.OUR_WAVE_CAPACITY; slot++) {
            if (wb.getOurWaveState(slot) != Whiteboard.WAVE_ACTIVE) {
                continue;
            }
            long waveId = (long) wb.getOurWave(slot, OurWaveColumn.WAVE_ID);
            if (!writtenOurWaveIds[pi].add(waveId)) {
                continue;
            }
            csvWriters[pi].writeOurWaveRow(wb, slot, battleId, currentRound);
        }
    }

    private void flushOpenTheirWaves(int pi, Whiteboard wb) throws IOException {
        for (int slot = 0; slot < Whiteboard.THEIR_WAVE_CAPACITY; slot++) {
            if (wb.getTheirWaveState(slot) != Whiteboard.WAVE_ACTIVE) {
                continue;
            }
            if (!writtenTheirWaveSlots[pi].add(slot)) {
                continue;
            }
            csvWriters[pi].writeTheirWaveRow(wb, slot, battleId, currentRound);
        }
    }

    private void flushOpenDejavuWaves(int pi, long tick) throws IOException {
        for (DejavuWave wave : dejavuWaves[pi]) {
            if (wave.resolved) {
                continue;
            }
            wave.flushUnresolved();
            csvWriters[pi].writeDejavuWaveRow(wave.values, battleId, currentRound, tick);
        }
    }

    private long lastKnownTick() {
        if (prevCommandSnapshot != null) {
            return prevCommandSnapshot.getTurn();
        }
        for (ObserverContext ctx : observers) {
            double tick = ctx.wb().getFeature(Feature.TICK);
            if (!Double.isNaN(tick)) {
                return (long) tick;
            }
        }
        return 0;
    }

    private void writeResolvedTheirWaves(int pi, Whiteboard wb) throws IOException {
        double tick = wb.getFeature(Feature.TICK);
        if (Double.isNaN(tick)) {
            return;
        }
        for (int slot = 0; slot < Whiteboard.THEIR_WAVE_CAPACITY; slot++) {
            if (wb.getTheirWaveState(slot) != Whiteboard.WAVE_RESOLVED) {
                continue;
            }
            double breakTick = wb.getTheirWave(slot, TheirWaveColumn.BREAK_TICK);
            if (Double.isNaN(breakTick) || Math.abs(breakTick - tick) > 1e-4) {
                continue;
            }
            if (!writtenTheirWaveSlots[pi].add(slot)) {
                continue;
            }
            csvWriters[pi].writeTheirWaveRow(wb, slot, battleId, currentRound);
        }
    }

    private void recordOurWaveStats(int pi, int slot, Whiteboard wb) {
        double hit = wb.getOurWave(slot, OurWaveColumn.BREAK_HIT);
        if (!Double.isNaN(hit)) {
            realOurBreaks[pi]++;
        }
        if (hit >= 1.0) {
            realOurHits[pi]++;
        }
    }

    private void writeScoreRows(BattleEndEvent event) {
        for (ObserverContext ctx : observers) {
            int pi = ctx.perspectiveIndex();
            Whiteboard wb = ctx.wb();
            wb.setFeature(Feature.ROUND_RESULT, event.resultForPerspective(pi));
            wb.setFeature(Feature.ROUND_HIT_RATE,
                    realOurBreaks[pi] > 0 ? (double) realOurHits[pi] / realOurBreaks[pi] : 0);
            try {
                csvWriters[pi].writeScoreRow(wb, battleId, currentRound >= 0 ? currentRound : 0);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write score row for perspective " + pi, e);
            }
        }
    }

    private void resetRound(int round) {
        for (ObserverContext ctx : observers) {
            ctx.resetRound(round);
        }
        for (int i = 0; i < 2; i++) {
            writtenOurWaveIds[i].clear();
            writtenTheirWaveSlots[i].clear();
            dejavuWaves[i].clear();
            pendingIncomingBulletIds[i].clear();
            realOurBreaks[i] = 0;
            realOurHits[i] = 0;
        }
        prevCommandSnapshot = null;
        prevPrevCommandSnapshot = null;
    }

    @SuppressWarnings("unchecked")
    private static Set<Long>[] newIdSets() {
        return new Set[] { new HashSet<Long>(), new HashSet<Long>() };
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer>[] newSlotSets() {
        return new Set[] { new HashSet<Integer>(), new HashSet<Integer>() };
    }

    @SuppressWarnings("unchecked")
    private static List<DejavuWave>[] newWaveLists() {
        return new List[] { new ArrayList<DejavuWave>(), new ArrayList<DejavuWave>() };
    }

    @SuppressWarnings("unchecked")
    private static List<IncomingBulletId>[] newIncomingBulletLists() {
        return new List[] { new ArrayList<IncomingBulletId>(), new ArrayList<IncomingBulletId>() };
    }

    private static IBulletSnapshot freshBullet(ITurnSnapshot prev, ITurnSnapshot curr, int ownerIndex) {
        Set<Integer> priorIds = new HashSet<Integer>();
        for (IBulletSnapshot bullet : prev.getBullets()) {
            if (bullet.getOwnerIndex() == ownerIndex) {
                priorIds.add(bullet.getBulletId());
            }
        }
        for (IBulletSnapshot bullet : curr.getBullets()) {
            if (bullet.getOwnerIndex() == ownerIndex && !priorIds.contains(bullet.getBulletId())) {
                return bullet;
            }
        }
        return null;
    }

    private static final class IncomingBulletId {
        private final int bulletId;
        private final double power;
        private final double x;
        private final double y;
        private final double heading;

        private IncomingBulletId(IBulletSnapshot bullet) {
            this.bulletId = bullet.getBulletId();
            this.power = bullet.getPower();
            this.x = bullet.getX();
            this.y = bullet.getY();
            this.heading = bullet.getHeading();
        }
    }

    private static final class DejavuWave {
        private final int ownerIndex;
        private final int bulletId;
        private final double fireX;
        private final double fireY;
        private final double fireTick;
        private final double bulletSpeed;
        private final double fireBearing;
        private final double[] values = new double[OurWaveColumn.COUNT];
        private boolean hitVictim;
        private boolean resolved;

        private DejavuWave(int ownerIndex, int bulletId, double fireX, double fireY,
                double fireTick, double bulletSpeed, double fireBearing) {
            this.ownerIndex = ownerIndex;
            this.bulletId = bulletId;
            this.fireX = fireX;
            this.fireY = fireY;
            this.fireTick = fireTick;
            this.bulletSpeed = bulletSpeed;
            this.fireBearing = fireBearing;
            for (int i = 0; i < values.length; i++) {
                values[i] = Double.NaN;
            }
        }

        static DejavuWave create(int ownerIndex, TickCommands commands, IBulletSnapshot bullet,
                ITurnSnapshot prev, ITurnSnapshot prevPrev) {
            IRobotSnapshot[] robots = prev.getRobots();
            IRobotSnapshot self = robots[ownerIndex];
            IRobotSnapshot opponent = robots[1 - ownerIndex];
            double power = commands.getFirePower();
            double bulletSpeed = GuessFactor.bulletSpeed(power);
            double fireTick = commands.getTurn() - 1.0;
            double fireX = self.getX();
            double fireY = self.getY();
            double dx = opponent.getX() - fireX;
            double dy = opponent.getY() - fireY;
            double fireBearing = Math.atan2(dx, dy);
            double distance = Math.hypot(dx, dy);
            double latVel = opponent.getVelocity() * Math.sin(opponent.getBodyHeading() - fireBearing);
            double advVel = opponent.getVelocity() * Math.cos(opponent.getBodyHeading() - fireBearing);

            DejavuWave wave = new DejavuWave(ownerIndex, bullet.getBulletId(), fireX, fireY,
                    fireTick, bulletSpeed, fireBearing);
            wave.values[OurWaveColumn.FIRE_DISTANCE.ordinal()] = distance;
            wave.values[OurWaveColumn.FIRE_LATERAL_VELOCITY.ordinal()] = latVel;
            wave.values[OurWaveColumn.FIRE_ADVANCING_VELOCITY.ordinal()] = advVel;
            wave.values[OurWaveColumn.FIRE_BULLET_SPEED.ordinal()] = bulletSpeed;
            wave.values[OurWaveColumn.FIRE_MEA.ordinal()] = GuessFactor.maxEscapeAngle(bulletSpeed);
            wave.values[OurWaveColumn.FIRE_DIRECTION.ordinal()] = GuessFactor.direction(latVel);
            wave.values[OurWaveColumn.FIRE_BEARING_ABSOLUTE.ordinal()] = fireBearing;
            wave.values[OurWaveColumn.FIRE_X.ordinal()] = fireX;
            wave.values[OurWaveColumn.FIRE_Y.ordinal()] = fireY;
            wave.values[OurWaveColumn.FIRE_OPPONENT_X.ordinal()] = opponent.getX();
            wave.values[OurWaveColumn.FIRE_OPPONENT_Y.ordinal()] = opponent.getY();
            wave.values[OurWaveColumn.FIRE_POWER.ordinal()] = power;
            wave.values[OurWaveColumn.FIRE_TICK.ordinal()] = fireTick;
            wave.values[OurWaveColumn.FIRE_BULLET_ID.ordinal()] = bullet.getBulletId();
            wave.values[OurWaveColumn.IS_REAL.ordinal()] = 1.0;
            wave.observeBullet(bullet);
            if (prevPrev != null) {
                IRobotSnapshot aimSelf = prevPrev.getRobots()[ownerIndex];
                IRobotSnapshot aimOpponent = prevPrev.getRobots()[1 - ownerIndex];
                double aimDx = aimOpponent.getX() - aimSelf.getX();
                double aimDy = aimOpponent.getY() - aimSelf.getY();
                wave.values[OurWaveColumn.AIM_X.ordinal()] = aimSelf.getX();
                wave.values[OurWaveColumn.AIM_Y.ordinal()] = aimSelf.getY();
                wave.values[OurWaveColumn.AIM_OPPONENT_X.ordinal()] = aimOpponent.getX();
                wave.values[OurWaveColumn.AIM_OPPONENT_Y.ordinal()] = aimOpponent.getY();
                wave.values[OurWaveColumn.AIM_DISTANCE.ordinal()] = Math.hypot(aimDx, aimDy);
                wave.values[OurWaveColumn.AIM_BEARING_ABSOLUTE.ordinal()] = Math.atan2(aimDx, aimDy);
                double aimBearing = wave.values[OurWaveColumn.AIM_BEARING_ABSOLUTE.ordinal()];
                double aimOffset = RoboMath.normalRelativeAngle(bullet.getHeading() - aimBearing);
                wave.values[OurWaveColumn.AIM_GF.ordinal()] = GuessFactor.guessFactor(
                        aimOffset, wave.values[OurWaveColumn.FIRE_MEA.ordinal()],
                        (int) wave.values[OurWaveColumn.FIRE_DIRECTION.ordinal()]);
            }
            return wave;
        }

        boolean hasReached(IRobotSnapshot opponent, long tick) {
            double travelled = (tick - fireTick) * bulletSpeed;
            double dx = opponent.getX() - fireX;
            double dy = opponent.getY() - fireY;
            return travelled >= Math.hypot(dx, dy);
        }

        void observeBullets(IBulletSnapshot[] bullets) {
            if (bullets == null || hitVictim) {
                return;
            }
            for (IBulletSnapshot bullet : bullets) {
                observeBullet(bullet);
                if (hitVictim) {
                    return;
                }
            }
        }

        void observeBullet(IBulletSnapshot bullet) {
            if (bullet == null
                    || bullet.getOwnerIndex() != ownerIndex
                    || bullet.getBulletId() != bulletId) {
                return;
            }
            BulletState state = bullet.getState();
            int victimIndex = bullet.getVictimIndex();
            if (state == BulletState.HIT_VICTIM
                    || (victimIndex == 1 - ownerIndex
                            && state != BulletState.HIT_BULLET
                            && state != BulletState.HIT_WALL)) {
                hitVictim = true;
            }
        }

        void resolve(IRobotSnapshot opponent, IBulletSnapshot[] bullets, long tick) {
            observeBullets(bullets);
            double dx = opponent.getX() - fireX;
            double dy = opponent.getY() - fireY;
            double actualBearing = Math.atan2(dx, dy);
            double bearingOffset = RoboMath.normalRelativeAngle(actualBearing - fireBearing);
            double mea = GuessFactor.maxEscapeAngle(bulletSpeed);
            values[OurWaveColumn.BREAK_TICK.ordinal()] = tick;
            values[OurWaveColumn.BREAK_GF.ordinal()] = mea != 0 ? Math.max(-1.0, Math.min(1.0, bearingOffset / mea)) : 0;
            values[OurWaveColumn.BREAK_BEARING_OFFSET.ordinal()] = bearingOffset;
            values[OurWaveColumn.BREAK_OPPONENT_X.ordinal()] = opponent.getX();
            values[OurWaveColumn.BREAK_OPPONENT_Y.ordinal()] = opponent.getY();
            values[OurWaveColumn.BREAK_HIT.ordinal()] = hitVictim ? 1.0 : 0.0;
            resolved = true;
        }

        void flushUnresolved() {
            if (hitVictim) {
                values[OurWaveColumn.BREAK_HIT.ordinal()] = 1.0;
            }
            resolved = true;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            csvWriters[0].close();
        } finally {
            csvWriters[1].close();
        }
    }

    static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}