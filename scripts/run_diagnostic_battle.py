"""Run a diagnostic battle with FeatureLogger enabled and compare features.

This script automates the diagnostic loop:
  1. Build the robot JAR and pipeline
  2. Run a single battle with FeatureLogger enabled (-Dautopilot.featureLog=true)
  3. Record the battle with the pipeline (to get ticks.csv)
  4. Copy the FeatureLogger CSV from robot data directory
  5. Run compare_features.py on the results

Usage:
    python scripts/run_diagnostic_battle.py [--opponent florent.FloatingTadpole] [--rounds 10]

Prerequisites:
    - Robot JAR built (gradlew :robot:jar)
    - Pipeline installDist (gradlew :pipeline:installDist)
    - Robocode installed at the expected location
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

# Project root (parent of scripts/)
PROJECT_ROOT = Path(__file__).resolve().parent.parent

# Robocode paths
ROBOCODE_HOME = PROJECT_ROOT / 'rumble' / 'robocode'
ROBOT_JAR = PROJECT_ROOT / 'robot' / 'build' / 'libs' / 'robot.jar'
PIPELINE_BIN = PROJECT_ROOT / 'pipeline' / 'build' / 'install' / 'pipeline' / 'bin' / 'pipeline'
OUTPUT_DIR = PROJECT_ROOT / 'output' / 'diagnostic'
RECORDINGS_DIR = PROJECT_ROOT / 'recordings'


def find_robocode_home() -> Path:
    """Find the Robocode installation directory."""
    candidates = [
        ROBOCODE_HOME,
        PROJECT_ROOT / 'robocode',
        Path.home() / 'robocode',
        Path(os.environ.get('ROBOCODE_HOME', '')) if os.environ.get('ROBOCODE_HOME') else None,
    ]
    for c in candidates:
        if c and c.exists() and (c / 'libs').exists():
            return c
    return ROBOCODE_HOME  # default, will fail later with clear error


def main():
    parser = argparse.ArgumentParser(description='Run diagnostic battle with FeatureLogger')
    parser.add_argument('--opponent', default='florent.FloatingTadpole',
                        help='Opponent robot class name (default: florent.FloatingTadpole)')
    parser.add_argument('--rounds', type=int, default=10,
                        help='Number of rounds (default: 10)')
    parser.add_argument('--skip-build', action='store_true',
                        help='Skip Gradle build step')
    parser.add_argument('--skip-battle', action='store_true',
                        help='Skip battle, just run comparison on existing output')
    args = parser.parse_args()

    robocode_home = find_robocode_home()

    print("=" * 70)
    print("DIAGNOSTIC BATTLE — Feature Logger Comparison")
    print("=" * 70)
    print(f"  Opponent:      {args.opponent}")
    print(f"  Rounds:        {args.rounds}")
    print(f"  Robocode home: {robocode_home}")
    print(f"  Robot JAR:     {ROBOT_JAR}")
    print(f"  Output:        {OUTPUT_DIR}")
    print()

    if not args.skip_battle:
        # Step 1: Build
        if not args.skip_build:
            print("[1/5] Building robot JAR and pipeline...")
            result = subprocess.run(
                ['cmd', '/c', str(PROJECT_ROOT / 'gradlew.bat'),
                 ':pipeline:installDist', ':robot:jar', '--console=plain'],
                cwd=str(PROJECT_ROOT), capture_output=True, text=True
            )
            if result.returncode != 0:
                print(f"Build failed:\n{result.stderr}")
                sys.exit(1)
            print("  Build OK")
        else:
            print("[1/5] Skipping build")

        # Step 2: Prepare battle
        print("[2/5] Preparing battle configuration...")
        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

        # The actual battle execution depends on the local setup.
        # Print manual instructions since Robocode battle configs vary.
        print()
        print("  MANUAL STEPS REQUIRED:")
        print("  ─────────────────────")
        print(f"  1. Copy {ROBOT_JAR.name} to {robocode_home / 'robots'}")
        print(f"  2. Run Robocode with feature logging enabled:")
        print()
        print(f"     cd {robocode_home}")
        print(f"     java -Dautopilot.featureLog=true \\")
        print(f"       -cp \"libs/*\" robocode.Robocode \\")
        print(f"       -battle battles/diagnostic.battle \\")
        print(f"       -results {OUTPUT_DIR / 'results.txt'}")
        print()
        print(f"  3. Or use the existing rumble infrastructure:")
        print()
        print(f"     # One-liner for PowerShell:")
        print(f"     java -Dautopilot.featureLog=true `")
        print(f"       -cp \"{robocode_home / 'libs' / '*'}\" robocode.Robocode `")
        print(f"       -nodisplay -nosound `")
        print(f"       -battle diagnostic.battle")
        print()
        print(f"  4. After the battle, the FeatureLogger CSV will be at:")
        print(f"     {robocode_home / 'robots' / '.data' / 'cz.zamboch.distilled.Autopilot.data' / 'features_fire_power.csv'}")
        print()
        print(f"  5. Also run the pipeline on the same battle to get ticks.csv:")
        print(f"     (The pipeline recorder should capture the same battle)")
        print()

        # Create a minimal battle file for reference
        battle_file = OUTPUT_DIR / 'diagnostic.battle'
        battle_content = f"""#Battle Properties
robocode.battle.numRounds={args.rounds}
robocode.battle.gunCoolingRate=0.1
robocode.battle.rules.inactivityTime=450
robocode.battle.selectedRobots=cz.zamboch.distilled.Autopilot,{args.opponent}
robocode.battle.initialPositions=(50,50,0),(750,550,0)
"""
        battle_file.write_text(battle_content)
        print(f"  Battle file written to: {battle_file}")

    # Step 3: Find and compare feature files
    print()
    print("[3/5] Looking for FeatureLogger output...")

    # Common locations for the feature logger CSV
    feature_csv_candidates = [
        OUTPUT_DIR / 'features_fire_power.csv',
        robocode_home / 'robots' / '.data' / 'cz.zamboch.distilled.Autopilot.data' / 'features_fire_power.csv',
        PROJECT_ROOT / 'output' / 'local' / 'features_fire_power.csv',
    ]

    feature_csv = None
    for c in feature_csv_candidates:
        if c.exists():
            feature_csv = c
            print(f"  Found: {feature_csv}")
            break

    if feature_csv is None:
        print("  Not found. Expected locations:")
        for c in feature_csv_candidates:
            print(f"    - {c}")
        print()
        print("  After running the diagnostic battle, copy the CSV to one of")
        print("  the locations above, or pass it directly:")
        print(f"    python scripts/compare_features.py <feature_csv> <ticks_csv_dir>")
        sys.exit(0)

    # Step 4: Find matching ticks.csv
    print("[4/5] Looking for matching pipeline ticks.csv...")
    ticks_dir = None

    # Check output/csv for the most recent battle with our robot
    csv_root = PROJECT_ROOT / 'output' / 'csv'
    if csv_root.exists():
        # Find directories containing our robot's ticks.csv
        our_dirs = []
        for ticks_file in csv_root.rglob('ticks.csv'):
            robot_name = ticks_file.parent.name
            if 'Autopilot' in robot_name or 'autopilot' in robot_name:
                our_dirs.append(ticks_file.parent)

        if our_dirs:
            # Use most recently modified
            our_dirs.sort(key=lambda p: p.stat().st_mtime, reverse=True)
            ticks_dir = our_dirs[0]
            print(f"  Found: {ticks_dir}")
    
    # Also check output/local
    local_ticks = PROJECT_ROOT / 'output' / 'local' / 'ticks.csv'
    if local_ticks.exists() and ticks_dir is None:
        ticks_dir = local_ticks.parent
        print(f"  Found: {ticks_dir}")

    if ticks_dir is None:
        print("  Not found. Run the pipeline on the same battle first.")
        print(f"  Then: python scripts/compare_features.py {feature_csv} <ticks_csv_dir>")
        sys.exit(0)

    # Step 5: Run comparison
    print("[5/5] Running feature comparison...")
    print()

    # Import and run the comparison
    sys.path.insert(0, str(PROJECT_ROOT / 'scripts'))
    from compare_features import compare
    compare(str(feature_csv), str(ticks_dir))


if __name__ == '__main__':
    main()
