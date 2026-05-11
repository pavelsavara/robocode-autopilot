#!/usr/bin/env python3
"""
Automated sanity check script for robocode-autopilot.
Reads pipeline CSV output and runs 6 mandatory checks + 3 bonus ML checks.

Usage:
    python sanity_check.py [--data-dir PATH]

Data dir defaults to output/local/ relative to the repo root.
Expects:
    <data-dir>/csv/<battle_id>/<robot_name>/internal.csv
    <data-dir>/csv/<battle_id>/<robot_name>/ticks.csv
    <data-dir>/csv/<battle_id>/<robot_name>/scores.csv
    <data-dir>/csv/<battle_id>/<robot_name>/waves.csv
    <data-dir>/csv/<battle_id>/<robot_name>/debug.log
    <data-dir>/results/summary.json
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path

import numpy as np
import pandas as pd


# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------

OUR_BOT_PREFIX = "Autopilot"


def find_battle_dirs(csv_root: Path, battle_id_filter=None):
    """Yield (battle_id, bot_dir) for every Autopilot perspective directory.
    
    If battle_id_filter is provided (a set), only yield matching battle IDs.
    """
    if not csv_root.is_dir():
        return
    for battle_dir in sorted(csv_root.iterdir()):
        if not battle_dir.is_dir():
            continue
        if battle_id_filter is not None and battle_dir.name not in battle_id_filter:
            continue
        for bot_dir in battle_dir.iterdir():
            if bot_dir.is_dir() and bot_dir.name.startswith(OUR_BOT_PREFIX):
                yield battle_dir.name, bot_dir


def read_csv_safe(path: Path, nrows=None):
    """Read a CSV, returning None if it doesn't exist or is empty."""
    if not path.is_file():
        return None
    try:
        df = pd.read_csv(path, nrows=nrows)
        if df.empty:
            return None
        return df
    except Exception:
        return None


def load_all_internal(csv_root: Path, max_battles=None, battle_id_filter=None):
    """Load and concatenate internal.csv from all battles."""
    frames = []
    for i, (bid, bdir) in enumerate(find_battle_dirs(csv_root, battle_id_filter)):
        if max_battles and i >= max_battles:
            break
        df = read_csv_safe(bdir / "internal.csv")
        if df is not None:
            df["battle_id"] = bid
            frames.append(df)
    if not frames:
        return None
    return pd.concat(frames, ignore_index=True)


def load_all_ticks(csv_root: Path, max_battles=None, sample_frac=0.2, battle_id_filter=None):
    """Load and concatenate ticks.csv (with optional row-sampling)."""
    frames = []
    for i, (bid, bdir) in enumerate(find_battle_dirs(csv_root, battle_id_filter)):
        if max_battles and i >= max_battles:
            break
        df = read_csv_safe(bdir / "ticks.csv")
        if df is not None:
            if sample_frac < 1.0 and len(df) > 500:
                df = df.sample(frac=sample_frac, random_state=42)
            df["battle_id"] = bid
            frames.append(df)
    if not frames:
        return None
    return pd.concat(frames, ignore_index=True)


def load_all_scores(csv_root: Path, max_battles=None, battle_id_filter=None):
    """Load and concatenate scores.csv from all battles."""
    frames = []
    for i, (bid, bdir) in enumerate(find_battle_dirs(csv_root, battle_id_filter)):
        if max_battles and i >= max_battles:
            break
        df = read_csv_safe(bdir / "scores.csv")
        if df is not None:
            df["battle_id"] = bid
            frames.append(df)
    if not frames:
        return None
    return pd.concat(frames, ignore_index=True)


def parse_debug_logs(csv_root: Path, battle_id_filter=None):
    """Parse debug.log files for TickBudget and SkippedTurn data.

    Returns:
        budgets: list of (battle_id, budget_value) from DATA_SAVE lines
        skipped: list of (battle_id, tick, round, budget) from SKIPPED lines
    """
    budgets = []
    skipped = []
    budget_re = re.compile(r"budget=(\d+)")
    skipped_re = re.compile(
        r"SKIPPED tick=(\d+)\s+(?:round=(\d+)\s+)?budget=(\d+)"
    )

    for bid, bdir in find_battle_dirs(csv_root, battle_id_filter):
        log_path = bdir / "debug.log"
        if not log_path.is_file():
            continue
        try:
            text = log_path.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue

        # Extract final budget from DATA_SAVE line (last one wins)
        last_budget = None
        for line in text.splitlines():
            if "DATA_SAVE" in line:
                m = budget_re.search(line)
                if m:
                    last_budget = int(m.group(1))
            if line.startswith("SKIPPED"):
                m = skipped_re.search(line)
                if m:
                    skipped.append(
                        {
                            "battle_id": bid,
                            "tick": int(m.group(1)),
                            "round": int(m.group(2)) if m.group(2) else -1,
                            "budget": int(m.group(3)),
                        }
                    )
        if last_budget is not None:
            budgets.append({"battle_id": bid, "budget": last_budget})

    return budgets, skipped


# ---------------------------------------------------------------------------
# Check result structure
# ---------------------------------------------------------------------------

class CheckResult:
    """Result of a single sanity check."""

    def __init__(self, num, name, passed, message, is_bonus=False):
        self.num = num
        self.name = name
        self.passed = passed  # True/False/None (None = warn)
        self.message = message
        self.is_bonus = is_bonus

    @property
    def label(self):
        if self.passed is None:
            return "WARN"
        elif self.passed:
            return "PASS"
        else:
            return "FAIL"

    def __str__(self):
        return f"[{self.label}] #{self.num} {self.name}: {self.message}"


# ---------------------------------------------------------------------------
# Mandatory checks
# ---------------------------------------------------------------------------

def check_tick_budget(budgets, skipped):
    """#1: TickBudget — tree budget >= 100 (mean and min)."""
    if not budgets:
        return CheckResult(1, "TickBudget", False,
                           "No budget data found in debug.log (no DATA_SAVE lines)")

    budget_vals = [b["budget"] for b in budgets]
    mean_b = np.mean(budget_vals)
    min_b = np.min(budget_vals)
    max_b = np.max(budget_vals)

    passed = min_b >= 100
    msg = f"mean={mean_b:.0f}, min={min_b}, max={max_b} (n={len(budget_vals)} battles)"
    return CheckResult(1, "TickBudget", passed, msg)


def check_skipped_turns(skipped, n_battles):
    """#2: Skipped turns — < 5 per battle, avg < 1."""
    # Group skipped turns by battle
    from collections import Counter
    battle_counts = Counter(s["battle_id"] for s in skipped)

    total = len(skipped)
    avg = total / max(n_battles, 1)
    max_per_battle = max(battle_counts.values()) if battle_counts else 0
    violations = sum(1 for c in battle_counts.values() if c >= 5)

    passed = max_per_battle < 5 and avg < 1
    msg = (f"avg={avg:.1f}/battle, max={max_per_battle}"
           f" ({violations} battles with >=5 skips, total={total})")
    return CheckResult(2, "Skipped turns", passed, msg)


def check_gun_selection(internal_df):
    """#3: Gun selection — best gun selected >40% when clear winner exists."""
    if internal_df is None or "selected_gun_idx" not in internal_df.columns:
        return CheckResult(3, "Gun selection", False,
                           "No internal.csv data or missing selected_gun_idx")

    # Gun hit rate columns
    gun_hr_cols = sorted(
        [c for c in internal_df.columns if c.startswith("gun_hr_")]
    )
    if not gun_hr_cols:
        return CheckResult(3, "Gun selection", False,
                           "No gun_hr_* columns found")

    total_ticks = len(internal_df)
    gun_counts = internal_df["selected_gun_idx"].value_counts()
    top_gun_idx = gun_counts.index[0]
    top_gun_pct = gun_counts.iloc[0] / total_ticks * 100

    # Find which gun has the best hit rate on average
    mean_hrs = {}
    for col in gun_hr_cols:
        idx = int(col.split("_")[-1])
        vals = pd.to_numeric(internal_df[col], errors="coerce")
        mean_hrs[idx] = vals.mean()

    best_hr_idx = max(mean_hrs, key=mean_hrs.get)
    best_hr_val = mean_hrs[best_hr_idx]

    # How often is the best-HR gun actually selected?
    best_gun_selected = gun_counts.get(best_hr_idx, 0) / total_ticks * 100

    detail_parts = []
    for idx in sorted(gun_counts.index):
        pct = gun_counts[idx] / total_ticks * 100
        hr = mean_hrs.get(idx, 0)
        detail_parts.append(f"gun{idx}={pct:.0f}%(hr={hr:.3f})")
    detail = ", ".join(detail_parts)

    # Pass if best-HR gun is selected >= 40%
    passed = best_gun_selected >= 40 or best_hr_val < 0.001
    msg = (f"best_hr=gun{best_hr_idx}({best_hr_val:.3f}), "
           f"selected {best_gun_selected:.0f}% | {detail}")
    return CheckResult(3, "Gun selection", passed, msg)


def check_ml_predictions(internal_df):
    """#4: ML predictions in range with non-zero variance."""
    if internal_df is None:
        return CheckResult(4, "ML predictions", False,
                           "No internal.csv data")

    issues = []
    checks_done = 0

    # Fire power: [0.1, 3.0]
    if "predicted_fire_power" in internal_df.columns:
        vals = pd.to_numeric(internal_df["predicted_fire_power"], errors="coerce").dropna()
        if len(vals) > 0:
            checks_done += 1
            vmin, vmax, vstd = vals.min(), vals.max(), vals.std()
            if vmin < 0.0 or vmax > 3.5:
                issues.append(f"fire_power range [{vmin:.2f}, {vmax:.2f}]")
            if vstd < 0.001:
                issues.append(f"fire_power collapsed (std={vstd:.4f})")

    # Lateral velocity: [-8, 8]
    if "predicted_lat_vel_5" in internal_df.columns:
        vals = pd.to_numeric(internal_df["predicted_lat_vel_5"], errors="coerce").dropna()
        if len(vals) > 0:
            checks_done += 1
            vmin, vmax, vstd = vals.min(), vals.max(), vals.std()
            if vmin < -9.0 or vmax > 9.0:
                issues.append(f"lat_vel range [{vmin:.2f}, {vmax:.2f}]")
            if vstd < 0.001:
                issues.append(f"lat_vel collapsed (std={vstd:.4f})")

    # Fire probability: [0, 1]
    if "predicted_opponent_fires_3" in internal_df.columns:
        vals = pd.to_numeric(
            internal_df["predicted_opponent_fires_3"], errors="coerce"
        ).dropna()
        if len(vals) > 0:
            checks_done += 1
            vmin, vmax, vstd = vals.min(), vals.max(), vals.std()
            if vmin < -0.05 or vmax > 1.05:
                issues.append(f"fire_prob range [{vmin:.2f}, {vmax:.2f}]")
            if vstd < 0.001:
                issues.append(f"fire_prob collapsed (std={vstd:.4f})")

    if checks_done == 0:
        return CheckResult(4, "ML predictions", False,
                           "No PREDICTED_* columns found")

    if issues:
        return CheckResult(4, "ML predictions", False,
                           f"{len(issues)} issues: " + "; ".join(issues))

    return CheckResult(4, "ML predictions", True,
                       f"all {checks_done} models in range, variance OK")


def check_wave_detection(ticks_df):
    """#5: Wave detection — avg waves in flight > 0.5."""
    if ticks_df is None:
        return CheckResult(5, "Wave detection", False, "No ticks.csv data")

    col = "n_opponent_waves_in_flight"
    if col not in ticks_df.columns:
        return CheckResult(5, "Wave detection", False,
                           f"Column {col} not found in ticks.csv")

    vals = pd.to_numeric(ticks_df[col], errors="coerce").dropna()
    if len(vals) == 0:
        return CheckResult(5, "Wave detection", False, "All NaN in wave column")

    avg = vals.mean()
    passed = avg > 0.5
    msg = f"avg={avg:.2f} waves/tick (n={len(vals)} ticks)"
    return CheckResult(5, "Wave detection", passed, msg)


def check_feature_completeness(ticks_df):
    """#6: Feature completeness — no critical feature >50% NaN."""
    if ticks_df is None:
        return CheckResult(6, "Feature completeness", False,
                           "No ticks.csv data")

    # Only check numeric-looking columns (skip battle_id, etc.)
    critical_cols = [
        c for c in ticks_df.columns
        if c not in ("battle_id", "round", "tick", "scan_available")
    ]

    nan_report = {}
    for col in critical_cols:
        vals = pd.to_numeric(ticks_df[col], errors="coerce")
        nan_frac = vals.isna().mean()
        if nan_frac > 0.50:
            nan_report[col] = nan_frac

    if nan_report:
        worst = sorted(nan_report.items(), key=lambda x: -x[1])[:5]
        detail = ", ".join(f"{c}({v:.0%})" for c, v in worst)
        return CheckResult(6, "Feature completeness", False,
                           f"{len(nan_report)} columns >50% NaN: {detail}")

    return CheckResult(6, "Feature completeness", True,
                       f"0 critical NaN columns (checked {len(critical_cols)})")


# ---------------------------------------------------------------------------
# Bonus ML checks
# ---------------------------------------------------------------------------

def bonus_fire_power_r2(internal_df):
    """Fire power in-game R² (predicted vs actual at fire events)."""
    if internal_df is None:
        return CheckResult("B1", "Fire power R²", None, "No data")

    pred_col = "predicted_fire_power"
    actual_col = "opponent_fire_power"
    fired_col = "opponent_fired"

    for col in (pred_col, actual_col, fired_col):
        if col not in internal_df.columns:
            return CheckResult("B1", "Fire power R²", None,
                               f"Missing column {col}")

    # Filter to fire events only
    df = internal_df.copy()
    df[fired_col] = pd.to_numeric(df[fired_col], errors="coerce")
    df[pred_col] = pd.to_numeric(df[pred_col], errors="coerce")
    df[actual_col] = pd.to_numeric(df[actual_col], errors="coerce")
    fire_mask = df[fired_col] == 1
    fire_df = df.loc[fire_mask].dropna(subset=[pred_col, actual_col])

    if len(fire_df) < 10:
        return CheckResult("B1", "Fire power R²", None,
                           f"Only {len(fire_df)} fire events")

    y_true = fire_df[actual_col].values
    y_pred = fire_df[pred_col].values
    ss_res = np.sum((y_true - y_pred) ** 2)
    ss_tot = np.sum((y_true - np.mean(y_true)) ** 2)
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")

    threshold = 0.5
    passed = None if r2 < threshold else True
    msg = f"{r2:.2f} (threshold: {threshold}, n={len(fire_df)} fire events)"
    return CheckResult("B1", "Fire power R²", passed, msg)


def bonus_movement_r2(internal_df):
    """Movement prediction in-game R² (predicted vs actual lateral velocity)."""
    if internal_df is None:
        return CheckResult("B4", "Movement R²", None, "No data")

    pred_col = "predicted_lat_vel_5"
    actual_col = "opponent_lateral_velocity"

    for col in (pred_col, actual_col):
        if col not in internal_df.columns:
            return CheckResult("B4", "Movement R²", None,
                               f"Missing column {col}")

    df = internal_df.copy()
    df[pred_col] = pd.to_numeric(df[pred_col], errors="coerce")
    df[actual_col] = pd.to_numeric(df[actual_col], errors="coerce")
    df = df.dropna(subset=[pred_col, actual_col])

    if len(df) < 100:
        return CheckResult("B4", "Movement R²", None,
                           f"Only {len(df)} samples")

    y_true = df[actual_col].values
    y_pred = df[pred_col].values
    ss_res = np.sum((y_true - y_pred) ** 2)
    ss_tot = np.sum((y_true - np.mean(y_true)) ** 2)
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")

    threshold = 0.3
    passed = None if r2 < threshold else True
    msg = f"{r2:.2f} (threshold: {threshold}, n={len(df)} ticks)"
    return CheckResult("B4", "Movement R²", passed, msg)


def bonus_fire_timing_auc(internal_df):
    """Fire timing in-game AUC (predicted probability vs actual fire events)."""
    if internal_df is None:
        return CheckResult("B5", "Fire timing AUC", None, "No data")

    pred_col = "predicted_opponent_fires_3"
    fired_col = "opponent_fired"

    for col in (pred_col, fired_col):
        if col not in internal_df.columns:
            return CheckResult("B5", "Fire timing AUC", None,
                               f"Missing column {col}")

    df = internal_df.copy()
    df[pred_col] = pd.to_numeric(df[pred_col], errors="coerce")
    df[fired_col] = pd.to_numeric(df[fired_col], errors="coerce")
    df = df.dropna(subset=[pred_col, fired_col])

    if len(df) < 100:
        return CheckResult("B5", "Fire timing AUC", None,
                           f"Only {len(df)} samples")

    y_true = df[fired_col].values
    y_pred = df[pred_col].values

    # Need both classes present
    if y_true.sum() < 5 or (1 - y_true).sum() < 5:
        return CheckResult("B5", "Fire timing AUC", None,
                           "Too few positive or negative samples")

    from sklearn.metrics import roc_auc_score
    auc = roc_auc_score(y_true, y_pred)

    threshold = 0.6
    passed = None if auc < threshold else True
    msg = f"{auc:.2f} (threshold: {threshold}, n={len(df)} ticks)"
    return CheckResult("B5", "Fire timing AUC", passed, msg)


def bonus_fire_timing_calibration(internal_df):
    """Fire timing calibration (predicted rate vs actual rate)."""
    if internal_df is None:
        return CheckResult("B2", "Fire timing calibration", None, "No data")

    pred_col = "predicted_opponent_fires_3"
    fired_col = "opponent_fired"

    for col in (pred_col, fired_col):
        if col not in internal_df.columns:
            return CheckResult("B2", "Fire timing calibration", None,
                               f"Missing column {col}")

    df = internal_df.copy()
    df[pred_col] = pd.to_numeric(df[pred_col], errors="coerce")
    df[fired_col] = pd.to_numeric(df[fired_col], errors="coerce")
    df = df.dropna(subset=[pred_col, fired_col])

    if len(df) < 50:
        return CheckResult("B2", "Fire timing calibration", None,
                           f"Only {len(df)} ticks")

    pred_rate = df[pred_col].mean() * 100
    actual_rate = df[fired_col].mean() * 100

    # A 10x discrepancy is a calibration problem
    ratio = pred_rate / max(actual_rate, 0.01)
    passed = None if ratio > 5 or ratio < 0.2 else True
    msg = f"{pred_rate:.0f}% predicted vs {actual_rate:.0f}% actual"
    return CheckResult("B2", "Fire timing calibration", passed, msg)


def bonus_prediction_collapse(internal_df):
    """Check if any prediction distribution has collapsed (std < threshold)."""
    if internal_df is None:
        return CheckResult("B3", "Prediction distribution", None, "No data")

    pred_cols = {
        "predicted_fire_power": 0.05,
        "predicted_lat_vel_5": 0.1,
        "predicted_opponent_fires_3": 0.01,
    }

    collapsed = []
    checked = 0
    for col, threshold in pred_cols.items():
        if col not in internal_df.columns:
            continue
        vals = pd.to_numeric(internal_df[col], errors="coerce").dropna()
        if len(vals) < 10:
            continue
        checked += 1
        std = vals.std()
        if std < threshold:
            collapsed.append(f"{col}(std={std:.4f}<{threshold})")

    if checked == 0:
        return CheckResult("B3", "Prediction distribution", None, "No prediction columns")

    if collapsed:
        return CheckResult("B3", "Prediction distribution", None,
                           f"{len(collapsed)} collapsed: " + "; ".join(collapsed))

    return CheckResult("B3", "Prediction distribution", True,
                       f"all {checked} predictions have healthy variance")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def run_checks(data_dir: Path, battle_id_filter=None):
    """Run all sanity checks and return list of CheckResults."""
    csv_root = data_dir / "csv"

    if not csv_root.is_dir():
        print(f"ERROR: CSV directory not found: {csv_root}", file=sys.stderr)
        sys.exit(2)

    # Count battles
    battle_dirs = list(find_battle_dirs(csv_root, battle_id_filter))
    n_battles = len(battle_dirs)
    if n_battles == 0:
        print(f"ERROR: No Autopilot battle data in {csv_root}", file=sys.stderr)
        if battle_id_filter:
            print(f"  (filtered to {len(battle_id_filter)} battle IDs)", file=sys.stderr)
        sys.exit(2)

    filter_msg = f" (filtered to {len(battle_id_filter)} sprint battles)" if battle_id_filter else ""
    print(f"Loading data from {csv_root} ({n_battles} battles{filter_msg})...",
          file=sys.stderr)

    # Load data
    budgets, skipped = parse_debug_logs(csv_root, battle_id_filter)
    internal_df = load_all_internal(csv_root, battle_id_filter=battle_id_filter)
    ticks_df = load_all_ticks(csv_root, sample_frac=0.3, battle_id_filter=battle_id_filter)
    # scores_df not needed for current checks but available for future use

    results = []

    # Mandatory checks
    results.append(check_tick_budget(budgets, skipped))
    results.append(check_skipped_turns(skipped, n_battles))
    results.append(check_gun_selection(internal_df))
    results.append(check_ml_predictions(internal_df))
    results.append(check_wave_detection(ticks_df))
    results.append(check_feature_completeness(ticks_df))

    # Bonus ML checks
    for bonus in [
        bonus_fire_power_r2(internal_df),
        bonus_movement_r2(internal_df),
        bonus_fire_timing_auc(internal_df),
        bonus_fire_timing_calibration(internal_df),
        bonus_prediction_collapse(internal_df),
    ]:
        bonus.is_bonus = True
        results.append(bonus)

    return results


def format_output(results):
    """Format results as the standard output block."""
    mandatory = [r for r in results if not r.is_bonus]
    bonus = [r for r in results if r.is_bonus]

    lines = ["", "=== SANITY CHECK RESULTS ==="]
    for r in mandatory:
        lines.append(str(r))

    if bonus:
        lines.append("")
        lines.append("--- BONUS ML ---")
        for r in bonus:
            lines.append(str(r))

    mandatory_pass = sum(1 for r in mandatory if r.passed is not None and r.passed)
    mandatory_fail = sum(1 for r in mandatory if r.passed is not None and not r.passed)
    bonus_warn = sum(1 for r in bonus if r.passed is None)

    lines.append("")
    summary = f"RESULT: {mandatory_pass}/{len(mandatory)} mandatory PASS"
    if mandatory_fail:
        summary += f", {mandatory_fail} FAIL"
    if bonus_warn:
        summary += f", {bonus_warn} WARN"
    lines.append(summary)

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(
        description="Robocode-autopilot sanity checks"
    )
    parser.add_argument(
        "--data-dir",
        type=str,
        default=None,
        help="Path to output/local/ (default: auto-detect from script location)",
    )
    parser.add_argument(
        "--battle-ids",
        type=str,
        default=None,
        help="Path to JSON file with list of battle IDs to filter to",
    )
    args = parser.parse_args()

    if args.data_dir:
        data_dir = Path(args.data_dir)
    else:
        # Auto-detect: script is in scripts/, data is in output/local/
        repo_root = Path(__file__).resolve().parent.parent
        data_dir = repo_root / "output" / "local"

    if not data_dir.is_dir():
        print(f"ERROR: Data directory not found: {data_dir}", file=sys.stderr)
        sys.exit(2)

    # Load battle ID filter if provided
    battle_id_filter = None
    if args.battle_ids:
        bid_path = Path(args.battle_ids)
        if not bid_path.is_file():
            print(f"ERROR: Battle IDs file not found: {bid_path}", file=sys.stderr)
            sys.exit(2)
        with open(bid_path) as f:
            battle_id_filter = set(json.load(f))
        print(f"Filtering to {len(battle_id_filter)} sprint battle IDs", file=sys.stderr)

    results = run_checks(data_dir, battle_id_filter=battle_id_filter)
    output = format_output(results)
    print(output)

    # Exit code: 0 if all mandatory pass, 1 if any fail
    mandatory = [r for r in results if not r.is_bonus]
    any_fail = any(r.passed is not None and not r.passed for r in mandatory)
    sys.exit(1 if any_fail else 0)


if __name__ == "__main__":
    main()
