import json, sys
for nb in sys.argv[1:]:
    print(f'== {nb} ==')
    cells = json.load(open(nb, encoding='utf-8'))['cells']
    for i, c in enumerate(cells):
        src = ''.join(c.get('source', []))
        first = src.split('\n', 1)[0][:120]
        print(f'  [{i}] {c["cell_type"]}: {first}')
