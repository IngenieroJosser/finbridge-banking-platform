# 04 | Modelo de datos de referencia — diseñar, no copiar un CRUD

Debes producir el ERD y la migración Flyway `V2__banking_schema.sql` a partir de este contrato lógico. **No se entrega el DDL resuelto.** Justifica PK, FK, índices, CHECK, locks y unicidad.

| Tabla/Agregado | Campos clave conceptuales | Restricciones que debes materializar |
|---|---|---|
| `customer` | `id UUID`, `external_ref`, `status` | Ref externa única |
| `account` | `id UUID`, `customer_id`, `currency`, `status`, `created_at` | FK customer, moneda COP en MVP |
| `account_balance` | `account_id`, `available_amount`, `version` | PK/FK account, `amount >= 0`, versión de concurrencia opcional |
| `transfer` | `id UUID`, `source_id`, `destination_id`, `amount`, `currency`, `type`, `status`, `reference`, `created_at` | FK source/destination, amount positivo |
| `journal` | `id UUID`, `transfer_id`, `journal_type`, `created_at` | FK transfer nullable para apertura; referencia de negocio única donde corresponda |
| `posting` | `id UUID`, `journal_id`, `account_id`, `side`, `amount`, `currency` | Dos o más partidas; monto positivo, currency consistente |
| `idempotency_record` | `actor_id`, `operation`, `key`, `request_hash`, `resource_id`, `created_at` | UNIQUE(actor_id, operation, key) |
| `audit_event` | id, aggregate_type, aggregate_id, actor_id, event_type, correlation_id, occurred_at, metadata | append-only en flujo normal |
| `outbox_event` | id, aggregate_id, event_type, payload, status, attempts, created_at | Para fase de integración |
| `provider_event` | provider, external_event_id, transfer_id, status, received_at | UNIQUE(provider, external_event_id) |
| `reconciliation_batch` | id, date, status, totals, created_at | Para importación por lotes |
| `reconciliation_item` | id, batch_id, transfer_id, category, detail | FK batch; clasificación de discrepancia |

## Invariantes de ledger

- Toda escritura financiera corresponde a un `journal`.
- Cada journal tiene **al menos dos postings**, todos de la misma moneda.
- `sum(DEBIT amounts) == sum(CREDIT amounts)` por journal.
- `posting` es append-only: no `UPDATE` ni `DELETE` en la lógica normal.
- El saldo proyectado debe concordar con los movimientos de la cuenta según la convención contable elegida.

**Reto importante:** Algunas invariantes agregadas (`SUM` balanceado o mínimo 2 partidas) no quedan resueltas solo con un `CHECK` por fila. Propón validación transaccional y/o constraint triggers, más una prueba que demuestre que nunca se confirma un journal incompleto.

## Ejercicios de diseño SQL

1. ¿Qué filas bloquearías para una transferencia A->B?
2. ¿En qué orden las bloquearías para evitar deadlocks en A->B y B->A concurrentes?
3. ¿Cómo harías la creación idempotente robusta ante carreras?
4. ¿Qué índices usarías para listar movimientos de una cuenta por fecha con paginación?
5. ¿Cómo evitarías N+1 y la carga accidental de datos sensibles en respuestas REST?
6. ¿Qué pasa si falla la inserción del segundo posting?
7. ¿Cómo guardarías millones de movimientos sin bloquear consultas operativas?

No uses `spring.jpa.hibernate.ddl-auto=update`: el esquema se gobierna por migraciones versionadas.
