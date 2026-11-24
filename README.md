# FinBridge | Enterprise Banking Transaction Platform

> **Caso ficticio de entrenamiento backend Java con exigencia empresarial**. No es un proyecto, repositorio ni evaluación oficial de GFT. Todos los clientes, entidades y datos son simulados.

## Tu rol

Eres el Backend Engineer asignado a NovaBank. Debes entregar una plataforma que procese transferencias internas sin pérdida ni duplicación de dinero, y posteriormente integre una red externa de pagos, conciliación, seguridad y observabilidad.

**Versión actual:** Sprints 1–3 codificados (FB-001…FB-009). Migraciones V1–V6, transferencias ACID, idempotencia por actor autenticado, JWT RS256, roles, ownership, auditoría append-only, Problem Details, logging JSON y CI. La ejecución de Maven/Testcontainers y la prueba real del IdP local deben confirmarse en un entorno con Docker y Maven.

Guías: [`docs/10-sprint1-implementacion.md`](docs/10-sprint1-implementacion.md), [`docs/11-sprint2-motor-transferencias.md`](docs/11-sprint2-motor-transferencias.md), [`docs/12-incidente-sprint2-hotfix-v5.md`](docs/12-incidente-sprint2-hotfix-v5.md) y [`docs/13-sprint3-acceso-calidad.md`](docs/13-sprint3-acceso-calidad.md). Las guías 10–12 describen el estado histórico de sus entregas; para autenticación y ejecución actual prevalece la guía 13.

## Qué problema estás resolviendo

El banco tiene un core legado y una capa API. Recibe solicitudes concurrentes, reintentos del cliente, timeouts de terceros y reportes de compensación que pueden contradecir sus registros. Una transferencia incorrecta no es una simple excepción HTTP: puede ocasionar una pérdida contable, una operación duplicada o una incidencia de auditoría.

## Stack inicial conservado de tu plantilla

- Java 21 + Spring Boot 3.5.16, Maven
- Spring Web, Spring Data JPA, Validation, Actuator
- PostgreSQL 16, Flyway
- Docker Compose, Dockerfile multi-stage
- JUnit 5, Mockito, GitHub Actions

**Ya añadido:** Testcontainers, Spring Security OAuth2 Resource Server y un perfil Keycloak de laboratorio.

**A agregar después:** mensajería real, observabilidad avanzada, resiliencia y conciliación. No presentar este sandbox como banco productivo.

## Comenzar (macOS, Linux o Windows con WSL)

Conserva tu `.env` si ya existe; agrega `JWT_ISSUER`, `JWT_JWK_SET_URI` y `JWT_AUDIENCE` según `.env.example`. No cambies el puerto ni el volumen de una base existente.

```bash
# Solo para instalaciones nuevas:
# cp .env.example .env
python3 scripts/init-local-idp.py
docker compose --profile idp up -d db idp
set -a; source .env; set +a
mvn clean verify
mvn spring-boot:run
```

El IdP local es exclusivamente de laboratorio. El script crea credenciales sintéticas aleatorias en `.local-idp/`, excluido de Git. Espera a que el endpoint de descubrimiento del realm responda antes de obtener un token: `curl -f http://localhost:8180/realms/finbridge/.well-known/openid-configuration`. `python3 scripts/local-token.py customer-a` obtiene un access token real del IdP; no lo publiques ni guardes en Git. Ver guía 13 para el flujo completo y configuración con un IdP externo. El perfil `full` puede arrancarse junto con `idp`: `docker compose --profile idp --profile full up --build -d`.

Health sin token: `curl http://localhost:8080/actuator/health`. Los demás endpoints implementados requieren JWT. Integración externa, conciliación y movimientos paginados siguen fuera de esta entrega. V1–V5 permanecen intactas; V6 refuerza la auditoría y conserva la idempotencia histórica de transferencias internas.

## Ruta sugerida

| Sprint | Enfoque | Criterio de salida |
|---|---|---|
| 1 | Modelo, migraciones, cuentas, saldo inicial trazable | Integración PostgreSQL + invariantes básicas |
| 2 | Transferencias internas, idempotencia, concurrencia | Sin dobles débitos, sin sobregiros, asientos balanceados |
| 3 | Seguridad, auditoría, CI de integración | JWT/RBAC, trazabilidad, tests de extremo a extremo |
| 4 | Proveedor simulado, outbox, fallos, conciliación | Reintentos seguros, webhooks duplicados, discrepancias detectadas |

**Entrega mínima evaluable:** Sprint 1 y 2 con pruebas sólidas. Los Sprints 3 y 4 elevan el reto a integración empresarial.

## Documentos — lee en orden

1. `docs/01-brief-negocio.md` — rol, restricciones y objetivos.
2. `docs/02-reglas-y-flujos.md` — invariantes, máquina de estados y riesgos.
3. `docs/03-arquitectura-y-adr.md` — diseño y decisiones justificadas.
4. `docs/04-modelo-de-datos.md` — entidades y restricciones sin SQL resuelto.
5. `docs/05-backlog.md` — historias de usuario ordenadas y criterios.
6. `docs/06-pruebas-de-aceptacion.md` — casos que debes demostrar.
7. `docs/07-calidad-seguridad-operacion.md` — DoD, CI/CD y observabilidad.
8. `docs/08-evaluacion-y-defensa.md` — preguntas tipo code review/arquitectura.
9. `contracts/openapi.yaml` — contrato HTTP de referencia.
10. `fixtures/reconciliation_sample.csv` — datos 100% ficticios.

## Comandos de calidad

```bash
mvn test
mvn clean verify
```

`mvn clean verify` ahora incluye pruebas contra PostgreSQL mediante Testcontainers y **requiere Docker en ejecución**; no debe omitirse en CI. Ver el alcance por historia en `docs/10-sprint1-implementacion.md`.

## Siguiente tarea

Confirmar Sprint 3 y regresión de Sprint 1–2 con `mvn clean verify` y el workflow CI. Demostrar AT-11 (IDOR) y AT-12 (401), además de pruebas de firma, issuer, audience, roles, auditoría e idempotencia por titular. No avanzar a integración externa hasta tener evidencia verde. No exponer este laboratorio como sistema bancario real.
