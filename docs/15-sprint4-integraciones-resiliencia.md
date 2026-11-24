# 15 | Integraciones y resiliencia

## Alcance

FB-010–FB-013 (21 SP). V7 amplía el modelo V6 sin reescribir migraciones. El servicio sigue siendo un monolito modular; el proveedor simulado tiene un recibo durable y un commit independiente en PostgreSQL. Esta separación demuestra la semántica at-least-once, no una integración bancaria de red real ni exactly-once distribuido.

## ADR-016 — Reserva y ciclo contable externo

| Etapa | Débito | Crédito | Efecto |
|---|---|---|---|
| RESERVED | Pasivo del cliente | Pasivo técnico de compensación | Reduce disponible; retiene fondos |
| SUBMITTED | Ninguno | Ninguno | Proveedor aceptó; no liquida |
| SETTLED | Pasivo de compensación | Activo de efectivo del banco | Consume reserva y reduce efectivo |
| REJECTED | Pasivo de compensación | Pasivo del cliente | Devuelve disponible |
| IN_REVIEW | Ninguno | Ninguno | Preserva reserva ante incertidumbre |

V7 agrega la cuenta técnica `00000000-0000-4000-8000-000000000003`. El activo de banco existente es `...0002`. La proyección incrementa pasivos con créditos y activos con débitos. La validación diferida comprueba exactamente dos postings por journal, sus importes, sentido y cuentas; exige outbox y un journal de reserva, y exige exactamente un journal de liquidación o devolución para estado terminal. Los estados terminales son irreversibles. Los locks sobre balances se adquieren en el mismo orden UUID que los flujos previos. El identificador de idempotencia es por actor y operación, con hash canónico y unicidad durable. La respuesta inicial es HTTP 202 y Location; un replay devuelve 202 y el estado actual de la misma operación. 

## ADR-017 — Outbox y proveedor simulado

`TransferRequested` se persiste junto a reserva, idempotencia y auditoría. `OutboxDispatcher` toma un evento pendiente con `FOR UPDATE SKIP LOCKED`; `SimulatedProvider.submit` usa REQUIRES_NEW con un recibo único por event_id y transfer_id. El proceso de prueba puede fallar tras confirmar el recibo y antes de marcar PUBLISHED; al reintentar el proveedor devuelve el mismo recibo. El simulador es un adaptador local de base de datos, no un servidor HTTP externo. La transacción del banco conserva el lock mientras el adaptador local realiza un commit corto; para un proveedor de red, sustituir por claim con lease, envío fuera de transacción y ack condicional, con timeout y circuit breaker.

| providerBankCode | Comportamiento sintético |
|---|---|
| SANDBOX_BANK | Aceptación seguida de liquidación |
| SANDBOX_REJECT | Aceptación seguida de rechazo |
| SANDBOX_TIMEOUT | Fallo de entrega repetible |

Cualquier otro código válido utiliza el escenario de liquidación simulado. No representa un banco real. Backoff 2, 4, 8 y 16 segundos antes de nuevos intentos; al quinto fallo outbox FAILED (DLQ lógica) y transferencia IN_REVIEW. No se reembolsa por timeout. La entrega de callbacks tiene cinco intentos acotados con pausa de 30 s; una entrega agotada queda visible en `finbridge.provider.callback.dlq`. No existe redrive automático de DLQ; investigar y resolver con evidencia autorizada. El worker procesa lotes acotados de 20 por ciclo; está deshabilitado por defecto. 

## ADR-018 — Callback y estados

`POST /api/v1/provider/events` utiliza HMAC en lugar de JWT. La firma es `hex(HMAC_SHA256(secret, ASCII(timestamp) + '.' + rawBody))`, timestamp Unix en segundos, ventana simétrica de 300 s. El secreto debe tener al menos 32 bytes y estar fuera de Git. Se compara la firma en tiempo constante antes de parsear JSON. Límite de 16 KiB. El evento único `(provider, external_event_id)` y hash de bytes evitan aplicar dos veces o reutilizar una clave con payload diferente. Un reintento válido puede volver a firmarse con timestamp fresco conservando bytes originales; un timestamp vencido devuelve 401. `providerTransferId` es único y, en el simulador, determinista `pay-{transferId}`. Los callbacks tempranos pueden confirmar SUBMITTED y luego el resultado dentro de una única transacción. Estados desconocidos con sintaxis válida llevan a revisión; estados posteriores a uno terminal quedan registrados como ignorados, sin revertir el ledger.

La recepción de un callback tardío de un estado terminal opuesto requiere investigación, no compensación automática. El actor técnico `...0094` representa el sistema externo; se conserva la correlación original y el registro de recepción. Auditoría devuelve `journalId` primario y `journalIds`, con `journalId` en cada posting.

## ADR-019 — Conciliación y seguridad del archivo

`POST /api/v1/reconciliations/import` (OPERATIONS) admite multipart `file`, UTF-8, hasta 1 MiB, 10.000 filas físicas, 4 KiB por fila, cabecera exacta del fixture y una fecha de liquidación por archivo. Comillas, comas y comillas escapadas están contempladas; las filas con saltos internos se tratan como inválidas y se ponen en cuarentena sin conservar texto original. Cabecera inválida o ausencia de filas válidas: HTTP 400. El procesamiento local es acotado y síncrono dentro de una transacción de snapshot repetible, aunque conserva el contrato HTTP 202 del lote. El lote se devuelve COMPLETED; no existe cola de ingestión asíncrona todavía.

Categorías: MATCHED, MISSING_IN_BANK, MISSING_IN_PROVIDER, AMOUNT_MISMATCH, STATUS_MISMATCH, DUPLICATE_PROVIDER_ENTRY, PROVIDER_ID_MISMATCH, CURRENCY_MISMATCH, DATE_MISMATCH, MALFORMED_ROW. Se informa una categoría principal por fila; los duplicados tienen prioridad. La detección de faltantes en proveedor se limita a operaciones terminales de la fecha del reporte del proveedor único y se omite si hay filas malformadas. Se presume un reporte diario completo para ese cálculo; no usar extractos parciales. No se actualizan transferencias, journals, postings ni balances. El dataset del proyecto se valida contra contrapartes bancarias sintéticas creadas en Testcontainers, no contra registros inexistentes. Exportación CSV escapada y protección frente a fórmulas; paginación 1–100. OPERATIONS y AUDITOR consultan/exportan.

## ADR-020 — Operación y métricas

`/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness` no dependen de la disponibilidad del proveedor. `/actuator/metrics` y `/actuator/metrics/{name}` requieren OPERATIONS o AUDITOR. Las etiquetas son status/outcome y rutas HTTP parametrizadas, no UUID. `finbridge.http.error.ratio` es 5xx/total de solicitudes API del proceso; no mide fallos de reglas de negocio. Los contadores por estado consultan la persistencia; los temporizadores de despacho/callback y HTTP son métricas por proceso. `finbridge.ledger.invariant.failure` es una consulta de asientos desbalanceados, no un contador de todos los rollbacks. No se afirma p95 ni throughput sin una medición real.

Métricas: `finbridge.transfers` por status, `finbridge.transfer.accepted`, `finbridge.transfer.rejected`, `finbridge.outbox.pending`, `finbridge.outbox.dlq`, `finbridge.outbox.oldest.age.seconds`, `finbridge.outbox.delivery`, `finbridge.provider.callbacks`, `finbridge.provider.callback.dlq`, `finbridge.reconciliation.mismatches`, `finbridge.ledger.invariant.failure`, `finbridge.http.error.ratio`, `http.server.requests`, `finbridge.outbox.dispatch.duration`, `finbridge.provider.callback.duration`. El snapshot HTML reproducible es `python3 scripts/metrics-snapshot.py`; genera `target/metrics-snapshot.html` sin imprimir tokens. Abrir después de ejecutar casos de aceptación. Sin muestras, el panel lo indica.

## Ejecución y prueba

Conservar `.env` anterior. Añadir `PROVIDER_HMAC_SECRET` con valor generado localmente (por ejemplo `openssl rand -hex 32`) y `PROVIDER_WORKER_ENABLED=true`. Nunca subir el secreto. `docker compose --profile idp up -d db idp`; `set -a; source .env; set +a`; `mvn clean verify`; `mvn spring-boot:run`. Para el laboratorio administrativo mantener `SANDBOX_FUNDING_ENABLED=true`. Detener el backend antes de ejecutar pruebas si se desea una demo limpia. V7 se aplica al arrancar. Usar `scripts/verify-keycloak-e2e.py` para la regresión interna.

Ejemplo externo con cuenta propia ya fondeada: `TOKEN=$(python3 scripts/local-token.py customer-a)`; `ACCOUNT_ID` debe ser UUID de una cuenta propia real del laboratorio.

```bash
curl -fsS -X POST http://localhost:8080/api/v1/external-transfers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"sourceAccountId\":\"$ACCOUNT_ID\",\"providerBankCode\":\"SANDBOX_BANK\",\"destinationAccountReference\":\"SYNTH-1234\",\"amount\":25000,\"currency\":\"COP\"}"
```

Consultar `GET /api/v1/external-transfers/{id}` con el mismo token. Con worker activo el simulador publica y entrega el callback. Para una prueba de incertidumbre, utilizar SANDBOX_TIMEOUT y consultar IN_REVIEW y outbox DLQ sin tocar saldos. Para una devolución, SANDBOX_REJECT. Los tests prueban además el callback HTTP firmado sobre bytes reales, el crash tras publicación y el replay. 

## Aceptación y límites

`FinBridgeSprint4IntegrationTest` incluye reserva/idempotencia, AT-13, AT-14, AT-15, AT-16, backoff/DLQ, rechazo, concurrencia, AT-17/18, cuarentena, exportación y métricas. Los 24 tests de Sprints 1–3 permanecen. La evidencia real de ejecución debe proceder de Maven/Testcontainers y CI; no se declara aceptación por análisis estático. El simulador comparte instancia PostgreSQL y no verifica una red externa; la prueba de crash es una excepción inyectada tras el commit del proveedor, no una terminación de proceso físico. La importación es síncrona y los informes están acotados. No se implementa redrive operativo, proveedor real, HA, WORM ni movimientos paginados de cuentas. Para operaciones reales, implementar secreto rotatorio por proveedor, transporte TLS, gestión de acceso, reconciliación operativa, pruebas de carga y retención externa.
