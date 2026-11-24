# 16 | Aceptación operativa y publicación

## Evidencias de referencia

El backlog FB-001–FB-013 comprende 68 SP. El 24 de noviembre de 2026 se aprobaron 34 pruebas Maven, incluidas diez de integración V7; la ejecución diagnóstica con worker deshabilitado terminó en 19,497 s, sin las incidencias previas de cierre. La regresión interna con Keycloak y la liquidación externa de 10.000 COP mediante el worker local se verificaron. El PR #4 fue integrado a main en `1b35737`, con check remoto `verify` satisfactorio. La ejecución local del hotfix y los escenarios adversos E2E constituyen gates adicionales de publicación; no se consideran ejecutados por la existencia del script.

## Procedimiento reproducible

Desde main actualizado (o la rama de aceptación), con Docker activo y backend detenido:

```bash
PROVIDER_WORKER_ENABLED=true mvn clean verify
```

Se esperan 34 pruebas, sin fallos, errores u omisiones y sin las advertencias de conexiones cerradas, worker al finalizar Testcontainers o cierre forzado de Surefire. Para instalaciones con un mirror autorizado de Maven, añadir `-s` con su ruta local. No modificar migraciones ni borrar volúmenes.

Conservar `.env` y el realm de Keycloak ya generado. En un sandbox exclusivo, sin operaciones concurrentes ni eventos pendientes, iniciar los servicios:

```bash
docker compose --profile idp up -d db idp
set -a; source .env; set +a
PROVIDER_WORKER_ENABLED=true SANDBOX_FUNDING_ENABLED=true mvn spring-boot:run
```

El secreto HMAC debe estar configurado localmente. En otra terminal:

```bash
python3 scripts/verify-sprint4-e2e.py --confirm-sandbox
```

El script comprueba health, readiness, liveness y OIDC; utiliza JWT reales. Crea una cuenta nueva con fondeo sintético de 100.000 COP y tres transferencias de 10.000 COP. No elimina ni modifica datos históricos. Los replays reutilizan la clave dentro de cada ejecución. Una nueva ejecución genera otro conjunto de cuentas y movimientos; si falla, consultar los UUID impresos antes de repetir. No ejecutar contra datos reales ni en paralelo con otros clientes de prueba.

| Escenario | Saldo disponible | Journals | Resultado |
|---|---:|---:|---|
| Fondeo | 100.000 COP | — | Cuenta activa |
| Liquidación | 90.000 COP | 2 | SETTLED |
| Rechazo | 90.000 COP | 2 | REJECTED, devolución contable |
| Timeout | 80.000 COP | 1 | IN_REVIEW, reserva conservada |

Se comprueban actores, correlación, partidas y estados; delta de dos publicaciones, cuatro reintentos y una DLQ. El script espera el resultado del worker sin adelantar timestamps ni modificar la BD. La DLQ no se redirige automáticamente.

La conciliación importa el fixture original y un reporte sintético parcial con una fila inválida deliberada. La cuarentena impide inferir ausencias en el reporte parcial; se verifican MATCHED, AMOUNT_MISMATCH y MALFORMED_ROW, además del duplicado del fixture. El fixture puede producir categorías distintas según los datos históricos del sandbox. La paginación, exportación y conservación del saldo y evidencia de las tres transferencias se contrastan por API. No se publican tokens ni se modifican balances por conciliación.

El directorio ignorado `target/s4-<run>/` conserva auditorías JSON, exportaciones CSV, snapshot HTML y `evidence.json` cuando todas las aserciones pasan. Los contadores de persistencia se comparan por delta; los timers incluyen polling ocioso. No constituyen una prueba de carga ni un SLA. El proveedor es simulado en la misma instancia PostgreSQL.

## Quality gates de publicación

- Hotfix: Maven local con worker habilitado y CI remoto aprobado.
- Aceptación: `SPRINT 4 E2E SUCCESS`, evidencia y dashboard revisados.
- Repositorio: cambios integrados mediante PR, check `verify` satisfactorio en main y rama protegida.
- Seguridad: secretos excluidos y credenciales sintéticas antiguas rotadas; las versiones históricas expuestas no deben reutilizarse.

Una vez registradas las evidencias, actualizar el estado de aceptación en README, fusionar el PR documental con CI aprobado y publicar el tag anotado `v1.0.0` sobre el commit de main efectivamente validado. La release describe una primera versión estable del laboratorio, no un servicio bancario certificado. Las limitaciones y decisiones ADR del documento 15 permanecen vigentes.
