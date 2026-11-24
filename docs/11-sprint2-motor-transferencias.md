# Sprint 2 | FB-004, FB-005 y FB-006 — diseño, pruebas y defensa

> Caso bancario **100 % ficticio**. La API aún **no valida JWT ni identifica al titular autenticado**. Está diseñada solo para sandbox local sin exposición pública. Sprint 3 / FB-007 debe resolver ownership real, OAuth2 Resource Server, 401/403 y límites de acceso antes de cualquier despliegue externo.

## 1. Objetivos y estado verificable

| Historia | Diseño entregado | Evidencia automática incluida |
|---|---|---|
| FB-004 | `POST /api/v1/transfers`, `GET /api/v1/transfers/{id}`, transferencia+ledger+proyección+auditoría en una transacción | AT-01 a AT-05, lectura 200/404, DB V4, importes exactos |
| FB-005 | Serialización por clave, SHA-256 canónico versionado y PK durable `(actor_id,operation,idempotency_key)` | AT-06, AT-07, 20 llamadas simultáneas idénticas |
| FB-006 | `account FOR SHARE` y `account_balance FOR UPDATE`, ambos en orden UUID, validación de fondos bajo lock | AT-08, AT-09, AT-10, ledger frente a proyección |

**Estado:** código y pruebas redactados; no se ha ejecutado Maven/Testcontainers en el entorno de generación, que no dispone de Maven/Docker. Se necesita `mvn clean verify` real antes de declarar las historias aceptadas.

## 2. Modelo contable / invariante de conservación

Dos cuentas cliente son pasivos del banco: un **DEBIT** a origen reduce el pasivo y su saldo disponible; un **CREDIT** a destino aumenta el pasivo y el saldo disponible. Para una transferencia interna de COP 25.000:

| Cuenta | Tipo de posting | Importe | Saldo antes | Saldo después |
|---|---|---:|---:|---:|
| Origen | DEBIT | 25.000,00 | 100.000,00 | 75.000,00 |
| Destino | CREDIT | 25.000,00 | 0,00 | 25.000,00 |
| **Total** | Debe = Haber | 25.000,00 cada lado | 100.000,00 | 100.000,00 |

No se hace `UPDATE account_balance` desde Java. Solo `INSERT posting` activa el trigger de V2, cuya actualización de balance está dentro de la misma transacción. `NUMERIC(19,2)` y `BigDecimal` sin redondeo implícito; valor estrictamente positivo, límite unitario 50 millones COP. Un intento de sobregiro se rechaza además en PostgreSQL por `CHECK (available_amount >= 0)`.

## 3. Secuencia transaccional real

1. Validar encabezado `Idempotency-Key` (ASCII imprimible 16–128), sintaxis y regla financiera (`InternalTransferOrder`).
2. Normalizar importe a escala 2, referencia `trim/NFC`, referencia vacía a `null`; computar `SHA-256` de versión `internal-transfer:v1` más campos canónicos.
3. Tomar `pg_advisory_xact_lock(hashtextextended(actor|INTERNAL_TRANSFER|key,0))`; consultar `idempotency_record`. Si existe el mismo hash, recuperar el **registro persistido** y devolver mismo `id`, fecha y correlación; si el hash difiere, 409.
4. Ordenar las dos UUID; bloquear filas de `account` mediante `FOR SHARE`, una por vez, en dicho orden. Verificar existencia 404, estado ACTIVE, COP, tipo CUSTOMER_LIABILITY y mismo titular ficticio.
5. Bloquear proyecciones `account_balance` con `PESSIMISTIC_WRITE` en el mismo orden. **Solo ahora** comprobar `saldo_origen >= importe`.
6. Insertar `transfer` COMPLETED, `journal` DRAFT, `posting` DEBIT a origen y `posting` CREDIT a destino, y publicar journal como POSTED. V2 actualiza saldos y verifica journal balanceado al commit.
7. Insertar `idempotency_record` y `audit_event` con `correlationId`, actor técnico y referencia al asiento.
8. Commit único. V4 verifica que una transferencia completada tiene exactamente dos postings **en las cuentas, sentidos e importe correctos**. Si cualquier inserción falla, **todo**, incluidos saldos proyectados, se revierte.
9. HTTP 201 con `Location: /api/v1/transfers/{id}`, `status=COMPLETED`. Repetición idéntica también responde 201 con el mismo recurso, nunca un segundo journal.

### Por qué se necesitan las dos capas de control

- **Advisory lock transaccional:** serializa peticiones idénticas incluso entre procesos Spring independientes mientras compartan PostgreSQL. Una colisión de hash de advisory lock solo causaría espera adicional; la unicidad no depende de él.
- **PK de idempotencia:** rechaza dobles inserciones incluso si un cliente omite el protocolo de locks; es la última barrera durable y abarca un actor + operación + clave.
- **Bloqueos de saldo:** resuelven peticiones con **claves diferentes**; después de que la primera confirme, la segunda ve un saldo actualizado. `@Transactional` sin locks no basta.
- **Orden total UUID:** evita ciclos cuando A→B compite con B→A. Cada transferencia adquiere ambos balances en la misma secuencia total.
- **Constraint triggers diferidos:** verifican invariantes de varias filas al commit, no con validaciones efímeras en memoria.

### Invariantes adicionales por migración V4

No se ha reescrito V1, V2 ni V3. `V4__internal_transfer_integrity.sql` agrega:

- `ck_internal_journal_requires_transfer`: un journal interno apunta obligatoriamente a una transferencia.
- `ck_internal_transfer_integrity`: para `INTERNAL` `COMPLETED` exige un journal `POSTED` y exactamente dos postings, un DEBIT origen y un CREDIT destino del importe original.
- `trg_internal_transfer_immutable`: no permitir `UPDATE/DELETE` de una transferencia interna completada; futuras correcciones deben ser movimientos compensatorios rastreables.

Estas son garantías para las rutas SQL previstas. Un DBA privilegiado puede eludir triggers; el hardening de permisos SQL, particionado y reconciliación están fuera del Sprint 2.

## 4. Contrato y códigos HTTP

| Caso | Código | Comportamiento |
|---|---|---|
| Transferencia correcta o retry idéntico | **201** | Mismo `transferId` y `Location` |
| GET existente / inexistente | **200 / 404** | DTO estable / RFC 9457 |
| Body incompleto, monto cero/negativo o centavos inexactos, clave inválida | **400** | Ninguna mutación; AT-02 exige específicamente 400 para monto cero |
| Cuenta ausente | **404** | Ninguna mutación |
| Cuenta bloqueada, misma cuenta, otro titular, USD, exceso por límite o fondos insuficientes | **422** | Regla financiera no cumplida |
| Reutilización de misma clave con otro cuerpo canónico | **409** | Nunca realiza otro asiento |

**Nota de ownership:** se limita el sandbox a dos cuentas del **mismo `customer_id`**; no existe principal de usuario real, porque la autenticación está explícitamente pendiente hasta Sprint 3. Conocer UUIDs permite invocar la API si se expone, por tanto **NO** despliegues fuera de localhost ni infieras protección IDOR. Las transferencias entre titulares diferentes requieren autorización de producto y cambios de política explícitos en un Sprint futuro.

## 5. Ejecución local (sin borrar la base anterior)

Si ya tienes una carpeta de trabajo del Sprint 1, incorpora el código de la nueva entrega con Git en una rama `feature/fb-004-006`, conserva tu `.env` y reutiliza la **misma** base de datos. No elimines volúmenes de Docker. Flyway detecta V4 y la aplica después de V3:

```bash
# Desde la raíz del proyecto actualizado
set -a; source .env; set +a
docker compose up -d db
mvn clean verify
mvn spring-boot:run
```

Si es una copia en carpeta distinta con Docker Compose, su volumen será nuevo y la base se inicializará desde V1; ajusta `DB_PORT` en `.env` si 5433 está ocupado. Los tests usan contenedores PostgreSQL 16 independientes y requieren Docker Desktop activo.

### Demostración manual

1. Habilitar `SANDBOX_FUNDING_ENABLED=true`, generar `SANDBOX_ADMIN_TOKEN` local (no subirlo a Git), reiniciar la aplicación.
2. Crear dos cuentas con el **mismo** cliente ficticio `00000000-0000-4000-8000-000000000011`.
3. Fondear **solo** origen con 100000 por el endpoint administrativo de Sprint 1.
4. Ejecutar petición de transferencia y guardar la clave:

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: internal-demo-2026-00001' \
  -H 'X-Correlation-ID: local-sprint2-001' \
  -d '{"sourceAccountId":"REEMPLAZAR_A","destinationAccountId":"REEMPLAZAR_B", "amount":25000.00,"currency":"COP","reference":"Pago de prueba"}'
```

5. Repetir **exactamente** la petición (201, mismo ID, sin débito extra). Cambiar `amount` con la misma clave (409). Consultar saldos (75k/25k) y `GET /api/v1/transfers/{id}`.

### SQL de investigación

```sql
SELECT t.id, t.status, j.id AS journal_id, j.status AS journal_status,
       COUNT(p.id) AS postings,
       COALESCE(SUM(p.amount) FILTER (WHERE p.side='DEBIT'),0) AS debits,
       COALESCE(SUM(p.amount) FILTER (WHERE p.side='CREDIT'),0) AS credits
FROM transfer t JOIN journal j ON j.transfer_id=t.id
JOIN posting p ON p.journal_id=j.id
WHERE t.transfer_type='INTERNAL'
GROUP BY t.id,t.status,j.id,j.status ORDER BY t.id;

SELECT a.id, b.available_amount,
       COALESCE(SUM(CASE WHEN p.side='CREDIT' THEN p.amount ELSE -p.amount END),0) AS ledger_balance
FROM account a JOIN account_balance b ON b.account_id=a.id
LEFT JOIN posting p ON p.account_id=a.id
WHERE a.account_type='CUSTOMER_LIABILITY'
GROUP BY a.id,b.available_amount HAVING b.available_amount <> 
       COALESCE(SUM(CASE WHEN p.side='CREDIT' THEN p.amount ELSE -p.amount END),0);
-- Esperado: 0 filas de discrepancia.
```

## 6. Pruebas adversariales incluidas

- `TransferDomainTest`: canonicalización, importe exacto y códigos de error.
- `FinBridgeSprint2IntegrationTest`: AT-01…AT-10; 20 requests a la vez con clave idéntica; dos débitos simultáneos de 80k; 16 transferencias A↔B simultáneas; journal incompleto forzado; no transfer COMPLETED huérfana; inmutabilidad tras commit; auditoría; replay y conflicto.
- `FinBridgeSprint1IntegrationTest` permanece sin cambios para regresión FB-001 a FB-003.

**Revisión pendiente de aceptación:** ejecutar `mvn clean verify` en Mac con Docker y adjuntar log de salida. Si falla una prueba, no declarar cierre; investigar en este orden: migración Flyway, vínculo JDBC/JPA dentro de transacción, lock order, trigger diferido y serialización HTTP.

## 7. Limitaciones / deuda a priorizar

- **FB-007:** JWT real firmado, autorización por titular/rol y partición de claves de idempotencia por principal autenticado; no aceptar el actor del body ni de un encabezado arbitrario.
- **FB-008:** métricas, Problem Details con códigos de error estables, trazas por `correlationId`, tests negativos adicionales.
- **Sprint 4:** estatus y reversión de transferencias externas, outbox, webhooks y conciliación; no confundir `COMPLETED` interno con `SETTLED` de proveedor externo.
- **Escalabilidad:** el lector de balances JPA utiliza locks pesimistas y se apoyan en índices primarios de UUID. Medir contención, tamaño de pool y p95 antes de cambiar aislamiento o separar servicios.
- **Operación:** `idempotency_record` sin TTL en el ejercicio; para producción definir horizonte contractual, particionado, tratamiento de datos personales y retención conforme a política de auditoría.
