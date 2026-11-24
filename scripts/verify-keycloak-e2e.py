#!/usr/bin/env python3
"""FinBridge local-only acceptance test. Uses actual Keycloak access tokens and HTTP API."""
import argparse
import json
import subprocess
import sys
import uuid
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:8080'
ISSUER = 'http://localhost:8180/realms/finbridge'
CUSTOMER_A = '00000000-0000-4000-8000-000000000011'
CUSTOMER_B = '00000000-0000-4000-8000-000000000012'

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

HTTP = build_opener(ProxyHandler({}), NoRedirect())

class Failure(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Failure(message)


def token(user):
    try:
        proc = subprocess.run([sys.executable, str(ROOT / 'scripts/local-token.py'), user],
                              capture_output=True, text=True, timeout=20, check=True)
    except (subprocess.SubprocessError, OSError):
        raise Failure(f'No se pudo obtener token para {user}; revisar Keycloak y realm local.') from None
    value = proc.stdout.strip()
    require(value.count('.') == 2 and not any(c.isspace() for c in value), f'Token inválido para {user}')
    return value


def request(label, method, path, expected, bearer=None, body=None, key=None, correlation=None, base=BASE):
    cid = correlation or str(uuid.uuid4())
    headers = {'Accept': 'application/json, application/problem+json', 'X-Correlation-ID': cid}
    if bearer is not None:
        headers['Authorization'] = 'Bearer ' + bearer
    if key is not None:
        headers['Idempotency-Key'] = key
    data = None
    if body is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(body, separators=(',', ':')).encode()
    req = Request(base + path, data=data, headers=headers, method=method)
    try:
        response = HTTP.open(req, timeout=15)
    except HTTPError as exc:
        response = exc
    except (URLError, OSError) as exc:
        raise Failure(f'{label}: no se pudo conectar ({type(exc).__name__})') from None
    with response:
        status = response.code
        require(status == expected, f'{label}: HTTP {status}, esperado {expected}')
        content = response.read(1_000_001)
        require(len(content) <= 1_000_000, f'{label}: respuesta demasiado grande')
        try:
            result = json.loads(content, parse_float=Decimal)
        except (ValueError, UnicodeError):
            raise Failure(f'{label}: respuesta no JSON') from None
        require(isinstance(result, dict), f'{label}: respuesta JSON inesperada')
        if base == BASE:
            require(response.headers.get('X-Correlation-ID') == cid, f'{label}: cabecera de correlación incorrecta')
            if expected >= 400:
                require(response.headers.get_content_type() == 'application/problem+json', f'{label}: no es Problem Details')
                require(result.get('status') == expected and result.get('correlationId') == cid,
                        f'{label}: Problem Details inconsistente')
    print(f'PASS {label}: HTTP {status}', flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-sandbox', action='store_true', help='Confirma que se pueden crear cuentas y movimientos sintéticos persistentes')
    args = parser.parse_args()
    if not args.confirm_sandbox:
        parser.error('Ejecutar únicamente en el sandbox local con --confirm-sandbox')
    run = 'e2e-' + uuid.uuid4().hex
    print('Run:', run, flush=True)
    health = request('Health', 'GET', '/actuator/health', 200)
    require(health.get('status') == 'UP', 'Aplicación no está UP')
    discovery = request('OIDC discovery', 'GET', '/.well-known/openid-configuration', 200, base=ISSUER)
    require(discovery.get('issuer') == ISSUER, 'Issuer del IdP no coincide')
    ops, admin, customer, other, auditor = (token(u) for u in ('operations', 'admin', 'customer-a', 'customer-b', 'auditor'))
    print('PASS Obtención de 5 tokens reales del IdP (no se imprimen)', flush=True)

    def open_account(label, owner):
        r = request(label, 'POST', '/api/v1/accounts', 201, ops, {'customerId': owner, 'currency': 'COP'})
        require(r.get('customerId') == owner and r.get('status') == 'ACTIVE' and r.get('currency') == 'COP', f'{label}: cuenta incorrecta')
        return str(uuid.UUID(r['id']))

    a = open_account('Apertura A', CUSTOMER_A)
    b = open_account('Apertura B', CUSTOMER_A)
    c = open_account('Apertura C (otro titular)', CUSTOMER_B)
    require(len({a,b,c}) == 3, 'IDs de cuentas no son únicos')
    print(f'Cuentas sintéticas: A={a} B={b} C={c}', flush=True)

    def balance(label, account, bearer, expected):
        r = request(label, 'GET', f'/api/v1/accounts/{account}/balance', 200, bearer)
        require(r.get('accountId') == account and r.get('currency') == 'COP' and Decimal(str(r.get('availableBalance'))) == Decimal(expected), f'{label}: saldo incorrecto')

    balance('Saldo inicial A', a, customer, '0')
    balance('Saldo inicial B', b, customer, '0')
    balance('Saldo inicial C', c, other, '0')
    request('Sin JWT', 'GET', f'/api/v1/accounts/{a}/balance', 401)
    request('JWT inválido', 'GET', f'/api/v1/accounts/{a}/balance', 401, 'invalid.token.value')
    request('Saldo ajeno', 'GET', f'/api/v1/accounts/{a}/balance', 403, other)
    funding = {'amount': 100000, 'reason': 'Fondeo sintetico E2E'}
    request('Fondeo por cliente denegado', 'POST', f'/api/v1/admin/accounts/{a}/opening-balance', 403, customer, funding, run+'-fund')
    f = request('Fondeo administrativo', 'POST', f'/api/v1/admin/accounts/{a}/opening-balance', 201, admin, funding, run+'-fund')
    require(f.get('accountId') == a and Decimal(str(f.get('amount'))) == Decimal(100000) and f.get('currency') == 'COP', 'Fondeo inconsistente')
    balance('Saldo A fondeado', a, customer, '100000')

    body = {'sourceAccountId': a, 'destinationAccountId': b, 'amount': 25000, 'currency': 'COP'}
    path = '/api/v1/transfers'
    request('Auditor no puede transferir', 'POST', path, 403, auditor, body, run+'-aud')
    request('Origen ajeno', 'POST', path, 403, other, body, run+'-other')
    request('Destino ajeno', 'POST', path, 403, customer, dict(body, destinationAccountId=c), run+'-foreign')
    request('ownerId inyectado', 'POST', path, 400, customer, dict(body, ownerId=CUSTOMER_A), run+'-owner')
    request('Fondos insuficientes', 'POST', path, 422, customer, dict(body, amount=100001), run+'-funds')
    cid = run + '-transfer'
    key = run + '-key'
    first = request('Transferencia autorizada', 'POST', path, 201, customer, body, key, cid)
    transfer = str(uuid.UUID(first['id']))
    require(first.get('status') == 'COMPLETED' and first.get('correlationId') == cid and Decimal(str(first.get('amount'))) == Decimal(25000) and first.get('currency') == 'COP', 'Transferencia inconsistente')
    replay = request('Replay idempotente', 'POST', path, 201, customer, body, key)
    require(replay.get('id') == transfer and replay.get('correlationId') == cid, 'Replay no conserva transferencia/correlación original')
    request('Conflicto de idempotencia', 'POST', path, 409, customer, dict(body, amount=25001), key)
    request('Consulta de transferencia propia', 'GET', f'{path}/{transfer}', 200, customer)
    request('Consulta de transferencia ajena', 'GET', f'{path}/{transfer}', 403, other)
    balance('Saldo final A', a, customer, '75000')
    balance('Saldo final B', b, customer, '25000')
    balance('Saldo final C sin cambios', c, other, '0')
    audit_path = f'/api/v1/audit/transfers/{transfer}'
    request('Cliente no puede consultar auditoría', 'GET', audit_path, 403, customer)
    audit = request('Auditor reconstruye transferencia', 'GET', audit_path, 200, auditor)
    require(audit.get('transferId') == transfer and audit.get('status') == 'COMPLETED' and audit.get('correlationId') == cid, 'Cabecera de auditoría inconsistente')
    journal = str(uuid.UUID(audit['journalId']))
    events = [e for e in audit['events'] if e.get('eventType') == 'INTERNAL_TRANSFER_COMPLETED']
    require(len(events) == 1 and events[0].get('actorId') == CUSTOMER_A and events[0].get('correlationId') == cid, 'Evento de auditoría duplicado o incorrecto')
    postings = audit['postings']
    require(len(postings) == 2, 'Se requieren exactamente dos postings')
    actual = {(p['accountId'], p['side'], Decimal(str(p['amount'])), p['currency']) for p in postings}
    require(actual == {(a, 'DEBIT', Decimal(25000), 'COP'), (b, 'CREDIT', Decimal(25000), 'COP')}, 'Asiento contable inconsistente')
    print(f'PASS Ledger: DEBIT 25000 COP / CREDIT 25000 COP, journalId={journal}', flush=True)
    print(f'PASS Auditoría: actor, correlationId={cid}, único evento; transferId={transfer}', flush=True)
    print('E2E SUCCESS — saldos A=75000, B=25000, C=0 COP. Datos sintéticos conservados.', flush=True)

if __name__ == '__main__':
    try:
        main()
    except (Failure, KeyError, ValueError, TypeError, ArithmeticError) as exc:
        # No imprimir cuerpos, tokens ni valores de entrada en errores inesperados.
        print('E2E FAILED — ' + (str(exc) if isinstance(exc, Failure) else f'Respuesta incompatible ({type(exc).__name__})'), file=sys.stderr)
        sys.exit(1)
