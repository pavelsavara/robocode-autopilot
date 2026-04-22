# Intuition Phase — Key Findings

## Dataset
- 266,890 tick rows, 22 predictor features
- 5 robots: BeepBoop 2.0, Diamond 1.8.22, DrussGT 3.1.7, Saguaro 0.1, ScalarR 0.005h.053-noshield

## ML Baselines
- **Fire prediction task:** Random Forest (100 trees)
  - Test accuracy: 0.9888
  - Train accuracy: 0.9915

## Top 5 Features for Fire Prediction
- opponent_inferred_gun_heat: 0.8845
- opponent_energy: 0.0326
- our_gun_heat: 0.0203
- distance_norm: 0.0100
- energy_ratio: 0.0089

## Next Steps
- Investigate top features in more depth
- Add more recordings for more training data
- Explore sequence-based models (LSTM, etc.) for time-series patterns
- Build features specifically designed for the top predictors
