# 09 | Decisiones que debes completar por tu cuenta

El desafío no te da soluciones cerradas. Deja un ADR por decisión:

- ADR-007: ¿la idempotencia devuelve `201` otra vez o `200` en reintentos? El contrato actual especifica la creación inicial (201). Documenta tu política de replay y ajústala explícitamente.
- ADR-008: ¿bloqueo pesimista de filas, optimista con versión, o ambas según caso de uso? Documenta trade-offs y resultados de test.
- ADR-009: ¿la cuenta de contrapartida de apertura utiliza el mismo tipo `account` o un libro mayor de cuentas contables diferenciado? Justifica los efectos sobre auditoría.
- ADR-010: ¿cómo garantizas journal equilibrado sin introducir estado inconsistente entre insert y commit?
- ADR-011: ¿dónde termina la responsabilidad del servicio de transferencias y empieza la conciliación?
- ADR-012: ¿cuál es tu política de retención de eventos e idempotency keys? En el ejercicio no expires por defecto.

**No persigas complejidad cosmética:** Hibernate, Spring y Kafka son herramientas. Tu defensa profesional debe demostrar invariantes, recuperabilidad, seguridad y costes de operación.
