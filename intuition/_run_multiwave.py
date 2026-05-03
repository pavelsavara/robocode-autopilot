"""Quick multi-wave GF analysis — runs as standalone script."""
import sys; sys.path.insert(0, '.')
import os; os.environ['MPLBACKEND'] = 'Agg'
import numpy as np
import pandas as pd
from scipy.stats import ks_2samp
from _loader import build_robot_index, load_stratified

print('=== Loading data ===')
selection = build_robot_index(max_robots=30, battles_per_robot=2, seed=42)
waves = load_stratified('waves.csv', selection)
print(f'Waves: {len(waves):,}')

# Instead of loading ALL ticks, load them and immediately slim down
# to only the columns and rows we need (ticks near wave break times)
print('Loading ticks (will slim immediately)...')
ticks_raw = load_stratified('ticks.csv', selection, row_frac=1.0)
keep_cols = ['battle_id','round','tick','robot_name',
             'gf_current_at_power_1','gf_current_at_power_1_5','gf_current_at_power_2']
keep_cols = [c for c in keep_cols if c in ticks_raw.columns]
ticks = ticks_raw[keep_cols].copy()
del ticks_raw
import gc; gc.collect()
print(f'Ticks: {len(ticks):,}')

print('\n=== Computing wave pressure ===')
w = waves.sort_values(['battle_id','round','robot_name','tick']).copy()
w['arrival_tick'] = w['tick'] + w['wave_fire_distance'] / w['wave_bullet_speed']
w['damage'] = np.where(w['wave_bullet_power'] <= 1.0,
                       4.0 * w['wave_bullet_power'],
                       6.0 * w['wave_bullet_power'] - 2.0)

records = []
for key, grp in w.groupby(['battle_id','round','robot_name']):
    ft = grp['tick'].values.copy()
    at = grp['arrival_tick'].values.copy()
    dmg = grp['damage'].values.copy()
    spd = grp['wave_bullet_speed'].values.copy()
    dist = grp['wave_fire_distance'].values.copy()
    pwr = grp['wave_bullet_power'].values.copy()
    n_waves = len(ft)
    for i in range(n_waves):
        # Only look at waves before index i
        if i == 0:
            n = 0
            td = float(dmg[i])
            ng = np.nan
        else:
            inf = (ft[:i] < ft[i]) & (at[:i] > ft[i])
            n = int(inf.sum())
            td = float(dmg[:i][inf].sum()) + float(dmg[i])
            if n > 0:
                ng = float(np.abs(at[:i][inf] - at[i]).min())
            else:
                ng = np.nan
        records.append({
            'battle_id': key[0], 'round': key[1], 'robot_name': key[2],
            'tick': ft[i], 'arrival_tick': at[i],
            'bullet_power': pwr[i], 'bullet_speed': spd[i],
            'fire_distance': dist[i],
            'n_waves_in_flight': n, 'total_wave_damage': td, 'nearest_wave_gap': ng,
        })

wf = pd.DataFrame(records)
print(f'Wave events: {len(wf):,}')
print(wf['n_waves_in_flight'].value_counts().sort_index().head(10))

print('\n=== Joining with GF at break ===')
wf['break_tick'] = wf['arrival_tick'].round().astype(int)
merged = wf.merge(
    ticks.rename(columns={'tick': 'break_tick'}),
    on=['battle_id', 'round', 'robot_name', 'break_tick'],
    how='inner'
)

def pick_gf(row):
    p = row['bullet_power']
    if p <= 1.25:
        return row['gf_current_at_power_1']
    elif p <= 1.75:
        return row['gf_current_at_power_1_5']
    else:
        return row['gf_current_at_power_2']

merged['gf_at_break'] = merged.apply(pick_gf, axis=1)
valid = merged.dropna(subset=['gf_at_break']).copy()
print(f'Valid: {len(valid):,} / {len(wf):,}')

print('\n=== GF by pressure ===')
valid['pressure'] = pd.cut(valid['n_waves_in_flight'],
                           bins=[-1, 0, 1, 100],
                           labels=['none', 'mod', 'high'])
for lb in ['none', 'mod', 'high']:
    s = valid[valid['pressure'] == lb]['gf_at_break']
    if len(s) > 0:
        print(f'  {lb}: n={len(s):,} mean={s.mean():.4f} std={s.std():.4f} '
              f'|mean|={s.abs().mean():.4f}')

gf_n = valid[valid['pressure'] == 'none']['gf_at_break'].dropna()
gf_m = valid[valid['pressure'] == 'mod']['gf_at_break'].dropna()
gf_h = valid[valid['pressure'] == 'high']['gf_at_break'].dropna()
if len(gf_n) > 0 and len(gf_m) > 0:
    ks, p = ks_2samp(gf_n, gf_m)
    print(f'  KS none vs mod: {ks:.4f} p={p:.2e}')
if len(gf_n) > 0 and len(gf_h) > 0:
    ks, p = ks_2samp(gf_n, gf_h)
    print(f'  KS none vs high: {ks:.4f} p={p:.2e}')

print('\n=== Gap analysis ===')
pr = valid[valid['n_waves_in_flight'] > 0].copy()
pr['gap_cat'] = pd.cut(pr['nearest_wave_gap'],
                       bins=[-1, 5, 15, 100],
                       labels=['tight', 'mod', 'loose'])
for lb in ['tight', 'mod', 'loose']:
    s = pr[pr['gap_cat'] == lb]['gf_at_break']
    if len(s) > 0:
        print(f'  {lb}: n={len(s):,} mean={s.mean():.4f} std={s.std():.4f} '
              f'|mean|={s.abs().mean():.4f}')
gt = pr[pr['gap_cat'] == 'tight']['gf_at_break'].dropna()
gl = pr[pr['gap_cat'] == 'loose']['gf_at_break'].dropna()
if len(gt) > 0 and len(gl) > 0:
    ks, p = ks_2samp(gt, gl)
    print(f'  KS tight vs loose: {ks:.4f} p={p:.2e}')

print('\n=== Per-bot sensitivity ===')
bot_ks = []
for bot, grp in valid.groupby('robot_name'):
    gno = grp[grp['n_waves_in_flight'] == 0]['gf_at_break'].dropna()
    gyes = grp[grp['n_waves_in_flight'] > 0]['gf_at_break'].dropna()
    if len(gno) >= 30 and len(gyes) >= 30:
        ks, p = ks_2samp(gno, gyes)
        bot_ks.append({
            'bot': bot, 'ks': ks, 'p': p,
            'std_no': gno.std(), 'std_yes': gyes.std(),
            'delta': gyes.std() - gno.std(),
        })

bks = pd.DataFrame(bot_ks).sort_values('ks', ascending=False)
print(f'Bots with data: {len(bks)}')
narrows = (bks['delta'] < 0).sum()
widens = (bks['delta'] > 0).sum()
print(f'Narrows under pressure: {narrows} / {len(bks)}')
print(f'Widens under pressure:  {widens} / {len(bks)}')
print(bks.head(15).to_string(index=False))

print('\n=== ML feature importance ===')
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import GroupKFold, cross_val_score

feat_b = ['fire_distance', 'bullet_speed', 'bullet_power']
feat_w = ['n_waves_in_flight', 'nearest_wave_gap', 'total_wave_damage']
vm = valid.dropna(subset=feat_b + ['gf_at_break']).copy()
vm['nearest_wave_gap'] = vm['nearest_wave_gap'].fillna(999)
vm['gf_bin'] = pd.cut(vm['gf_at_break'], bins=np.linspace(-1, 1, 12), labels=False)
vm = vm.dropna(subset=['gf_bin'])
vm['gf_bin'] = vm['gf_bin'].astype(int)
if len(vm) > 50000:
    vm = vm.sample(50000, random_state=42)
groups = vm['battle_id'].astype('category').cat.codes
cv = GroupKFold(n_splits=5)
y = vm['gf_bin'].values
Xb = vm[feat_b].values.astype(np.float32)
Xw = vm[feat_b + feat_w].values.astype(np.float32)
sb = cross_val_score(
    RandomForestClassifier(100, max_depth=8, random_state=42, n_jobs=-1),
    Xb, y, cv=cv.split(Xb, y, groups), scoring='accuracy')
sw = cross_val_score(
    RandomForestClassifier(100, max_depth=8, random_state=42, n_jobs=-1),
    Xw, y, cv=cv.split(Xw, y, groups), scoring='accuracy')
print(f'Base:      {sb.mean():.4f} +/- {sb.std():.4f}')
print(f'Base+wave: {sw.mean():.4f} +/- {sw.std():.4f}')
print(f'Delta:     {sw.mean() - sb.mean():+.4f}')
rf = RandomForestClassifier(100, max_depth=8, random_state=42, n_jobs=-1).fit(Xw, y)
for n, imp in sorted(zip(feat_b + feat_w, rf.feature_importances_), key=lambda x: -x[1]):
    print(f'  {n:30s} {imp:.4f}')
print('\nDone.')
