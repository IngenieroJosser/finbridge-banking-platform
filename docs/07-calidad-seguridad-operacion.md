# 07 | Calidad, seguridad y operación

## Calidad de entrega

- DTOs de entrada/salida con validación explícita y contrato estable (OpenAPI).
- `BigDecimal` con escala y reglas definidas, transacciones cortas y locks ordenados.
- Tests unitarios + integración contra PostgreSQL real + pruebas negativas + concurrencia.
- Migraciones Flyway versionadas y revisadas en PR; `ddl-auto: validate`.
- Logs estructurados y correlación; no loggear secretos, JWT completos, nombres bancarios sensibles o PII.
- CI debe fallar cuando una prueba o una migración falla. Cobertura orientativa >=80% de la lógica de dominio, no como sustituto de pruebas críticas.

## Matriz de controles

| Riesgo | Control recomendado |
|---|---|
| IDOR en accountId | Autorización ownership + scopes/roles |
| Token falsificado | Validación issuer, audience, firma y expiración |
| Replay de webhook | Firma HMAC, ventana temporal, id de evento único |
| Doble débito | Idempotency key persistida + índice único + transacción |
| Sobregiro por concurrencia | Lock de fila / aislamiento probado |
| SQL injection | Consultas parametrizadas y nunca concatenar input |
| Datos sensibles | Mínimo dato, secrets manager al desplegar, logs redactados |
| Denial of service | Rate limit en gateway, timeouts, paginación y límites de body |
| Reintentos infinitos | Backoff acotado, DLQ, runbook |
| Métricas costosas | No introducir UUID ni accountId como tags de métricas |

## Telemetría mínima al terminar

- `http.server.requests` por ruta, método y resultado; histogramas de latencia.
- Contadores `transfer_accepted`, `transfer_rejected`, `ledger_invariant_failure` (éste debería mantenerse en cero), `provider_callback_duplicate`.
- `outbox_pending`, `outbox_oldest_age_seconds`, `reconciliation_mismatches`.
- `correlationId`, `transferId`, status sin imprimir los detalles bancarios sensibles.
- Health checks de proceso, base de datos, readiness. Evita que un proveedor externo de pagos derribe la readiness de la API de consulta.

## Operación de incidente simulado

1. Detectar: alerta en porcentaje de fallos de transferencia o antigüedad de outbox.
2. Contener: no reintentar manualmente sin clave de idempotencia.
3. Investigar: consultar `transferId`, correlación, journal y eventos del proveedor.
4. Reparar: si el estado está incierto, consultar al proveedor; no asumir rechazo por timeout.
5. Compensar: usar transacción compensatoria rastreable y aprobada, no borrar journal.
6. Cerrar: ticket de incidente, causa raíz, prueba de regresión y evidencia de conciliación.

## Límites de despliegue

Desarrollar en local. Para un entorno cloud de demostración, asegurar configuración por variables, secrets externos, service account de permisos mínimos, connection pooling, backups/restauración y límites de gasto. No presentarlo como listo para procesar pagos reales sin procesos regulatorios y certificación pertinentes.
