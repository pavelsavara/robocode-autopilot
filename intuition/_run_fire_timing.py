"""Run just the fire timing task from train_sequence.py."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import os
os.environ['PYTHONIOENCODING'] = 'utf-8'

from train_sequence import train_fire_timing, MODEL_ROOT
from _loader import build_robot_index, load_stratified
import json

csv_root = Path(__file__).parent / '..' / 'output' / 'csv'
selection = build_robot_index(csv_root=csv_root, max_robots=50, battles_per_robot=2, seed=42)
print("\nLoading ticks.csv...")
ticks = load_stratified('ticks.csv', selection, csv_root=csv_root, row_frac=1.0)

fire_metrics = train_fire_timing(ticks)
if fire_metrics:
    MODEL_ROOT.mkdir(parents=True, exist_ok=True)
    print(f"\nFire timing: AUC={fire_metrics.get('lstm_cv_auc_mean', 'N/A')}, "
          f"F1={fire_metrics.get('lstm_cv_f1_mean', 'N/A')}")
else:
    print("Fire timing task failed or had no valid windows.")
