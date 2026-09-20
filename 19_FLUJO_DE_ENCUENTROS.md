# 19. Preparación y transporte de encuentros

> Incremento posterior: [historial de observaciones](22_ENCUENTROS_HISTORIAL_DE_OBSERVACIONES.md). Ya se admiten versiones anteriores incluidas en el mensaje o disponibles en destino; las restricciones históricas sobre ese caso se actualizan allí.


> Actualización: [órdenes y relación entre las tres entidades](20_FLUJO_DE_ORDENES.md). Las referencias a órdenes ya se reciben y se completan mediante vínculos persistentes; la restricción histórica de rechazar toda referencia a una orden queda superada.


## Identidades y referencias

`server.id` identifica al establecimiento. Cada entidad usa una secuencia independiente:
`posta_A / PATIENT / 1` y `posta_A / ENCOUNTER / 1` son registros diferentes.
Se eliminó el UUID del nodo, no los UUID nativos del paciente o del encuentro.

El UUID del paciente en el JSON del encuentro sirve para localizar la misma ficha
en el destino. El paciente puede proceder de B y el encuentro de A: el encuentro
conserva el UUID de ese paciente, pero su identidad de sincronización lleva el
origen A. No se utiliza el `patient_id` numérico de A para asociarlo en otra base.

## Recorrido implementado

1. Un alta nueva genera el evento de creación en la misma transacción que el
   registro clínico. No requiere conexión al maestro.
2. La preparación inicial incorpora registros activos anteriores al OMOD. Primero
   prepara pacientes por lotes; cuando una pasada no encuentra pacientes pendientes,
   puede preparar un lote de encuentros. No vuelve a guardar los encuentros clínicos.
3. La tarea de la posta ejecuta el ciclo de pacientes. Si termina correctamente y
   está configurado el endpoint de encuentros, ejecuta después su ciclo.
4. La posta consulta las confirmaciones de ENCOUNTER del maestro, envía eventos
   propios en orden y exige la confirmación de cada uno antes de avanzar.
5. Consulta los demás orígenes disponibles en el maestro y descarga los encuentros
   posteriores a su última confirmación local de cada origen.
6. El receptor busca al paciente por UUID y guarda encuentro, observaciones, evento
   original y confirmación en una sola transacción. Los reintentos coincidentes no
   vuelven a crear el encuentro. Los registros recibidos conservan su origen.
7. Las demás postas repiten ese ciclo contra el maestro. No intercambian directamente
   entre ellas. El maestro también puede originar encuentros para su descarga.

Se reutiliza el transporte HTTPS GET/POST con JSON. `EncounterSyncClient` comprueba
el nombre y rol del maestro, el protocolo y la entidad ENCOUNTER en las respuestas.
Las confirmaciones de pacientes y encuentros son independientes. Se procesan hasta
100 envíos propios y hasta 100 recepciones por origen en cada ciclo, como en pacientes.

## Conexión intermitente y dependencias

Los eventos están persistidos en la base, no solo en memoria. No se borran tras
enviarlos: pueden necesitarlos otros destinos. Las confirmaciones indican por dónde
continuar. `PENDING` no es una confirmación individual por destino.

Si falla la conexión, la ejecución periódica lo reintenta en el siguiente intervalo.
Si se perdió una respuesta después de guardar, la consulta de confirmaciones o el
reconocimiento del mismo evento evita duplicar la recepción.

Pacientes primero no implica que ya se hayan entregado todos: los ciclos tienen
límites y cada origen puede tener trabajo pendiente. Si falta el paciente de un
encuentro, el receptor rechaza el evento; no se confirma ni se salta su secuencia.
El siguiente ciclo vuelve a procesar pacientes antes de intentar los encuentros.
La creación/preparación de un encuentro no crea ni prepara automáticamente su paciente.

Un error de contenido o un catálogo ausente también detiene el ciclo. Reintentar
no resuelve esa causa. Continúan aplicando los límites del documento 18: órdenes
asociadas, archivos complejos, versiones anteriores de observaciones, diagnósticos
y condiciones aún no soportados. Una visita referenciada debe existir en destino.

Esta es una cola de eventos persistidos, no una réplica de transacciones SQL ni
una copia completa de tablas remotas. El JSON de preparación inicial representa
los datos en el momento de prepararlo, no su estado histórico original.

## Configuración añadida

| Variable | Función |
| --- | --- |
| `SYNCMR_PREPARE_ENCOUNTERS_ENABLED=true` | Incluye lotes de encuentros en la tarea inicial, después de pacientes. Por defecto false. Requiere `SYNCMR_PREPARE_EXISTING_ENABLED=true`. |
| `SYNCMR_MASTER_ENCOUNTER_ENDPOINT` | URL HTTPS completa del maestro terminada en `/moduleServlet/synchronizationmr/encounterSync`. Si no se define, el ciclo periódico sigue procesando solo pacientes. |

Se mantienen credenciales, identidad del maestro e intervalo del documento 10,
actualizados a `SYNCMR_MASTER_SERVER_ID`. La cuenta necesita los permisos de
encuentros y observaciones además de los de pacientes. La preparación usa el mismo
tamaño de lote e intervalo del documento 16; no necesita red ni rol POSTA.
No se modificó la configuración de las instancias del usuario.

Las operaciones Java nuevas son `prepareExistingEncounters(limit)` y
`countEncountersPendingPreparation()` en `EncounterSyncService`. Límite: 1 a 100.
Cada lote confirma por separado cuando se utiliza el trabajador suministrado.
Los eventos antiguos sin JSON se detectan y requieren revisión; no se regeneran.

## Comprobación en DBeaver

```sql
SELECT server_id, patient_sequence, encounter_sequence
FROM synchronizationmr_local_node;

SELECT encounter_uuid, patient_uuid, origin_server_id, entity_sequence, event_uuid
FROM synchronizationmr_encounter_event
ORDER BY origin_server_id, entity_sequence;

SELECT origin_server_id, confirmed_sequence
FROM synchronizationmr_encounter_receipt;
```

## Alcance pendiente

El circuito de preparación y transporte de encuentros queda implementado para el
contenido que admite el receptor. No se declara soporte completo de encuentros con
todos sus posibles datos: los casos rechazados del documento 18 siguen pendientes.
La preparación y sincronización de órdenes existentes corresponde al siguiente
flujo independiente y todavía no está implementada. Después se realizará la prueba
entre tres instancias, incluyendo interrupciones de conexión y catálogos compatibles.


## Validación local

`mvn -o package`: BUILD SUCCESS, 148 pruebas, sin fallos, errores ni omisiones.
Las pruebas añadidas verifican preparación sin crear filas clínicas, conservación del
JSON, reanudación después de rollback, eventos antiguos sin contenido y límites de lote.
El cliente se prueba con transporte simulado: identidad del maestro, secuencias,
confirmaciones, reintentos y recuperación tras fallos de comunicación. No son aún
pruebas entre instancias reales con MariaDB.
