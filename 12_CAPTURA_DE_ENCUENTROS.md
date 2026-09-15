# 12. Primera captura de encuentros

> Actualización: los encuentros nuevos ya guardan su JSON con observaciones desde el incremento
> [13_JSON_DEL_ENCUENTRO_Y_OBSERVACIONES.md](13_JSON_DEL_ENCUENTRO_Y_OBSERVACIONES.md).
> Esta guía describe el paso anterior, que solo guardaba metadatos.

Este incremento detecta **creaciones de encuentros** y guarda un registro pendiente local.
Todavía no construye el JSON, no transporta encuentros ni prepara automáticamente al paciente.

## Flujo

1. OpenMRS recibe `EncounterService.saveEncounter(encounter)`.
2. `EncounterCreationAdvice` comprueba si ese encuentro ya existe en la base local.
3. Deja que OpenMRS complete el guardado normal.
4. Si era una creación, `EncounterSyncService` registra el evento mediante `EncounterSyncDao`.
5. El encuentro y su evento se confirman en la misma transacción; si esta falla, se revierten juntos.

El advice envuelve el guardado en una transacción si el llamador todavía no abrió una.
No llama al maestro ni exige que el paciente tenga identidad de sincronización.
Si falta el UUID del nodo, se genera usando el servicio común del nodo.

Un fallo de red no afecta a este guardado porque no usa red. Un fallo **local de persistencia
de la captura** sí revierte la transacción: no se ignora silenciosamente para dejar un encuentro
sin su evento. Esto es diferente de exigir una identidad de sincronización previa del paciente.

Las ediciones no crean eventos nuevos. Una llamada repetida a la captura tampoco consume otra
secuencia para el mismo encuentro. El controlador HTTP y el cliente periódico siguen procesando
únicamente pacientes en esta etapa.

## Tablas y referencias

Se añade `encounter_sequence`, inicialmente cero, a `synchronizationmr_local_node`.
Es independiente de `patient_sequence`, aunque utiliza la misma fila bloqueada para asignar
números de forma transaccional.

La tabla nueva `synchronizationmr_encounter_event` reúne la identidad y el evento de creación
en este primer incremento. Admite una sola creación capturada por encuentro:

| Columna | Significado |
| --- | --- |
| `event_uuid` | UUID del evento; clave primaria. |
| `encounter_id` | Referencia al encuentro local de OpenMRS; única. |
| `encounter_uuid` | UUID nativo del encuentro; único. |
| `patient_id` | Referencia al paciente local, sin exigir una fila en nuestras tablas de pacientes. |
| `patient_uuid` | Referencia nativa al paciente conservada al capturar. |
| `origin_node_uuid` | Nodo donde se capturó. |
| `entity_sequence` | Secuencia de encuentros de ese origen. |
| `operation` | `CREATE`. |
| `state` | `PENDING`: registrado localmente, todavía sin contenido para envío. |
| `date_created` | Fecha de captura del evento. |

El tipo `ENCOUNTER` está implícito en esta tabla. La identidad de sincronización sigue siendo
**origen + entidad + secuencia**. Los UUID clínicos son referencias; no se comparan entre
personas para resolver duplicados históricos.

Ejemplo: un paciente sin identidad de sincronización puede tener un encuentro capturado como
`posta A / ENCOUNTER / 1`. Preparar y entregar el paciente antes de aplicar ese encuentro en el
destino es una dependencia que se implementará en los siguientes incrementos.

## Observaciones y órdenes

Se revisó `EncounterServiceImpl.saveEncounter()` en el JAR de fuentes local de OpenMRS 2.4.2:
guarda el encuentro mediante el DAO, procesa los grupos con `OrderService.saveOrderGroup`,
las órdenes nuevas sin grupo con `OrderService.saveOrder(order, null)` y las observaciones
de nivel superior mediante `ObsService.saveObs`.

Nuestro advice captura después de que termine ese método. Este incremento **no captura
órdenes por su cuenta ni serializa observaciones**. Para órdenes agrupadas todavía hay que
revisar la ruta interna y probar su captura para no omitir ni duplicar eventos.

Para el JSON de encuentros se propone incluir las observaciones asociadas conservando sus
identidades y grupos. Falta definir cómo capturar observaciones añadidas o corregidas después:
detectar solo el alta del encuentro no cubre esos cambios posteriores.

## Pruebas

Desde `synchronizationmr`:

```powershell
mvn -pl api '-Dtest=EncounterCaptureIntegrationTest' test
```

Las pruebas usan OpenMRS y una base H2 local. Verifican creación con paciente sin identidad de
sincronización, independencia del contador de pacientes, dos creaciones sin duplicar al editar
o repetir la captura, reversión conjunta, exclusión de ediciones de encuentros antiguos y
guardado cuando no hay transacción exterior. No contactan servidores.

```powershell
mvn package
```

Este último comando ejecuta también las pruebas anteriores y genera el OMOD. La migración
todavía debe validarse con el motor de base de datos del servidor de despliegue.

El siguiente paso será definir el contenido del JSON del encuentro y la preparación de su
paciente, antes de ampliar la consulta, recepción y envío. Los registros de este incremento
solo tienen metadatos: no contienen una copia histórica de las observaciones.
