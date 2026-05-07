"""Batch re-run key notebooks on the enriched CSV data.

Runs notebooks that use numeric_feature_cols() and would auto-discover
the 13 new columns. Skips pure-wave notebooks (08, 10, 12) and
historical ones (04, 07).

Usage: python intuition/_run_batch_nb.py
"""
import subprocess, sys, time

notebooks = [
    ('01_data_overview.ipynb', 'Data overview — column stats + distributions'),
    ('02_correlations.ipynb', 'Correlations — full heatmap with new features'),
    ('03_clustering.ipynb', 'Clustering — game state K-Means'),
    ('05_movement_prediction.ipynb', 'Movement prediction — GBM with new features'),
    ('06_bot_fingerprinting.ipynb', 'Bot fingerprinting — LightGBM'),
    ('08_wave_analysis.ipynb', 'Wave analysis'),
    ('11_scan_gap_density.ipynb', 'Scan gap density'),
    ('13_multiwave_gf.ipynb', 'Multi-wave GF — with new wave-gap columns'),
    ('14_gbm_model_analysis.ipynb', 'GBM model analysis'),
    ('15_opponent_profiles.ipynb', 'Opponent profiles — strength + heatmaps'),
]

for nb, desc in notebooks:
    path = f'intuition/{nb}'
    print(f'\n{"="*60}')
    print(f'Running {nb}: {desc}')
    print(f'{"="*60}')
    t0 = time.time()
    result = subprocess.run([
        sys.executable, '-m', 'jupyter', 'nbconvert',
        '--to', 'notebook', '--execute', path,
        '--inplace',
        '--ExecutePreprocessor.timeout=600',
        '--allow-errors',
    ], capture_output=True, text=True)
    dt = time.time() - t0
    if result.returncode == 0:
        print(f'  OK ({dt:.0f}s)')
    else:
        print(f'  FAILED ({dt:.0f}s)')
        # Print last 5 lines of stderr
        for line in result.stderr.strip().split('\n')[-5:]:
            print(f'  {line}')
    
print('\nDone.')
