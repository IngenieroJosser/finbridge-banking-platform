#!/usr/bin/env python3
"""Local integration demo only: retrieve an access token from Keycloak, not from FinBridge."""
import json,sys,urllib.request,urllib.parse
from pathlib import Path
if len(sys.argv)!=2:raise SystemExit('Usage: python3 scripts/local-token.py customer-a|customer-b|operations|admin|auditor')
realm=json.loads((Path(__file__).resolve().parents[1]/'.local-idp/realm.json').read_text())
user=next((u for u in realm['users'] if u['username']==sys.argv[1]),None)
if user is None:raise SystemExit('Unknown local identity')
body=urllib.parse.urlencode(dict(grant_type='password',client_id='finbridge-cli',username=user['username'],password=user['credentials'][0]['value'])).encode()
try:
    with urllib.request.urlopen('http://localhost:8180/realms/finbridge/protocol/openid-connect/token',data=body,timeout=10) as r:
        print(json.load(r)['access_token'])
except Exception:
    raise SystemExit('Local IdP token request failed; verify realm import and readiness.')
