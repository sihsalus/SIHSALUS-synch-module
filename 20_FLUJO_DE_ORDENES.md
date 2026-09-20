# 20. Primera versión del flujo de órdenes y relación entre las tres entidades

> Ajuste posterior: [preparación de órdenes y dependencias](24_ORDENES_PREPARACION_Y_DEPENDENCIAS.md). La carga inicial prepara la orden anterior antes de su dependiente y señala bloqueos sin dar la carga por terminada.


> Incremento posterior: [historial de observaciones](22_ENCUENTROS_HISTORIAL_DE_OBSERVACIONES.md). Ya se admiten versiones anteriores incluidas en el mensaje o disponibles en destino; las restricciones históricas sobre ese caso se actualizan allí.


Estado: implementado y comprobado con pruebas automatizadas; pendiente validación entre las tres instancias reales.
Este documento actualiza los documentos 18 y 19 respecto a órdenes y referencias desde observaciones.

## Qué queda disponible

| Flujo | Primera versión disponible | Aún no significa |
| --- | --- | --- |
| Pacientes | Captura de altas, preparación de activos existentes, JSON, transporte y recepción | Resolver automáticamente dos fichas independientes de la misma persona, replicar todas las modificaciones o todas las entidades asociadas |
| Encuentros | Alta, preparación de activos existentes, observaciones y grupos, transporte, recepción y referencias a órdenes | Replicar visitas, diagnósticos, condiciones, archivos complejos o versiones anteriores de observaciones |
| Órdenes | Alta, preparación de activas/no anuladas existentes, JSON, transporte, recepción, medicamentos, exámenes y órdenes base | Replicar grupos de órdenes, extensiones clínicas personalizadas ni todos los cambios posteriores de estado |

Las órdenes históricas detenidas o vencidas también entran en la preparación si no están anuladas: aquí “no anulada” no equivale a tratamiento actualmente activo.

## Flujo completo

1. El profesional guarda una orden mediante OpenMRS. El módulo intercepta `saveOrder`, `saveRetrospectiveOrder` y las dos variantes de `discontinueOrder` que crean una nueva orden de suspensión. Guarda el evento JSON y su secuencia en la misma transacción de la orden.
2. No necesita conexión para registrar el evento. No crea implícitamente un paciente ni un encuentro para poder enviar una orden.
3. Los registros anteriores al OMOD se incorporan mediante preparación por lotes: pacientes, encuentros y órdenes. Esta preparación lee registros clínicos y escribe sus eventos; no vuelve a crear los registros clínicos originales.
4. Con los tres endpoints habilitados, la posta ejecuta pacientes → encuentros → órdenes. Cada entidad conserva secuencias y confirmaciones independientes por origen. Un fallo interrumpe el ciclo y se reintenta en el siguiente intervalo.
5. En cada ciclo de una entidad, la posta consulta cuánto confirmó el maestro, envía sus eventos pendientes y obtiene los eventos de los demás orígenes. El maestro conserva el origen y el JSON original para distribuirlos a otras postas. Los eventos creados en el propio maestro también pueden ser obtenidos por las postas.
6. Al recibir una orden se resuelven por UUID su paciente, encuentro, profesional, concepto, tipo de orden y ámbito de atención. Los catálogos deben estar disponibles en el destino. El paciente del encuentro debe coincidir con el de la orden.
7. El receptor utiliza el servicio nativo de OpenMRS para el guardado retrospectivo, conserva el evento recibido y confirma su secuencia dentro de una sola transacción. No genera otro evento local por esa importación.
8. Si se pierde la respuesta después de guardar, el mismo evento puede reenviarse: se comprueban secuencia, UUID del evento, UUID de la orden y contenido antes de devolver la confirmación. Un contenido distinto en una secuencia confirmada se rechaza.

La programación sigue siendo opcional. Instalar el OMOD crea el esquema y registra los componentes; no configura automáticamente usuarios, HTTPS, `server.id`, rol, endpoints ni horarios nocturnos.

## Paciente, encuentro e identidad

`server.id` identifica el establecimiento. Una orden puede tener identidad de sincronización `posta_A / ORDER / 8`, un UUID clínico y un `order_id` numérico local. Son conceptos distintos.

El UUID clínico se conserva al copiar la orden. Los identificadores numéricos locales no se copian como claves entre bases. El paciente y el encuentro se buscan por sus UUID clínicos conservados.

Ejemplo: Ana llegó originalmente desde B a A. En A se registra un encuentro y una orden para Ana. El encuentro y la orden se originan en A, pero referencian el UUID de la ficha de Ana creada en B.

En la versión de OpenMRS usada por el proyecto (API 2.4.2), una orden nativa requiere un encuentro. Esto difiere de la posibilidad de órdenes sin encuentro mencionada en la reunión. El receptor respeta el modelo nativo: si falta el encuentro, no inventa uno y no confirma la orden. Las órdenes siguen siendo una entidad de sincronización independiente aunque tengan esta relación obligatoria.

OpenMRS genera un `orderNumber` local al guardar. El número original se conserva en `sourceOrderNumber` dentro del JSON. No se promete que dos instancias muestren el mismo número de orden; la identidad compartida se basa en UUID y origen/secuencia.

## Qué contiene el JSON

Sobre común: `schemaVersion: 1`, `entityType: ORDER`, `operation: CREATE`, origen, secuencia, UUID del evento y momento de captura.

`CREATE` significa incorporar una fila de orden. Una revisión o suspensión también crea una fila nativa nueva y se distingue por `payload.action` y `previousOrderUuid`.

| Sección | Información |
| --- | --- |
| Relaciones | UUID de orden, paciente, encuentro, tipo, concepto, profesional, ámbito de atención, motivo codificado, orden anterior y referencia de grupo |
| Datos generales | Instrucciones, número de acceso, motivo libre, urgencia, acción, comentarios, estado del ejecutor, peso de ordenación y anulación |
| Fechas | Activación, vencimiento, programación, detención y detención de la orden anterior cuando corresponde |
| Medicamento (`DRUG`) | Fármaco, dosis/unidades, frecuencia, vía, cantidad/unidades, duración/unidades, repeticiones, indicación según necesidad, marca, instrucciones y medicamento no codificado |
| Examen (`TEST`) | Muestra, lateralidad, historia clínica, frecuencia y número de repeticiones |

No se serializan objetos Hibernate completos, IDs locales de catálogos ni clases Java arbitrarias. Los estilos de dosificación admitidos son `SimpleDosingInstructions` y `FreeTextDosingInstructions`; otros estilos se rechazan explícitamente.

## Encuentros y observaciones que esperan una orden

Existe una dependencia circular: el encuentro puede mencionar una orden que todavía no llegó, mientras que la orden necesita que exista el encuentro.

El encuentro se recibe con sus observaciones y se registra cada relación pendiente en `synchronizationmr_order_link`. No se crea una orden ficticia. El JSON conserva el UUID esperado.

Cuando llega la orden:

- Se comprueba que pertenece al paciente esperado.
- Para la lista de órdenes del encuentro, se comprueba también que la orden pertenece a ese encuentro.
- Para una observación, la orden puede provenir de otro encuentro del mismo paciente, como ocurre con un resultado posterior.
- Se completa la clave `obs.order_id` y se elimina el vínculo pendiente, dentro de la transacción de recepción de la orden.

Si la orden ya existía al recibir la observación, el vínculo se completa en esa recepción. Un conflicto no se resuelve moviendo una orden a otro paciente o encuentro.

OpenMRS considera inmutable una observación guardada. Completar esta referencia de importación usa una actualización JDBC limitada a `obs.order_id` y recarga la entidad en Hibernate; no crea una nueva versión clínica de la observación. Las fechas originales de órdenes también se restauran de forma explícita después del guardado nativo, en la misma transacción, porque el servicio nativo puede normalizarlas.

**Confirmar un encuentro significa que su contenido y sus dependencias pendientes quedaron guardados. No significa que ya hayan llegado todas sus órdenes.** Para verificar completitud debe revisarse también la tabla de vínculos pendientes.

## Revisiones, suspensiones y datos históricos

Una orden nueva de revisión o suspensión referencia explícitamente la anterior. El receptor comprueba la relación y deja que OpenMRS aplique sus reglas; no selecciona automáticamente otra orden por parecido.

Una orden histórica anterior puede llegar ya detenida. Para recibir la fila de revisión/suspensión que explica esa detención, el receptor exige que la fecha coincida con el JSON previo conservado. Reaplica el cierre en la misma transacción y restaura la fecha original. Ante inconsistencias o cambios locales incompatibles se rechaza la recepción.

Esto no implementa un flujo general de UPDATE/DELETE: cambios posteriores de ejecutor, anulaciones, correcciones de pacientes o encuentros y otras mutaciones requieren un contrato adicional. Tampoco se fuerza la aceptación de medicamentos que las reglas nativas rechacen por tratamientos incompatibles o superpuestos.

## Configuración adicional

Además de la configuración de pacientes y encuentros del documento 19:

```text
SYNCMR_MASTER_ORDER_ENDPOINT=https://<maestro>/openmrs/moduleServlet/synchronizationmr/orderSync
SYNCMR_PREPARE_ORDERS_ENABLED=true
```

El endpoint de órdenes requiere que esté configurado el de encuentros. La preparación de órdenes requiere `SYNCMR_PREPARE_EXISTING_ENABLED=true` y `SYNCMR_PREPARE_ENCOUNTERS_ENABLED=true`.

La preparación se puede activar para la carga inicial fuera de atención y desactivar después. No se añadió un calendario que detecte automáticamente la noche. También puede invocarse `OrderSyncService.prepareExistingOrders(limite)` bajo contexto autenticado, con lotes de 1 a 100.

El transporte es HTTP REST sobre HTTPS, con GET/POST y JSON. Usa los servlets del OMOD, Spring y la API de OpenMRS; no se creó una aplicación Spring Boot independiente. El nombre de la carpeta `controller` no determina que una petición sea REST.

El usuario técnico necesita los permisos de sincronización y los permisos clínicos correspondientes (`Add Orders`, `Get Orders`, consulta de dependencias y observaciones). La preparación requiere `Prepare Synchronization Records`. Se mantienen las verificaciones de rol y origen del par remoto.

## Tablas y comprobación en DBeaver

Liquibase añade estructuras del módulo sin borrar tablas clínicas:

| Tabla/campo | Propósito |
| --- | --- |
| `synchronizationmr_local_node.order_sequence` | Contador local de órdenes |
| `synchronizationmr_order_event` | Identidad, referencia local, origen, secuencia y JSON inmutable |
| `synchronizationmr_order_receipt` | Última secuencia consecutiva recibida por origen |
| `synchronizationmr_order_link` | Relaciones de encuentros/observaciones pendientes de completar |
| `orders`, `drug_order`, `test_order` | Registros clínicos nativos guardados en el destino |

Consultas de lectura para las pruebas posteriores:

```sql
SELECT origin_server_id, entity_sequence, order_uuid, order_id, operation
FROM synchronizationmr_order_event
ORDER BY origin_server_id, entity_sequence;

SELECT * FROM synchronizationmr_order_receipt;

SELECT order_uuid, encounter_uuid, patient_uuid, obs_uuid
FROM synchronizationmr_order_link;

SELECT order_id, uuid, patient_id, encounter_id, order_number,
       previous_order_id, order_action, date_activated, date_stopped
FROM orders;
```

`state=PENDING` no prueba que ningún destino haya recibido un evento: se conservan eventos para varios destinos. Se comprueban las confirmaciones por origen, como en pacientes y encuentros.

El endpoint de órdenes expone los mismos recursos `node`, `origins`, `status`, `events` y `receive`. `GET ?resource=status&origin=<server.id>` incluye `pendingOrderLinksTotal`, total de vínculos pendientes en esa instancia (no limitado al origen consultado). También está disponible `OrderReceiveService.countPendingOrderLinks()`.

## Validación y pendientes de campo

La compilación completa final ejecutó **190 pruebas: 150 API y 40 OMOD, sin fallos ni errores**. Registro: `synchronizationmr/api/target/order-full-build.log`.

Las pruebas nuevas cubren captura local, preparación por lotes y reanudación, recepción con paciente/encuentro, medicamentos y exámenes, revisiones, suspensiones, históricos detenidos, reintentos, secuencias, rollback real, resolución de vínculos en ambos órdenes de llegada, rechazo de relaciones con otro paciente y contrato HTTP con autenticación simulada.

Las pruebas de integración usan H2 y OpenMRS API 2.4.2. Las pruebas de cliente/endpoint de órdenes simulan HTTP; no sustituyen una prueba real entre servidores con TLS. Quedan por validar las tres instancias, MariaDB, la versión de ejecución de OpenMRS, los catálogos compartidos y las interrupciones reales de conexión.

Siguen fuera del contrato inicial los grupos de órdenes, subclases personalizadas, visitas no disponibles, diagnósticos/condiciones del encuentro, archivos complejos y versiones anteriores de observaciones. No se confirman silenciosamente datos no admitidos. Un evento rechazado queda sin confirmar y detiene la secuencia de ese origen hasta resolver su causa.

La asignación de identidad a registros existentes no concilia dos fichas independientes de la misma persona. Si varias bases ya contienen copias previas sin recibos de sincronización, se debe revisar ese escenario antes de una carga general.

Artefacto generado: `synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`. Debe instalarse/actualizarse en cada instancia: guardar cambios en el código no actualiza el OMOD que ya está cargado.
