# Primer incremento: registro de pacientes nuevos

> Actualización posterior: ahora los eventos nuevos guardan una copia JSON de los datos básicos
> del paciente. Véase [05_JSON_DE_CREACION_DEL_PACIENTE.md](05_JSON_DE_CREACION_DEL_PACIENTE.md).
> Las referencias de esta guía al evento sin contenido describen el primer incremento anterior.
> El total actual es 26 pruebas; los totales de 22 que siguen corresponden al paso del nodo independiente.

## Explicación sencilla

Actualización: la identidad del nodo ya puede obtenerse sin crear un paciente. Se añadió
`LocalNodeService.getOrCreateNodeUuid()` y un `LocalNodeDao` común. No se genera automáticamente
al arrancar: se genera cuando una operación solicita el UUID y se confirma su transacción.
Si ya existe se conserva; obtenerlo no avanza la secuencia de pacientes ni crea eventos.
Las futuras capturas de encuentros y órdenes podrán utilizar esta operación, pero aún no
están implementadas. No se modificó la estructura de las tablas.

Desde la carpeta `synchronizationmr`, ejecutar las cuatro pruebas del nodo:

```powershell
mvn -pl api '-Dtest=LocalNodeServiceIntegrationTest' test
```

Comprueban obtención repetida sin crear pacientes ni eventos, persistencia sin transacción
externa, reversión al cancelar la transacción y dos primeras llamadas simultáneas.
Además se añadió `PatientCaptureIntegrationTest#patientUsesPreviouslyInitializedNodeUuid`,
que comprueba que un paciente nuevo conserva el origen preparado previamente.
El total actual es 22 pruebas: 8 unitarias, 10 de integración de pacientes y 4 del nodo.

El UUID del paciente y la identidad de sincronización de origen deberán conservarse al
recibir un paciente en otro nodo. La recepción todavía no está implementada; esta decisión
no resuelve registros independientes de una misma persona ni la incorporación de pacientes anteriores.

En este documento, «alta» significa registrar un paciente nuevo; no significa darle el alta médica.
Lo implementado guarda al paciente en OpenMRS y deja anotado que su creación está pendiente de
sincronizar. Ambos registros se guardan juntos. Todavía no se envía nada al maestro.

La tabla de eventos pendientes es **el comienzo de la cola de sincronización**. Falta implementar
quién lee esa cola, envía los datos, reintenta cuando vuelve la conexión y registra las confirmaciones.
El pendiente se registra siempre, haya o no Internet. Por ahora guarda una referencia al paciente,
no una copia de sus datos clínicos.

El identificador de sincronización del paciente es la combinación **UUID del nodo de origen +
tipo PATIENT + número secuencial**. Para mostrarlo de forma entendible usamos, por ejemplo,
**POSTA-01 / PACIENTE / 1**. El nombre POSTA-01 es una etiqueta configurable; internamente se usa
el UUID del nodo para distinguirlo aunque otra posta tenga el mismo nombre.
El número 1 por sí solo no es único entre postas. Este identificador pertenece al paciente;
el UUID del evento identifica la anotación de su creación y es otro dato distinto.

## Columnas de las tres tablas nuevas

Los datos personales siguen en las tablas de OpenMRS. Estas tres tablas guardan el control de
sincronización. Liquibase es el archivo de instrucciones que las crea al instalar el módulo.

### synchronizationmr_local_node: quién soy y qué número sigue

| Columna | Qué guarda |
|---|---|
| `singleton_id` | Siempre 1: identifica la única fila de configuración local. No identifica a un paciente. |
| `node_uuid` | Código estable de esta instalación, generado al solicitarlo al servicio común y confirmar la transacción. |
| `patient_sequence` | Último número de sincronización asignado a un paciente aquí. Comienza en 0. |

El nombre visible POSTA-01 se configura en la propiedad `synchronizationmr.nodeLabel`, fuera de esta tabla.

### synchronizationmr_patient_identity: qué identidad tiene cada paciente

| Columna | Qué guarda |
|---|---|
| `patient_id` | Número interno del paciente en esta base OpenMRS; enlaza con `patient.patient_id`. |
| `patient_uuid` | UUID que OpenMRS ya asignó al paciente. |
| `origin_node_uuid` | UUID del nodo donde se creó el paciente. |
| `entity_sequence` | Número secuencial de sincronización asignado al paciente en su origen. |

El tipo PATIENT está implícito porque esta tabla es exclusiva de pacientes; no hay una columna
`entity_type`. El origen se copia desde el nodo local al crear el registro, pero no hay una clave
foránea hacia la tabla local de nodos: más adelante también habrá identidades de otros orígenes.

### synchronizationmr_patient_event: qué creación queda pendiente

| Columna | Qué guarda |
|---|---|
| `event_uuid` | Código único de este evento de creación. |
| `patient_id` | Enlace al paciente registrado en la tabla de identidades anterior. |
| `operation` | `CREATE`, que significa creación. |
| `state` | `PENDING`, que significa pendiente. La demostración lo muestra como PENDIENTE. |
| `date_created` | Fecha y hora en que se registró el evento. |

En esta etapa se admite un evento de creación por paciente. Todavía no es un historial de todas
sus modificaciones ni una lista de entregas a cada destino.

Por ejemplo, si OpenMRS asigna `patient_id = 42` y nuestro contador asigna la secuencia `1`,
la tabla de identidades enlaza el paciente 42 con su origen y secuencia 1. La tabla de eventos
también usa `patient_id = 42` para indicar qué paciente está pendiente. El 42 y el 1 cumplen
funciones diferentes y no tienen por qué coincidir.

## Situaciones importantes con ejemplos

- **Guardar otra vez el mismo paciente:** no recibe otro número ni otro evento de creación.
  Esto todavía no sincroniza sus modificaciones ni detecta si registraron a la misma persona
  como dos pacientes distintos.
- **Crear dos pacientes al mismo tiempo:** el contador se usa por turnos, de modo que cada uno
  recibe un número diferente.
- **Fallar el guardado local del paciente o del evento:** se deshacen juntos los cambios del
  paciente, contador, identidad y evento. Se informa el error; el registro deberá intentarse de nuevo.
- **No tener Internet:** este paso no hace llamadas de red, así que puede dejar el evento pendiente
  si la base local funciona. El envío al recuperar la conexión todavía falta implementarlo.
- **Registrar como paciente a una persona que ya existía en OpenMRS:** también cuenta como
  creación de paciente, aunque esa persona ya tuviera un número interno.
- **Cambiar el nombre visible de la posta:** cambia la etiqueta mostrada, pero se conservan el
  UUID del origen y la secuencia del paciente.

## Qué se implementó

Verificación del 2026-09-14: `mvn -o package` terminó correctamente; 8 pruebas unitarias y 14 pruebas de integración local, sin fallos, errores ni casos omitidos. El paquete se generó en `synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`. No se desplegó en un servidor.

Captura de altas realizadas por `PatientService.savePatient`, incluidas las conversiones de una Person existente a Patient. Registra una identidad de sincronización y un evento CREATE/PENDING. Las ediciones de pacientes existentes no crean otra identidad ni otro evento de alta.

Este incremento **todavía no transmite datos entre servidores**, no captura encuentros/órdenes y no sincroniza modificaciones. Pacientes, encuentros y órdenes siguen dentro del alcance acordado, pero el primer recorrido implementado es el registro local del paciente. El evento pendiente almacena una referencia al paciente; el contrato JSON, el contenido a transmitir, revisiones y entregas por destino corresponden al siguiente incremento.

## Identificador entendible sin depender del nombre de una posta

| Campo | Ejemplo | Función |
|---|---|---|
| Nombre visible del nodo | POSTA-01 | Ayuda a interpretar la demostración. Se configura con `synchronizationmr.nodeLabel`. |
| UUID del nodo de origen | UUID generado y persistido localmente | Identidad técnica estable, independiente del nombre visible. |
| Tipo de entidad | PATIENT | Identifica la secuencia de pacientes. |
| Secuencia | 1 | Número asignado en ese nodo para ese tipo. |
| UUID del paciente | UUID nativo de OpenMRS | Referencia al paciente, distinta del número secuencial. |
| UUID del evento | UUID propio de la operación pendiente | Identifica el evento CREATE que se registró. |

La representación legible será **POSTA-01 / PACIENTE / 1**. No se guarda ni se analiza como una cadena compuesta: la identidad técnica se forma con UUID del origen, tipo y secuencia. Dos postas pueden tener un paciente número 1 sin confundirlos. Cambiar el nombre visible no modifica la identidad.

El nombre por defecto es NODO-LOCAL, no un establecimiento real. El UUID del nodo se obtiene mediante LocalNodeService.getOrCreateNodeUuid(), sin necesitar un paciente. Se conserva cuando se confirma la transacción. La captura de pacientes reutiliza el mismo LocalNodeDao. No clonar una base ya inicializada para crear otra posta independiente: copiaría también esa identidad. El aprovisionamiento/registro de nuevos nodos y la importación remota todavía deben diseñarse; el nombre visible por sí solo no distingue clones.

## Cómo funciona este incremento

1. El advice reconoce `savePatient` y comprueba si ya existe la fila de paciente antes del guardado.
2. Participa en la transacción actual o abre una con el gestor transaccional de OpenMRS para envolver el guardado y la captura.
3. OpenMRS guarda al paciente mediante sus servicios normales.
4. El DAO bloquea una fila local de contador hasta finalizar la transacción. Asigna la siguiente secuencia y guarda identidad y evento.
5. Se confirma todo junto. Si falla la captura, se revierte también el guardado clínico; no se oculta el error.

No hay ninguna llamada de red en este recorrido. La independencia de la conectividad no implica que un fallo de la base local deba ignorarse. El advice necesita que el módulo esté activo: las altas previas a instalarlo o realizadas mientras está detenido no se recuperan automáticamente.

Se eligió JDBC parametrizado sobre la conexión de la sesión Hibernate actual para estas tres tablas sencillas. No se abre otra conexión ni otra base; se conserva la misma transacción que usa OpenMRS. Las escrituras clínicas continúan exclusivamente en los servicios nativos. Esta es una concreción de la propuesta DAO/Hibernate del análisis, no un segundo motor de persistencia.

Tablas nuevas:

- `synchronizationmr_local_node`: UUID del nodo y último número de paciente asignado, protegidos por bloqueo transaccional.
- `synchronizationmr_patient_identity`: paciente local, UUID clínico e identidad de sincronización; restricciones únicas y FK real a `patient`.
- `synchronizationmr_patient_event`: operación CREATE, estado PENDING y fecha; una alta por identidad en este incremento.

No ejecutar UPDATE/DELETE sobre estas tablas para preparar una demostración. Las FKs también impiden purgar físicamente un paciente referenciado; las políticas de eliminación y retención quedan pendientes.

## Demostración local sin interfaz gráfica

Desde la carpeta `synchronizationmr`, ejecutar en PowerShell:

```powershell
mvn -pl api '-Dtest=PatientCreationAdviceTest' test
```

Esto ejecuta las pruebas unitarias basadas en eventos: alta, edición, conversión de persona a paciente, error clínico, error de captura, manejo de la transacción y métodos que no corresponde interceptar. Las dependencias están simuladas; no presenta una transmisión entre nodos.

Para mostrar la creación real en el contexto de pruebas OpenMRS, ejecutar:

```powershell
mvn -pl api '-Dtest=PatientCaptureIntegrationTest#capturesWithoutAnOuterTestTransaction' test
```

La prueba crea datos sintéticos en una base de pruebas aislada, ejecuta el servicio real, comprueba el registro y muestra una línea de este formato:

```text
CAPTURA CONFIRMADA: POSTA-01 / PACIENTE / <secuencia> | origen=<UUID> | estado=PENDIENTE
```

La secuencia y el UUID son valores de la ejecución. No se imprimen nombres ni otros datos personales. `PENDING` significa **registrado y pendiente de envío**, no recibido por el maestro. La prueba comprueba los datos con aserciones; imprimir un mensaje por sí solo no demostraría que se guardaron.

Para ejecutar todas las pruebas del API:

```powershell
mvn -pl api test
```

Para compilar el proyecto completo y generar el OMOD:

```powershell
mvn package
```

Usar `package` para construir ambos submódulos desde cero: el empaquetado heredado necesita el JAR de `api` antes de expandir sus recursos en `omod`. Los informes detallados quedan en `synchronizationmr/api/target/surefire-reports`. Las pruebas usan datos sintéticos, no las historias clínicas de un servidor del proyecto.

El perfil Maven `tests-on-modern-jdk`, activo desde Java 9, permite los accesos de reflexión que necesitan las dependencias antiguas del contexto de pruebas. No modifica el runtime del OMOD ni certifica que OpenMRS 2.4.2 deba desplegarse con Java 21. La compilación conserva el destino Java 8 del proyecto.

## Qué comprueban las pruebas de integración local

- Paciente e identidad/evento pendientes guardados conjuntamente.
- Repetir un guardado o una solicitud interna de captura no duplica la identidad ni el evento.
- Secuencias diferentes para pacientes sucesivos y para dos altas concurrentes.
- Conversión de persona existente a paciente.
- Cambio de nombre visible sin renumeración.
- Rollback de paciente, identidad, evento y contador.
- Persistencia al confirmar y consultar desde una transacción nueva.
- Captura sin una transacción de prueba externa: el advice y el servicio funcionan con una frontera transaccional real.
- Fallo simulado después de insertar el evento: también revierte al paciente, aunque no exista una transacción externa.

La suite verifica la migración real del OMOD en una base de pruebas local y el contexto real de OpenMRS 2.4.2. Aún se debe validar el bloqueo y las migraciones en el motor y versión de base del despliegue, instalar el OMOD en una instancia completa y comprobar después el protocolo entre nodos.

## Demostración posterior ante el jurado

Con el servidor y el transporte implementados, mostrar el caso completo usando solicitudes HTTP autenticadas (por ejemplo desde Postman o consola), consultas de estado y evidencia persistida: alta en posta A → recepción en maestro → recepción en posta B. Desconectar el enlace manteniendo activa la posta, registrar nuevas altas, comprobar sus pendientes y restablecer el enlace para demostrar recuperación sin duplicados.

Ese escenario posterior será integración entre instancias. La demostración actual prueba exclusivamente la captura local durable, que es su prerrequisito.
