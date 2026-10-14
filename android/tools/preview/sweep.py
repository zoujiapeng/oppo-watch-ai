#!/usr/bin/env python3
"""跑一组 run.mjs 参数，把增益统计打成表。用法：
    python3 sweep.py --radius 139,120,100,80 --modes wave,dotsonly --noglow 1
"""
import argparse, json, subprocess, sys, os

ap = argparse.ArgumentParser()
ap.add_argument('--sweep', default='radius')     # 逗号分隔要扫的 key（映射到 run.mjs 的参数名）
ap.add_argument('--values', default='')          # 逗号分隔的值
ap.add_argument('--modes', default='wave,dotsonly')
ap.add_argument('--bg', default='synthetic')
ap.add_argument('--extra', default='')           # 额外固定参数，如 "--noglow 1 --t 4"
ap.add_argument('--out', default='')
args = ap.parse_args()

here = os.path.dirname(os.path.abspath(__file__))
vals = [v for v in args.values.split(',') if v]
rows = []
for v in vals or ['']:
    cmd = ['node', 'run.mjs', '--bg', args.bg, '--modes', args.modes]
    if args.extra:
        cmd += args.extra.split()
    if v:
        cmd += ['--' + args.sweep, v]
    if args.out:
        cmd += ['--out', args.out]
    p = subprocess.run(cmd, cwd=here, capture_output=True, text=True)
    out = '\n'.join(l for l in p.stdout.splitlines() if not l.startswith('WARNING'))
    try:
        d = json.loads(out)
    except Exception:
        print('FAILED', args.sweep, v, p.stdout[-800:], p.stderr[-800:], file=sys.stderr)
        continue
    for r in d['rows']:
        rows.append((v, r))

hdr = (f"{args.sweep:>10} {'mode':<10} {'addMean':>8} {'peak':>6} {'hotpx':>7} {'W':>5} {'H':>5} "
       f"{'cyOff':>6} {'rim':>7} {'rimBot':>7}")
print(hdr)
print('-' * len(hdr))
for v, r in rows:
    print(f"{v:>10} {r['mode']:<10} {r['addMean']:>8.4f} {r['addPeak']:>6.3f} {r['hotPx']:>7} "
          f"{r['clusterW']:>5} {r['clusterH']:>5} {str(r['clusterCyOff']):>6} "
          f"{r.get('rimAddMean', 0):>7.4f} {r.get('rimBottomAddMean', 0):>7.4f}")
