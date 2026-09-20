# Consulta de eventos de pacientes por origen

> Actualización de identidad: [documento 14](14_IDENTIDAD_SERVER_ID_Y_ENTORNO.md).
> `server.id` sustituye al UUID de nodo; pacientes usan esquema 3, encuentros esquema 2
> y HTTP protocolo 2. Las referencias al UUID de origen y la configuración anterior
> que siguen describen la implementación histórica de este incremento.


> Paso posterior: [recepción local y confirmación consecutiva](07_RECEPCION_LOCAL_Y_CONFIRMACION.md).
> El total actual es 45 pruebas; las 34 indicadas abajo corresponden al incremento de consultas.

Este incremento implementa consultas Java para seleccionar eventos a entregar. No hace llamadas
HTTP, no recibe pacientes ni guarda confirmaciones del destino. El tipo de entidad es PATIENT;
encuentros y órdenes todavía no están implementados.

## Operaciones

`PatientSyncService.getHighestPatientSequence(originNodeUuid)` devuelve la mayor secuencia
registrada en la tabla de identidades para ese origen, o 0 si no encuentra registros.
No genera un UUID, no incrementa contadores y **no demuestra continuidad ni entrega**.
El origen se indica mediante un UUID válido, no mediante la etiqueta POSTA-01.

`PatientSyncService.getPatientEventsAfter(originNodeUuid, afterSequence, limit)` devuelve
eventos estrictamente posteriores a `afterSequence`, ordenados ascendentemente. El límite
admite entre 1 y 100 registros. La secuencia inicial admite 0 para comenzar desde el 1.
Un origen desconocido o una consulta después del último registro produce una lista vacía.

Ejemplo: existen las secuencias 1, 2 y 3 y el destino confirmó hasta el 1:

```java
PatientSyncService service = Context.getService(PatientSyncService.class);
List<PatientSyncEvent> events = service.getPatientEventsAfter(originUuid, 1, 50);
// Devuelve 2 y 3 con sus JSON originales. No confirma su entrega.
```

Cada `PatientSyncEvent` contiene origen, tipo PATIENT, secuencia, UUID del paciente, UUID del
evento y `payloadJson`. El contenido procede del evento persistido; no se vuelve a serializar
al paciente actual. No se utiliza el nombre del nodo local para etiquetar registros de otros orígenes.

## Reglas para no saltarse pendientes

- La primera secuencia de la página debe ser `afterSequence + 1`, y las demás consecutivas.
- Si falta una identidad intermedia, se produce un error al encontrar una secuencia posterior.
- Si existe la identidad pero no su evento, o el JSON es nulo o vacío, también se produce un error.
- Ante esos errores se rechaza la página completa, incluso si comenzó con registros válidos.
- Si el problema está fuera del límite de la página, se detectará al solicitar la página siguiente.
- Consultar no modifica el estado PENDING ni el contador ni crea una confirmación.

La consulta parte del cursor proporcionado: no comprueba que el destino tenga realmente los
registros anteriores. El futuro receptor deberá persistir una confirmación consecutiva por
origen y tipo solo después de guardar los registros. No debe avanzar ese cursor por haber leído
una página o por observar el MAX. Tampoco esta consulta reconstruye identidades eliminadas al
final de una secuencia; es selección de registros existentes, no una auditoría de integridad histórica.

No se filtra por un estado global de entrega: un evento entregado a un destino puede ser necesario
para otro. El registro de entregas por destino y la conservación de eventos para redistribución
forman parte del trabajo posterior.

## Ambas direcciones

La consulta usa el origen persistido, no exige que sea el nodo local. Por eso puede utilizarse
tanto al consultar una posta como al consultar el maestro cuando este tenga eventos importados.
La prueba de dos orígenes prepara directamente esos datos en la base de pruebas; **no demuestra
una recepción o redistribución por red**, que todavía no existe.

La consulta de máximo requiere `View Synchronization Records`. La consulta de eventos exige
también `Get Patients`, conjuntamente, porque los JSON contienen datos personales.
Son consultas de solo lectura dentro de las transacciones de OpenMRS; no hay endpoints públicos nuevos.

## Pruebas

Desde la carpeta `synchronizationmr`, ejecutar la demostración de páginas:

```powershell
mvn -pl api '-Dtest=PatientCaptureIntegrationTest#queriesOrderedPagesWithoutChangingPendingEvents' test
```

La prueba crea tres pacientes ficticios, consulta una página de dos y otra de uno, compara los
UUID y el JSON guardado, comprueba el final y verifica que todos siguen pendientes. Muestra:

```text
CONSULTA VERIFICADA: primera página=2 | segunda página=1 | sin confirmar entregas
```

Las otras cuatro pruebas cubren evento sin JSON, identidad sin evento, hueco de secuencia y
separación de orígenes con validación de argumentos. Se usa la base de pruebas de OpenMRS.

```powershell
mvn -pl api test
```

El total actual es 34 pruebas: 11 unitarias y 23 de integración local.
El siguiente paso será definir e implementar la recepción y la confirmación consecutiva,
incluyendo la resolución de referencias necesarias para crear al paciente en el destino.
