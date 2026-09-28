# Resultados añadidos a encuentros existentes

## Recuperación explícita de observaciones anteriores

Validación del 27/09/2026 a las 19:09: `mvn clean install` terminó con
BUILD SUCCESS y 226 pruebas aprobadas (186 API + 40 OMOD), sin fallos,
errores ni omisiones. Se verificaron recuperación de grupos, conservación
del CREATE y datos clínicos, repetición sin duplicados, avance por lotes y
reintento después de rollback. Se ajustó una prueba antigua que confundía el
contador de eventos con la cantidad de CREATE. La sintaxis PowerShell pasó.
El OMOD de `omod/target` y el instalado en Maven coinciden con SHA-256
`967E08A42121464545AEAC0720BDC9698EFC835ACB78D478989F25E2248F1ECB`.
El 27/09/2026 a las 19:29 se verificaron las tres instancias detenidas
(sin listeners 8080/8081/8082 ni procesos Java de esos servidores) y se
copió este paquete a sus directorios `modules`. Las tres copias coinciden
con el SHA-256 anterior. Respaldo:
`.local-sync-https/omod-before-results-recovery-20260927-192928/`.
Tras el arranque se verificó la recuperación de ORD-1 en las tres bases:
13 observaciones activas con UUID distintos (12 valores numéricos y su grupo),
con los mismos UUID, valores, fecha clínica 2026-09-22 10:13:11, paciente,
encuentro y vínculo con ORD-1. No hay enlaces de órdenes pendientes.
Evento ADD_OBS `47b3a688-9e78-4d81-94e0-eb95bdb1e007`, origen `posta_a`,
secuencia 11, creado el 27/09/2026 a las 19:42:31. SHA-256 del JSON idéntico
en los tres nodos:
`0a09da56ce7295a4fb265040acd38f03cbff87dc7a9df21b308288b7dac86836`.
Maestro y B confirman la secuencia 11 de A. El usuario confirmó también el
hemograma en las pantallas de maestro y B.

Después de reiniciar A con preparación de resultados, se inspeccionaron en
solo lectura los campos del scheduler mediante el puerto de depuración local
1045, sin invocar métodos ni suspender la instancia. Se observó el cursor avanzar
8 -> 12 -> 13 y `resultsComplete=true`, con resultados habilitados y lote 2.
La comprobación posterior en las tres bases conserva 13 observaciones activas
con UUID distintos (12 valores y su grupo) y exactamente un evento de adición
para ORD-1, el mismo UUID y secuencia 11. Queda verificada la repetición de la
recuperación sin duplicados en el laboratorio. Sigue pendiente la prueba de
resultados nuevos con un destino desconectado.

### ORD-4 con B desconectada: primera mitad verificada

Con OpenMRS de B detenido (sin listener 8082), A, maestro y HTTPS continuaron
activos. El usuario guardó en A el valor ficticio 6 para ORD-4, Blood urea nitrogen
(`f818e1e9-6559-4fad-8e05-477a5d61fa3b`). Se comprobó la observación
`68fa3fcb-89b4-46fe-a7ca-7e3bdd5dc4ab` en A y maestro, fecha clínica
2026-09-22 10:54:06 y encuentro `52686588-75c3-4faf-8f12-3fc69a3bf1b1`.
Ambos conservan el evento `8979cd97-4981-4f63-9233-66185cee913b`, origen A,
secuencia 12, con hash JSON
`d5fc0a37d5b080827851f6c26a28cdd89b39414cf82d645e60305de14de99fc1`.
Maestro confirma A=12. B todavía no tiene la observación ni el evento y mantiene
A=11.

Tras arrancar B con sincronización normal, sin preparación histórica, se verificó
la entrega: B confirma A=12 y conserva exactamente una observación activa de
ORD-4, UUID `68fa3fcb-89b4-46fe-a7ca-7e3bdd5dc4ab`, valor 6, misma fecha clínica
y encuentro que A y maestro. El evento y hash anteriores coinciden en las tres
bases, sin duplicados ni enlaces de órdenes pendientes. Queda comprobada la
entrega de un resultado nuevo a un destino que estuvo desconectado. La revisión
visual de este resultado en el SPA de B fue confirmada por el usuario. Queda
cerrado este escenario de prueba tanto en base de datos como en pantalla.
Log local: `.local-sync-https/results-recovery-build.log`. PowerShell reportó
NativeCommandError por una advertencia de XStream en stderr; Maven completó
el reactor, las pruebas y la instalación, verificados también por sus reportes
XML y ambos artefactos. No se omitieron pruebas para obtener el paquete.

Se agregó `EncounterSyncService.prepareExistingObservations(afterEncounterId, limit)`.
Recorre encuentros activos que ya tienen CREATE publicado, por ID local creciente,
con lotes de 1 a 100 encuentros. Compara los UUID de las observaciones originales
activas contra los JSON de CREATE y ADD_OBS; solo publica las que faltan mediante
ADD_OBS. No vuelve a guardar datos clínicos ni modifica los JSON anteriores.
Conserva UUID, fecha clínica, valores, grupos y referencias a órdenes.

La ejecución se solicita con `SYNCMR_PREPARE_RESULTS_ENABLED=true`; requiere
preparación de pacientes, encuentros y órdenes. El scheduler espera a que esas
preparaciones no tengan pendientes antes de revisar resultados. Hace una pasada
por activación; el cursor está en memoria y avanza después de confirmar el lote.
Un reinicio vuelve a revisar desde el principio sin volver a publicar UUID ya
incluidos. Los resultados nuevos siguen siendo capturados por los servicios.

El límite cuenta encuentros, no observaciones individuales. Si un lote falla,
no confirma eventos parciales ni avanza el cursor. Los grupos cuyos padres no
pueden publicarse detienen la preparación para revisión. Se mantienen fuera
de esta operación las correcciones (`previousVersion`), las anulaciones y los
archivos; terminar la pasada no certifica que esos contenidos estén sincronizados.

La prueba nueva de ORD-5 ya fue verificada en las tres bases y visualmente por
el usuario. Results muestra el mismo valor bajo tres paneles del catálogo;
se comprobó una sola observación activa por instancia. La recuperación manual
de ORD-1 queda pendiente de instalar y activar esta nueva preparación.

El historial que sigue corresponde al desarrollo anterior; el procedimiento
vigente de compilación sigue siendo `mvn clean install` en `synchronizationmr`.

Procedimiento vigente: usar `mvn clean install` y el OMOD de
`synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`.
La compilación normal del usuario del 27/09/2026 a las 17:50 pasó las 221 pruebas.
Por solicitud del usuario se retiraron el perfil `isolated-sync-build` y sus
carpetas `.build-sync`; las menciones siguientes se conservan como historial,
no como instrucciones vigentes para compilar o localizar el paquete.

Fecha: 27/09/2026. Estado: código y paquete verificados, OMOD actualizado en las tres instancias apagadas; arranque y prueba SPA pendientes.

## Actualización de las instancias

El 27/09/2026 se ejecutó `mvn -o -Pisolated-sync-build install -DskipTests`
después de la batería completa aprobada. La instalación terminó con BUILD SUCCESS;
no se repitieron las pruebas porque no hubo cambios de código. El empaquetado de
esta ejecución tiene SHA-256
`F09C7707B74BFD7AE2FB612B3941C940EC906B10DD06D4B4E67C3406F9CBF32D`, coincidente
con el artefacto instalado en Maven y las tres copias de modules. Se comprobaron
las clases del API interno contra las de `.build-sync/classes` y la migración.

Antes de copiar se verificó la ausencia de listeners 8080/8081/8082 y de procesos
Java de las tres instancias. Se respaldaron los módulos anteriores en
`.local-sync-https/omod-before-results-20260927-174316/` y se actualizaron maestro,
A y B. No se arrancaron instancias ni se ejecutó todavía la migración sobre sus
bases: eso ocurrirá en el próximo arranque. Verificar módulo cargado y resultados
del SPA antes de considerar cerrada la prueba manual.

## Resultado de la compilación final

El usuario autorizó nuevamente ejecutar la compilación. El 27/09/2026 a las
17:38:51 (Lima), `mvn -o -Pisolated-sync-build package` terminó con **BUILD SUCCESS**:
181 pruebas de API y 40 de OMOD, **221 en total, sin fallos, errores ni omitidas**.
La corrección del fixture de preparación histórica quedó verificada. No fue
necesario modificar nuevamente el código durante esta ejecución.

Paquete: `synchronizationmr/omod/.build-sync/synchronizationmr-1.0.0-SNAPSHOT.omod`.
SHA-256: `4D27475686F637577002895AFF1455471A3A0CF5C968280E635814694B8FC126`.
Se inspeccionó su contenido: API interno, `ObservationAdditionAdvice`,
`EncounterEventStream`, `EncounterDependencyException`, registro del interceptor
en config.xml y migración `20260927-encounter-additions` incluidos.
Registro de ejecución: `synchronizationmr/api/target/results-isolated-build.log`.

Las tres instancias fueron detenidas por el usuario. Esta ejecución solo generó
el paquete: no instaló el artefacto Maven ni reemplazó los OMOD de las instancias.
Esos pasos siguen pendientes antes del arranque y de la prueba manual.

## Problema observado

El SPA guardó los resultados del hemograma ORD-1 como observaciones del encuentro
ya existente. La captura de CREATE no registró esa incorporación. La creación
de una orden DISCONTINUE sí se distribuyó, pero los resultados y el estado de
cumplimiento de la orden no se sincronizaron íntegramente.

## Alcance implementado

- Captura al guardar encuentros y al llamar `ObsService.saveObs`, incluida la
  entrada de resultados por REST. Las llamadas anidadas del mismo encuentro se
  capturan al terminar el guardado exterior para conservar el grupo completo.
- Operación `ADD_OBS`, esquema 4, dentro del flujo de secuencias de encuentros.
  Solo contiene identidad del paciente/encuentro y observaciones adicionales.
  CREATE conserva sus esquemas 2/3 y su JSON original.
- Tabla adicional `synchronizationmr_encounter_addition`: admite varios eventos
  por encuentro sin quitar las restricciones de identidad del CREATE anterior.
  La consulta del transporte combina ambas tablas; usa el mismo recibo de
  encuentros y el mismo contador local, protegidos por el bloqueo del nodo.
- Las observaciones publicadas se identifican por UUID en los eventos conservados.
  Un nuevo guardado sin observaciones adicionales no consume otra secuencia.
  Se conservan grupos nuevos, enlaces a grupos existentes y UUID de órdenes.
- El receptor valida el contenido antes de guardarlo, no sobrescribe UUID de
  observaciones existentes y conserva el JSON recibido para reenviarlo.
  Observaciones, evento, enlaces y confirmación comparten la transacción.
- Si falta la orden, se utiliza el mecanismo existente de enlaces pendientes.
  Si falta el encuentro o el grupo de otro origen, se deja pendiente ese origen
  y se permite recibir los demás; se reintenta en el siguiente ciclo.

## Límites explícitos

Esto no implementa correcciones/anulaciones de observaciones, modificaciones
generales del encuentro, cierre de visitas ni actualización de `fulfillerStatus`.
Las observaciones con `previousVersion` o anuladas no se publican como una
incorporación nueva. Los archivos de observaciones complejas siguen sin admitirse.

No hay un barrido automático al instalar el OMOD para recuperar los resultados
antiguos de ORD-1. Cuando se guarda un encuentro ya preparado, o sus observaciones,
se buscan observaciones activas sin publicar de ese encuentro. La recuperación
explícita de datos anteriores debe planificarse; no volver a introducir los mismos
resultados manualmente para forzar una sincronización.

La tabla de CREATE sigue teniendo un registro por encuentro, mientras que las
adiciones se cuentan aparte. Para comprobar la entrega se usan los recibos y el
flujo combinado, no la igualdad entre cantidad de encuentros y cantidad de eventos.

## Historial de validación anterior a la compilación final

- Pasaron las 25 pruebas anteriores de captura/recepción de encuentros durante
  el desarrollo.
- Pasaron las pruebas nuevas de grupos, guardado directo de observaciones,
  enlace resultado–orden, reintentos, rechazo de UUID/conflictos, ausencia de eco,
  rollback y recepción de dependencias entre orígenes.
- La ejecución completa llegó a 181 pruebas de API: las 11 pruebas de
  `EncounterAdditionIntegrationTest` pasaron; hubo dos fallos en la preparación
  histórica porque su fixture limpiaba CREATE pero no la nueva tabla de adiciones.
  Se actualizó esa limpieza, pero falta completar nuevamente toda la batería.
- Una ejecución posterior no pudo descubrir una clase de prueba cuyo bytecode
  contenía una referencia sin resolver a `EncounterSyncService`. El IDE y Maven
  comparten `target`. Se agregó el perfil opcional `isolated-sync-build`, que
  genera artefactos en `.build-sync` por módulo, fuera de la salida del IDE.
- La compilación aislada restringida falló al acceder al JAR de Velocity del caché
  Maven. Las solicitudes de ejecución con permisos ampliados (primero `install`,
  luego solo `package`) fueron rechazadas. No se completó el paquete nuevo ni se
  actualizaron las tres instancias. El OMOD de `omod/target` sigue siendo anterior.

Comando reproducible desde `synchronizationmr` (ya completado en la compilación final):

```powershell
mvn -o -Pisolated-sync-build package
```

Revisar `BUILD SUCCESS`, todos los reportes API/OMOD y el archivo generado en
`omod/.build-sync/synchronizationmr-1.0.0-SNAPSHOT.omod`. No desplegar el archivo
antiguo de `omod/target` por confusión. Después se debe actualizar también el
artefacto Maven local usado por el SDK, para evitar que el arranque restaure la
versión anterior; esta instalación quedó pendiente de autorización.

## Prueba manual posterior

1. Completar compilación y pruebas. Respaldar los OMOD instalados y actualizar
   las tres instancias con sus procesos detenidos. No detener el módulo desde
   la interfaz ni alterar manualmente sus eventos anteriores.
2. Arrancar y comprobar la migración y el OMOD efectivamente cargado en las tres.
3. Usar una solicitud ficticia sin resultados, ya sincronizada, y añadir resultados
   en una sola instancia mediante el SPA.
4. Comparar UUID, valores, grupos, paciente, encuentro y enlace a la orden en las
   tres bases; comprobar los recibos y su representación en Results.
5. Repetir entrega/reconexión y verificar que no aparecen observaciones duplicadas.
   Separar este resultado de la prueba pendiente del estado de cumplimiento.

Hasta completar esos pasos, no afirmar que la prueba real de resultados quedó cerrada.
