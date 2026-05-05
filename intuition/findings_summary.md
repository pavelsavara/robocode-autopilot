# Intuition Phase — Key Findings

*Updated: 2026-05-05. Full details: [wiki/ml-results.md](../wiki/ml-results.md).*

## Dataset
- ~1,944 battles, 50 bots, ~3.9k ticks.csv files, ~20 GB
- 1,003,073 tick rows after stratified sampling
- 50 robots from the top LiteRumble competitors

## Honest ML Baselines (Post-Leakage-Fix)

| Task | Model | Metric | Value |
|---|---|---|---|
| Fire power | XGBoost (window) | R² | **0.931** |
| Fire power | XGBoost (window) | MAE | **0.094** |
| Fingerprint (50 classes) | LightGBM | Top-1 | **0.516** |
| Movement N=5 | GBM-window | R² | **0.735** |
| Fire timing (3-tick) | GBM-window | AUC | **0.863** |
| Round outcome | XGBoost (early-100) | Acc | **0.520** (needs work) |

## Top Features for Fire Power Prediction (Clean)
| Feature | Importance |
|---|---|
| `opponent_energy_wstd` | 38.2% |
| `opponent_energy_wmean` | 6.3% |
| `mea_for_our_bullet` | 3.4% |
| `energy_ratio` | 2.5% |
| `our_bullet_speed` | 2.2% |

## Key Insight
20-tick sliding window features (rolling mean/std) are the single most
important ML innovation. Without them: fire power R²=0.572, movement R²=0.07.

## Leakage Patterns Discovered
1. **Wave-derived reset** — features reset at fire tick leak `opponent_fired`
2. **Algebraic identity** — `bullet_speed = 20 − 3×power` leaks fire power
3. **Scan meta-leakage** — scan coverage predicts label quality, not behavior
4. **Outcome tautology** — full-round `energy_ratio_mean` IS the outcome
5. **Cross-round pooling** — destroyed temporal structure in movement analysis

See [wiki/leakage.md](../wiki/leakage.md) for the full taxonomy.
