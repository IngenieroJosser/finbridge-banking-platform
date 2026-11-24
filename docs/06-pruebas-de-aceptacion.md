# 06 | Escenarios de aceptación — QA adversarial

Este documento es la especificación de aceptación. AT-01 a AT-10 están **codificados** en `FinBridgeSprint2IntegrationTest`, con pruebas puras en `TransferDomainTest`; **no equivalen a pruebas aprobadas** hasta ejecutar `mvn clean verify` con PostgreSQL 16/Testcontainers y adjuntar el resultado. AT-11 y AT-12 están codificados en `FinBridgeSprint3IntegrationTest`; pendientes de ejecución real. AT-13 a AT-18 corresponden al Sprint 4.

| ID | Preparación | Acción | Resultado exigido |
|---|---|---|---|
| AT-01 | Cuenta A=100000, B=0 | A transfiere 25000 a B | A=75000, B=25000; 1 journal balanceado |
| AT-02 | A=100000 | A transfiere 0 | HTTP 400; sin mutaciones |
| AT-03 | A=100000, B=0 | A transfiere 120000 | HTTP 422; A=100000, B=0 |
| AT-04 | A activa, B bloqueada | A transfiere 1000 a B | HTTP 422; sin postings |
| AT-05 | A activa | A se transfiere a sí misma | HTTP 422; sin postings |
| AT-06 | A=100000, B=0 | Repetir 10 veces mismo Idempotency-Key y body | Igual transferId; un débito |
| AT-07 | A=100000 | Repetir clave anterior con amount distinto | HTTP 409; sin nueva transferencia |
| AT-08 | A=100000, B=0, C=0 | 2 solicitudes simultáneas de 80000 a B y C | Exactamente una completa; A=20000 |
| AT-09 | A=100000, B=100000 | Cargas concurrentes A->B y B->A | Sin deadlock no recuperado; suma total conservada |
| AT-10 | Operación nueva | Forzar error tras primer posting | Rollback completo; 0 journal parcial |
| AT-11 | Cliente X no es dueño de A | Token X solicita A->B | HTTP 403; sin transferencia |
| AT-12 | Sin JWT | Solicitar saldo | HTTP 401 en fase seguridad |
| AT-13 | Proveedor retorna 202 | Transferencia externa | Estado `SUBMITTED/PENDING`; NO `SETTLED` |
| AT-14 | Evento E marca SETTLED | Entregar E dos veces | Un settlement; segunda entrega no cambia saldo |
| AT-15 | Ya SETTLED | Entregar callback tardío de SUBMITTED | Estado no retrocede |
| AT-16 | Outbox publicado, crash antes del ack local | Reejecutar publicador | Mensaje puede repetirse; consumidor solo aplica una vez |
| AT-17 | CSV reporta monto mayor | Conciliar lote | `AMOUNT_MISMATCH`; no ajuste automático |
| AT-18 | CSV contiene dos filas con mismo ID externo | Conciliar lote | `DUPLICATE_PROVIDER_ENTRY`; incidencia visible |

## Cómo demostrarlo técnicamente

- **Unitarias:** validaciones puras, invariantes de máquina de estados, cálculo decimal.
- **MVC:** contratos y códigos HTTP con MockMvc.
- **Integración real:** Testcontainers PostgreSQL; ejecutar Flyway y JPA de verdad.
- **Concurrencia:** `CountDownLatch` / `ExecutorService`, inicio coordinado, resultado determinista, no usar H2 como sustituto de PostgreSQL.
- **Integración externa:** WireMock/mock server y reenvío de eventos.
- **Carga:** k6 o Gatling como objetivo opcional. Medir p95, error rate y throughput, nunca inventar cifras de rendimiento.

## Definition of Done para los Sprints 1-2

- [ ] Ninguna prueba AT-01 a AT-10 pendiente.
- [ ] Consistencia de ledger demostrada con consulta SQL.
- [ ] `mvn clean verify` verde.
- [ ] Se puede levantar desde cero con `docker compose` + Flyway.
- [ ] No hay cambios de esquema automáticos de Hibernate ni credenciales en Git.
- [ ] README describe decisiones, errores y ejemplos reales de ejecución.

## Definition of Done Sprint 3

- [ ] AT-01 a AT-12 y regresión de seguridad en verde con PostgreSQL real.
- [ ] JWT inválido/ausente rechazado, roles y ownership demostrados.
- [ ] Evidencia de auditoría reconstruible y append-only; errores RFC 9457.
- [ ] Workflow de PR verde y reportes Surefire disponibles.
