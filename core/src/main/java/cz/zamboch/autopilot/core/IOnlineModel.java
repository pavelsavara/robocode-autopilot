package cz.zamboch.autopilot.core;

/**
 * Interface for online gun-targeting models. Each model predicts the best GF
 * to aim at and is updated when a wave resolves.
 */
public interface IOnlineModel {

    /**
     * Predict the best GF value for the given wave situation.
     *
     * @param wb   the whiteboard
     * @param slot the our-wave slot index containing fire-time features
     * @return predicted GF in [-1, 1]
     */
    double predict(Whiteboard wb, int slot);

    /**
     * Update the model with the resolved wave's actual break GF.
     *
     * @param wb      the whiteboard
     * @param slot    the our-wave slot index (fully resolved)
     * @param breakGf the actual GF at wave break
     */
    void update(Whiteboard wb, int slot, double breakGf);

    /** Model name for logging/debugging. */
    String getName();
}
