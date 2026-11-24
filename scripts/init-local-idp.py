#!/usr/bin/env python3
"""Generate synthetic Keycloak realm and random local-only credentials; never commit outputs."""
import json, os, secrets
from pathlib import Path
root=Path(__file__).resolve().parents[1]/'.local-idp'
root.mkdir(mode=0o700,exist_ok=True)
realm_file=root/'realm.json'
if realm_file.exists():
    raise SystemExit('Realm already generated; reuse it (do not regenerate credentials for a running IdP).')
identities=[('customer-a','00000000-0000-4000-8000-000000000011','CUSTOMER'),('customer-b','00000000-0000-4000-8000-000000000012','CUSTOMER'),('operations','00000000-0000-4000-8000-000000000091','OPERATIONS'),('admin','00000000-0000-4000-8000-000000000092','ADMIN'),('auditor','00000000-0000-4000-8000-000000000093','AUDITOR')]
users=[]
for username,subject,role in identities:
    users.append(dict(id=subject,username=username,enabled=True,emailVerified=True,firstName='Synthetic',lastName=role,email=username+'@example.invalid',realmRoles=[role],credentials=[dict(type='password',value=secrets.token_urlsafe(24),temporary=False)]))
realm=dict(realm='finbridge',enabled=True,sslRequired='none',registrationAllowed=False,accessTokenLifespan=300,roles={'realm':[{'name':r} for r in ['CUSTOMER','OPERATIONS','AUDITOR','ADMIN']]},users=users,clients=[dict(clientId='finbridge-cli',enabled=True,protocol='openid-connect',publicClient=True,standardFlowEnabled=False,directAccessGrantsEnabled=True,fullScopeAllowed=True,protocolMappers=[dict(name='finbridge-roles',protocol='openid-connect',protocolMapper='oidc-usermodel-realm-role-mapper',config={'claim.name':'roles','jsonType.label':'String','multivalued':'true','access.token.claim':'true','id.token.claim':'false','userinfo.token.claim':'false'}),dict(name='finbridge-audience',protocol='openid-connect',protocolMapper='oidc-audience-mapper',config={'included.custom.audience':'finbridge-api','access.token.claim':'true','id.token.claim':'false'})])])
fd=os.open(realm_file,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as f:json.dump(realm,f,indent=2)
# Docker Desktop shares host ownership; make the import file readable inside the container.
# Parent directory remains 0700 and ignored by Git; contents are sandbox-only.
realm_file.chmod(0o644)
print('Generated .local-idp/realm.json with random synthetic credentials; never commit or share it.')
