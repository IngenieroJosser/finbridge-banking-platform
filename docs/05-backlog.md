# 05 | Backlog priorizado (simulación de Jira)

Reglas de trabajo: una historia = una rama corta + commits claros + PR + evidencia de tests + revisión. No pases de historia si sus criterios principales fallan.

## Sprint 1 — Fundamentos y persistencia

### FB-001 (5 pts) | Esquema contable

**Como** ingeniero de plataforma, **quiero** tener cuentas, balances y ledger con migraciones versionadas **para** disponer de persistencia trazable.

**Aceptación:** `V2__banking_schema.sql`; claves y restricciones; `ddl-auto: validate`; esquema reproducible desde base vacía; diseño documentado; pruebas PostgreSQL con Testcontainers. Prohibido editar V1 ya aplicado.

### FB-002 (3 pts) | Apertura y consulta de cuenta

**Como** operador autorizado, **quiero** crear una cuenta COP activa a saldo cero, **para** habilitar operaciones.

**Aceptación:** `POST /api/v1/accounts` y `GET /api/v1/accounts/{id}/balance`; UUIDs, 201/200/404/400; no exponer Entity JPA.

### FB-003 (5 pts) | Saldo inicial contable

**Como** administrador, **quiero** fondear una cuenta de prueba mediante asiento balanceado, **para** no modificar saldos por SQL sin trazabilidad.

**Aceptación:** apertura con cuenta de contrapartida del banco; ningún balance negativo; no duplicar carga por clave; prueba del total de postings y auditoría.

## Sprint 2 — Motor de transferencias

### FB-004 (8 pts) | Transferencia interna atómica

**Como** cliente, **quiero** transferir dinero entre mis cuentas autorizadas, **para** mover fondos sin pérdida.

**Aceptación:** endpoint implementado; rechazo de misma cuenta, bloqueadas, currency distinta, monto inválido y saldo insuficiente; transferencia/journal/postings/balances atómicos; 201 si completa, 422 para regla de negocio y 404 para recurso ausente.

### FB-005 (5 pts) | Idempotencia robusta

**Como** API Consumer, **quiero** reintentar una solicitud sin doble débito, **para** tolerar fallas de conectividad.

**Aceptación:** misma clave+cuerpo produce mismo transferId; misma clave+cuerpo distinto 409; prueba con 20 llamadas concurrentes de clave idéntica; unicidad garantizada en DB.

### FB-006 (8 pts) | Protección contra condiciones de carrera

**Como** responsable del ledger, **quiero** evitar sobregiros por transferencias simultáneas, **para** proteger consistencia contable.

**Aceptación:** dos transferencias de 80k desde cuenta de 100k => solo 1 éxito; prueba de A->B y B->A concurrentes; sin journals a medio registrar; evitar deadlocks mediante orden de locks.

## Sprint 3 — Acceso y calidad de servicio

### FB-007 (5 pts) | JWT y autorización de propiedad

**Como** responsable de seguridad, **quiero** permitir transferencias solo a titulares, **para** evitar acceso horizontal no autorizado.

**Aceptación:** Spring Security OAuth2 resource server con JWT validado; roles CUSTOMER/OPERATIONS/AUDITOR/ADMIN; 401 sin token, 403 cuenta ajena; tests de autorización y no confiar en ownerId del body.

### FB-008 (3 pts) | Auditoría y errores

**Como** auditor, **quiero** reconstruir cada transferencia mediante `correlationId`, actor y asientos, **para** investigar incidentes.

**Aceptación:** audit append-only, Problem Details para errores, logs estructurados y sin datos sensibles.

### FB-009 (5 pts) | CI y pruebas de integración

**Como** equipo de desarrollo, **quiero** validar cada PR automáticamente, **para** evitar regresiones.

**Aceptación:** Maven verify + Testcontainers PostgreSQL; unitarias, MVC, integración y concurrencia; pipeline verde con Docker disponible.

## Sprint 4 — Integraciones y resiliencia

### FB-010 (8 pts) | Proveedor externo simulado y transactional outbox

**Como** banco, **quiero** transferir a otra entidad, **para** ofrecer pagos interbancarios sin bloqueo síncrono.

**Aceptación:** `POST /api/v1/external-transfers` devuelve 202; reserva contable; outbox en misma transacción; proveedor simulado; retries con backoff y DLQ; prueba crash-after-publish.

### FB-011 (5 pts) | Callback seguro e idempotente

**Como** integrador, **quiero** recibir resultados atrasados o duplicados, **para** finalizar la transferencia una sola vez.

**Aceptación:** firma HMAC, verificación de timestamp y prevención de replay, identificador externo único, estados irreversibles, compensación al rechazo.

### FB-012 (5 pts) | Conciliación de archivos

**Como** analista operativo, **quiero** cargar un reporte del proveedor y clasificar diferencias, **para** abrir incidencias sin alterar saldos automáticamente.

**Aceptación:** import CSV robusta; filas malformadas a cuarentena o error documentado; categorías de discrepancia; informe paginado exportable; dataset `fixtures` como prueba.

### FB-013 (3 pts) | Observabilidad operativa

**Como** on-call engineer, **quiero** métricas y trazabilidad, **para** diagnosticar lentitud y fallas.

**Aceptación:** métricas por status, latencia, outbox lag, error ratio, health/readiness, dashboards o screenshots reproducibles; sin etiquetas de alta cardinalidad con UUIDs.

## Entrega por cada PR

- Descripción del cambio y riesgo.
- Migraciones y notas de rollback/forward-fix cuando aplique.
- Evidencia de tests, incluidos negativos.
- Ejemplo curl y respuesta.
- Decisión técnica: por qué elegiste esa alternativa.
