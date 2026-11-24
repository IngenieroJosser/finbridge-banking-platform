# 14 | Validación end-to-end y CI del Sprint 3

## Prueba de aceptación con el IdP local

Precondiciones: PostgreSQL y Keycloak de `compose.yaml` iniciados, realm sintético generado, aplicación FinBridge V6 en `127.0.0.1:8080`, issuer `http://localhost:8180/realms/finbridge`, audiencia `finbridge-api`, fondeo habilitado únicamente en el sandbox (`SANDBOX_FUNDING_ENABLED=true`). El script usa Python 3 estándar y `scripts/local-token.py`. No se admiten destinos remotos. La prueba crea tres cuentas y movimientos sintéticos persistentes; no modifica migraciones, no borra journals y no se debe ejecutar con datos reales.

```bash
python3 scripts/verify-keycloak-e2e.py --confirm-sandbox
```

El script exige los códigos HTTP, correlaciones, saldos exactos, identidad del actor y dos postings balanceados. Emplea UUID nuevos por ejecución y comprueba que el replay no duplica el débito ni el evento. No muestra tokens ni credenciales. Una condición incumplida finaliza con código distinto de cero; el marcador `E2E SUCCESS` se emite únicamente después de validar todas las condiciones. Los IDs sintéticos y correlationId permiten reconstruir el caso en los registros. No reutilizar estas identidades ni el password grant en producción.

| Operación | Resultado esperado |
|---|---|
| Health y discovery OIDC | 200 |
| Aperturas A/B (cliente A), C (cliente B) | 201; saldos cero |
| Sin token / token inválido | 401 Problem Details |
| Lectura ajena, rol indebido y transferencia con titular ajeno | 403 Problem Details |
| Fondeo administrativo A | 201; 100.000 COP |
| ownerId adicional | 400 |
| Fondos insuficientes | 422 |
| Transferencia A → B de 25.000 COP | 201 COMPLETED |
| Replay / cuerpo distinto con misma clave | 201 mismo ID / 409 |
| Saldos finales | A 75.000; B 25.000; C 0 COP |
| Auditoría | Un evento de confirmación, actor correcto, correlación original, dos postings DEBIT/CREDIT de 25.000 COP |

## Integración continua

El workflow `.github/workflows/ci.yml` ejecuta Java 21, comprueba Docker y ejecuta `mvn -B --no-transfer-progress clean verify` en pushes a `main` y PR. Publica `target/surefire-reports/TEST-*.xml` como `finbridge-test-reports`. Las pruebas usan PostgreSQL real y fixture JWKS efímero sin depender del Keycloak local. La prueba end-to-end es un gate complementario local, no forma parte del workflow actual.

Antes de publicar, comprobar que `.env`, `.local-idp/`, `target/`, credenciales, datos reales y configuración personal Maven no se encuentran en los archivos rastreados ni en el historial. No publicar logs con secretos. Crear un repositorio vacío, enviar la rama `main`, comprobar la ejecución y configurar el check `verify` como requerido en las reglas de protección de `main`, si el plan y la configuración lo permiten. El mirror personal `~/.m2/settings-finbridge.xml` no debe incorporarse al proyecto.

## Evidencia de aceptación

- Maven local: 24 pruebas, 0 fallos, 0 errores, 0 omitidas (24/11/2026).
- E2E con Keycloak: requiere ejecución real y marcador `E2E SUCCESS`.
- CI remoto: requiere ejecución satisfactoria en GitHub Actions y reporte XML asociado al commit evaluado.
