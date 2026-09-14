# Recepción local de pacientes y confirmación consecutiva

Este paso reconstruye un paciente a partir del JSON y lo guarda mediante los servicios de OpenMRS.
Todavía no hay solicitudes HTTP, tareas periódicas ni dos servidores conectados. La misma operación
local podrá utilizarse en el maestro o en una posta cuando implementemos el transporte.

## Recorrido

Decisión posterior del usuario: todas las postas recibirán todos los registros del alcance a través
del maestro. No habrá selección de destinos por paciente. Las consultas periódicas usarán la
confirmación consecutiva de cada origen y tipo, sin confundirla con la mayor secuencia disponible.
La regla está registrada en el documento 02; la comunicación y la periodicidad aún no se implementan.

Por ejemplo, si el maestro tiene A-1, A-2 y A-3 y la posta B confirmó A-1, B solicitará A-2 y A-3.
La posta C realizará la misma comparación con su propia confirmación. Recibir en B no confirma
la recepción en C. El origen A se conserva en todas las copias.

1. `PatientReceiveService.receivePatient(json)` inicia una transacción o participa en la actual.
2. `PatientIncomingEvent` valida el contrato y sus identificadores. Admite las versiones 1 y 2,
   entidad PATIENT y operación CREATE. Rechaza versiones desconocidas y campos fuera del contrato.
3. `PatientReceiveDao` obtiene el bloqueo del nodo local y consulta la confirmación de ese origen.
4. Si el evento ya fue confirmado, compara secuencia, UUID de paciente, UUID de evento y contenido
   JSON. Si coinciden devuelve la confirmación actual; si no, informa un conflicto.
5. Para un evento nuevo exige exactamente la secuencia siguiente. Resuelve los tipos de
   identificador, ubicaciones y causa de muerte por su UUID. Si faltan o están retirados, falla.
6. Reconstruye al paciente con nombres, identificadores y direcciones del mensaje, conservando
   sus UUID. OpenMRS realiza el guardado y sus validaciones clínicas habituales.
7. Guarda identidad y evento originales y avanza la confirmación en la misma transacción.

`IncomingPatientSave` identifica temporalmente, solo en el hilo actual, al objeto Patient recibido.
El advice deja pasar ese guardado sin generar otra identidad local. La marca se limpia en un
finally, también si falla el guardado. Otros pacientes guardados en el hilo mantienen su captura normal.

## Nueva tabla

`synchronizationmr_patient_receipt` contiene:

| Columna | Significado |
|---|---|
| `origin_node_uuid` | Origen de los eventos recibidos. Clave primaria. |
| `confirmed_sequence` | Última secuencia consecutiva recibida correctamente para ese origen. |

El tipo PATIENT está implícito en la tabla. Si no hay fila, la consulta devuelve 0.
Esta confirmación pertenece al receptor local, no representa confirmaciones de todos los destinos.
No se calcula con MAX. Si está en 1, recibir 3 falla y continúa en 1.

La creación, el evento y esta fila se confirman juntos. El valor retornado por el servicio no debe
enviarse como acuse remoto antes del commit: si el llamador abrió una transacción externa, todavía
tiene que confirmarla. El transporte futuro debe respetar esa frontera transaccional.

El contador de pacientes originados localmente no aumenta al importar. El origen remoto y el JSON
se conservan para la consulta y redistribución posteriores. El evento queda en PENDING porque aún
puede necesitar entregarse a otros destinos; la recepción local se expresa en la nueva tabla.
La primera implementación serializa recepciones mediante el bloqueo local ya existente; optimizar
paralelismo por origen es trabajo posterior y requiere mantener las mismas garantías.

## Casos pendientes y límites explícitos

- Un reenvío del evento ya confirmado no crea otro paciente ni aplica cambios posteriores.
- Si el UUID del paciente/persona ya existe pero no corresponde a una recepción confirmada,
  se rechaza para revisión. No se sobrescribe ni se vincula automáticamente. La incorporación y
  conciliación de pacientes preexistentes sigue pendiente.
- Un mensaje del propio origen local se rechaza como importación remota. El futuro transporte
  deberá evitar devolver a un nodo los eventos que este mismo originó.
- Los nombres, direcciones e identificadores no pueden reutilizar UUID de registros locales ajenos.
- No se crean catálogos automáticamente. Las referencias necesarias deben estar disponibles en
  el destino. No se buscan equivalencias por parecido de nombre.
- Versión 1 sin direcciones se acepta sin inventarlas. Versión 2 requiere el arreglo addresses.
  Los UUID nativos se conservan como referencias opacas de hasta 38 caracteres; el origen y el
  evento usan el formato UUID generado por el componente.
- Los atributos personalizados, hora de nacimiento, ediciones, encuentros y órdenes no se reciben
  en este contrato. Solo se admite el perfil básico descrito en el documento 05.
- El mensaje admite hasta 1 000 000 de caracteres y hasta 100 elementos en cada colección.
  Una futura API deberá limitar también el tamaño del cuerpo HTTP y autenticar al nodo emisor.

El nuevo privilegio `Receive Synchronization Records` se exige junto con `Add Patients` y
`Get Patients`. También se respetan los permisos de los servicios nativos usados para consultar
personas y catálogos. No se elevan permisos ni se añade un endpoint público en este incremento.
La procedencia de red y autorización de orígenes se resolverán al implementar el transporte.

## Cómo probarlo

Desde `synchronizationmr`:

```powershell
mvn -pl api '-Dtest=PatientReceiveIntegrationTest' test
```

Son 11 pruebas: recepción con identidad preservada y consulta para redistribución, reenvío idéntico,
hueco de secuencia, catálogo ausente, contenido contradictorio, fallo tras guardar el paciente con
reversión completa y reintento, paciente ya existente, recepción duplicada simultánea, JSON inválido,
compatibilidad con versión 1 y rechazo de un llamador sin autenticar.

Para ejecutar solo la demostración:

```powershell
mvn -pl api '-Dtest=PatientReceiveIntegrationTest#receivesPatientAndPreservesOriginForRedistribution' test
```

Genera `api/target/demo-sincronizacion/recepcion-paciente.json` con comparación de UUID enviados y
recibidos, origen, nodo receptor, confirmación y contador local antes/después. Son datos ficticios;
el JSON se crea en memoria como si viniera de otro nodo y se recibe en la base local de pruebas.
No demuestra todavía una conexión posta–maestro. La transacción de esta demostración se revierte
al terminar, y `mvn clean` elimina el archivo de evidencia.

Para ejecutar todas las pruebas:

```powershell
mvn -pl api test
```

El total es 45 pruebas: 11 unitarias y 34 de integración local.
La migración y los bloqueos aún deben probarse en el motor del servidor de despliegue.

El trabajo siguiente es resolver la configuración del transporte posta–maestro, permisos y
catálogos compartidos, y exponer la consulta/recepción con confirmaciones antes de automatizar
el ciclo periódico. No se añadió un temporizador que simule una comunicación que aún no existe.
