# 13 | Sprint 3 — Acceso y calidad de servicio

## Alcance y estado

FB-007, FB-008 y FB-009 codificados sobre la entrega V5. V1–V5 no se modifican. Esta guía prevalece sobre las instrucciones históricas de seguridad de las guías 10–12. La compilación con las dependencias nuevas, Testcontainers, el IdP y el workflow remoto requieren verificación en el entorno de desarrollo; ningún resultado pendiente se declara aprobado.

## ADR-013 — JWT y autorización por titular

La API actúa exclusivamente como OAuth2 Resource Server. El IdP firma el token RS256; Nimbus recupera las claves públicas desde el JWKS configurado. Se valida firma, algoritmo permitido, issuer, audiencia, expiración obligatoria, nbf (si existe) y subject UUID canónico; se aplica la tolerancia temporal predeterminada de Spring Security. No se confía en `ownerId`, `customerId` ni actor enviados por el cliente en la transferencia. Los campos desconocidos de JSON se rechazan.

Contrato de identidad negociado para este laboratorio: un único issuer confiable emite `sub` UUID estable; para CUSTOMER ese identificador es exactamente `customer.id` en PostgreSQL, y para empleados es un UUID de identidad de personal. El IdP controla `roles` (array de strings). No basta con configurar un proveedor que emita subjects opacos diferentes: en ese caso debe implementarse un mapping de identidad gobernado, único por `(issuer, subject)`, antes de operar. No aceptar claims editables por el usuario. El fixture Keycloak importa los UUID de los clientes sintéticos de V3. No se implementan passwords ni un emisor JWT dentro de FinBridge.

| Operación | CUSTOMER | OPERATIONS | AUDITOR | ADMIN |
|---|---|---|---|---|
| POST accounts | No | Sí | No | Sí |
| GET account / balance | Propia | Sí | Sí | Sí |
| POST transfers | Ambas cuentas propias | No | No | No |
| GET transfer | Ambas cuentas propias | Sí | Sí | Sí |
| POST admin opening-balance | No | No | No | Sí, con opt-in de sandbox |
| GET audit/transfers/{id} | No | Sí | Sí | No |

Los permisos se combinan cuando el IdP asigna más de un rol. Incluso con roles adicionales, crear/reintentar una transferencia siempre exige CUSTOMER y ambas cuentas propias. Las rutas no implementadas se deniegan por defecto; solo GET health/liveness/readiness son públicas. CSRF se deshabilita porque la API no utiliza cookies de sesión ni autenticación automática del navegador; si el mecanismo cambia, reevaluar. No se habilita CORS por defecto.

Los filtros controlan el rol y los servicios vuelven a exigir el permiso. En transferencias nuevas, el ownership se comprueba contra las filas `account` bloqueadas `FOR SHARE`, antes de adquirir saldos y escribir. En replays se verifica de nuevo la propiedad sin volver a validar saldo/estado mutable. V6 hace inmutable `account.customer_id`; cambiar titular exige cerrar y abrir otra cuenta. Las lecturas no exponen entidades JPA. Las claves siguen siendo únicas por `(actor_id, operation, idempotency_key)`, ahora con actor autenticado. Los replays exitosos conservan 201, ID y correlación original en body; la cabecera `X-Correlation-ID` identifica cada petición HTTP.

### Compatibilidad con datos de Sprint 2

V6 reasigna solo claves de transferencias internas del actor técnico histórico al cliente que era propietario de ambas cuentas. El PK aborta cualquier colisión: no se sobrescriben claves ni se reintenta sin conciliación. En una base real ejecutar backup y revisión antes de migrar. Los eventos históricos no se editan; sus actores sintéticos permanecen visibles. El fondeo previo conserva el actor administrativo histórico: los administradores nuevos tienen su propio ámbito, y la restricción de fondeo único por cuenta impide una segunda apertura. El usuario admin de laboratorio conserva el UUID antiguo exclusivamente para compatibilidad de ese fixture, no para representar identidad real.

## ADR-014 — Evidencia y errores

- El evento de una transferencia confirmada se escribe con el actor JWT, `correlationId`, timestamp y journal dentro de la transacción ACID. Si el commit falla, se revierte el evento junto con la transferencia, idempotencia y saldo.
- V2 impide `UPDATE/DELETE` de auditoría; V6 agrega protección contra `TRUNCATE` y un índice de correlación. El endpoint `GET /api/v1/audit/transfers/{id}` reconstruye evidencia de solo lectura: actor, eventos, journal y postings. No devuelve referencia libre ni metadata que pueda contener PII.
- El evento contable no se duplica en replay. Rechazos previos al commit quedan en logs HTTP, no en el ledger; no se pretende auditar una transferencia inexistente como confirmada. La bitácora de accesos/rechazos de seguridad con retención externa es una evolución operativa.
- Problem Details RFC 9457 para 400/401/403/404/409/422/500. Incluye `correlationId`. El filtro de seguridad devuelve 401/403 con `application/problem+json`, incluso antes del controller. Los errores inesperados no exponen stacktrace, SQL ni input; los logs registran clase de excepción y correlación.
- Logs JSON Logstash con MDC acotado a la petición. Se aceptan correlation IDs opacos de 1–128 caracteres `[A-Za-z0-9._-]`, sin PII/secretos; si falta se genera UUID; si es inválido se rechaza. Se registra plantilla de ruta, método, status y duración, no query string ni body ni JWT. En respuesta de transferencia se registra `transferId` tras retorno del servicio transaccional. No activar DEBUG/TRACE de seguridad/JDBC ni dumps HTTP en entornos con credenciales. La correlación entrante no equivale a autenticación ni es identificador único de transferencia.
- El propietario de PostgreSQL o un superusuario aún puede alterar triggers; append-only no es WORM ni certificación regulatoria. En despliegues compartidos separar usuario migrador y usuario runtime, revocar UPDATE/DELETE/TRUNCATE de auditoría, restringir DDL, exportar logs a almacenamiento protegido, aplicar retención y revisión de accesos. Seeds y credenciales locales no son aptos para producción.
- Health probes y métricas de solicitudes HTTP habilitadas. Los contadores del proveedor, outbox y conciliación se incorporan con Sprint 4. Sin promesas de throughput ni latencia no medida.

Consulta SQL de investigación con parámetros desde una conexión autorizada:

```sql
SELECT a.correlation_id, a.actor_id, a.occurred_at, a.event_type,
       t.id AS transfer_id, j.id AS journal_id, p.side, p.amount, p.currency
FROM audit_event a
JOIN transfer t ON a.aggregate_type='TRANSFER' AND a.aggregate_id=t.id
JOIN journal j ON j.transfer_id=t.id
JOIN posting p ON p.journal_id=j.id
WHERE a.correlation_id = :correlation_id
ORDER BY a.occurred_at, a.id, p.id;
```

## Ejecución local — conservar la base V5

1. Conserva `.env` y añade las tres variables de JWT que aparecen en `.env.example`; conserva DB_PORT (por ejemplo 5434), DB_USER y DB_PASSWORD. Para la demo de fondeo únicamente, cambia `SANDBOX_FUNDING_ENABLED=true`. Elimina `SANDBOX_ADMIN_TOKEN`: ya no se usa.
2. `python3 scripts/init-local-idp.py` (solo la primera vez). Se generan credenciales aleatorias ficticias y el realm en `.local-idp/`, fuera de Git. No publicar este archivo. El perfil Keycloak usa `start-dev`, HTTP loopback, usuarios sintéticos y password grant únicamente como herramienta local de pruebas; no se usa en producción. Para aplicación web/móvil usar Authorization Code + PKCE en un IdP administrado; para workload, el flujo apropiado y permisos separados.
3. `docker compose --profile idp up -d db idp`. Confirma disponibilidad con `curl -f http://localhost:8180/realms/finbridge/.well-known/openid-configuration` (repetir si aún arranca). Si el puerto 8180 está ocupado, ajustar conjuntamente hostname, port mapping, issuer, JWKS y URL del helper. `KEYCLOAK_IMAGE` permite seleccionar una versión aprobada; el tag incluido es una referencia de laboratorio, no un baseline de seguridad verificado.
4. `set -a; source .env; set +a`; `mvn clean verify`; `mvn spring-boot:run` (en otra terminal para la demo). Flyway aplica V6; no usar `repair`, `clean` ni `docker compose down -v` para este cambio.
5. Obtén tokens independientes en otra terminal. Expiran en cinco minutos; vuelve a pedirlos si es necesario. No usar `set -x` ni imprimir variables con secretos.

```bash
OPS=$(python3 scripts/local-token.py operations)
ADM=$(python3 scripts/local-token.py admin)
CUST=$(python3 scripts/local-token.py customer-a)
OTHER=$(python3 scripts/local-token.py customer-b)
AUD=$(python3 scripts/local-token.py auditor)
```

Ejemplo completo (requiere Python 3, curl y sandbox de fondeo explícitamente habilitado). Los tokens de shell nunca se incluyen en el repositorio; no usar curl verbose.

```bash
BASE=http://localhost:8080
open_account() {
  curl -fsS -X POST "$BASE/api/v1/accounts" \
    -H "Authorization: Bearer $OPS" -H 'Content-Type: application/json' \
    -d '{"customerId":"00000000-0000-4000-8000-000000000011","currency":"COP"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])'
}
A=$(open_account)
B=$(open_account)
curl -fsS -X POST "$BASE/api/v1/admin/accounts/$A/opening-balance" \
  -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount":100000,"reason":"Fondeo sintetico de demo"}'
KEY=$(uuidgen)
BODY=$(printf '{"sourceAccountId":"%s","destinationAccountId":"%s","amount":25000,"currency":"COP"}' "$A" "$B")
# Esperado 401
curl -i "$BASE/api/v1/accounts/$A/balance"
# Esperado 403
curl -i "$BASE/api/v1/accounts/$A/balance" -H "Authorization: Bearer $OTHER"
# Esperado 201; guardar ID para auditoría
TRANSFER=$(curl -fsS -X POST "$BASE/api/v1/transfers" -H "Authorization: Bearer $CUST" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -H 'X-Correlation-ID: sprint3-demo' -d "$BODY" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
# Replay: mismo transferId, sin segundo débito
curl -i -X POST "$BASE/api/v1/transfers" -H "Authorization: Bearer $CUST" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY"
curl -fsS "$BASE/api/v1/audit/transfers/$TRANSFER" -H "Authorization: Bearer $AUD"
curl -fsS "$BASE/api/v1/accounts/$A/balance" -H "Authorization: Bearer $CUST"
```

Para conectar otro IdP, suministrar `JWT_ISSUER` exacto, `JWT_JWK_SET_URI` de confianza y `JWT_AUDIENCE`; usar HTTPS fuera del laboratorio y configurar el contrato de roles/subject antes de habilitar la aplicación. Nunca aceptar un JWKS determinado por el token. En Docker, `JWT_JWK_SET_URI_DOCKER` distingue la dirección alcanzable dentro del contenedor del issuer público. No hay issuer fallback ni autenticación alternativa de sandbox.

## ADR-015 — Quality gates y regresión

| Criterio | Evidencia implementada | Estado |
|---|---|---|
| FB-007 firma, issuer, audience, exp, roles | `TestJwt` (RSA efímero y servidor JWKS), `FinBridgeSprint3IntegrationTest` | Pendiente ejecución |
| AT-11 IDOR y ownerId falsificado | POST origen/destino ajeno, GET cuenta/saldo/transferencia, JSON adicional y ausencia de mutación | Pendiente ejecución |
| AT-12 sin JWT | GET saldo 401 + Problem Details; firma inválida y claims negativos | Pendiente ejecución |
| FB-008 | consulta auditor, actor/correlación/postings, UPDATE/DELETE/TRUNCATE bloqueados, errores correlacionados | Pendiente ejecución |
| FB-009 | suites anteriores conservadas con Bearer real; workflow `clean verify` y artefactos XML | Pendiente ejecución |

`mvn clean verify` es el gate. Las pruebas usan PostgreSQL 16/Testcontainers, no H2, y no dependen de Keycloak ni de secretos reales. El fixture criptográfico arranca un JWKS efímero HTTP local; no sustituye el decoder. No se desactivan filtros de seguridad en las pruebas ni se silencian fallos de Docker. Las pruebas de 20 requests concurrentes, sobregiro, A→B/B→A y rollback conservan las aserciones financieras originales. Los tests no imprimen requests MockMvc para evitar volcar tokens. El runner GitHub Actions comprueba Docker, Java 21 y ejecuta Maven verify en cada PR; publica reportes JUnit XML aun en fallo. Configurar branch protection con el check `verify` obligatorio y revisión antes de merge (ajuste del repositorio, no aplicado por este paquete). Cobertura objetivo de dominio >=80% sigue siendo orientativa según doc 07; no se inventa porcentaje ni se impone gate sin medición inicial.

`LegacyIdempotencyMigrationTest` prepara una base V5 con una transferencia y asiento reales, aplica V6 y comprueba que el actor de la clave se migra, que el actor auditado original se conserva y que los saldos no cambian.

Criterio de salida: ejecución verde de todas las pruebas, V6 aplicada sobre base existente y base vacía, IdP local verificado con roles y aud/iss correctos, workflow verde real. Si falla un test, consultar `target/surefire-reports`, preservar migraciones y corregir mediante una migración siguiente cuando corresponda.
