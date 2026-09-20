# 16. Preparación inicial de pacientes por lotes

> Extensión posterior: [documento 19](19_FLUJO_DE_ENCUENTROS.md).
> La misma tarea puede preparar encuentros después de pacientes al habilitar
> `SYNCMR_PREPARE_ENCOUNTERS_ENABLED=true`.


Este incremento incorpora pacientes que ya existían antes de instalar el OMOD.
Prepara sus eventos locales; no significa que otro establecimiento ya los recibió.
No depende de crear encuentros y puede ejecutarse sin Internet, tanto en MASTER
como en POSTA.

## Funcionamiento

1. La tarea abre una sesión técnica local y se autentica en OpenMRS.
2. `PatientSyncService.prepareExistingPatients(limit)` abre la transacción del lote.
3. Se valida y bloquea la identidad local `server.id`, usando el mismo bloqueo que
   la captura de altas y la recepción de pacientes.
4. Se seleccionan, por `patient_id` local, hasta el límite indicado de pacientes
   activos pendientes. No se carga toda la base en memoria.
5. Se reutiliza `ensurePatientSyncRecord`: conserva el paciente y su UUID, asigna
   origen y secuencia y guarda el evento con el JSON actual de esquema 4.
6. Se confirma el lote. La sesión se cierra antes de esperar el siguiente intervalo.

Las identidades y eventos persistidos indican qué trabajo ya se completó. No hace
falta una marca de “primera vez” que pudiera quedar activada tras una ejecución
parcial. Tras detener y reiniciar el módulo, se vuelven a consultar los pendientes.
Si no hay ninguno, el lote devuelve cero sin crear eventos ni consumir secuencias.
Mientras esté habilitada, la tarea sigue comprobando si aparecen pendientes.

Se excluyen pacientes anulados y registros ya preparados, incluidos los importados
de otras postas. No se cambia su origen ni se regenera su copia JSON por una edición
posterior. Tampoco se concilian personas registradas independientemente en varias
postas.

## Errores e interrupciones

El lote es atómico: un fallo revierte sus identidades, eventos y avances de contador.
Los lotes anteriores confirmados se conservan. La tarea vuelve a intentar después
del intervalo. No se saltan silenciosamente pacientes con errores.

Una identidad cuyo evento o JSON falte sigue figurando como pendiente y detiene el
lote al intentar prepararla. No se inventa otra copia histórica ni se declara
terminado ese trabajo. Una corrección de datos o catálogos puede ser necesaria;
reintentar no resuelve por sí solo un formato de atributo no admitido.

La detención interrumpe el trabajador y se comprueba esa interrupción entre pacientes.
Las operaciones JDBC en curso pueden necesitar tiempo para terminar. No hay llamadas
de red dentro de la transacción de preparación.

## Activación para las instancias de prueba

Primero configurar la Global Property `server.id` y una cuenta técnica local con
`Prepare Synchronization Records` y `Get Patients`. Las referencias de atributos
pueden requerir permisos de lectura de conceptos o ubicaciones. Para consultar el
contador de pendientes se requiere también `View Synchronization Records`.

La activación es explícita para permitir ejecutar esta carga fuera de atención, como
se indicó en la reunión. No se ejecuta una carga masiva automáticamente al subir un
OMOD sin configuración. El proceso comienza al arrancar el módulo con:

| Variable de entorno | Valor o significado |
| --- | --- |
| `SYNCMR_PREPARE_EXISTING_ENABLED` | `true` para habilitar; por defecto `false`. |
| `SYNCMR_PREPARE_BATCH_SIZE` | De 1 a 100 pacientes; por defecto 25. |
| `SYNCMR_PREPARE_INTERVAL_SECONDS` | De 10 a 86400 segundos; por defecto 60. |
| `SYNCMR_LOCAL_USERNAME` | Usuario técnico local de OpenMRS. |
| `SYNCMR_LOCAL_PASSWORD` | Contraseña local, sin persistirla en el repositorio. |

No requiere endpoint, usuario remoto ni `SYNCMR_ENABLED=true`. Esta última variable
controla el transporte periódico ya existente, de forma independiente. Si ambos
están activos, el transporte puede enviar los lotes que ya se hayan confirmado.
La primera ejecución espera el intervalo configurado. Para deshabilitar, cambiar la
variable y reiniciar el proceso OpenMRS; editar otra terminal no cambia el entorno
del proceso en ejecución. No se ha añadido un programador de horario nocturno:
la ventana de ejecución se controla al habilitar y detener el proceso.

Para una llamada Java explícita con contexto autenticado:

```java
PatientSyncService service = Context.getService(PatientSyncService.class);
long pending = service.countPatientsPendingPreparation();
int prepared = service.prepareExistingPatients(25);
```

No envolver repetidas llamadas en una única transacción externa si se quiere
confirmar cada lote por separado. El trabajador suministrado ya respeta esto.

## Comprobación en DBeaver

En la base de la instancia que esté ejecutando la preparación:

```sql
SELECT COUNT(*) AS pacientes_pendientes
FROM patient p
JOIN person pe ON pe.person_id = p.patient_id
LEFT JOIN synchronizationmr_patient_identity i ON i.patient_id = p.patient_id
LEFT JOIN synchronizationmr_patient_event e ON e.patient_id = i.patient_id
WHERE pe.voided = FALSE
  AND (i.patient_id IS NULL OR e.event_uuid IS NULL
       OR e.payload_json IS NULL OR TRIM(e.payload_json) = '');

SELECT server_id, patient_sequence
FROM synchronizationmr_local_node;

SELECT i.patient_id, i.origin_server_id, i.entity_sequence, e.event_uuid
FROM synchronizationmr_patient_identity i
JOIN synchronizationmr_patient_event e ON e.patient_id = i.patient_id
ORDER BY i.origin_server_id, i.entity_sequence;
```

Pendientes en cero significa que los pacientes activos tienen identidad y evento con
contenido. No demuestra que estén recibidos en el maestro ni certifica que un JSON
histórico contiene todos los campos del esquema más reciente.

## Alcance

No se añaden tablas ni changesets. El progreso usa las tablas existentes. No se
tocaron las bases ni los contenedores del usuario. Se mantiene pendiente la prueba
entre instancias reales y el trabajo de encuentros, órdenes y otros datos del paciente.


## Validación realizada

`mvn -o package`: BUILD SUCCESS, 112 pruebas sin fallos, errores ni omisiones.
Las nuevas pruebas cubren límites, avance por lotes sin duplicados, conservación
del JSON, reanudación tras rollback con un lote anterior confirmado, evento sin
contenido, interrupción, configuración independiente de la red y reversión completa
cuando el segundo paciente del lote tiene un atributo no admitido. Los datos para
estas pruebas se aíslan en H2; no se modifican las bases reales.

Paquete: `synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`.
