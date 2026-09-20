# 11. Preparar un paciente que ya existía

> Incremento posterior implementado: [preparación inicial por lotes](16_PREPARACION_INICIAL_POR_LOTES.md).
> La tarea local es reanudable, se activa de forma independiente del transporte y
> reutiliza la preparación individual descrita en estos documentos.


> Actualización de identidad: [documento 14](14_IDENTIDAD_SERVER_ID_Y_ENTORNO.md).
> `server.id` sustituye al UUID de nodo; pacientes usan esquema 3, encuentros esquema 2
> y HTTP protocolo 2. Las referencias al UUID de origen y la configuración anterior
> que siguen describen la implementación histórica de este incremento.


## Qué se implementó

Se añadió `PatientSyncService.ensurePatientSyncRecord(localPatientId)`. Prepara un paciente
existente para que pueda enviarse mediante el mismo circuito de JSON, consulta, recepción y
confirmación que ya construimos.

No recorre todos los pacientes al instalar el módulo. Tampoco se ejecuta al consultar un
paciente con un `get`. Es una operación explícita que reutilizaremos en la preparación inicial por lotes
de pacientes existentes. No se invoca desde la captura de encuentros.

## Ejemplo del flujo

Ana ya existe en la posta, pero todavía no tiene registro de sincronización.

1. La futura función de preparación inicial solicita preparar a Ana usando su identificador interno local.
2. El servicio carga al paciente real de la posta; rechaza identificadores inexistentes o pacientes anulados.
3. Dentro de una transacción, reutiliza el bloqueo del contador que ya emplea la captura de altas.
4. Si no tiene registro, asigna la siguiente secuencia de pacientes del nodo y guarda su identidad
   y un evento `CREATE` con los datos actuales en JSON versión 3.
5. Si ya tiene registro y JSON, devuelve la identidad existente sin consumir otra secuencia ni
   regenerar el contenido. También conserva el origen si el paciente fue recibido de otro nodo.
6. El evento queda disponible para el cliente periódico. Prepararlo no significa que ya se haya enviado.

No se guarda otro paciente ni se vuelve a llamar a `savePatient` para simular un alta.
El JSON representa el estado del paciente al prepararlo, no su estado histórico al registrarlo
años antes. Las modificaciones posteriores continúan fuera del alcance de este incremento.

## Identificadores: qué se compara aquí

El parámetro `localPatientId` es el `patient_id` de la base local. Solo sirve para cargar la
entidad en esa instalación. No se envía como identificador común ni se compara con el ID del maestro.

El DAO existente usa el UUID del paciente para localizar **su asociación local** con nuestras
tablas. Esto no es una búsqueda de coincidencias entre personas de dos instalaciones.

Para consultar faltantes y reconocer lo sincronizado se conserva el identificador compuesto:
**origen + PATIENT + secuencia**. El UUID nativo del paciente permanece en el JSON para conservar
las referencias clínicas; no reemplaza la secuencia de sincronización.

## Qué no resuelve

El servicio no puede saber si la misma persona fue registrada independientemente en otra
instancia. No fusiona personas, no compara nombres y no vincula automáticamente registros
preexistentes en el maestro. Ese caso necesita preparación coordinada o reconciliación previa.

Por tanto, las pruebas iniciales deben usar pacientes que solo existen en el origen o datos
compartidos preparados de forma coordinada. Esta operación no elimina los rechazos de recepción
que ya protegen al destino frente a un paciente existente sin una correspondencia reconocida.

Si hay una identidad con un evento antiguo cuyo JSON falta, se rechaza la preparación y se
solicita revisión: no se reconstruye silenciosamente un evento histórico con los datos actuales.

## Transacción y permisos

La operación requiere `Prepare Synchronization Records` y `Get Patients`. El privilegio nuevo
está declarado en `config.xml`; no se concede automáticamente a las cuentas existentes.

Si se llama dentro de la transacción de un encuentro, se incorpora a ella: si esa transacción
se revierte, también se revierte la preparación. Si se llama sin transacción previa, el servicio
crea la suya. Esto utiliza los proxies de OpenMRS obtenidos con `Context.getService`.

No se añadieron tablas, endpoints HTTP ni procesos de carga masiva.

## Cómo probarlo

Desde la carpeta `synchronizationmr`:

```powershell
mvn -pl api '-Dtest=PatientCaptureIntegrationTest,PatientReceiveIntegrationTest' test
```

En `PatientCaptureIntegrationTest` se añadieron cuatro pruebas:

| Prueba | Resultado esperado |
| --- | --- |
| Preparar un paciente de los datos iniciales de prueba | Mantiene el mismo paciente; crea identidad y JSON consultable para envío. |
| Prepararlo otra vez, incluso tras modificarlo | Conserva secuencia, evento y JSON inicial. |
| Identificador inválido o JSON histórico ausente | Rechaza la operación. |
| Revertir la transacción que lo preparó | El paciente previo sigue existiendo y su preparación no queda guardada. |

La prueba de recepción existente también comprueba que preparar un paciente importado reutiliza
su origen remoto, su secuencia y su JSON sin incrementar el contador de creaciones locales.

Se utiliza el paciente 2 de los datos ficticios de OpenMRS para representar a alguien que ya
existía antes de capturar eventos. Ese 2 es un ID local de prueba, no su secuencia de sincronización.

Para ejecutar todo y generar el OMOD:

```powershell
mvn package
```

## Siguiente paso

Completar el contrato JSON del paciente y después implementar la preparación inicial por lotes,
reanudable y sin duplicar identidades o eventos. La captura de encuentros ya existe y no invoca
esta operación. Su futura entrega deberá asegurar que el paciente exista en el destino.
La preparación inicial no concilia registros independientes de una misma persona entre postas.
