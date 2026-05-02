import json, sys
path = sys.argv[1]
nb = json.load(open(path, encoding='utf-8'))
for i, c in enumerate(nb['cells']):
    if c['cell_type'] != 'code':
        continue
    for o in c.get('outputs', []):
        t = o.get('output_type')
        txt = ''
        if t == 'stream':
            txt = ''.join(o.get('text', []))
        elif t in ('execute_result', 'display_data'):
            d = o.get('data', {})
            if 'text/plain' in d:
                txt = ''.join(d['text/plain'])
        elif t == 'error':
            txt = '[ERROR] ' + o.get('ename', '') + ': ' + o.get('evalue', '')
        if txt.strip():
            print(f'-- cell {i} ({t}) --')
            print(txt.rstrip()[:2500])
            print()
