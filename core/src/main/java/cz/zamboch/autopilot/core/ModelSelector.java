package cz.zamboch.autopilot.core;

/**
 * Selects the best online model based on recent prediction regret.
 * <p>
 * Tracks a rolling window of absolute prediction errors for each model.
 * When asked to predict, returns the prediction from the model with the
 * lowest average recent error. All models are updated on every wave break.
 */
public final class ModelSelector {

    /** Rolling window size for regret tracking. */
    private static final int WINDOW_SIZE = 50;

    private final IOnlineModel[] models;
    private final double[][] errors; // [modelIndex][ringIndex]
    private final int[] errorCount;  // number of entries filled (up to WINDOW_SIZE)
    private final int[] errorHead;   // next write position in ring

    public ModelSelector(IOnlineModel... models) {
        if (models.length == 0) {
            throw new IllegalArgumentException("At least one model required");
        }
        this.models = models;
        this.errors = new double[models.length][WINDOW_SIZE];
        this.errorCount = new int[models.length];
        this.errorHead = new int[models.length];
    }

    /**
     * Predict the best GF using the model with lowest recent regret.
     * Falls back to the first model if no data yet.
     */
    public double predict(Whiteboard wb, int slot) {
        int best = bestModelIndex();
        return models[best].predict(wb, slot);
    }

    /**
     * Predict using each model for the current tick situation (before wave
     * is allocated). Used by OurWaveFeatures for gun aiming.
     *
     * @param distance current distance to opponent
     * @param latVel   current lateral velocity of opponent
     * @return predicted GF from the best model
     */
    public double predictForAim(double distance, double latVel) {
        int best = bestModelIndex();
        // For now, only VCS-style models support this simple predict.
        // Future models that need full wave context will override differently.
        IOnlineModel model = models[best];
        if (model instanceof VcsStore) {
            VcsStore vcs = (VcsStore) model;
            int distSeg = GuessFactor.distanceSegment(distance);
            int latVelSeg = GuessFactor.lateralVelocitySegment(
                    Double.isNaN(latVel) ? 0 : latVel);
            int bestBin = vcs.getBestBin(distSeg, latVelSeg);
            return GuessFactor.binIndexToGf(bestBin, GuessFactor.NUM_BINS);
        }
        // Fallback: head-on
        return 0.0;
    }

    /**
     * Update all models with a resolved wave's actual break GF.
     * Also records prediction error for regret tracking.
     */
    public void update(Whiteboard wb, int slot, double breakGf) {
        for (int i = 0; i < models.length; i++) {
            // Record prediction error before updating
            double predicted = models[i].predict(wb, slot);
            double error = Math.abs(predicted - breakGf);
            recordError(i, error);

            // Update the model
            models[i].update(wb, slot, breakGf);
        }
    }

    /** Get the index of the model with lowest average recent error. */
    private int bestModelIndex() {
        if (models.length == 1) {
            return 0;
        }
        int best = 0;
        double bestAvg = averageError(0);
        for (int i = 1; i < models.length; i++) {
            double avg = averageError(i);
            if (avg < bestAvg) {
                bestAvg = avg;
                best = i;
            }
        }
        return best;
    }

    private double averageError(int modelIndex) {
        int count = errorCount[modelIndex];
        if (count == 0) {
            return Double.MAX_VALUE; // no data → lowest priority
        }
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += errors[modelIndex][i];
        }
        return sum / count;
    }

    private void recordError(int modelIndex, double error) {
        int head = errorHead[modelIndex];
        errors[modelIndex][head] = error;
        errorHead[modelIndex] = (head + 1) % WINDOW_SIZE;
        if (errorCount[modelIndex] < WINDOW_SIZE) {
            errorCount[modelIndex]++;
        }
    }

    /** Get the number of registered models. */
    public int getModelCount() {
        return models.length;
    }

    /** Get a model by index. */
    public IOnlineModel getModel(int index) {
        return models[index];
    }

    /** Get the name of the currently best model. */
    public String getBestModelName() {
        return models[bestModelIndex()].getName();
    }

    /** Get the average error for a model (for diagnostics). */
    public double getAverageError(int modelIndex) {
        return averageError(modelIndex);
    }

    /**
     * Record a pipeline-side update for regret tracking. Used when the pipeline
     * resolves waves via TrackedWave objects (not the ring buffer).
     * Records each model's prediction error without calling update() (the
     * pipeline handles VCS increment directly).
     */
    public void recordPipelineUpdate(int distSeg, int latVelSeg, double breakGf) {
        for (int i = 0; i < models.length; i++) {
            IOnlineModel model = models[i];
            double predicted;
            if (model instanceof VcsStore) {
                VcsStore vcs = (VcsStore) model;
                int bestBin = vcs.getBestBin(distSeg, latVelSeg);
                predicted = GuessFactor.binIndexToGf(bestBin, GuessFactor.NUM_BINS);
            } else {
                predicted = 0.0;
            }
            double error = Math.abs(predicted - breakGf);
            recordError(i, error);
        }
    }
}
