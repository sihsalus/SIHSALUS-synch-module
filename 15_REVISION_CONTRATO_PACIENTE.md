# 15. Revisión del JSON de pacientes antes de la preparación inicial

> Incremento posterior implementado: [preparación inicial por lotes](16_PREPARACION_INICIAL_POR_LOTES.md).
> La tarea local es reanudable, se activa de forma independiente del transporte y
> reutiliza la preparación individual descrita en estos documentos.


Revisión del 2026-09-20. Este documento distingue el contrato implementado de las ampliaciones pendientes.
Los eventos nuevos de pacientes usan esquema 4; se admite también el esquema 3 histórico.
El protocolo HTTP conserva su versión 2.

## Requerimiento y primer resultado

Según el relato de la reunión, Iván pide distribuir entre los establecimientos los
datos de la microred e incorporar pacientes registrados antes de instalar el módulo.
El texto no define una lista exhaustiva de campos ni un mecanismo de conciliación
de personas registradas independientemente en varias postas.

La preparación inicial utilizará el mismo serializador que las nuevas creaciones.
Por eso revisamos primero su contenido: preparar todos los pacientes con un JSON
incompleto congelaría esas omisiones en eventos que actualmente son inmutables.

El JSON actual NO es una conversión directa de la tabla `patient`. Se construye
desde el objeto Patient y sus datos de Person, nombres, identificadores y direcciones.
El destino reconstruye esos objetos y los guarda mediante los servicios de OpenMRS.
Los números internos de las tablas no necesitan coincidir entre instalaciones.

## Comparación con el código actual

| Datos | Emisor y receptor actuales | Trabajo pendiente |
| --- | --- | --- |
| UUID del paciente | Se conserva. | No equivale a identificar duplicados humanos. |
| Origen y secuencia | `originServerId` + PATIENT + secuencia; esquema 4 para eventos nuevos. | Conservarlos al distribuir. |
| Género, fecha de nacimiento y estimación | Se envían y reconstruyen. | Mantener fecha civil sin conversión a UTC. |
| Hora de nacimiento (`birthtime`) | Se envía y reconstruye como `HH:mm:ss` o null. | Hora civil sin conversión a UTC. |
| Fallecimiento y causa | Se envían y reconstruyen. | La causa codificada requiere un concepto disponible en destino. |
| Nombres | Se incluyen los no anulados y su preferencia. | El historial anulado no está cubierto. |
| Identificadores | Se incluyen los no anulados, tipo y ubicación por UUID. | Tipos y ubicaciones deben existir en destino. |
| Direcciones | Se incluyen las no anuladas, address1..15, campos geográficos, coordenadas y fechas. | Verificar las etiquetas y configuración geográfica de la instalación real. |
| Atributos personalizados de Person | Se conservan los activos con UUID, tipo, formato y valor. | Formatos admitidos detallados abajo; otros se rechazan explícitamente. |
| Anulaciones y auditoría | No se replica su historia completa. | Definir alcance; no presentar este JSON como copia íntegra de tablas. |
| Relaciones, alergias y estado de alergias | No están en el contrato. | Definir su tratamiento y dependencias antes de afirmar cobertura completa del paciente. |
| Ediciones posteriores | No generan eventos de actualización. | Requieren otro incremento; ampliar CREATE no sincroniza ediciones. |

Encuentros, observaciones y órdenes no se incorporan por esta revisión al JSON de
pacientes. El trabajo actual se concentra en el paciente y sus datos asociados.

## Direcciones: conservar valores y significado

El relato menciona país, región, provincia, distrito, villa, dirección específica
y barrio. No especifica qué columna corresponde a cada etiqueta. No asignaremos
por suposición provincia o barrio a un `addressN` concreto.

El emisor ya copia los campos disponibles de PersonAddress y el receptor los
reconstruye. Aun así, las instancias deben interpretar esos campos de la misma forma.
La correspondencia con las etiquetas locales queda pendiente de comprobar en la
configuración geográfica que se utilizará en las nuevas instancias.

## Ampliación implementada del contrato

Los eventos nuevos incorporan `birthtime` y `attributes`. La hora se representa como
hora civil `HH:mm:ss`, según el mapeo SQL TIME de OpenMRS, sin fecha ni zona horaria.
La ausencia se expresa con null; `attributes` es una lista, vacía si no hay atributos activos.

Cada atributo incluye:

```json
{
  "uuid": "uuid-del-atributo",
  "attributeTypeUuid": "uuid-del-tipo",
  "format": "java.lang.String",
  "value": "Contacto ficticio",
  "valueReferenceUuid": null
}
```

Los formatos simples admitidos son `java.lang.String`, `java.lang.Boolean`,
`java.lang.Integer`, `java.lang.Long`, `java.lang.Float` y `java.lang.Double`.
El valor se conserva como texto; se valida su formato, sin normalizarlo. No se
admiten valores vacíos ni números no finitos. Los formatos no admitidos producen
un error explícito, no una omisión silenciosa del atributo. Esto también puede
impedir la captura local hasta resolver el formato no soportado.

Las referencias `org.openmrs.Concept` y `org.openmrs.Location` usan `value: null`
y `valueReferenceUuid` con el UUID del objeto referenciado. El receptor resuelve
ese UUID y guarda su ID local. No se transmiten IDs numéricos del origen como
referencias compartidas. No se cargan clases dinámicamente desde el JSON.

El tipo del atributo debe existir, estar activo y tener el mismo formato en destino.
Las referencias también deben existir y estar activas. Los UUID repetidos o ya
ocupados por otro atributo se rechazan. Se conservan varios valores activos del
mismo tipo cuando pueden representarse en el modelo de OpenMRS; no se usa el método
que sustituye un valor anterior al añadir otro del mismo tipo.

Los eventos del esquema 3 siguen admitiéndose y redistribuyéndose sin reescribirlos.
Su ausencia de estos campos significa que no se capturaron, no una prueba de que
el paciente original no los tuviera. El esquema 4 exige ambos campos. Los nodos con
el OMOD anterior no admiten esquema 4: actualizar todos antes de intercambiar eventos
nuevos. No se añade ninguna migración de Liquibase ni se alteran bases reales.

## Comprobaciones exigidas al implementar

- Serializar, recibir y volver a consultar un paciente ficticio conservando los
  nuevos campos, UUID y referencias aunque los IDs numéricos sean distintos.
- Probar datos opcionales ausentes y rechazar formatos o dependencias no admitidos
  sin guardar parcialmente al paciente ni avanzar su confirmación.
- Mantener el reconocimiento de reintentos y la inmutabilidad del evento guardado.
- Preparar un paciente preexistente mediante el mismo contrato, sin crear otro
  paciente ni consumir otra secuencia al repetir la preparación.

La función inicial por lotes ya está implementada en el documento 16. Asigna
identidades a registros locales sin esperar un encuentro. No determina por sí sola
si dos registros independientes representan a la misma persona.

## Evidencia revisada

- `PatientCreationPayloadSerializer.java`: campos emitidos y exclusión de anulados.
- `PatientIncomingEvent.java`: campos admitidos, reconstrucción y catálogos por UUID.
- `PatientReceiveDao.java`: recepción transaccional, reintentos y conflictos.
- Documentos 05 y 11 y relato de la reunión con Iván.
- Fuentes locales de OpenMRS 2.4.2: Patient, Person, PersonAttribute y PersonAttributeType.

Este incremento amplía el emisor y receptor Java, con pruebas de conservación y
rechazo. Liquibase y las bases de las instancias no se modifican. Siguen pendientes
el resto de grupos de la tabla y la prueba entre instancias. El documento 17 reúne
el estado actual y los criterios para esas pruebas.


## Resultado de la validación

`mvn -o package` terminó con BUILD SUCCESS: 105 pruebas, sin fallos, errores ni
omisiones. Se verificaron persistencia de la hora, atributos múltiples del mismo tipo,
resolución de ubicación por UUID, rechazo de un tipo ausente, formatos no soportados,
hora inválida, compatibilidad con esquema 3 y reintentos sin reescribir el evento.
Las pruebas son locales con el contexto de OpenMRS y H2; no sustituyen la validación
entre las tres instancias MariaDB. Paquete generado:
`synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`.
