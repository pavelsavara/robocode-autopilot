# Intuition Phase � Key Findings

## Dataset
- 1,003,073 tick rows, 48 predictor features
- 5 robots: Ali 0.4.9, Ascendant 1.2.27, BeepBoop 2.0, BlackBox 0.0.2, CHCl3 1.4.2, Cardigan 1.09, CassiusClay 2rho.02no, Chalk 2.6.Be, Combat 3.25.0, CunobelinDC 1.2, Cyanide 1.90, Diamond 1.8.22, Domogled 1.2, Dookious 1.573c, DrussGT 3.1.7, Engineer 0.5.4, Firebird 0.25, Firestarter 2.0f, Foilist 1.3.1, Garm 0.9u, Gilgalad 1.99.5c, GresSuffurd 0.4.13, Holden 1.13a, Horizon 1.2.2, Hydra 0.21, Knight 0.6.28, Midboss 1q.fast, Nene 1.0.5, Neuromancer 7.12, Phoenix 1.02, PowerHouse 1.7e3, Pris 0.92, PulsarMax 0.8.9, Raven 3.56j8, Roborio 1.2.4, RougeDC willow, Saguaro 1.0, ScalarR 0.005h.053-noshield, Seraphim 2.3.1, Shadow 3.83c, SilverSurfer 2.53.33fix, Toad 0.14t, Tomcat 3.68, WaveSerpent 2.11, WhiteFang 2.8.1, Wintermute 0.8, X2 0.17, XanderCat 12.9, YersiniaPestis 3.0, deBroglie rev0108

## ML Baselines
- **Fire prediction task:** Random Forest (100 trees)
  - Test accuracy: 0.9313
  - Train accuracy: 0.9316

## Top 5 Features for Fire Prediction
- energy_ratio: 0.0966
- opponent_energy: 0.0941
- our_gun_heat: 0.0881
- scan_coverage_50: 0.0825
- ticks_since_we_fired: 0.0798

## Next Steps
- Investigate top features in more depth
- Add more recordings for more training data
- Explore sequence-based models (LSTM, etc.) for time-series patterns
- Build features specifically designed for the top predictors
