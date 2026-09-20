# 22. Encuentros: conservación de versiones anteriores de observaciones

> Aclaración posterior de alcance y corrección: [relaciones y asignación de visita](23_ENCUENTROS_RELACIONES_Y_ASIGNACION_DE_VISITA.md). No se añaden automáticamente flujos para las otras entidades mencionadas al final de este documento.


## Ajuste implementado

El receptor rechazaba cualquier observación con `previousVersionUuid`. Esto podía bloquear un encuentro ya existente que se preparaba para sincronizar después de una corrección clínica, aunque sus valores y versiones anteriores estuvieran en el JSON.

Ahora se conserva esa relación. Ejemplo ficticio: una observación original registra 60, se anula al corregirla y una nueva observación registra 62,5. Al importar el encuentro se conservan ambos UUID, ambos valores, la anulación original y el vínculo de la corrección con su versión anterior.

La versión anterior puede venir en el mismo mensaje, incluso dentro de un grupo de observaciones, o existir ya en el destino. El orden de las observaciones dentro del JSON no determina si el vínculo se puede reconstruir.

## Reglas y transacción

- Primero se valida el encuentro y sus observaciones mediante el contrato existente.
- Se verifica que cada versión referenciada exista en el mensaje o en la base de destino y corresponda al mismo paciente.
- Se rechazan autorreferencias, ciclos y cadenas de más de 1000 observaciones. Se mantienen los límites existentes del contrato para tamaño y profundidad de grupos.
- Si falta una versión externa, no se crea una observación ficticia, no se guarda el encuentro ni se confirma su secuencia. Debe llegar o resolverse esa dependencia antes de reintentar.
- Se guardan las observaciones por los servicios nativos de OpenMRS y, una vez que tienen sus IDs locales, se completan los enlaces `obs.previous_version` antes de confirmar la transacción.
- Esa finalización usa una actualización JDBC restringida a las observaciones recién importadas, con comprobación de vínculo vacío y recarga de Hibernate. No vuelve a llamar a `saveObs` para editar una observación guardada: eso podría generar otra revisión y otro UUID.
- Encuentro, observaciones, enlaces, evento original y recibo se confirman o revierten juntos. No se modifica la observación anterior ya existente.
- Un reenvío idéntico devuelve la confirmación existente sin reconstruir versiones ni duplicar observaciones.

La validación/reconstrucción está en `EncounterObservationVersions`; la transacción la coordina `EncounterReceiveDao`.

## Compatibilidad y alcance

No hay nuevos campos JSON ni tablas: `previousVersionUuid` ya se enviaba en el esquema 2. Se amplía lo admitido por el receptor. Debe actualizarse el OMOD en todos los nodos: un receptor anterior todavía rechaza estas referencias.

Esto conserva historia incluida en el snapshot de un encuentro; no introduce eventos UPDATE para modificaciones hechas después de capturar ese snapshot. Las modificaciones posteriores de pacientes continúan aplazadas, según el alcance acordado.

Las órdenes siguen siendo independientes y sus referencias se resuelven con el mecanismo del documento 20. Las versiones anteriores de observaciones no se dejan en esa cola: se exigen como parte del mensaje o como dependencia ya disponible.

Si la versión anterior existe solamente en un encuentro aún no recibido, el envío necesita resolver el orden de esa dependencia. Este incremento no añade ordenación topológica de la preparación ni un nuevo flujo de observaciones independiente.

## Validación

Se añadieron cinco casos de integración:

1. Historial en el mismo snapshot, dentro de un grupo, con persistencia/recarga y reintento.
2. Referencia a una observación de otro encuentro del mismo paciente ya recibido.
3. Rechazo de una referencia ausente y de autorreferencia, sin guardado ni confirmación.
4. Rechazo de una referencia a otro paciente.
5. Rechazo de un ciclo entre dos versiones.

Además se amplió la prueba de rollback real: una colisión del UUID del evento después de guardar las observaciones y sus enlaces revierte también el historial. Un reintento válido posterior puede completar la recepción.

Resultado de la compilación completa: **197 pruebas, 157 API y 40 OMOD; sin fallos, errores ni omisiones**. Log: `synchronizationmr/api/target/encounter-history-build.log`.

OMOD: `synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`.

Las pruebas usan OpenMRS/H2 y transporte simulado. La validación entre las tres instancias y con MariaDB sigue pendiente. No se modificaron instancias ni contenedores.

## Pendientes de encuentros

Este ajuste cierra la conservación de las referencias históricas admitidas, no toda la cobertura de encuentros. Continúan pendientes:

- Visitas referenciadas que todavía no existen en destino: hoy se rechazan.
- Diagnósticos y condiciones incluidos en el encuentro.
- Archivos de observaciones complejas.
- Modificaciones posteriores a la captura del evento.
- Dependencias/catálogos y recorrido real A → maestro → B.

El siguiente punto a revisar son las visitas, porque el encuentro puede estar asociado a una y actualmente solo se transmite su UUID. Su incorporación debe conservar datos y relaciones; no basta con inventar una visita vacía para evitar el rechazo.
