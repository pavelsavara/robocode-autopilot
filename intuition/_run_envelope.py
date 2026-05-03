"""Compute reachable envelope sizes, grid densities, and trajectory sharing.

Simulates Robocode movement physics exactly to answer:
1. How many distinct reachable states at each horizon?
2. What spatial grid density covers the envelope?
3. How many trajectories share early steps?
4. What happens with coarser time steps (2, 3, 5)?
"""
import math
import numpy as np
from collections import defaultdict

# Robocode physics constants
MAX_VEL = 8.0
ACCEL = 1.0
DECEL = 2.0
ROBOT_HALF = 18

def max_turn_rate(vel):
    """Max body turn rate in radians at given speed."""
    return math.radians(10.0 - 0.75 * abs(vel))

def step(x, y, heading, vel, accel, turn_frac):
    """Advance one tick. turn_frac in [-1, +1] as fraction of max turn."""
    max_turn = max_turn_rate(vel)
    turn = turn_frac * max_turn
    heading = heading + turn
    
    # Apply acceleration
    new_vel = vel + accel
    new_vel = max(-MAX_VEL, min(MAX_VEL, new_vel))
    
    # Move
    x = x + new_vel * math.sin(heading)
    y = y + new_vel * math.cos(heading)
    
    return x, y, heading, new_vel

# ============================================================
# 1. Reachable states at each horizon
# ============================================================
print("=" * 70)
print("1. REACHABLE STATES BY HORIZON")
print("   (starting at v=0 and v=8, heading=0, turn options=5, accel options=3)")
print("=" * 70)

# Turn fraction options
TURN_OPTIONS_COARSE = [-1.0, -0.5, 0.0, 0.5, 1.0]  # 5 options
TURN_OPTIONS_FINE = [-1.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75, 1.0]  # 9 options
ACCEL_OPTIONS = [-DECEL, 0.0, ACCEL]  # 3 options

def count_reachable(init_vel, max_horizon, turn_options, grid_px=2.0):
    """BFS through the reachable tree, counting distinct grid cells reached."""
    # State: (grid_x, grid_y, quantized_vel, quantized_heading)
    # We quantize to avoid float drift creating false "new" states
    
    def quantize(x, y, heading, vel):
        gx = round(x / grid_px)
        gy = round(y / grid_px)
        qv = round(vel * 10)  # 0.1 velocity resolution
        qh = round(heading * 100)  # ~0.6 degree heading resolution
        return (gx, gy, qv, qh)
    
    def quantize_pos(x, y):
        return (round(x / grid_px), round(y / grid_px))
    
    # Start state
    states = {quantize(0, 0, 0, init_vel): (0, 0, 0, init_vel)}
    
    results = []
    for t in range(1, max_horizon + 1):
        new_states = {}
        for key, (x, y, h, v) in states.items():
            for a in ACCEL_OPTIONS:
                for tf in turn_options:
                    nx, ny, nh, nv = step(x, y, h, v, a, tf)
                    nk = quantize(nx, ny, nh, nv)
                    if nk not in new_states:
                        new_states[nk] = (nx, ny, nh, nv)
        
        states = new_states
        
        # Count distinct positions (ignoring heading/velocity)
        positions = set()
        xs, ys = [], []
        for (gx, gy, qv, qh), (x, y, h, v) in states.items():
            positions.add((gx, gy))
            xs.append(x)
            ys.append(y)
        
        xs = np.array(xs)
        ys = np.array(ys)
        max_dist = np.sqrt(xs**2 + ys**2).max() if len(xs) > 0 else 0
        
        results.append({
            't': t,
            'n_states': len(states),
            'n_positions': len(positions),
            'max_dist': max_dist,
            'x_range': (xs.min(), xs.max()) if len(xs) > 0 else (0, 0),
            'y_range': (ys.min(), ys.max()) if len(ys) > 0 else (0, 0),
        })
        
        # Prune if too large (keep only unique positions with best velocity)
        if len(states) > 500000:
            print(f"    t={t}: {len(states):,} states, pruning to position-only...")
            break
    
    return results

for init_vel, label in [(0, "v=0 (stationary)"), (8, "v=8 (full speed)")]:
    print(f"\n  Starting at {label}, 5 turn options:")
    results = count_reachable(init_vel, 12, TURN_OPTIONS_COARSE, grid_px=2.0)
    print(f"  {'t':>3s}  {'states':>10s}  {'positions':>10s}  {'max_dist':>8s}  {'x_range':>20s}")
    for r in results:
        print(f"  {r['t']:3d}  {r['n_states']:10,d}  {r['n_positions']:10,d}  "
              f"{r['max_dist']:8.1f}  [{r['x_range'][0]:.0f}, {r['x_range'][1]:.0f}]")

# ============================================================
# 2. Branching factor and tree size
# ============================================================
print("\n" + "=" * 70)
print("2. BRANCHING FACTOR AND TREE SIZE")
print("=" * 70)

for n_turns in [3, 5, 7, 9]:
    for n_accel in [3]:
        bf = n_turns * n_accel
        for horizon in [5, 8, 10, 15, 20]:
            total = bf ** horizon
            print(f"  turns={n_turns} × accel={n_accel} = bf={bf:2d}  "
                  f"horizon={horizon:2d}  → {total:>15,.0f} leaves"
                  + (" ← feasible" if total < 1e6 else ""))

# ============================================================
# 3. Grid density for the envelope
# ============================================================
print("\n" + "=" * 70)
print("3. ENVELOPE AREA AND GRID CANDIDATES")
print("=" * 70)

for init_vel in [0, 4, 8]:
    for horizon in [5, 8, 10]:
        results = count_reachable(init_vel, horizon, TURN_OPTIONS_COARSE, grid_px=2.0)
        r = results[-1]
        x_span = r['x_range'][1] - r['x_range'][0]
        y_span = r['y_range'][1] - r['y_range'][0]
        area = x_span * y_span  # bounding box
        
        for grid_px in [5, 10, 20]:
            n_grid = int((x_span / grid_px) * (y_span / grid_px))
            print(f"  v={init_vel} t={horizon:2d}: area≈{area:,.0f}px²  "
                  f"span=({x_span:.0f}×{y_span:.0f})  "
                  f"grid@{grid_px}px → {n_grid:,d} candidates  "
                  f"(inside envelope: ~{r['n_positions']:,d})")

# ============================================================
# 4. Coarse time steps (every 2, 3, 5 ticks)
# ============================================================
print("\n" + "=" * 70)
print("4. COARSE TIME STEPS — CAN WE PLAN EVERY 2-5 TICKS?")
print("=" * 70)

def step_n(x, y, heading, vel, accel, turn_frac, n_ticks):
    """Advance n ticks with constant controls."""
    for _ in range(n_ticks):
        x, y, heading, vel = step(x, y, heading, vel, accel, turn_frac)
    return x, y, heading, vel

def count_reachable_coarse(init_vel, total_ticks, step_size, turn_options):
    """Count reachable states with decisions every step_size ticks."""
    def quantize(x, y, heading, vel):
        return (round(x/2), round(y/2), round(vel*10), round(heading*100))
    
    states = {quantize(0, 0, 0, init_vel): (0, 0, 0, init_vel)}
    n_decisions = total_ticks // step_size
    
    for d in range(n_decisions):
        new_states = {}
        for key, (x, y, h, v) in states.items():
            for a in ACCEL_OPTIONS:
                for tf in turn_options:
                    nx, ny, nh, nv = step_n(x, y, h, v, a, tf, step_size)
                    nk = quantize(nx, ny, nh, nv)
                    if nk not in new_states:
                        new_states[nk] = (nx, ny, nh, nv)
        states = new_states
        if len(states) > 500000:
            return d + 1, len(states), -1
    
    positions = set((round(x/2), round(y/2)) for (x, y, h, v) in states.values())
    return n_decisions, len(states), len(positions)

print(f"\n  Total horizon = 10 ticks, v=8, 5 turn options:")
print(f"  {'step':>4s}  {'decisions':>9s}  {'states':>10s}  {'positions':>10s}  {'bf^n':>12s}")
for step_size in [1, 2, 3, 5, 10]:
    n_dec, n_states, n_pos = count_reachable_coarse(8, 10, step_size, TURN_OPTIONS_COARSE)
    bf = len(TURN_OPTIONS_COARSE) * len(ACCEL_OPTIONS)
    theoretical = bf ** n_dec
    print(f"  {step_size:4d}  {n_dec:9d}  {n_states:10,d}  {n_pos:10,d}  {theoretical:12,d}")

print(f"\n  Total horizon = 10 ticks, v=0, 5 turn options:")
for step_size in [1, 2, 3, 5, 10]:
    n_dec, n_states, n_pos = count_reachable_coarse(0, 10, step_size, TURN_OPTIONS_COARSE)
    bf = len(TURN_OPTIONS_COARSE) * len(ACCEL_OPTIONS)
    theoretical = bf ** n_dec
    print(f"  {step_size:4d}  {n_dec:9d}  {n_states:10,d}  {n_pos:10,d}  {theoretical:12,d}")

# ============================================================
# 5. Trajectory sharing — how many early steps are common?
# ============================================================
print("\n" + "=" * 70)
print("5. TRAJECTORY PREFIX SHARING")
print("   How many distinct trajectories at step t lead to top-20 endpoints?")
print("=" * 70)

def analyze_sharing(init_vel, horizon, turn_options):
    """Track full trajectories, then analyze prefix sharing among best endpoints."""
    # Each trajectory is a sequence of (accel, turn_frac) decisions
    # Store: trajectory_key → final (x, y)
    
    from itertools import product
    
    decisions = list(product(ACCEL_OPTIONS, turn_options))
    n_dec = len(decisions)
    
    if n_dec ** horizon > 5e6:
        print(f"  Too many trajectories ({n_dec}^{horizon}={n_dec**horizon:.0e}), skipping")
        return
    
    # Generate all trajectories
    trajectories = []  # list of (decision_sequence, final_x, final_y)
    
    def gen(depth, x, y, h, v, seq):
        if depth == horizon:
            trajectories.append((tuple(seq), x, y))
            return
        for a, tf in decisions:
            nx, ny, nh, nv = step(x, y, h, v, a, tf)
            seq.append((a, tf))
            gen(depth + 1, nx, ny, nh, nv, seq)
            seq.pop()
    
    gen(0, 0, 0, 0, init_vel, [])
    
    # Group by endpoint (quantized to 5px grid)
    endpoint_groups = defaultdict(list)
    for seq, fx, fy in trajectories:
        key = (round(fx / 5), round(fy / 5))
        endpoint_groups[key].append(seq)
    
    n_endpoints = len(endpoint_groups)
    
    # For the top-20 most-reached endpoints, check prefix sharing
    top_endpoints = sorted(endpoint_groups.items(), key=lambda kv: -len(kv[1]))[:20]
    
    print(f"\n  v={init_vel}, horizon={horizon}, {n_dec} options/tick")
    print(f"  Total trajectories: {len(trajectories):,}")
    print(f"  Distinct endpoints (5px grid): {n_endpoints:,}")
    
    # For each prefix length, count distinct prefixes among top-20 endpoint trajectories
    print(f"\n  Prefix sharing among top-20 endpoints:")
    print(f"  {'prefix_len':>10s}  {'distinct_prefixes':>17s}  {'sharing_ratio':>13s}")
    
    all_top_seqs = []
    for key, seqs in top_endpoints:
        all_top_seqs.extend(seqs[:50])  # sample up to 50 per endpoint
    
    for prefix_len in range(1, horizon + 1):
        prefixes = set(seq[:prefix_len] for seq in all_top_seqs)
        ratio = len(all_top_seqs) / len(prefixes) if len(prefixes) > 0 else 0
        print(f"  {prefix_len:10d}  {len(prefixes):17,d}  {ratio:13.1f}×")

# Can only do small horizons due to exponential
for init_vel in [0, 8]:
    analyze_sharing(init_vel, 5, [-1.0, 0.0, 1.0])  # 3 turn options for tractability

# ============================================================
# 6. Endpoint-only vs full trajectory
# ============================================================
print("\n" + "=" * 70)
print("6. ENDPOINT REACHABILITY — WHAT FRACTION OF BBOX IS REACHABLE?")
print("=" * 70)

for init_vel in [0, 4, 8]:
    for horizon in [5, 10]:
        results = count_reachable(init_vel, horizon, TURN_OPTIONS_COARSE, grid_px=5.0)
        r = results[-1]
        x_span = r['x_range'][1] - r['x_range'][0]
        y_span = r['y_range'][1] - r['y_range'][0]
        bbox_cells = max(1, int((x_span / 5) * (y_span / 5)))
        fill_ratio = r['n_positions'] / bbox_cells if bbox_cells > 0 else 0
        print(f"  v={init_vel} t={horizon:2d}: {r['n_positions']:,d} reachable positions (5px grid)  "
              f"bbox={bbox_cells:,d} cells  fill={fill_ratio:.1%}")

print("\nDone.")
