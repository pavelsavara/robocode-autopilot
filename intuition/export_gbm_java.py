"""Export trained GBM models to Java constant arrays for GbmTreeEnsemble.

Reads XGBoost (.json) or LightGBM (.txt) model files and produces:
  1. A Java class with static final arrays (featureIndex, threshold, etc.)
  2. A feature-mapping array (CSV column name → model input index)
  3. Test fixtures (100 random inputs + expected outputs)

Usage:
    python export_gbm_java.py fire_power
    python export_gbm_java.py fire_timing
    python export_gbm_java.py movement
    python export_gbm_java.py --all

Output: robot/src/main/java/cz/zamboch/distilled/<ModelName>Data.java
        robot/src/main/resources/<task>.bin
"""
from __future__ import annotations

import json
import struct
import sys
from pathlib import Path
from typing import Any

import numpy as np

MODEL_ROOT = Path(__file__).parent / 'models' / 'distill'
JAVA_OUT = Path(__file__).parent.parent / 'robot' / 'src' / 'main' / 'java' / 'cz' / 'zamboch' / 'distilled'
RES_OUT = Path(__file__).parent.parent / 'robot' / 'src' / 'main' / 'resources' / 'cz' / 'zamboch' / 'distilled'
FIXTURE_OUT = Path(__file__).parent.parent / 'robot' / 'src' / 'test' / 'resources' / 'distilled'


# ── XGBoost JSON parsing ──────────────────────────────────────────────

def _parse_xgb_json(model_path: Path) -> dict:
    """Parse XGBoost model saved via save_model(format='json')."""
    with open(model_path, 'r') as f:
        raw = json.load(f)

    learner = raw['learner']
    params = learner['learner_model_param']
    base_score_raw = params.get('base_score', '0.5')
    # XGBoost may store as '[1.3478941E0]' or '0.5'
    if isinstance(base_score_raw, str):
        base_score_raw = base_score_raw.strip('[]')
    base_score = float(base_score_raw)
    num_class = int(params.get('num_class', '0'))

    tree_info = learner['gradient_booster']['model']
    trees_raw = tree_info['trees']

    trees = []
    for tree_raw in trees_raw:
        nodes = _parse_xgb_tree(tree_raw)
        trees.append(nodes)

    return {
        'trees': trees,
        'base_score': base_score,
        'num_class': max(1, num_class),
    }


def _parse_xgb_tree(tree_raw: dict) -> list[dict]:
    """Parse a single XGBoost tree into a list of node dicts."""
    n_nodes = tree_raw['tree_param']['num_nodes']
    n_nodes = int(n_nodes)

    left_children = tree_raw['left_children']
    right_children = tree_raw['right_children']
    split_indices = tree_raw['split_indices']
    split_conditions = tree_raw['split_conditions']
    default_left = tree_raw.get('default_left', [1] * n_nodes)

    nodes = []
    for i in range(n_nodes):
        is_leaf = (left_children[i] == -1)
        nodes.append({
            'feature': -1 if is_leaf else split_indices[i],
            'threshold': float('nan') if is_leaf else split_conditions[i],
            'left': left_children[i],
            'right': right_children[i],
            'value': split_conditions[i] if is_leaf else 0.0,
            'default_left': bool(default_left[i]) if i < len(default_left) else True,
        })
    return nodes


# ── LightGBM text parsing ────────────────────────────────────────────

def _parse_lgbm_txt(model_path: Path) -> dict:
    """Parse LightGBM model saved via save_model (text format)."""
    text = model_path.read_text()
    lines = text.split('\n')

    # Parse header
    num_class = 1
    num_trees = 0
    for line in lines:
        if line.startswith('num_class='):
            num_class = int(line.split('=')[1])
        if line.startswith('num_trees='):
            # LightGBM: num_trees = total trees / num_class for multi-class
            # but in the file it's already the total count
            pass
        if line.startswith('Tree='):
            num_trees += 1

    trees = []
    i = 0
    while i < len(lines):
        if lines[i].startswith('Tree='):
            tree_nodes, i = _parse_lgbm_tree(lines, i)
            trees.append(tree_nodes)
        else:
            i += 1

    return {
        'trees': trees,
        'base_score': 0.0,  # LightGBM doesn't use base_score in same way
        'num_class': num_class,
    }


def _parse_lgbm_tree(lines: list[str], start: int) -> tuple[list[dict], int]:
    """Parse a single LightGBM tree block."""
    i = start + 1  # skip 'Tree=N' line
    meta = {}
    while i < len(lines) and not lines[i].startswith('Tree=') and lines[i].strip():
        if '=' in lines[i]:
            key, val = lines[i].split('=', 1)
            meta[key] = val
        i += 1

    num_leaves = int(meta.get('num_leaves', '1'))
    num_internal = num_leaves - 1

    # Parse arrays
    def parse_int_array(key):
        s = meta.get(key, '')
        return [int(x) for x in s.split()] if s else []

    def parse_float_array(key):
        s = meta.get(key, '')
        return [float(x) for x in s.split()] if s else []

    split_feature = parse_int_array('split_feature')
    threshold = parse_float_array('threshold')
    left_child = parse_int_array('left_child')
    right_child = parse_int_array('right_child')
    leaf_value = parse_float_array('leaf_value')

    # LightGBM uses negative indices for leaves: leaf idx = ~node_idx
    # Internal nodes: 0..num_internal-1
    # Leaves: encoded as ~leaf_idx in left_child/right_child
    total_nodes = num_internal + num_leaves
    nodes = []

    # First add internal nodes
    for j in range(num_internal):
        nodes.append({
            'feature': split_feature[j] if j < len(split_feature) else -1,
            'threshold': threshold[j] if j < len(threshold) else float('nan'),
            'left': left_child[j] if j < len(left_child) else -1,
            'right': right_child[j] if j < len(right_child) else -1,
            'value': 0.0,
        })

    # Then add leaf nodes
    for j in range(num_leaves):
        nodes.append({
            'feature': -1,
            'threshold': float('nan'),
            'left': -1,
            'right': -1,
            'value': leaf_value[j] if j < len(leaf_value) else 0.0,
        })

    # Fix child references: negative values are leaf references
    for j in range(num_internal):
        if nodes[j]['left'] < 0:
            nodes[j]['left'] = num_internal + (~nodes[j]['left'])
        if nodes[j]['right'] < 0:
            nodes[j]['right'] = num_internal + (~nodes[j]['right'])

    return nodes, i


# ── Common export ─────────────────────────────────────────────────────

def export_model(task: str):
    """Export a trained model to Java arrays."""
    task_dir = MODEL_ROOT / task

    # Read feature_cols from metrics.json or summary.json
    metrics_path = task_dir / 'metrics.json'
    summary_path = MODEL_ROOT / 'summary.json'

    if metrics_path.exists():
        with open(metrics_path) as f:
            task_meta = json.load(f)
    elif summary_path.exists():
        with open(summary_path) as f:
            summary = json.load(f)
        if task not in summary:
            print(f"ERROR: Task '{task}' not found")
            return
        task_meta = summary[task]
    else:
        print(f"ERROR: No metrics found for {task}")
        return

    feature_cols = task_meta['feature_cols']

    # --- GUARD: Verify feature_cols.json matches ---
    fc_path = task_dir / 'feature_cols.json'
    if fc_path.exists():
        import hashlib
        with open(fc_path) as f:
            saved_cols = json.load(f)
        if saved_cols != feature_cols:
            print("ERROR: feature_cols.json does not match metrics.json!")
            print(f"  metrics.json has {len(feature_cols)} features, feature_cols.json has {len(saved_cols)}")
            for i, (a, b) in enumerate(zip(feature_cols, saved_cols)):
                if a != b:
                    print(f"  First diff at index {i}: metrics={a}, file={b}")
                    break
            sys.exit(1)
        print(f"  Feature order verified: {len(feature_cols)} columns, "
              f"hash={hashlib.sha256(','.join(feature_cols).encode()).hexdigest()[:16]}")

    # Determine model format
    json_path = task_dir / 'model.json'
    txt_path = task_dir / 'model.txt'

    if json_path.exists():
        print(f"Parsing XGBoost JSON: {json_path} ({json_path.stat().st_size / 1e6:.1f} MB)")
        model_data = _parse_xgb_json(json_path)
    elif txt_path.exists():
        print(f"Parsing LightGBM text: {txt_path} ({txt_path.stat().st_size / 1e6:.1f} MB)")
        model_data = _parse_lgbm_txt(txt_path)
    else:
        print(f"ERROR: No model file found in {task_dir}")
        return

    trees = model_data['trees']
    base_score = model_data['base_score']
    num_class = model_data['num_class']

    # Find max nodes across all trees
    max_nodes = max(len(t) for t in trees)
    n_trees = len(trees)

    print(f"  Trees: {n_trees}, max nodes/tree: {max_nodes}, "
          f"classes: {num_class}, features: {len(feature_cols)}")

    total_nodes = sum(len(t) for t in trees)
    print(f"  Total nodes: {total_nodes:,} (no padding)")

    # Serialize to compact binary
    binary_data = _serialize_trees(n_trees, base_score, num_class, trees)
    print(f"  Binary size: {len(binary_data):,} bytes")

    # Base64 encode
    import base64
    b64 = base64.b64encode(binary_data).decode('ascii')
    print(f"  Base64 size: {len(b64):,} chars ({len(b64) // 1024} KB)")

    # Generate Java class with embedded Base64 strings
    class_name = _task_to_class_name(task)
    java_code = _generate_java_embedded(
        class_name, task, n_trees, base_score, num_class,
        feature_cols, total_nodes, b64
    )

    JAVA_OUT.mkdir(parents=True, exist_ok=True)
    java_path = JAVA_OUT / f'{class_name}Data.java'
    java_path.write_text(java_code, encoding='utf-8')
    print(f"  Java class: {java_path} ({len(java_code) // 1024} KB)")

    # Generate test fixtures
    _generate_fixtures(task, model_data, feature_cols, task_meta)


def _feature_hash(cols: list[str]) -> str:
    """SHA-256 prefix of comma-joined feature names for consistency checks."""
    import hashlib
    return hashlib.sha256(','.join(cols).encode()).hexdigest()[:16]


def _task_to_class_name(task: str) -> str:
    parts = task.split('_')
    return ''.join(p.capitalize() for p in parts)


def _task_to_resource_name(task: str) -> str:
    return task.replace('_', '-')


def _serialize_trees(n_trees: int, base_score: float, num_class: int,
                     trees: list[list[dict]]) -> bytes:
    """Serialize tree data to compact binary.

    Format: [nTrees:i32][baseScore:f32][numClass:i16]
    Per tree: [nNodes:i16][feat:i16*n][thresh:f32*n][left:i16*n][right:i16*n][leaf:f32*n]
    """
    import io
    buf = io.BytesIO()
    buf.write(struct.pack('>i', n_trees))
    buf.write(struct.pack('>f', float(base_score)))
    buf.write(struct.pack('>h', num_class))

    for tree_nodes in trees:
        n = len(tree_nodes)
        buf.write(struct.pack('>h', n))
        for node in tree_nodes:
            buf.write(struct.pack('>h', node['feature']))
        for node in tree_nodes:
            t = node.get('threshold', float('nan'))
            buf.write(struct.pack('>f', float(t) if not np.isnan(t) else float('nan')))
        for node in tree_nodes:
            buf.write(struct.pack('>h', node['left']))
        for node in tree_nodes:
            buf.write(struct.pack('>h', node['right']))
        for node in tree_nodes:
            buf.write(struct.pack('>f', float(node['value'])))

    return buf.getvalue()


# Max bytes per Java string constant (UTF-8 in class file). Leave headroom.
_JAVA_STRING_CHUNK = 60_000


def _generate_java_embedded(class_name: str, task: str,
                             n_trees: int, base_score: float, num_class: int,
                             feature_cols: list[str],
                             total_nodes: int, b64: str) -> str:
    """Generate a Java class with model data embedded as Base64 string constants.

    No file I/O needed — works inside Robocode's security sandbox.
    """
    feat_names_java = ', '.join(f'"{c}"' for c in feature_cols)

    # Split Base64 into chunks that fit in a single Java string constant
    chunks = [b64[i:i + _JAVA_STRING_CHUNK] for i in range(0, len(b64), _JAVA_STRING_CHUNK)]

    # Build chunk declarations
    chunk_decls = []
    chunk_refs = []
    for i, chunk in enumerate(chunks):
        chunk_decls.append(f'    private static final String D{i} = "{chunk}";')
        chunk_refs.append(f'D{i}')

    chunk_decls_str = '\n'.join(chunk_decls)
    if len(chunks) == 1:
        decode_expr = 'java.util.Base64.getDecoder().decode(D0)'
    else:
        # Use StringBuilder to prevent javac from folding string constants
        sb_lines = ['            StringBuilder sb = new StringBuilder(%d);' % len(b64)]
        for ref in chunk_refs:
            sb_lines.append(f'            sb.append({ref});')
        sb_lines.append('            byte[] raw = java.util.Base64.getDecoder().decode(sb.toString());')
        decode_expr = None  # handled inline

    if decode_expr:
        decode_block = f'            byte[] raw = {decode_expr};'
    else:
        decode_block = '\n'.join(sb_lines)

    return f'''package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.ml.GbmTreeEnsemble;

/**
 * Auto-generated model data for the {task} GBM model.
 * Tree data is embedded as Base64-encoded string constants — no file I/O,
 * works inside Robocode's security sandbox.
 *
 * <p>Model: {n_trees} trees, {total_nodes:,} total nodes, {num_class} class(es),
 * {len(feature_cols)} input features. Base score: {base_score}.</p>
 */
public final class {class_name}Data {{

    private {class_name}Data() {{}}

    /** Feature column names in model input order. */
    public static final String[] FEATURE_NAMES = {{
        {feat_names_java}
    }};

    public static final int N_TREES = {n_trees};
    public static final double BASE_SCORE = {base_score};
    public static final int N_CLASSES = {num_class};

    /** SHA-256 prefix of the comma-joined feature names. Export-time consistency check. */
    public static final String FEATURE_ORDER_HASH = "{_feature_hash(feature_cols)}";

    // Base64-encoded tree data (split to stay under 64KB per string constant)
{chunk_decls_str}

    /**
     * Decode and load the tree ensemble from embedded Base64 data.
     * Called once at robot startup. No file I/O.
     */
    public static GbmTreeEnsemble load() {{
        try {{
{decode_block}
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw);

            int nTrees = bb.getInt();
            double baseScore = bb.getFloat();
            int nClasses = bb.getShort();

            int[] offsets = new int[nTrees];
            int totalNodes = 0;

            int[] featureIndex;
            double[] threshold;
            int[] leftChild;
            int[] rightChild;
            double[] leafValue;

            // First pass: compute total nodes
            int pos = bb.position();
            for (int t = 0; t < nTrees; t++) {{
                int n = bb.getShort();
                totalNodes += n;
                bb.position(bb.position() + n * 14); // skip: 2+4+2+2+4 bytes per node
            }}

            // Allocate flat arrays
            featureIndex = new int[totalNodes];
            threshold = new double[totalNodes];
            leftChild = new int[totalNodes];
            rightChild = new int[totalNodes];
            leafValue = new double[totalNodes];

            // Second pass: read data
            bb.position(pos);
            int offset = 0;
            for (int t = 0; t < nTrees; t++) {{
                int n = bb.getShort();
                offsets[t] = offset;
                for (int i = 0; i < n; i++) featureIndex[offset + i] = bb.getShort();
                for (int i = 0; i < n; i++) threshold[offset + i] = bb.getFloat();
                for (int i = 0; i < n; i++) leftChild[offset + i] = bb.getShort();
                for (int i = 0; i < n; i++) rightChild[offset + i] = bb.getShort();
                for (int i = 0; i < n; i++) leafValue[offset + i] = bb.getFloat();
                offset += n;
            }}

            return new GbmTreeEnsemble(nTrees, offsets,
                    featureIndex, threshold, leftChild, rightChild,
                    leafValue, baseScore, nClasses);
        }} catch (Exception e) {{
            throw new RuntimeException("Failed to decode {task} model", e);
        }}
    }}
}}
'''


def _generate_fixtures(task: str, model_data: dict,
                       feature_cols: list[str], task_meta: dict):
    """Generate 100 random test inputs + expected outputs for validation."""
    FIXTURE_OUT.mkdir(parents=True, exist_ok=True)

    rng = np.random.RandomState(42)
    n_features = len(feature_cols)
    n_samples = 100

    # Generate random feature vectors in reasonable ranges
    X_test = rng.randn(n_samples, n_features).astype(np.float64)
    # Scale to typical ranges: most features are in [-10, 800]
    X_test *= 100

    # Evaluate using our parsed trees
    trees = model_data['trees']
    base_score = model_data['base_score']
    num_class = model_data['num_class']

    predictions = []
    for x in X_test:
        if num_class <= 1:
            pred = base_score
            for tree_nodes in trees:
                pred += _eval_tree(tree_nodes, x)
            predictions.append([pred])
        else:
            class_sums = [base_score] * num_class
            for t, tree_nodes in enumerate(trees):
                cls = t % num_class
                class_sums[cls] += _eval_tree(tree_nodes, x)
            predictions.append(class_sums)

    fixture = {
        'task': task,
        'n_features': n_features,
        'n_samples': n_samples,
        'feature_cols': feature_cols,
        'inputs': X_test.tolist(),
        'expected_raw': predictions,
    }

    fixture_path = FIXTURE_OUT / f'{task}_fixtures.json'
    with open(fixture_path, 'w') as f:
        json.dump(fixture, f)
    print(f"  Fixtures: {fixture_path} ({n_samples} samples)")


def _eval_tree(nodes: list[dict], features: np.ndarray) -> float:
    """Evaluate a single tree on a feature vector (Python reference impl)."""
    idx = 0
    while True:
        node = nodes[idx]
        if node['feature'] < 0:
            return node['value']
        feat_val = features[node['feature']] if node['feature'] < len(features) else float('nan')
        if np.isnan(feat_val) or feat_val < node['threshold']:
            idx = node['left']
        else:
            idx = node['right']


# ── CLI ───────────────────────────────────────────────────────────────

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: python export_gbm_java.py <task|--all>")
        print("Tasks: fire_power, fingerprint")
        sys.exit(1)

    if sys.argv[1] == '--all':
        for task in ['fire_power', 'fire_timing', 'movement']:
            print(f"\n{'='*60}")
            print(f"Exporting: {task}")
            print(f"{'='*60}")
            export_model(task)
    else:
        export_model(sys.argv[1])
