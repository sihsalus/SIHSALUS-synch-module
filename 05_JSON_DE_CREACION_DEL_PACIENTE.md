# Copia JSON de la creación del paciente

> Paso posterior implementado: [consulta de registros faltantes por origen](06_CONSULTA_DE_REGISTROS_FALTANTES.md).
> El total del proyecto pasó a 34 pruebas; las 29 indicadas abajo corresponden a este incremento del JSON.

Este incremento completa el contenido básico del evento pendiente. No envía datos al maestro
ni incorpora automáticamente pacientes anteriores a la instalación del módulo.

## Qué cambia

Antes guardábamos «se creó este paciente». Ahora guardamos también una copia de sus datos
básicos en ese momento. Si luego cambia su nombre, el JSON original permanece igual.

El flujo sigue siendo:

1. `PatientCreationAdvice` comprueba si el paciente es nuevo y continúa con `savePatient`.
2. `PatientSyncServiceImpl` solicita registrar la creación.
3. `PatientSyncDao` obtiene el origen y asigna la secuencia, descartando primero duplicados.
4. `PatientCreationPayloadSerializer` construye el JSON con campos explícitos.
5. El DAO guarda el JSON en el mismo evento y la misma transacción del paciente.

Si falla la construcción o el guardado del JSON, se revierte la creación junto con su identidad,
evento y avance del contador. No hay llamadas de red en este recorrido.

## Nueva columna y compatibilidad

`synchronizationmr_patient_event.payload_json` almacena el documento completo como texto largo
(tipo Liquibase `clob`). Se añadió un nuevo changeSet: no se reemplazó el que creó las tablas.
La columna permite nulos para conservar los eventos generados por versiones anteriores.

Los eventos antiguos no se rellenan automáticamente. Leer el paciente actual no garantiza
recuperar sus datos del momento de creación. La futura entrega debe detectar estos eventos
sin contenido y no tratarlos como mensajes listos para enviar; su recuperación está pendiente.

## Contrato básico, versión 2

| Campo exterior | Significado |
|---|---|
| `schemaVersion` | Versión de la estructura, actualmente 2. |
| `eventUuid` | UUID del evento persistido. |
| `originNodeUuid` | UUID estable del origen. |
| `entityType` | PATIENT. |
| `entitySequence` | Secuencia asignada en el origen. |
| `operation` | CREATE. |
| `occurredAt` | Instante de captura en formato ISO-8601 UTC. |
| `payload` | Datos básicos copiados del paciente. |

`payload` incluye:

- `patientUuid`: UUID nativo que deberá conservar el receptor.
- `gender`, `birthdate`, `birthdateEstimated`.
- `dead`, `deathDate`, `deathdateEstimated`, `causeOfDeathUuid`, `causeOfDeathNonCoded`.
- `names`: nombres activos con UUID, preferencia, prefijo, nombres, apellidos y grado.
- `identifiers`: identificadores activos, UUID, preferencia, UUID del tipo y de la ubicación.
- `addresses`: todas las direcciones no anuladas, incluidas las no preferidas.

### Direcciones y compatibilidad con la versión anterior

Los campos se contrastaron con `org.openmrs.PersonAddress` del código fuente de OpenMRS 2.4.2
instalado en el repositorio Maven local. No se asignan significados locales inventados a address1..15.

| Campos de cada dirección | Contenido |
|---|---|
| `uuid`, `preferred` | Identidad de la dirección y si es la preferida. |
| `address1` a `address15` | Componentes de dirección disponibles en OpenMRS; la instalación define sus etiquetas. |
| `cityVillage`, `countyDistrict`, `stateProvince`, `country`, `postalCode` | Localidad, distrito, región, país y código postal. |
| `latitude`, `longitude` | Coordenadas conservadas como texto, tal como las representa el modelo. |
| `startDate`, `endDate` | Fechas conservadas como instantes ISO-8601 UTC, o null si no existen. |

Si el paciente no tiene direcciones, `addresses` vale `[]`. Una dirección anulada no se incluye;
una dirección no anulada con fecha de fin sí se conserva. No se exportan claves internas ni auditoría.
Las modificaciones posteriores de la dirección no reescriben la copia de creación.

Los eventos nuevos usan `schemaVersion: 2`. Los JSON ya guardados con versión 1 se mantienen
intactos: la ausencia de addresses en ellos significa que no se capturó ese dato, no que el paciente
carezca de direcciones. No hay nueva columna ni migración porque se utiliza el mismo payload_json.
El receptor futuro tendrá que distinguir ambas versiones; aún no existe recepción implementada.

Para probar solamente los casos de direcciones del serializador:

```powershell
mvn -pl api '-Dtest=PatientCreationPayloadSerializerTest' test
```

Son tres pruebas: paciente sin direcciones; varias direcciones, preferencia, anulaciones, campos
opcionales y fechas; y rechazo de una dirección sin UUID. La demostración de integración existente
incluye ahora una dirección ficticia y comprueba que editarla no cambia el JSON original.

La fecha de nacimiento usa `yyyy-MM-dd`, conservando la fecha civil que maneja la instalación;
no se convierte a UTC. La fecha de fallecimiento se representa como instante ISO-8601.
Los campos opcionales ausentes se representan con `null`.

No se incluyen números internos de tablas, usuarios de auditoría, objetos Hibernate ni la etiqueta
visible de la posta. Tipos de identificador, ubicaciones y causa de muerte se referencian por UUID;
el receptor deberá resolver esos catálogos, sin asumir que sus números internos coinciden.

Este perfil básico aún no incluye atributos personalizados, hora de
nacimiento, relaciones, encuentros ni órdenes. No representa una exportación completa de la
historia clínica. Se debe ampliar según los campos acordados antes de usarlo como contrato completo
de recepción. No existe todavía un endpoint que lo reciba.

## Consulta y pruebas

### Relación con REST

El GET de paciente de la API REST es una representación para consultar al paciente. Nuestro JSON
es un contrato propio del evento: agrega origen, secuencia y UUID del evento. Su `payload` es una
selección inicial de datos y todavía no equivale a todos los datos del paciente ni a una respuesta
REST completa. La referencia REST sirve para contrastar campos y representaciones:
https://rest.openmrs.org/#list-patient-by-uuid

Como el módulo se ejecuta dentro de OpenMRS, recibe directamente el objeto Patient guardado por
el servicio Java. No hace una llamada HTTP a su propio GET para construir el JSON. Es necesario
terminar de acordar los campos del paciente antes de considerar completo el contrato de recepción.

La consulta de identidad `getByPatientUuid` mantiene su resultado sin datos personales.
La nueva consulta Java `PatientSyncService.getCreationPayload(patientUuid)` devuelve el JSON
persistido y exige conjuntamente los privilegios `View Synchronization Records` y `Get Patients`.
Devuelve `null` si no hay evento o si el evento antiguo no tiene JSON; puede consultarse la identidad
para distinguir esos casos. No se añadió una impresión del contenido personal en los registros.

Desde `synchronizationmr`, ejecutar una demostración:

```powershell
mvn -pl api '-Dtest=PatientCaptureIntegrationTest#creationPayloadPreservesIdentityAndClinicalReferences' test
```

La prueba valida el JSON guardado con nombres ficticios que contienen tildes y comillas, una fecha
de nacimiento, campos opcionales e identificadores. Imprime únicamente:

```text
JSON DE CREACIÓN VERIFICADO: versión=2 | direcciones guardadas | UUID conservado
```

También exporta dos archivos de evidencia en `api/target/demo-sincronizacion`:

- `paciente-creacion.json`: el JSON leído del evento guardado, con formato legible.
- `comparacion-identificadores.json`: valores del paciente OpenMRS, nodo local y evento frente
  a sus valores en el JSON. Cada pareja debe coincidir; las aserciones lo comprueban.

Desde `synchronizationmr` puedes abrirlos en el editor o consultarlos con:

```powershell
Get-Content -Encoding UTF8 .\api\target\demo-sincronizacion\paciente-creacion.json
Get-Content -Encoding UTF8 .\api\target\demo-sincronizacion\comparacion-identificadores.json
```

Estos archivos solo los genera la prueba con su paciente ficticio; no el módulo en producción.
Se reemplazan al repetir esta prueba y `mvn clean` los elimina. La prueba revierte su transacción
al terminar: los archivos son evidencia exportada, no una instancia de OpenMRS persistente.

También se añadieron pruebas de edición sin reemplazar el JSON, evento anterior sin contenido y
fallo al serializar con reversión del paciente, identidad y contador. El total es 29 pruebas:
8 unitarias del advice, 3 unitarias del serializador, 4 de integración del nodo y 14 de integración de pacientes.

```powershell
mvn -pl api test
```

Las pruebas se ejecutan sobre el contexto y base de pruebas de OpenMRS. Aún queda verificar la
migración en el motor del servidor y la recepción, dependencias, confirmaciones y reintentos entre nodos.
