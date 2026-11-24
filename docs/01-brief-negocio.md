# 01 | Brief empresarial — NovaBank

## Solicitud del cliente

«Los clientes deben transferir dinero desde nuestros canales web y móvil. Una misma petición puede llegar dos o diez veces porque el celular pierde conectividad. A su vez, el core transaccional no puede dar un saldo negativo ni registrar dos veces la misma operación. Próximamente conectaremos a un proveedor externo que responde de forma asíncrona. Auditoría exige reconstruir cada decisión.»

## Personas de negocio

- **Cliente titular:** consulta su cuenta y solicita movimientos entre cuentas autorizadas.
- **Operaciones:** investiga transferencias pendientes, rechazos y discrepancias.
- **Auditor:** consulta asientos y eventos de negocio inmutables, sin poder modificar dinero.
- **Administrador:** crea cuentas de prueba y autoriza la carga inicial contable.
- **Sistema externo simulado:** responde `ACCEPTED`, `SETTLED` o `REJECTED`, a veces tarde o duplicado.

## Alcance funcional

### MVP obligatorio

1. Crear y consultar cuentas ficticias, con propietario y estado.
2. Registrar carga inicial por operación contable auditada; prohibido `UPDATE balance = ...` libre.
3. Consultar saldo disponible.
4. Transferir entre dos cuentas internas COP del mismo banco.
5. Rechazar importes inválidos, cuentas bloqueadas, destino idéntico y fondos insuficientes.
6. Garantizar idempotencia por cliente, operación y clave de solicitud.
7. Registrar ledger inmutable de doble partida y obtener movimientos paginados.
8. Probar solicitudes concurrentes y rollback transaccional.

### Evolución empresarial

9. Autenticar solicitudes con JWT firmado por un proveedor de identidad, autorizar según propietario y rol.
10. Enviar transferencias externas de forma asíncrona vía outbox y adaptador a proveedor simulado.
11. Aplicar webhooks autenticados, tolerar duplicados y estados fuera de orden.
12. Conciliar reporte del proveedor con registros internos y emitir diferencias.
13. Incluir métricas, trazas, alertas y un runbook de incidentes.

## Restricciones negociadas (ficticias)

- Moneda MVP: exclusivamente COP; formato decimal exacto, precisión 19 y escala 2.
- Importe unitario mayor que 0 y menor o igual a 50.000.000,00 COP.
- Saldo inicial de cuentas: cero salvo asiento autorizado de apertura.
- Una transferencia interna confirmada es irreversible en MVP; errores posteriores se corrigen mediante asientos compensatorios explícitos, no borrando filas.
- La solución debe correr localmente sin cuentas de nube y usar datos sintéticos.
- HTTP sin sesión; componentes con límites claros, evitando microservicios prematuros.

## Fuera de alcance

Transferencias reales a bancos, integración con redes reguladas, tratamiento de datos bancarios reales, divisas, intereses, comisiones, KYC efectivo, fraude con ML, infraestructura HA multi-región y certificación de compliance. Se pueden discutir arquitectónicamente, pero no simular una certificación real.

## Métricas y resultado de negocio

- 0 pérdidas o duplicaciones en escenarios deterministas de prueba.
- 100% de transferencias internas exitosas con asientos contables balanceados.
- Toda transacción con `correlationId`, actor y marca de tiempo.
- Ningún error esperado de negocio expresado como HTTP 500.
- Informe de conciliación que separe faltantes, importes distintos, duplicados y estados inconsistentes.

**Nota:** los objetivos son criterios de la simulación, no indicadores reportados por GFT ni de un banco real.
