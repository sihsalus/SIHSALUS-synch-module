# 24. Órdenes existentes: preparación respetando dependencias

## Qué cambia

La preparación de órdenes existentes ya estaba implementada. Este incremento corrige cómo selecciona las que todavía no tienen evento: antes recorría únicamente el `order_id` local. Ese número no garantiza el orden de las dependencias, especialmente en bases con datos importados.

Una orden que referencia `previous_order_id` necesita que su anterior esté preparada primero. De lo contrario, el destino podría recibir la dependiente antes que la anterior y rechazarla, mientras la entrega secuencial impide saltar al evento posterior.

Ahora se seleccionan órdenes no anuladas pendientes cuya anterior no existe como relación o ya tiene un evento con JSON no vacío. Se capturan las elegibles y se vuelve a consultar hasta completar el límite del lote o agotar las elegibles. Así las dependencias habilitadas dentro del mismo lote también pueden prepararse, sin superar el límite configurado.

Se mantienen las secuencias por `server.id` y entidad, los eventos CREATE inmutables y el guardado atómico del lote. No se modifican filas clínicas ni se preparan pacientes o encuentros desde esta función.

## Pendientes bloqueados

- Si el lote avanzó y después solo quedan dependencias bloqueadas, devuelve la cantidad realmente preparada; esos avances pueden confirmarse.
- Si una nueva llamada no puede preparar ninguna orden pero aún hay pendientes, produce un error explícito. No devuelve cero como si la carga estuviera completa.
- Una anterior anulada sin evento, una referencia no resuelta o un ciclo requieren revisión. No se elimina la relación ni se inventa una orden anterior.
- Un evento histórico sin JSON sigue requiriendo revisión: no se reescribe ni se asigna otra identidad.
- Los lotes ya confirmados se conservan; un fallo de captura dentro del lote revierte ese lote.

El error no identifica automáticamente cuál de las causas es la responsable. El contador de pendientes y las relaciones de la base permiten investigarlo.

## Pruebas y resultado

Tres pruebas nuevas comprueban:

1. La anterior se prepara primero aunque su ID numérico sea mayor, con lotes de tamaño limitado; no se crean ni cambian órdenes clínicas y repetir la preparación no duplica eventos.
2. Un ciclo entre dos órdenes permanece pendiente, sin consumir secuencias para esas órdenes y sin reportar falsamente una carga terminada.
3. Una orden cuya anterior está anulada y no fue preparada tampoco recibe una secuencia adelantada.

Se mantienen las pruebas anteriores de preparación, rollback/reanudación y eventos sin JSON.

Compilación completa: **203 pruebas, 163 API y 40 OMOD; sin fallos, errores ni omisiones**.
Log: `synchronizationmr/api/target/order-dependencies-build.log`.
OMOD: `synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`.

Las pruebas son locales con OpenMRS/H2 y transporte simulado; no se modificaron instancias ni contenedores.

## Límites del ajuste

Este cambio organiza la preparación de eventos aún no creados. No reordena ni reescribe eventos que ya recibieron secuencia. Si una instalación tiene eventos antiguos en un orden incompatible con sus dependencias, necesita revisión antes de transmitirlos.

Tampoco resuelve por sí solo el caso de registrar una orden nueva dependiente de una histórica mientras todavía no se ha completado la carga inicial. Se mantiene la recomendación de completar la preparación inicial fuera de atención; ese escenario concurrente requiere un tratamiento adicional.

Que una orden anterior esté preparada no demuestra que todos los destinos la hayan recibido. Si el origen es distinto, siguen aplicando las comprobaciones de dependencias y reintentos de recepción.

No se añade un flujo UPDATE. Las nuevas filas nativas de revisión/suspensión continúan siendo CREATE con referencia a su anterior, como se explicó en el documento 20. Los grupos de órdenes, formatos de dosificación personalizados y validación entre las tres instancias siguen pendientes.
