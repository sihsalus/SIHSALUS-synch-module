# 14. server.id como única identidad del nodo

> Ampliación posterior: [documento 15](15_REVISION_CONTRATO_PACIENTE.md).
> Los nuevos eventos de pacientes usan esquema 4 con hora de nacimiento y atributos;
> el receptor conserva compatibilidad con esquema 3. HTTP sigue en protocolo 2.


Corrección del 2026-09-20 según la reunión con Iván y la aclaración del usuario.
Sustituye la propuesta anterior que asociaba un nombre a un UUID interno de nodo.
La revisión de la primera instancia encontró historial del esquema anterior:
50 identidades de pacientes, 50 eventos de pacientes y 1420 eventos de encuentros.
Por ello la migración se detuvo. El usuario decidió recrear las tres instancias
con bases nuevas para continuar. Este cambio no borra instancias ni bases existentes.

## Identidad definitiva

Cada establecimiento configura una Global Property `server.id` distinta y estable:

```text
testServer_1 / PATIENT / 1
testServer_2 / PATIENT / 1
testServer_1 / ENCOUNTER / 1
```

El módulo obtiene ese valor mediante:

```java
Context.getAdministrationService().getGlobalProperty("server.id");
```

`LocalNodeService.getLocalServerId()` lo valida y lo fija en la fila local bajo el
bloqueo de los contadores. No genera otro identificador. La identidad de un registro
es **server.id del origen + tipo de entidad + secuencia**. Importar un registro
conserva su origen; mostrarlo en otra posta tampoco le cambia el nombre de origen.

Los UUID del paciente, encuentro, observación y evento siguen existiendo porque
identifican objetos diferentes. Se elimina exclusivamente el UUID del nodo.
La etiqueta anterior `synchronizationmr.nodeLabel` deja de utilizarse.

## Configuración y estabilidad

Se admiten de 1 a 100 caracteres ASCII: letras, números, punto, guion y guion bajo,
empezando por letra o número. Se conserva el valor exacto, incluidas las mayúsculas.
Cada establecimiento debe tener un nombre distinto. No existe todavía un registro
central que imponga la unicidad entre todas las postas.

**Configurar server.id antes de capturar registros con el OMOD activo.** Si falta o
es inválido, se rechaza la captura y se revierte el guardado clínico con su evento;
no se inventa una identidad de respaldo. Esto es configuración local y no requiere
Internet. Una vez fijado, un cambio de nombre se rechaza para impedir que una misma
secuencia cambie de origen. Cambiar el rol no renumera el historial.

El `server.id` de `openmrs-server.properties` pertenece al SDK. En la inspección
anterior no existía como Global Property en la base activa: al desplegar se debe
configurar el mismo nombre dentro de OpenMRS. El módulo no lee archivos del SDK.

## Tablas y Liquibase

`synchronizationmr_local_node` conserva `singleton_id` (fila local, siempre 1),
`server_id` (única identidad del nodo), `patient_sequence` y `encounter_sequence`.
La copia persistida de server.id permite detectar un cambio de configuración;
no representa una segunda identidad.

La migración elimina `node_uuid` y cambia `origin_node_uuid` por
`origin_server_id` (VARCHAR(100)) en identidades de pacientes, eventos de encuentros
y confirmaciones de pacientes. Los índices y claves conservan su función.
Las columnas de identidad en MySQL/MariaDB usan comparación binaria para que el
orden de paginación y la comparación de nombres ASCII coincidan con Java.

No se borran tablas clínicas ni tablas de sincronización. Se añaden changesets;
los anteriores describen cómo se llega del esquema inicial al actual. El encabezado
XML pasa al esquema 3.1, compatible con Liquibase 3.10.2 de las pruebas, para admitir
el cambio de tipo de columna. No editar checksums ni borrar `DATABASECHANGELOG`.

La sustitución exige tablas de identidades, eventos y confirmaciones vacías. Si
hubiera historial con UUID de nodo, Liquibase se detiene sin reinterpretarlo ni
eliminarlo: ese caso necesitaría mapear los orígenes explícitamente. Los pacientes
y encuentros clínicos pueden existir sin registros de sincronización; esta
migración no los elimina ni los incorpora automáticamente.

## JSON y protocolo

El sobre del paciente utiliza **schemaVersion 3**:

```json
{
  "schemaVersion": 3,
  "originServerId": "testServer_1",
  "entityType": "PATIENT",
  "entitySequence": 1,
  "operation": "CREATE"
}
```

Es un fragmento explicativo. El evento completo conserva `eventUuid`, `occurredAt`
y el `payload` clínico. El encuentro usa **schemaVersion 2**, también con
`originServerId`; su transporte sigue pendiente.

El HTTP de pacientes pasa a **protocolVersion 2**. `GET resource=node` responde
con `serverId` y el rol, sin `nodeUuid`. Las páginas, estados y confirmaciones usan
`originServerId`; los parámetros `origin` y `afterOrigin` reciben nombres.
El cliente exige el nombre esperado del maestro y la versión 2 antes de enviar datos.
Los contratos antiguos con `originNodeUuid` se rechazan explícitamente.

| Configuración | Valor |
| --- | --- |
| Global Property `server.id` | Nombre local, por ejemplo `testServer_2`. |
| Global Property `synchronizationmr.nodeRole` | `MASTER` o `POSTA`. |
| Propiedad de usuario `synchronizationmr.peerServerId` | Nombre del par que representa esa cuenta. |
| Propiedad de usuario `synchronizationmr.peerRole` | Rol del par. |
| Variable `SYNCMR_MASTER_SERVER_ID` | Nombre esperado del maestro, por ejemplo `testServer_1`. |

`SYNCMR_MASTER_UUID` y `synchronizationmr.peerNodeUuid` dejan de utilizarse. Se
mantienen las cuentas técnicas, permisos, endpoint HTTPS y las demás variables
del programador. Las postas siguen comunicándose únicamente con el maestro.

## Validación y próximos pasos

Resultado: `mvn -o package` con el repositorio Maven local explícito terminó con
BUILD SUCCESS: 83 pruebas de API y 16 de OMOD, **99 en total**, sin fallos, errores
ni casos omitidos. Paquete generado:
`synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`.

Las pruebas cubren captura por server.id, estabilidad y concurrencia, rollback,
reenvíos, origen conservado al importar y redistribuir, autorización por nombre,
paginación y rechazo de protocolos anteriores. La migración se prueba sobre H2:
esquema de sincronización vacío con filas clínicas existentes y rechazo de un
historial de orígenes antiguos.

El proyecto compila y se prueba contra Platform 2.4.2. Los archivos de las instancias
declaran Reference Application 3.8.0-SNAPSHOT con Platform 2.8.10-SNAPSHOT. La validación
en ese runtime y en MariaDB queda pendiente para cuando se retomen las instancias.

Después corresponde completar el contrato de datos del paciente y su incorporación
inicial por lotes. Preparar un paciente no equivale a haberlo recibido: la entrega
de encuentros seguirá necesitando que su paciente exista primero en destino.


## Instalación en las tres instancias nuevas

1. Crear cada instancia con una base OpenMRS independiente y nueva. Reutilizar un
   volumen Docker antiguo conserva sus datos aunque cambie el nombre del contenedor.
2. Completar la instalación de OpenMRS y cargar el OMOD compilado de este repositorio.
   El archivo instalado no se actualiza automáticamente cuando cambia el código.
3. En las propiedades globales de OpenMRS, configurar los siguientes valores antes
   de crear pacientes o encuentros con el módulo activo:

   | Instancia | `server.id` | `synchronizationmr.nodeRole` |
   | --- | --- | --- |
   | Primera | `testServer_1` | `MASTER` |
   | Segunda | `testServer_2` | `POSTA` |
   | Tercera | `testServer_3` | `POSTA` |

4. En DBeaver, conectar a la base de esa instancia y consultar:

   ```sql
   SELECT property, property_value FROM global_property
   WHERE property IN ('server.id', 'synchronizationmr.nodeRole');

   SELECT singleton_id, server_id, patient_sequence, encounter_sequence
   FROM synchronizationmr_local_node;
   ```

   Inmediatamente después de instalar hay una fila con contadores en cero y
   `server_id` NULL. El servicio fija ese valor al solicitar la identidad por primera
   vez; guardar la propiedad global por sí solo no actualiza esta tabla.
5. Crear un paciente de prueba y comprobar su captura:

   ```sql
   SELECT patient_id, origin_server_id, entity_sequence
   FROM synchronizationmr_patient_identity;

   SELECT event_uuid, patient_id, operation, state
   FROM synchronizationmr_patient_event;
   ```

   Para el primer paciente capturado se espera el origen local, secuencia 1 y un
   evento CREATE/PENDING. Reiniciar el módulo debe conservar la identidad y los
   contadores. Esto verifica captura local; aún no demuestra intercambio entre nodos.

Liquibase aplica el historial completo una sola vez en la instalación nueva. Aunque
el XML conserva nombres antiguos en los cambios iniciales, el esquema final usa
`server_id` y `origin_server_id`, sin `node_uuid`. No es necesario borrar ni reescribir
los changesets. La prueba de instalación nueva también ejecuta Liquibase por segunda
vez con registros presentes para comprobar que no vuelve a exigir tablas vacías ni
reinicia los contadores.

Esta preparación no completa todos los ajustes de la reunión: siguen pendientes
el contrato completo del paciente, la preparación inicial por lotes y los siguientes
flujos de encuentros y órdenes. La prueba real en las nuevas instancias se hará con
el paquete que corresponda al incremento terminado.
