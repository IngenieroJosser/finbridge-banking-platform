#!/usr/bin/env python3
"""Local-only financial acceptance; creates new synthetic records per run, never deletes them."""
import argparse
import csv
import importlib.util
import io
import json
import subprocess
import sys
import time
import uuid
from collections import Counter
from decimal import Decimal
from pathlib import Path
from urllib.request import Request
from urllib.error import HTTPError, URLError

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('internal_e2e', ROOT / 'scripts/verify-keycloak-e2e.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)
require, token, request = common.require, common.token, common.request
CLEARING = '00000000-0000-4000-8000-000000000003'
CASH = '00000000-0000-4000-8000-000000000002'
SYSTEM = '00000000-0000-4000-8000-000000000094'
PATH = '/api/v1/external-transfers'
AMOUNT = Decimal('10000')


def raw(label, path, bearer, expected=200, payload=None, content_type=None):
    cid = str(uuid.uuid4())
    headers = {'Authorization': 'Bearer ' + bearer, 'X-Correlation-ID': cid}
    if content_type:
        headers['Content-Type'] = content_type
    req = Request(common.BASE + path, data=payload, headers=headers,
                  method='POST' if payload is not None else 'GET')
    try:
        response = common.HTTP.open(req, timeout=20)
    except HTTPError as exc:
        response = exc
    with response:
        require(response.code == expected, f'{label}: HTTP {response.code}, esperado {expected}')
        require(response.headers.get('X-Correlation-ID') == cid, f'{label}: correlationId incorrecto')
        data = response.read(8_000_001)
        require(len(data) <= 8_000_000, f'{label}: respuesta demasiado grande')
    return data


def upload(label, data, bearer):
    boundary = 'finbridge-' + uuid.uuid4().hex
    payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.csv"\r\n'
               'Content-Type: text/csv\r\n\r\n').encode() + data + f'\r\n--{boundary}--\r\n'.encode()
    batch = json.loads(raw(label, '/api/v1/reconciliations/import', bearer, 202, payload,
                           'multipart/form-data; boundary=' + boundary))
    require(batch['status'] == 'COMPLETED', f'{label}: lote no completado')
    return batch


def metrics(bearer):
    names = {'SETTLED': 'finbridge.transfers?tag=status:SETTLED',
             'REJECTED': 'finbridge.transfers?tag=status:REJECTED',
             'IN_REVIEW': 'finbridge.transfers?tag=status:IN_REVIEW',
             'dlq': 'finbridge.outbox.dlq', 'pending': 'finbridge.outbox.pending',
             'mismatches': 'finbridge.reconciliation.mismatches',
             'invariant_failure': 'finbridge.ledger.invariant.failure',
             'http_error_ratio': 'finbridge.http.error.ratio'}
    return {key: json.loads(raw('Metric '+key, '/actuator/metrics/'+name, bearer))['measurements'][0]['value']
            for key, name in names.items()}


def delivery_count(bearer, outcome):
    # A counter does not exist until its first observation. Check the unfiltered
    # meter and its advertised outcome tags before querying a new tag.
    data = json.loads(raw('Metric list', '/actuator/metrics', bearer))
    if 'finbridge.outbox.delivery' not in data['names']:
        return 0
    meter = json.loads(raw('Delivery', '/actuator/metrics/finbridge.outbox.delivery', bearer))
    if not any(t['tag'] == 'outcome' and outcome in t['values'] for t in meter['availableTags']):
        return 0
    values = json.loads(raw('Delivery '+outcome, '/actuator/metrics/finbridge.outbox.delivery?tag=outcome:'+outcome, bearer))
    return next(v['value'] for v in values['measurements'] if v['statistic'] == 'COUNT')


def verify_audit(audit, transfer, account, status, cid):
    require(audit['transferId'] == transfer and audit['status'] == status and audit['correlationId'] == cid,
            'Cabecera de auditoría inconsistente')
    groups = {}
    for p in audit['postings']:
        require(p['currency'] == 'COP' and Decimal(str(p['amount'])) == AMOUNT, 'Posting inválido')
        groups.setdefault(p['journalId'], []).append((p['accountId'], p['side']))
    expected = [frozenset(((account, 'DEBIT'), (CLEARING, 'CREDIT')))]
    if status != 'IN_REVIEW':
        expected.append(frozenset(((CLEARING, 'DEBIT'), (CASH if status == 'SETTLED' else account, 'CREDIT'))))
    require(set(groups) == set(audit['journalIds']) and all(len(v) == 2 for v in groups.values())
            and Counter(frozenset(v) for v in groups.values()) == Counter(expected), 'Journals/postings incorrectos')
    events = audit['events']
    require(all(e['correlationId'] == cid for e in events), 'Correlación perdida')
    wanted = ['RESERVED', 'SUBMITTED', status] if status != 'IN_REVIEW' else ['RESERVED', 'IN_REVIEW']
    require([e['eventType'] for e in events] == ['EXTERNAL_TRANSFER_'+s for s in wanted], 'Secuencia de eventos incorrecta')
    require(events[0]['actorId'] == common.CUSTOMER_A and all(e['actorId'] == SYSTEM for e in events[1:]), 'Actor incorrecto')
    return audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-sandbox', action='store_true')
    args = parser.parse_args()
    if not args.confirm_sandbox:
        parser.error('Solo sandbox local; requiere --confirm-sandbox')
    run = 's4-' + uuid.uuid4().hex
    print('Run:', run, flush=True)
    output = ROOT / 'target' / run
    output.mkdir(parents=True, exist_ok=False)
    for probe in ('health', 'health/readiness', 'health/liveness'):
        require(request(probe, 'GET', '/actuator/'+probe, 200)['status'] == 'UP', 'Probe no disponible')
    require(request('OIDC', 'GET', '/.well-known/openid-configuration', 200, base=common.ISSUER)['issuer'] == common.ISSUER, 'Issuer incorrecto')
    ops, admin, customer, auditor = (token(u) for u in ('operations', 'admin', 'customer-a', 'auditor'))
    before = metrics(ops)
    deliveries_before = {k: delivery_count(ops, k) for k in ('PUBLISHED','RETRY','DLQ')}
    require(before['invariant_failure'] == 0 and before['pending'] == 0, 'Sandbox con pendientes o asientos desbalanceados; revisar primero')
    account = str(uuid.UUID(request('Cuenta nueva', 'POST', '/api/v1/accounts', 201, ops,
                                    {'customerId': common.CUSTOMER_A, 'currency': 'COP'})['id']))
    print('Cuenta sintética:', account, flush=True)
    request('Fondeo', 'POST', f'/api/v1/admin/accounts/{account}/opening-balance', 201, admin,
            {'amount': 100000, 'reason': 'Synthetic Sprint 4 acceptance'}, run+'-fund')

    def balance(expected):
        r = request('Saldo', 'GET', f'/api/v1/accounts/{account}/balance', 200, customer)
        require(r['accountId'] == account and r['currency'] == 'COP' and Decimal(str(r['availableBalance'])) == Decimal(expected), 'Saldo inesperado')

    def scenario(bank, status, expected):
        key, cid = run+'-'+bank, run+'-'+status
        body = {'sourceAccountId': account, 'providerBankCode': bank,
                'destinationAccountReference': 'SYNTH-'+status, 'amount': int(AMOUNT), 'currency': 'COP'}
        r = request('Reserva '+status, 'POST', PATH, 202, customer, body, key, cid)
        transfer = str(uuid.UUID(r['id']))
        print(f'{status} transferId={transfer}', flush=True)
        require(r['status'] == 'RESERVED' and r['correlationId'] == cid and Decimal(str(r['amount'])) == AMOUNT, 'Reserva incorrecta')
        deadline = time.monotonic()+100
        while True:
            actual = request('Estado '+status, 'GET', PATH+'/'+transfer, 200, customer)['status']
            if actual == status:
                break
            require(actual in ('RESERVED','SUBMITTED') and time.monotonic() < deadline,
                    f'{status}: estado inesperado o timeout ({actual}); revisar worker y transferId impreso')
            time.sleep(2)
        replay = request('Replay '+status, 'POST', PATH, 202, customer, body, key)
        require(replay['id'] == transfer and replay['status'] == status and replay['correlationId'] == cid, 'Replay inconsistente')
        balance(expected)
        audit = verify_audit(request('Auditoría '+status, 'GET', '/api/v1/audit/transfers/'+transfer, 200, auditor), transfer, account, status, cid)
        (output / (status.lower()+'.json')).write_text(json.dumps(audit, default=str, indent=2), encoding='utf-8')
        print(f'PASS {status}: saldo={expected}, journals={len(audit["journalIds"])}', flush=True)
        return audit

    balance(100000)
    settled = scenario('SANDBOX_BANK', 'SETTLED', 90000)
    rejected = scenario('SANDBOX_REJECT', 'REJECTED', 90000)
    uncertain = scenario('SANDBOX_TIMEOUT', 'IN_REVIEW', 80000)
    after_external = metrics(ops)
    require(all(after_external[k] == before[k]+1 for k in ('SETTLED','REJECTED','IN_REVIEW','dlq')) and after_external['pending'] == 0, 'Métricas de estados/DLQ inconsistentes')
    deliveries_after = {k: delivery_count(ops, k) for k in deliveries_before}
    require(all(deliveries_after[k]-deliveries_before[k] == v for k,v in {'PUBLISHED':2,'RETRY':4,'DLQ':1}.items()), 'Contadores de reintentos/publicación inconsistentes')
    print('PASS DLQ y estados: +2 publicaciones, +4 reintentos, +1 DLQ', flush=True)

    def verify_batch(label, data, expected_categories):
        batch = upload(label, data, ops)
        bid = str(uuid.UUID(batch['batchId']))
        base = '/api/v1/reconciliations/'+bid
        require(request('Resumen '+label, 'GET', base, 200, auditor) == batch, 'Resumen no coincide')
        total = batch['matched'] + batch['mismatched']
        items = []
        for page in range((total+1)//2):
            result = request('Página '+label, 'GET', base+f'/items?page={page}&size=2', 200, auditor)
            require(result['totalElements'] == total and result['page'] == page and result['size'] == 2, 'Paginación inconsistente')
            items.extend(result['content'])
        cats = Counter(i['category'] for i in items)
        require(len(items) == total and cats['MATCHED'] == batch['matched'], 'Contadores de conciliación inconsistentes')
        require(all(cats[k] >= v for k,v in expected_categories.items()), f'{label}: categorías esperadas ausentes')
        exported = raw('Export '+label, base+'/export', auditor)
        rows = list(csv.DictReader(io.StringIO(exported.decode('utf-8'))))
        require(len(rows) == total and Counter(r['category'] for r in rows) == cats, 'CSV exportado inconsistente')
        (output / (label+'.csv')).write_bytes(exported)
        print(f'PASS conciliación {label}: {bid} {dict(cats)}', flush=True)
        return batch

    fixture = (ROOT/'fixtures/reconciliation_sample.csv').read_bytes()
    fixture_batch = verify_batch('fixture', fixture, {'DUPLICATE_PROVIDER_ENTRY': 1})
    # Deliberately partial synthetic report with an invalid row: quarantine prevents inference of absent provider rows.
    report = io.StringIO(newline='')
    writer = csv.writer(report, lineterminator='\n')
    writer.writerow(next(csv.reader(io.StringIO(fixture.decode('utf-8')))))
    for audit, amount in ((settled, 10000), (rejected, 10001)):
        tid = audit['transferId']
        date = audit['events'][-1]['occurredAt'][:10]
        require(date == settled['events'][-1]['occurredAt'][:10], 'Cruce de fecha UTC; repetir en otro horario')
        writer.writerow(['SANDBOX_BANK', 'evt-'+tid, 'pay-'+tid, tid, amount, 'COP', audit['status'], date])
    writer.writerow(['invalid-row'])
    partial_batch = verify_batch('synthetic-partial', report.getvalue().encode(),
                                 {'MATCHED': 1, 'AMOUNT_MISMATCH': 1, 'MALFORMED_ROW': 1})
    balance(80000)
    for audit in (settled, rejected, uncertain):
        require(request('Auditoría sin cambios', 'GET', '/api/v1/audit/transfers/'+audit['transferId'], 200, auditor) == audit,
                'La conciliación alteró la evidencia financiera')
    after = metrics(ops)
    require(after['mismatches'] == before['mismatches']+fixture_batch['mismatched']+partial_batch['mismatched']
            and after['invariant_failure'] == 0 and after['pending'] == 0 and after['dlq'] == before['dlq']+1,
            'Métricas finales inconsistentes')
    subprocess.run([sys.executable, str(ROOT/'scripts/metrics-snapshot.py')], check=True, timeout=60)
    (output/'metrics-snapshot.html').write_bytes((ROOT/'target/metrics-snapshot.html').read_bytes())
    (output/'evidence.json').write_text(json.dumps({'run':run,'accountId':account,'result':'PASS',
        'availableBalance':80000,'settledAmount':10000,'reservedAmount':10000,
        'transferIds':{a['status']:a['transferId'] for a in (settled,rejected,uncertain)},
        'batches':[fixture_batch,partial_batch],'metricsBefore':before,'metricsAfter':after,
        'deliveryBefore':deliveries_before,'deliveryAfter':deliveries_after},indent=2), encoding='utf-8')
    print('SPRINT 4 E2E SUCCESS — disponible=80000, liquidado=10000, reservado=10000 COP.')
    print('Evidencia:', output)


if __name__ == '__main__':
    try:
        main()
    except (common.Failure, KeyError, ValueError, TypeError, ArithmeticError, OSError, URLError, subprocess.SubprocessError) as exc:
        print('SPRINT 4 E2E FAILED — '+(str(exc) if isinstance(exc, common.Failure) else type(exc).__name__), file=sys.stderr)
        sys.exit(1)
