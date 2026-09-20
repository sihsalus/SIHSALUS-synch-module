# 13. JSON del encuentro y sus observaciones

> Incremento posterior: [recepción y endpoint de encuentros](18_RECEPCION_DE_ENCUENTROS.md).
> La captura no prepara al paciente; su preparación por lotes es independiente.
> La recepción exige que el paciente exista en destino.


> Actualización de identidad: [documento 14](14_IDENTIDAD_SERVER_ID_Y_ENTORNO.md).
> `server.id` sustituye al UUID de nodo; pacientes usan esquema 3, encuentros esquema 2
> y HTTP protocolo 2. Las referencias al UUID de origen y la configuración anterior
> que siguen describen la implementación histórica de este incremento.


## Qué viene primero

En este incremento se prepara **el contenido** del evento del encuentro. Las observaciones
son parte de ese contenido: no son un paso alternativo al JSON.

La preparación del paciente se ejecuta por separado. La entrega debe garantizar
que el destino tenga al paciente antes de aplicar el encuentro. El cliente periódico y el
servlet actuales siguen intercambiando únicamente eventos de pacientes.

## Flujo actual

```text
OpenMRS guarda el encuentro y procesa sus observaciones
                          ↓
El advice detecta que fue una creación
                          ↓
Se asigna la secuencia ENCOUNTER y se construye el JSON
                          ↓
Se guarda en synchronizationmr_encounter_event.payload_json
                          ↓
Se confirma la transacción del encuentro y del evento
```

El serializador nuevo se llama `EncounterCreationPayloadSerializer`. Copia campos explícitos;
no serializa automáticamente todos los objetos relacionados de Hibernate.

El contenido se genera una sola vez. Una edición posterior o una llamada repetida a la captura
no lo reemplaza. El paciente puede seguir sin identidad de sincronización: la captura guarda
su UUID nativo como referencia local, sin preparar ni enviar al paciente todavía.

## Qué contiene

| Parte | Contenido |
| --- | --- |
| Sobre del evento | `schemaVersion`, UUID de evento, origen, tipo `ENCOUNTER`, secuencia, operación `CREATE` y fecha de captura. |
| Encuentro | UUID, UUID del paciente, fecha clínica, tipo, ubicación, formulario, visita y estado de anulación. |
| Profesionales | UUID de la asociación, profesional y rol de los participantes activos. |
| `obs` | Observaciones con sus valores y grupos. |
| `orderUuids` | Referencias a órdenes asociadas; no contiene el JSON de las órdenes. |
| `unsupportedContent` | Indica si hay diagnósticos o condiciones independientes cuyo contenido todavía no se incluye. |

Para cada observación se conserva UUID, concepto, fecha, ubicación, referencia a orden si existe,
valor numérico, texto, fecha/hora o referencias a valores codificados y medicamentos. También
se guardan comentario, modificador, número de acceso, estado, interpretación, anulación y
referencia a la versión anterior cuando existen.

Los grupos se representan con `groupMembers`, que contiene sus observaciones hijas. No se
asigna una secuencia de sincronización independiente a cada observación. Se incluyen las
anuladas presentes en el encuentro y se indica su estado.

No usamos `getValueAsString` para convertir todos los resultados a texto: preservamos el tipo
de valor. El concepto referenciado define su significado y sus unidades, que deberán estar
configurados coherentemente en el destino. Los catálogos no se copian dentro del JSON.

Las fechas se representan en formato ISO UTC. Los IDs numéricos locales no se usan como
referencias comunes entre instalaciones.

## Versiones

`schemaVersion: 1` significa **primera versión del contenido ENCOUNTER**. El JSON de pacientes
continúa en su versión 2. Son esquemas de entidades diferentes; no se ha retrocedido el formato
del paciente. `protocolVersion: 1` sigue siendo la versión de la interfaz HTTP de pacientes.

## Límites de este incremento

- No incluye archivos de observaciones complejas. `valueComplex` conserva su referencia y
  `complexDataIncluded: false` señala que el archivo no viaja. La futura recepción deberá
  tratarlo explícitamente; la referencia no permite afirmar que el archivo ya está sincronizado.
- No incluye como objetos completos visitas, órdenes, diagnósticos ni condiciones independientes.
  Las referencias y `unsupportedContent` no sustituyen la sincronización de esas entidades.
- No captura observaciones agregadas o corregidas después del alta del encuentro. Eso requiere
  definir eventos posteriores o una regla de finalización del encuentro.
- No hay todavía recepción de este JSON ni resolución de sus referencias. En particular, una
  observación que referencia una orden requiere definir también esa dependencia.
- Los eventos capturados antes de esta migración conservan `payload_json = null`. No reconstruimos
  automáticamente el pasado usando los datos actuales. Habrá que tratarlos explícitamente antes
  de habilitar el envío de encuentros.

Una estructura de observaciones con UUID repetidos, ciclos o más de 50 niveles se rechaza al
serializar. El error revierte la transacción local, igual que otros fallos de captura; no se
confunde con una interrupción de red.

## Cómo probarlo y abrir el JSON

Desde `synchronizationmr`:

```powershell
mvn -pl api '-Dtest=EncounterCreationPayloadSerializerTest,EncounterCaptureIntegrationTest' test
```

Las pruebas unitarias revisan grupos, valores numéricos y codificados, texto y fechas,
observaciones anuladas, referencias a órdenes y exclusión de archivos complejos.

La prueba de integración `storesSnapshotAndKeepsItUnchangedAfterEditing` guarda un encuentro
en la base H2 de OpenMRS, comprueba sus UUID y el nodo de origen, lo modifica y verifica que el
JSON original no cambió. Genera:

```text
api/target/demo-sincronizacion/encuentro-creacion.json
```

El ejemplo de integración utiliza un encuentro de prueba sin observaciones; los grupos y valores
se verifican en las pruebas del serializador. Todo utiliza datos ficticios. No se contactan
instancias reales ni se demuestra todavía la reconstrucción de observaciones en otro servidor.

Para ejecutar todo y generar el OMOD:

```powershell
mvn package
```

El siguiente paso es resolver la preparación y las referencias necesarias para la entrega, y
construir la recepción del encuentro con sus observaciones, antes de habilitar su transporte.
