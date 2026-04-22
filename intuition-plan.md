# Intuition Phase — Statistical Exploration Plan

## Goal

Build intuition about the battle data before doing any machine learning.
We explore the CSV files (ticks, waves, scores) to understand distributions,
correlations, and natural groupings — the things every ML practitioner does **first**.

---

## Data Inventory

| File | Rows | Columns | Granularity |
|------|------|---------|-------------|
| `ticks.csv` | ~266,890 | 28 | One row per game tick per robot perspective |
| `waves.csv` | ~17,067 | 9 | One row per fired bullet (wave) |
| `scores.csv` | ~100 | 9 | One row per round |

- **10 battles**, **5 robots** (BeepBoop 2.0, Diamond 1.8.22, DrussGT 3.1.7, Saguaro 0.1, ScalarR)
- 2 perspectives per battle (robot A's view + robot B's view)
- Heavy `NaN` in ticks on no-scan ticks (opponent data unavailable when radar misses)

---

## Notebook Structure

Four notebooks, each self-contained, run in order:

### Notebook 1 — `01_data_overview.ipynb`
**Question: "What does the data look like?"**

| Step | What we do | Why | Math level |
|------|-----------|-----|------------|
| 1a | Load all CSVs into pandas DataFrames | Get data into memory | — |
| 1b | `.info()`, `.describe()`, `.shape` | How many rows, columns, types, missing values | Counting |
| 1c | NaN heatmap | Show which columns have missing data and when | Percentage = count/total × 100 |
| 1d | Histograms of every numeric column | See the **distribution** — is it bell-shaped, skewed, bimodal? | Histogram = counting values in bins |
| 1e | Box plots per robot | Compare how each robot's features differ | Median, quartiles (the "middle 50%") |
| 1f | Time-series plots (features over ticks) | See how a battle unfolds over time | x = tick, y = value |

**Key concepts explained in-notebook:**
- **Mean** = sum of values / count. The "average."
- **Median** = the middle value when sorted. Not fooled by extreme values.
- **Standard deviation (std)** = how spread out values are from the mean. Small std = values clump together. Large std = values are scattered.
- **Percentiles (25%, 75%)** = the value below which 25% (or 75%) of data falls. The range between 25th and 75th percentile is the **interquartile range (IQR)** — the "middle half."
- **Histogram** = divide the range into bins (buckets), count how many values fall in each bin, draw bars.
- **NaN** = "Not a Number" — missing data. Common here because the robot can only see the opponent when the radar scans them.

---

### Notebook 2 — `02_correlations.ipynb`
**Question: "Which features move together?"**

| Step | What we do | Why | Math level |
|------|-----------|-----|------------|
| 2a | Correlation matrix (heatmap) | Find pairs of features that increase/decrease together | Correlation = number from −1 to +1 |
| 2b | Scatter plots of top correlated pairs | Visually confirm the relationship | Dots on a grid |
| 2c | Feature vs. `opponent_fired` (point-biserial) | Which features best predict firing? | Same formula, one variable is 0/1 |
| 2d | Feature vs. `energy_ratio` | What influences who's winning? | Scatter + trend line |
| 2e | Per-robot correlation differences | Do top bots have different feature relationships? | Compare correlation matrices |

**Key concepts explained in-notebook:**
- **Correlation (r)** = a number from −1 to +1.
  - r = +1: perfect positive relationship (when A goes up, B goes up)
  - r = −1: perfect negative relationship (when A goes up, B goes down)
  - r = 0: no linear relationship
  - Formula: r = Σ((x−x̄)(y−ȳ)) / √(Σ(x−x̄)² × Σ(y−ȳ)²)
  - In plain English: multiply how far each x is from its average by how far each y is from its average, then normalize.
- **Correlation ≠ causation** — two things can move together by coincidence.
- **Scatter plot** = each data point is a dot, x-axis = one feature, y-axis = another. If dots form a line → correlated.

---

### Notebook 3 — `03_clustering.ipynb`
**Question: "Are there natural groups in the data?"**

| Step | What we do | Why | Math level |
|------|-----------|-----|------------|
| 3a | Standardize features (z-score) | Put all features on the same scale | z = (value − mean) / std |
| 3b | PCA (2D projection) | Reduce 28 columns to 2 for visualization | Finds the "most important directions" |
| 3c | Color PCA by robot name | Do different robots occupy different regions? | Visual inspection |
| 3d | K-Means clustering (k=2..6) | Let the algorithm find groups | Assign each point to nearest center |
| 3e | Elbow plot | Pick the best number of clusters | Plot error vs. k, look for the "elbow" |
| 3f | Cluster profiles | Describe what makes each cluster different | Compare cluster means |
| 3g | Waves clustering | Group bullet patterns (power, distance, speed) | Same K-Means on wave data |

**Key concepts explained in-notebook:**
- **Standardization (z-score)** = subtract the mean, divide by std. Now every feature has mean=0 and std=1. This prevents features with large numbers (like distance ~500) from dominating features with small numbers (like velocity ~8).
- **PCA (Principal Component Analysis)** = find the direction in which data varies the most, project everything onto that direction. Then find the next most-varying direction (perpendicular to the first). Result: a 2D picture of 28-dimensional data.
- **K-Means** = pick k random centers. Assign each point to its nearest center. Move centers to the average of their assigned points. Repeat until stable.
- **Elbow method** = run K-Means for k=2,3,4,5,6. Plot the total distance of points to their centers (called "inertia"). The "elbow" — where adding more clusters stops helping much — is the best k.

---

### Notebook 4 — `04_simple_ml.ipynb`
**Question: "Can we predict anything useful?"**

| Step | What we do | Why | Math level |
|------|-----------|-----|------------|
| 4a | Task 1: Predict `opponent_fired` (classification) | Can we detect enemy fire from other features? | Yes/No prediction |
| 4b | Train/test split (80/20) | Don't test on training data (that's cheating) | Random 80% for learning, 20% for checking |
| 4c | Decision Tree | Simple, interpretable model | Series of if/else rules |
| 4d | Random Forest | Many trees voting together → more accurate | Wisdom of crowds |
| 4e | Feature importance bar chart | Which features matter most for prediction? | How much each feature improves accuracy |
| 4f | Confusion matrix | How many correct/wrong predictions? | 2×2 table: predicted vs. actual |
| 4g | Task 2: Predict `net_damage` from scores | Which round-level stats predict winning? | Linear regression |
| 4h | Learning curves | Does more data help? | Plot accuracy vs. training set size |

**Key concepts explained in-notebook:**
- **Classification** = predict a category (fired: yes/no). **Regression** = predict a number (damage amount).
- **Train/test split** = hide 20% of data. Train the model on 80%. Test on the hidden 20%. If accuracy is good on the test set, the model actually learned something (not just memorized).
- **Decision Tree** = a flowchart. "Is distance > 300? → Yes → Is energy < 50? → Yes → predict fired." Easy to understand, but can overfit (memorize noise).
- **Random Forest** = grow 100 different decision trees, each using a random subset of features and data. Take a vote. More robust than a single tree.
- **Feature importance** = in a Random Forest, features that appear near the top of many trees (and produce big improvements) are "important." This tells us which measurements actually matter.
- **Confusion matrix** = a 2×2 table showing: True Positives (correctly predicted fired), True Negatives (correctly predicted not fired), False Positives (predicted fired but wasn't), False Negatives (missed a fire).
- **Accuracy** = (correct predictions) / (total predictions). But beware: if opponent fires only 5% of ticks, always predicting "not fired" gives 95% accuracy! That's why we also look at **precision** and **recall**.
- **Overfitting** = the model memorizes training data instead of learning general patterns. Signs: great training accuracy, poor test accuracy.

---

## Python Setup

```
D:\robocode-autopilot\intuition\
├── .venv/                    # Python virtual environment
├── requirements.txt          # pandas, scikit-learn, matplotlib, seaborn, jupyter
├── 01_data_overview.ipynb
├── 02_correlations.ipynb
├── 03_clustering.ipynb
└── 04_simple_ml.ipynb
```

### requirements.txt
```
pandas>=2.0
numpy>=1.24
matplotlib>=3.7
seaborn>=0.12
scikit-learn>=1.3
jupyter
ipykernel
```

---

## Implementation Order

1. Create `intuition/` folder, venv, install dependencies
2. Notebook 1 — data overview (load, describe, visualize distributions)
3. Notebook 2 — correlations (heatmap, scatter plots, per-robot differences)
4. Notebook 3 — clustering (PCA, K-Means, cluster profiles)
5. Notebook 4 — simple ML (fire prediction, feature importance, confusion matrix)

Each notebook is self-contained: loads data from `../output/csv/`, produces inline plots, explains every concept.

---

## What We Hope to Learn

- Which features have the most variance (= carry the most information)?
- Are there redundant features (highly correlated → one can replace the other)?
- Do different robots create distinct "fingerprints" in feature space?
- Can we detect opponent fire from observable features? How accurately?
- Which features are most predictive? (This guides future feature engineering.)
- Are there natural "game states" (clusters) — e.g., "close combat" vs. "long range circling"?
