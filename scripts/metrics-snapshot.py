#!/usr/bin/env python3
"""Local read-only dashboard snapshot using an operations token; writes ignored target/ HTML."""
import html, json, subprocess, sys
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler
from urllib.error import HTTPError
from datetime import datetime, timezone
ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:8080'
NAMES = ('finbridge.transfers', 'finbridge.transfer.accepted', 'finbridge.transfer.rejected',
         'finbridge.outbox.pending', 'finbridge.outbox.dlq', 'finbridge.outbox.oldest.age.seconds',
         'finbridge.provider.callback.dlq', 'finbridge.reconciliation.mismatches',
         'finbridge.ledger.invariant.failure', 'finbridge.http.error.ratio', 'http.server.requests',
         'finbridge.outbox.dispatch.duration', 'finbridge.provider.callback.duration',
         'finbridge.outbox.delivery', 'finbridge.provider.callbacks')

def main():
    token = subprocess.run([sys.executable, str(ROOT/'scripts/local-token.py'), 'operations'], check=True,
                           capture_output=True, text=True, timeout=20).stdout.strip()
    opener = build_opener(ProxyHandler({}))
    rows = []
    def get(path):
        with opener.open(Request(BASE+path, headers={'Authorization': 'Bearer '+token}), timeout=10) as r:
            return json.load(r)
    for metric in NAMES:
        path = '/actuator/metrics/'+metric
        try:
            m = get(path)
            if metric == 'finbridge.transfers':
                for state in ('RESERVED','SUBMITTED','IN_REVIEW','SETTLED','REJECTED'):
                    x = get(path+'?tag=status:'+state)
                    rows.append((metric+' / '+state, str(x['measurements'][0]['value'])))
            else:
                rows.append((metric, ', '.join(f"{x['statistic']}: {x['value']:.6g}" for x in m['measurements'])))
        except HTTPError as e:
            if e.code != 404: raise
            rows.append((metric, 'Sin muestras todavía'))
    out = ROOT/'target'/'metrics-snapshot.html'; out.parent.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).isoformat(timespec='seconds')
    table = ''.join(f'<tr><td>{html.escape(k)}</td><td>{html.escape(v)}</td></tr>' for k,v in rows)
    out.write_text(f'<!doctype html><html lang="es"><meta charset="utf-8"><title>FinBridge Operations</title><style>body{{font:16px system-ui;margin:3rem;background:#111827;color:#f3f4f6}}table{{border-collapse:collapse;width:100%}}td,th{{padding:12px;border-bottom:1px solid #374151;text-align:left}}td:first-child{{color:#93c5fd}}</style><h1>FinBridge | Operations snapshot</h1><p>{stamp} UTC — datos observados en sandbox local</p><table><thead><tr><th>Métrica</th><th>Valor</th></tr></thead><tbody>{table}</tbody></table><p>Error ratio: HTTP 5xx / solicitudes API del proceso. Los timers muestran COUNT, TOTAL_TIME y MAX; no representan percentiles medidos.</p></html>', encoding='utf-8')
    print(out)

if __name__ == '__main__':
    try: main()
    except Exception as e:
        print('Snapshot failed: '+type(e).__name__, file=sys.stderr); sys.exit(1)
