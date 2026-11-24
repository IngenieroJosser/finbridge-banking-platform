# Incidente de integración Sprint 2 — Hotfix V5

## Síntoma observado (23 de noviembre de 2026)

`mvn clean verify`: 19 pruebas ejecutadas, 0 fallos de aserción, 6 errores; 
las nueve pruebas de `FinBridgeSprint1IntegrationTest` finalizaron correctamente.
Las seis pruebas erróneas de Sprint 2 convergían en:

```text
org.postgresql.util.PSQLException:
ERROR: column reference "journal_id" is ambiguous
Where: PL/pgSQL function fb_validate_completed_internal_transfer() line 15
```

La inicialización de Spring Boot, Flyway V1–V4 y el DDL de Hibernate sí funcionaban.
El problema ocurre durante `COMMIT` porque la validación del ledger está implementada
como `CONSTRAINT TRIGGER DEFERRABLE INITIALLY DEFERRED`.

## Causa raíz y resolución

La función creada en V4 declaró `journal_id UUID` y consultó
`WHERE p.journal_id=journal_id`: PostgreSQL no puede determinar si el
identificador de la derecha designa una variable PL/pgSQL o una columna.

V5 **reemplaza exclusivamente la función**, sin recrear ni deshabilitar los triggers:
- Variable local renombrada `v_posted_journal_id` (sin colisión).
- Columnas calificadas con alias `j` y `p`.
- Regla original conservada: un asiento `POSTED` con dos partidas exactas,
  débito desde la cuenta origen y crédito a destino, por el importe solicitado.
- `COALESCE` defensivo ante un predicado nulo.
- `V1`, `V2`, `V3` y `V4` quedan **sin modificar**, respetando Flyway.

## Validación requerida en Mac con Docker Desktop

Desde la raíz del proyecto corregido, con la base de datos configurada:

```bash
set -a; source .env; set +a
docker compose up -d db
mvn clean verify
```

Las pruebas Testcontainers utilizan su propia base temporal. Para aplicar V5 también a tu base local, inicia una vez la aplicación (`mvn spring-boot:run`) y verifica después la migración:

```bash
docker compose exec db psql -U "$DB_USER" -d "$DB_NAME" -c   "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

**Resultados esperados:** Flyway instala V5 en bases existentes (o V1…V5
sobre base limpia) y los tests de Sprint 2 pasan. Las pruebas de regresión
ya contenidas en `FinBridgeSprint2IntegrationTest` comprueban transferencia
válida, 20 solicitudes con una clave idempotente, dos retiros concurrentes,
A→B/B→A concurrentes y rollback de asientos incompletos.

> Estas pruebas requieren la ejecución real de Maven/Testcontainers y no pueden
> declararse aprobadas a partir de una revisión estática del código.

## Riesgo residual

Una vez resuelto el error SQL pueden aflorar defectos independientes de
idempotencia o concurrencia. No dar el Sprint 2 por terminado hasta obtener
`BUILD SUCCESS` y revisar la evidencia en `target/surefire-reports`.

No ejecutar `flyway repair`, ni editar V4, ni eliminar volúmenes Docker para
resolver este incidente: no hace falta destruir datos.
