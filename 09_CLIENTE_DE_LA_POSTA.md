# 09. Cliente de sincronización de la posta

> Actualización: la ejecución periódica opcional de este cliente se implementó en
> [10_EJECUCION_PERIODICA.md](10_EJECUCION_PERIODICA.md). Esta guía explica el ciclo individual
> y sus pruebas; las referencias al temporizador pendiente describen el estado del incremento 09.

## Qué construimos

La guía 08 explica la entrada que recibe peticiones. Ahora añadimos el cliente: el código que
hace esas peticiones desde la posta al maestro. Trabaja con creaciones de pacientes y ejecuta
un ciclo cuando se llama a `synchronizeOnce()`. Todavía no arranca automáticamente al instalar
el OMOD ni tiene un temporizador.

Hay tres piezas nuevas en `api`, dentro del paquete `sync`:

| Clase | Responsabilidad |
| --- | --- |
| `PatientSyncClient` | Decide qué enviar y qué recibir; comprueba las confirmaciones. |
| `PatientRemoteTransport` | Define las operaciones de comunicación para poder simular la red en las pruebas. |
| `PatientHttpsTransport` | Hace las peticiones HTTPS al servlet de la guía 08. |

## Un ciclo paso a paso

1. Obtiene el UUID de la posta mediante `LocalNodeService`.
2. Consulta `node` al maestro y comprueba que el UUID coincide con el maestro configurado,
   que su rol es `MASTER` y que admite la primera versión del protocolo HTTP.
3. Pregunta al maestro hasta qué secuencia **ha recibido consecutivamente de esta posta**.
4. Envía los eventos locales que faltan, uno por uno, usando su JSON original.
5. Comprueba cada confirmación: debe corresponder a ese origen y a la secuencia enviada.
6. Consulta los orígenes disponibles en el maestro. Omite su propio origen.
7. Para cada otro origen, consulta la confirmación guardada en la base de datos local y pide
   los eventos que siguen. Los recibe con el `PatientReceiveService` que ya teníamos.
8. Devuelve dos cantidades: envíos confirmados y recepciones confirmadas en este ciclo.

Los registros siguen pasando por el maestro; el cliente no contacta otras postas. No creamos
tablas nuevas ni cambiamos la identidad de los pacientes. Tampoco marcamos un evento como
entregado a todas las postas mediante un único estado global.

Por ahora se usan páginas de un evento y un máximo de 100 envíos propios y 100 recepciones
por origen en cada ciclo. Los siguientes ciclos continuarán desde lo confirmado. Los orígenes
se descubren en páginas de 100. Esta elección facilita comprobar el orden y los reintentos;
el rendimiento con muchas postas deberá medirse en las pruebas entre instancias.

## Qué pasa si se corta la conexión

Si el evento no llegó, el maestro seguirá confirmando el mismo número. Al volver a ejecutar
el ciclo, el cliente enviará el mismo JSON pendiente.

Si el maestro lo guardó pero se perdió la respuesta, el siguiente ciclo preguntará de nuevo
por la confirmación y descubrirá que ya está recibido. La recepción existente también
tolera que se reenvíe exactamente el mismo evento.

En las descargas se usa la confirmación persistente de la posta. Si recibió el número 1 y se
cortó al consultar el 2, el siguiente ciclo comienza después del 1.

Un error interrumpe el ciclo actual; las transacciones que ya terminaron permanecen guardadas.
**Todavía no hay reintentos automáticos ni espera programada**: la reanudación ocurre al llamar
de nuevo al ciclo. El temporizador y su gestión de fallos serán otro incremento.

## Cómo se usará dentro de OpenMRS

La fábrica `PatientSyncClient.forLocalPosta(endpoint, masterUuid, username, password)` comprueba
que la instalación esté configurada como `POSTA`, obtiene los servicios reales desde `Context`
y construye el transporte HTTPS. `endpoint` es la ruta completa del servlet, sin parámetros:

```text
https://SERVIDOR/openmrs/moduleServlet/synchronizationmr/patientSync
```

El UUID esperado del maestro se configura explícitamente. El nombre y contraseña corresponden
a la cuenta técnica de esta posta **en el maestro**, como se explicó en la guía 08.

El llamador también necesita una sesión local de OpenMRS autenticada y con permisos para los
servicios que consulta y para recibir pacientes. La cuenta remota no autentica automáticamente
el trabajo local. El futuro trabajo programado deberá abrir y cerrar ese contexto correctamente.

No se incluyeron contraseñas en los archivos del repositorio ni propiedades globales para
guardarlas. La provisión de credenciales y la configuración del trabajo programado siguen
pendientes. Por ahora la fábrica recibe esos valores en memoria del llamador.

El ciclo no puede ejecutarse dentro de una transacción exterior: cada recepción debe confirmar
su propia transacción antes de avanzar, y no debemos mantener una transacción de base de datos
abierta mientras esperamos a la red. Se debe ejecutar un solo ciclo a la vez por posta.

El transporte utiliza la validación normal de certificados y nombres de servidor de Java,
no sigue redirecciones con las credenciales y aplica tiempos de espera de conexión y lectura.
No registra JSON clínicos ni contraseñas. La comprobación de certificados, filtros web y cuentas
con permisos restringidos todavía requiere una prueba de despliegue real.

## Cómo probar este incremento

Desde la carpeta `synchronizationmr`:

```powershell
mvn -pl api '-Dtest=PatientSyncClientTest' test
```

Las nueve pruebas comprueban:

1. Enviar eventos propios y recibir los de otros orígenes, omitiendo la descarga del propio.
2. Reanudar cuando el maestro guardó pero se perdió la confirmación.
3. Reenviar exactamente el mismo evento cuando no llegó.
4. Reanudar descargas desde la confirmación local persistida.
5. Rechazar una página que salta una secuencia antes de guardar.
6. Rechazar una identidad de maestro inesperada antes de enviar eventos clínicos.
7. Rechazar una confirmación atribuida a otro origen.
8. Impedir el ciclo dentro de una transacción exterior.
9. Rechazar URLs HTTP o URLs que incorporan credenciales o parámetros.

Son pruebas unitarias con servicios y transporte simulados: comprueban las decisiones del
cliente. No conectan dos bases de datos ni prueban una conexión TLS real. Los servicios de
recepción y el servlet conservan sus pruebas de integración local de los incrementos anteriores.

El informe queda en `api/target/surefire-reports/org.openmrs.module.synchronizationmr.sync.PatientSyncClientTest.txt`.
Para construir el OMOD y ejecutar también las pruebas anteriores:

```powershell
mvn package
```

## Qué sigue

Preparar la configuración y la ejecución periódica con su contexto local autenticado, evitar
ciclos simultáneos y gestionar los fallos sin perder lo confirmado. Después podremos comprobar
el recorrido completo entre instancias y los cortes de conexión reales. Encuentros, órdenes y
la reconciliación de pacientes preexistentes siguen pendientes.
