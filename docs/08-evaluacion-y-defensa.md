# 08 | Defensa técnica y rúbrica de code review

## Preguntas que te haría un Tech Lead

1. ¿Qué garantía te ofrece `@Transactional`, y cuál **no** te ofrece ante dos requests concurrentes?
2. ¿Cómo demuestras que ni una falla justo después de persistir un posting deja dinero duplicado?
3. ¿Cómo resuelves dos requests con el mismo idempotency key que llegan al mismo milisegundo?
4. ¿Por qué no usas `double` para montos y por qué elegiste `NUMERIC(19,2)`?
5. ¿Qué diferencia hay entre saldo calculado desde ledger y saldo materializado?
6. ¿Por qué un `HTTP 202` de un proveedor no significa `SETTLED`?
7. ¿Cómo aseguras que un evento se publica, incluso si la aplicación cae después del commit?
8. ¿Cómo evitas deadlocks en transferencias simultáneas A->B y B->A?
9. ¿Por qué elegiste un monolito modular en lugar de cinco microservicios?
10. ¿Cuál es tu plan ante una migración Flyway incorrecta aplicada en UAT?
11. ¿Dónde pondrías los límites entre controller, application service, domain y repository?
12. ¿Qué harías si auditoría encuentra una transferencia `SETTLED` sin asiento contable?
13. ¿Cómo proteges GET /accounts/{id}/balance contra acceso a cuentas ajenas?
14. ¿Cómo identificarías un problema de pool de conexiones vs latencia del proveedor?
15. ¿Qué cambiarías primero para crecer 10x: índice, cola, particionado o microservicio? ¿Qué medirías?

## Evaluación (100 puntos)

| Dimensión | Puntos | Qué se busca |
|---|---:|---|
| Correctitud financiera | 30 | Sin dinero creado/perdido; journals balanceados y consistencia |
| Concurrencia e idempotencia | 20 | pruebas deterministas, bloqueo robusto, reintentos seguros |
| Arquitectura y mantenibilidad | 15 | responsabilidades claras, DTOs, invariantes en dominio |
| Calidad de tests y CI | 15 | prueba real de BD, escenarios adversariales, evidencia |
| Seguridad | 10 | JWT y ownership, errores sin filtraciones |
| Operabilidad y resiliencia | 10 | métricas, auditoría, callbacks, runbook |

**Escala orientativa:** 0-59 insuficiente, 60-79 funcional con riesgos, 80-89 sólido, 90-100 fuerte perfil empresarial. Es una rúbrica creada para este ejercicio, no un criterio publicado por GFT.

## Formato de tu demostración final

- 3 min: problema, arquitectura y decisiones.
- 5 min: demo API con saldo y transferencia.
- 5 min: demo de idempotencia y escenario concurrente.
- 3 min: fallo simulado, rollback y lectura de ledger.
- 4 min: preguntas de code review y mejoras futuras.

No enseñes solo Swagger: muestra logs, transacciones, consultas SQL y pruebas reproducibles.
