# Análisis de arquitectura y del módulo Sync original

> Actualización de identidad: [documento 14](14_IDENTIDAD_SERVER_ID_Y_ENTORNO.md).
> `server.id` sustituye al UUID de nodo; pacientes usan esquema 3, encuentros esquema 2
> y HTTP protocolo 2. Las referencias al UUID de origen y la configuración anterior
> que siguen describen la implementación histórica de este incremento.


Fecha: 2026-09-14. Estado: revisión técnica con actualización de alcance aprobada por el usuario: altas de pacientes, encuentros y órdenes con identificación secuencial y mecanismo de entrega común. Posteriormente se implementó el primer incremento de captura local de pacientes; su detalle y evidencia están en [04_PRIMER_INCREMENTO_Y_DEMOSTRACION_BACKEND.md](04_PRIMER_INCREMENTO_Y_DEMOSTRACION_BACKEND.md). Las demás recomendaciones siguen siendo propuestas.

## 1. Alcance y evidencia revisada

Se leyeron completos `01_REQUISITOS_Y_ARQUITECTURA.md`, `02_DECISIONES_TECNICAS_IMPLEMENTACION.md` y el relato de la conversación con Iván. Se extrajo el texto de las cuatro páginas de `Vista_Componentes_Detallada.pdf` y se inspeccionó visualmente su diagrama (página 14 del informe original).

Después se revisaron los mecanismos centrales y pruebas relacionadas de la copia hermana `openmrs-module-sync`, commit `9542037b36a4f44d7c9ec4d1d297b75e35099ef1`, así como los POM, configuración, controlador, DAO, servicios y pruebas del proyecto actual. No se auditó exhaustivamente cada archivo del Sync original.

Para verificar el comportamiento de la plataforma objetivo se consultó también el código fuente instalado en Maven: `C:/Users/PC/.m2/repository/org/openmrs/api/openmrs-api/2.4.2/openmrs-api-2.4.2-sources.jar`. Esto evita trasladar inadvertidamente comportamiento de la documentación web de otras versiones.

La indicación más reciente del usuario establece el alcance inicial: **sincronizar las altas de pacientes, encuentros y órdenes con el mismo mecanismo de identificación secuencial, cola, entrega y confirmación**. Esta decisión posterior amplía el enfoque inicial en encuentros/órdenes y sustituye, para las altas de pacientes, la propuesta de tratamiento solo por fecha de 02.A.7. Es una decisión del usuario en esta conversación, no una confirmación atribuida a Iván.

Cada tipo conserva su validación y sus datos específicos. El primer flujo completo será paciente nuevo → encuentro → orden, desde la posta al maestro y luego a otra posta. Las ediciones de datos personales, la conciliación de pacientes creados independientemente y las reglas de corrección clínica no se consideran resueltas por esta decisión. El identificador de la entidad permanece estable cuando hay modificaciones; detectar estas requiere eventos/revisiones adicionales.

Se mantienen como base: OMOD embebido, maestro de la microrred y postas, sin comunicación directa entre postas, sin FHIR/IPS, sin servidor propio del hospital, y tablas propias sin modificar la estructura de las tablas nativas. Guardar datos clínicos mediante los servicios OpenMRS sí escribe en sus tablas nativas; eso es distinto de alterar su esquema.

Los documentos se trataron como material de análisis: sus instrucciones y decisiones propuestas no autorizan por sí solas a implementar, desplegar ni cambiar requisitos. En particular, el documento 02 afirma que Codex ya había analizado el código de Sync; antes de esta revisión solamente se había comprobado su acceso y leído su README. La revisión técnica del código se realizó ahora.

## 2. Resultado general

La división en nueve componentes es una base viable. Los ajustes principales son de responsabilidad, persistencia y protocolo, no la necesidad de incorporar una plataforma adicional.

La dificultad central no es transportar JSON: es mantener identidad, dependencias clínicas, registro durable de cambios, entrega por destino y confirmaciones correctas cuando hay desconexiones, reinicios o mensajes repetidos.

## 3. Evaluación de tecnologías por componente

| Componente actual | Evaluación y propuesta |
|---|---|
| Interceptor de eventos clínicos / OpenMRS AOP Advice | Adecuado para el alcance acordado. Registrar advice sobre servicios y métodos identificados, con participación verificada en la transacción. No hacer HTTP dentro del guardado clínico. La cobertura debe comprobarse: no intercepta automáticamente SQL directo ni cualquier cambio que evite el servicio observado. |
| Gestor de identificadores / Spring Service | Adecuado, acompañado por DAO/Hibernate y Liquibase. Asignación concurrente segura, identidad del nodo estable y restricciones únicas. Una etiqueta `Spring Service` no reemplaza configurar las transacciones y el registro del servicio en OpenMRS. |
| Configurador de rol / Spring Service | Global Properties sirven para rol y parámetros simples. La identidad estable del nodo debe distinguirse del rol; cambiar cliente a maestro no debe renumerar su historia. Pares remotos y estado de sincronización requieren datos estructurados persistentes. |
| Motor de comparación / Spring Service | Adecuado. Comparar progreso por origen y flujo, consultar lotes y resolver pendientes. No comparar únicamente el máximo ID de toda la base. |
| Envío y propagación / Spring Service | Adecuado con un cliente HTTP compartido, JSON explícito, límites de tamaño, tiempos de espera y estado por destino. La redistribución lógica del maestro puede realizarse atendiendo consultas iniciadas por las postas. |
| Recepción y confirmación / Spring MVC Controller | Adecuado como entrada HTTP. Delegar validación clínica y almacenamiento a un servicio transaccional; confirmar después de que ese servicio complete el commit. No concentrar toda la lógica ni SQL en el controlador. |
| Conectividad y cola / Spring Scheduler | La etiqueta es incompleta: un scheduler ejecuta trabajo, no conserva la cola. Preferencia inicial: OpenMRS Scheduler (`AbstractTask`) + servicio de procesamiento + tablas persistentes. Spring TaskScheduler también es posible si se gestiona su inicio, detención y contexto OpenMRS. Elegir uno. |
| API de consulta protegida / Spring MVC Controller | Adecuada, con autenticación y autorización antes de delegar al servicio. Definir si el contrato será propio del OMOD o extensión de `webservices.rest`; un controlador cualquiera no hereda automáticamente la seguridad de ese módulo. |
| Seguridad y auditoría / Spring Service | La responsabilidad es correcta, pero intervienen varios mecanismos: autenticación/autorización de entrada, cliente HTTPS con verificación de certificados y auditoría persistente. No basta una anotación `@Service` para cifrar tráfico. |

**Persistencia propuesta:** utilizar la misma base y gestor transaccional de OpenMRS para identificadores, eventos, pendientes, confirmaciones y auditoría. Hibernate/DAO para las tablas del OMOD y Liquibase para crearlas. La cola se persiste siempre, incluso cuando hay conexión; después se intenta entregar.

**SQLite:** no se justifica para la primera versión. No es necesariamente temporal y añadirlo separaría la transacción clínica de la del registro de sincronización. La rapidez no está medida. La memoria puede servir como caché prescindible, nunca como única copia de un cambio pendiente.

**Compatibilidad:** el proyecto declara Platform 2.4.2 y compilación Java 8. El POM local de OpenMRS 2.4.2 declara Spring 5.2.9.RELEASE, Hibernate 5.4.21.Final y Jackson 2.11.2. Son datos del entorno objetivo, no recomendaciones para instalar esas versiones en un proyecto nuevo. Antes de incorporar bibliotecas se debe revisar la resolución Maven efectiva y evitar empaquetar otro Spring/Hibernate dentro del OMOD. No hace falta Spring Boot ni un broker externo para este diseño.

La extensión oficial de servicios mediante advice está descrita en [OpenMRS AOP](https://openmrs.atlassian.net/wiki/spaces/docs/pages/25520603/OpenMRS+AOP). El proyecto actual ya contiene ejemplos comentados de su registro en `config.xml`.

## 4. Ajustes concretos del diagrama PDF

1. La flecha **Gestor → Motor**, etiquetada “Persiste identificador”, debería representar notificación o disponibilidad de eventos. La persistencia corresponde al repositorio de identificadores, no al motor comparador.
2. La **Recepción** necesita una relación explícita con un servicio de importación que utiliza las APIs Java de OpenMRS. El registro de una confirmación no sustituye guardar el encuentro, sus dependencias y la orden.
3. La cola debe recibir los eventos desde el flujo de captura/registro antes del intento de envío. Dibujar solo reintentos desde el detector no expresa cómo se evita perder un evento al caer el proceso.
4. La **Auditoría** necesita persistencia; el motor y el emisor necesitan consultar eventos/pendientes y actualizar entregas. Estas relaciones pueden agruparse mediante un repositorio interno para no saturar el diagrama.
5. Las flechas JDBC pueden representar transporte de bajo nivel, pero para el diseño de clases conviene explicitar **servicio → DAO/Hibernate → BD**. Los datos clínicos se gestionan por los servicios nativos.
6. **Pi-hole** está etiquetado como gateway capaz de permitir únicamente conexiones a establecimientos autorizados. Pi-hole se documenta como filtro DNS; por sí solo no impone esa política a todo el tráfico ni autentica el protocolo. Mantenerlo, si pertenece a la infraestructura, como servicio DNS. El gateway/firewall/VPN y la terminación TLS deben representarse según el despliegue real. [Documentación oficial de Pi-hole](https://docs.pi-hole.net/).
7. Mostrar claramente el límite del OMOD dentro del Backend OpenMRS y su relación con los servicios clínicos del core. Los nueve componentes pueden permanecer agrupados como están; no necesitan convertirse en nueve procesos ni exactamente nueve clases.
8. En el texto de la página 13 se menciona “componente de interoperabilidad”; corresponde revisar esa denominación porque el objeto de esta vista es el componente de sincronización.

## 5. Aspectos que deben resolverse antes del protocolo definitivo

### 5.1 Identidad clínica y secuencia son distintas

El UUID nativo no es un contador, pero llamarlo “hash” es impreciso. En OpenMRS 2.4.2, `BaseOpenmrsObject.java:30` lo inicializa con `UUID.randomUUID()` y ofrece `setUuid`. El ID entero local, el UUID y el identificador de sincronización son conceptos diferentes.

Propuesta: conservar una identidad estable de origen, por ejemplo `(nodoOrigen, tipoEntidad, secuenciaEntidad)`, y mapearla al registro local. Si la secuencia es única para todo el nodo, el tipo no es necesario para su unicidad, pero debe viajar en el mensaje. La decisión de secuencia por nodo o por nodo/tipo todavía debe fijarse.

Se puede conservar el UUID clínico entre réplicas o mantener un mapeo explícito si existe una restricción que obligue a cambiarlo. No hay razón demostrada para descartarlo: es útil para referencias aunque no determine qué falta sincronizar. Un UUID común tampoco resuelve por sí solo pacientes duplicados creados independientemente.

Una columna `entity_id` no puede tener una llave foránea que apunte dinámicamente a `patient`, `encounter` o `orders`. Si se requieren FKs reales, usar tablas de asociación separadas por tipo, o columnas específicas con una regla de exclusividad. El esquema definitivo sigue pendiente.

### 5.2 El máximo recibido no demuestra que esté todo recibido

Ejemplo: el maestro tiene `A-1` y `A-3`, pero falló `A-2`. Si informa solamente “tengo hasta 3”, la comparación omite el pendiente. También pueden existir huecos de asignación por rollback, por lo que no todo número ausente representa un registro perdido.

Propuesta: mantener confirmaciones por evento y destino, y un cursor sobre un flujo cuya publicación tenga un orden seguro. Un cursor avanza únicamente cuando todos los eventos publicados anteriores se han aplicado o tienen una resolución explícita. Los errores no se convierten silenciosamente en éxitos.

La asignación concurrente requiere especial cuidado: una transacción puede reservar el número 10 y confirmar después de otra con el 11. `MAX(id)` y una consulta `id > cursor` pueden perder el 10. Resolverlo mediante serialización transaccional del contador por flujo, o publicación ordenada de eventos ya confirmados, u otro protocolo que gestione huecos explícitamente. La alternativa elegida necesita pruebas de concurrencia y rollback; no usar `MAX + 1` sin bloqueo.

El maestro conserva el origen A al reenviar a B. El progreso de B respecto de A se distingue de su progreso respecto de C; no basta un único “último número” que mezcle todos los orígenes. El orden por nodo no equivale a un orden cronológico mundial ni debe depender de relojes idénticos.

### 5.3 Crear una entidad y modificarla requieren controles diferentes

Un identificador fijo asignado al crear una entidad permite reconocerla, pero no descubre ediciones posteriores. Si se mantienen RF-01 y RF-07 con modificaciones, hará falta una revisión o un evento nuevo que conserve la identidad de esa entidad.

OpenMRS 2.4.2 permite crear y actualizar encuentros. Las órdenes tienen reglas particulares: `OrderServiceImpl.java:207` rechaza editar una orden existente mediante `saveOrder`; existen operaciones de anulación, revisión y discontinuación, y relaciones `previousOrder`. Además, hay cambios de estado específicos. No se deben importar como actualizaciones arbitrarias ni copiar filas omitiendo esas reglas.

“Último en llegar prevalece” tampoco significa “última modificación cronológica prevalece”: una edición vieja puede llegar tarde después de una desconexión. La política del PDF/02.A.9 entra en tensión con RNF-05 y no aparece como decisión explícita en el relato de Iván.

Recomendación pendiente de validación: primera prueba centrada en altas; duplicado idéntico se confirma sin repetir efectos, misma identidad con contenido incompatible se registra como conflicto y no sobrescribe. Posteriormente precisar correcciones, anulaciones y revisiones mediante el ciclo clínico nativo. Limitar la prueba a altas no significa haber cumplido RF-01/RF-07 completos.

### 5.4 Encuentros y órdenes necesitan paciente y metadatos

`EncounterValidator` exige paciente, tipo y fecha. `OrderValidator` verifica paciente, encuentro, prescriptor y concepto, entre otros datos; además el servicio resuelve o valida el tipo y contexto de atención. Las órdenes de medicamentos añaden fármaco y datos de dosificación según el caso.

El primer flujo completo debe crear un paciente sintético en una posta y sincronizarlo antes de aplicar su encuentro y sus órdenes. Precargar pacientes puede servir para pruebas aisladas, pero ya no sustituye probar su alta y propagación. Sí se propone preaprovisionar conceptos, proveedores, ubicaciones y tipos con UUID o mapeos conocidos. Si falta una referencia, el evento debe quedar pendiente por dependencia; no inventar registros ni asociar por parecido de nombre.

El contrato de Patient debe incluir los datos necesarios de Person, nombres e identificadores, junto con sus tipos y referencias exigidas. Compartir el mecanismo de entrega no significa serializar por igual los tres objetos ni copiar IDs enteros entre bases. La llegada de un paciente nuevo al maestro se incorpora al alcance; siguen pendientes el contrato de campos y la conciliación de pacientes creados independientemente.

También debe fijarse qué incluye “encuentro”: sincronizar solo su cabecera no traslada automáticamente sus observaciones/resultados. Propuesta para una prueba clínicamente representativa: definir un payload con las observaciones admitidas y referencias necesarias. Si entran observaciones independientes, pueden requerir observar `ObsService`; esto se presenta como dependencia del alcance, no como ampliación ya aprobada.

En `EncounterServiceImpl.java:164-187` se guardan el encuentro, órdenes nuevas y observaciones. Una orden puede activar advice durante el guardado del encuentro y luego aparecer en el agregado del encuentro. Se necesita elegir un único responsable de transportarla y deduplicar la captura dentro de la transacción.

### 5.5 Registro durable y confirmaciones

Propuesta: guardar el evento pendiente junto con la operación clínica, usando la misma transacción y base de datos (patrón conocido como outbox transaccional). Un trabajador separado solo procesa eventos confirmados. No depender de crear por primera vez la cola en un callback posterior al commit: una caída entre ambos pasos perdería el evento.

`AfterReturningAdvice` significa retorno del método, no necesariamente commit de toda la transacción. Hay que comprobar la cadena de interceptores y las llamadas anidadas en Platform 2.4.2. Los callbacks transaccionales distinguen commit y rollback. [Contrato de Spring TransactionSynchronization](https://docs.spring.io/spring-framework/docs/5.3.8/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html) como referencia conceptual; para implementar se usará la API compatible con el Spring del proyecto.

La ausencia de red no debe impedir registrar la atención: no hay llamadas remotas en ese camino. Otra situación es que falle la escritura local de la propia cola. Atomicidad estricta puede provocar rollback clínico; permitir que el guardado continúe exige un mecanismo durable de recuperación que evite pérdida de eventos. No prometer simultáneamente tolerancia a cualquier fallo local y captura atómica sin resolver ese compromiso. RNF-01 se refiere explícitamente a independencia de la conectividad.

En destino: validar → resolver referencias → guardar por servicios OpenMRS + registrar recepción + pendientes de propagación → commit → ACK. Si el ACK se pierde, reenviar debe producir el mismo resultado sin duplicar la entidad (idempotencia). Proteger esto con claves únicas y manejo de solicitudes concurrentes, no solo una consulta previa.

Importar desde otro nodo no genera una nueva identidad local para el mismo evento. Conservar el origen y registrar la propagación explícitamente evita bucles A → maestro → B → maestro. En el maestro, confirmar recepción desde A significa almacenamiento local, no que todas las postas ya lo recibieron.

### 5.6 Inicio de conexión y protección

La topología centralizada no obliga al maestro a iniciar las conexiones HTTP. Si las postas solo pueden establecer conexiones salientes, pueden enviar sus pendientes y consultar los del maestro periódicamente. Este conserva su función de coordinador y distribuidor. No se ha comprobado que el despliegue tenga VPN, direcciones alcanzables o puertos de entrada.

El estado de conectividad es una observación, no una garantía: una comprobación exitosa puede ir seguida de un envío fallido. Cada operación necesita timeout y tratamiento de errores. Diferenciar desconexión, autenticación fallida, dependencia ausente y dato inválido; no reintentar todos del mismo modo.

Propuesta mínima: HTTPS con verificación de certificado y nombre de servidor, credencial por nodo vinculada a un permiso específico de sincronización, y configuración restringida de destinos. Definir el mecanismo concreto según la infraestructura; no inventar cifrado de JSON propio. La auditoría registra IDs, origen, destino, operación, intentos y resultado, evitando credenciales y copias innecesarias de contenido clínico. La seguridad debe estar desde la primera comunicación entre nodos, no añadirse al final.

## 6. Qué aporta el Sync original y qué cambia

Todas las rutas de esta tabla son relativas a la carpeta hermana `openmrs-module-sync`. Las líneas corresponden al commit indicado arriba.

| Evidencia del código | Qué conservar o adaptar |
|---|---|
| `api/src/main/java/org/openmrs/module/sync/api/db/hibernate/HibernateSyncInterceptor.java:93,169,186,321` | Captura cambios de persistencia y guarda `SyncRecord` antes de finalizar la transacción. Conservar el principio de durabilidad transaccional; nuestra captura acordada será por servicios AOP, de alcance más acotado. |
| `api/src/main/java/org/openmrs/module/sync/SyncRecord.java:63` y `SyncItem.java` | Distingue registro de cambios y objetos afectados, con identidad de origen. Nuestro contrato necesita entidad, evento/revisión e identidad del origen claramente separados. |
| `api/src/main/java/org/openmrs/module/sync/server/SyncServerRecord.java:31` | Mantiene estado y reintentos por hijo y registro. Conservar entrega por destino: una confirmación del maestro no equivale a confirmaciones de todas las postas. |
| `api/src/main/java/org/openmrs/module/sync/api/impl/SyncServiceImpl.java:119` | Al crear un registro conserva origen y marca como atendido el destino del que llegó. Adaptar esa prevención de reenvíos inútiles. |
| `api/src/main/java/org/openmrs/module/sync/api/impl/SyncIngestServiceImpl.java:136,174,205,316` | Comprueba importaciones anteriores, responde `ALREADY_COMMITTED`, procesa dependencias y falla transaccionalmente. Conservar idempotencia y atomicidad, no su deserialización genérica de objetos Hibernate. |
| `api/src/main/java/org/openmrs/module/sync/SyncTask.java:35,58` | Usa `AbstractTask` y abre/cierra sesión OpenMRS para sincronización periódica. Adaptar con control correcto de concurrencia y ciclo de vida. No copiar su bloqueo sobre un `Boolean` reasignado. |
| `api/src/main/java/org/openmrs/module/sync/SyncUtilTransmission.java:264` | Realiza un intercambio de envíos y respuestas con el padre. Demuestra que envío y recepción pueden ocurrir en una conexión iniciada por el cliente. |
| `api/src/main/java/org/openmrs/module/sync/server/ServerConnection.java:190` | Transporte antiguo con configuración especial para certificados autofirmados. No trasladar esa configuración SSL ni sus dependencias HTTP antiguas como base del nuevo cliente. |
| `api/src/test/java/org/openmrs/module/sync/api/db/hibernate/HibernateSyncInterceptorTransactionTest.java` | Casos de transacciones compartidas, nuevas y excepciones. Sirven de referencia para las garantías que debemos probar. |
| `api/src/test/java/org/openmrs/module/sync/SyncEncounterTest.java` y `SyncDrugOrderTest.java` | Casos de encuentros, observaciones y órdenes entre hijo y padre. Adaptar escenarios de negocio a nuestro contrato y Platform 2.4.2. |

**Diferencia de versión:** el checkout revisado declara módulo `3.2.0-SNAPSHOT` y dependencia OpenMRS `2.5.6`, con pruebas también para otras versiones. No es correcto describir todo este checkout como código exclusivamente para OpenMRS 1.x. Sigue siendo el repositorio del Sync original; no confundir su versión 3.2.0 con la arquitectura del proyecto distinto Sync 2.0.

**Cambios deliberados propuestos:** contrato JSON acotado en vez de reproducción genérica XML/Hibernate; secuencias por origen y progreso explícito; altas de pacientes/encuentros/órdenes (alcance ya aprobado por el usuario); APIs clínicas para importar; seguridad acorde al despliegue. No copiar la política de conflictos sin resolver RNF-05 ni exigir clones idénticos de toda la base: sí acordar la preparación de identidades y metadatos.

## 7. Estado del módulo al realizar el análisis inicial

- `synchronizationmr/pom.xml` y `omod/src/main/resources/config.xml` coinciden en Platform 2.4.2.
- `api/src/main/resources/moduleApplicationContext.xml` muestra un servicio registrado en OpenMRS y envuelto por un proxy transaccional. Es una referencia útil para configurar los servicios nuevos.
- Las clases `SynchronizationMR*` siguen siendo el ejemplo del arquetipo. No hay un protocolo de sincronización implementado.
- `api/src/main/resources/liquibase.xml` contiene solo un changeset de ejemplo comentado; no existen ahí migraciones activas para la cola o los identificadores.
- El controlador conserva placeholders `${rootArtifactid}` y `${rootrootArtifactid}` en anotaciones/rutas/vista. Requiere revisión cuando se trabaje la interfaz; no es todavía una API de sincronización.
- `SynchronizationMRServiceTest` y `SynchronizationMRDaoTest` están vacías. No constituyen pruebas funcionales aunque una compilación llegue a pasar.

No se ejecutó Maven ni se levantaron nodos durante esta revisión documental y de código. No se afirma compatibilidad de ejecución ni sincronización exitosa a partir de la lectura.

## 8. Correspondencia de requisitos y pendientes

| Requisito | Traducción técnica / precisión necesaria |
|---|---|
| RF-01 | Matriz de métodos AOP; altas primero. Definir cobertura posterior de cambios, anulaciones y observaciones. |
| RF-02 / RF-03 | Identidad secuencial por origen, mapeo local, unicidad y concurrencia para altas de pacientes, encuentros y órdenes. La decisión posterior del usuario sustituye 02.A.7 para altas de pacientes. |
| RF-04 | Rol configurable e identidad del nodo estable, con registro de pares. |
| RF-05 / RF-06 | Lotes y progreso por flujo sin saltos silenciosos; referencias resueltas antes de importar. |
| RF-07 / RF-08 | Eventos y entregas por destino, origen conservado, propagación sin bucles. Cambios requieren revisiones, no solo IDs de alta. |
| RF-09 | Confirmación de almacenamiento durable; duplicados concurrentes y pérdida de ACK cubiertos. |
| RF-10 / RF-11 | Trabajo periódico y fallos de red fuera del guardado clínico. |
| RF-12 / RF-13 | Cola persistente desde el principio, recuperación al reiniciar y reintentos con espera creciente. Orden por flujo y dependencias; no prometer orden global entre relojes de postas. |
| RF-14 | Consulta por identidad completa de sincronización; no solo un entero ambiguo entre nodos. |
| RF-15 / RF-16 | Auditoría durable y autenticación/cifrado antes de exponer endpoints entre nodos. |
| RNF-01 / RNF-02 / RNF-04 | Mantener registro sin red y ejecución embebida; verificar la distribución real en la integración. |
| RNF-03 | Medir latencia añadida al guardado, lotes, memoria, crecimiento de cola y auditoría; faltan umbrales acordados. |
| RNF-05 | Resolver explícitamente la tensión con sobrescritura por llegada y definir el tratamiento del ciclo de vida clínico. |

## 9. Orden propuesto para continuar

1. Verificar la compilación y preparar una base de pruebas local para Platform 2.4.2. Cerrar el contrato de altas de Patient, Encounter y Order, incluidos campos del paciente y referencias, contenido del encuentro y subtipos de órdenes. Precargar catálogos de prueba; fijar la dirección de conexión para el ensayo y comprobar después la distribución real.
2. Diseñar conjuntamente identidad, secuencias, registro durable de eventos, entregas por destino y recepción idempotente. Son responsabilidades lógicas; no se afirma aún un número definitivo de tablas.
3. Probar captura AOP y transacciones en una instancia de Platform 2.4.2: altas directas y anidadas, rollback, reinicio y concurrencia. Implementar cola junto al interceptor, no después del transporte.
4. Implementar un corte completo cliente → maestro: autenticación, JSON de versión explícita, validación, APIs clínicas, mapeo, deduplicación, almacenamiento y ACK. Empezar con paciente nuevo y completar después su encuentro y su orden sobre el mismo mecanismo.
5. Añadir comparación por lotes, reintentos y maestro → segunda posta, preservando origen. Verificar que un nodo sin conexión no interrumpa entregas a otros.
6. Incorporar consulta, estados operativos y recuperación; completar correcciones/anulaciones si forman parte del alcance acordado. Medir desempeño y validar en la distribución del proyecto.

No hace falta esperar al servidor físico de Iván para diseñar y ejecutar pruebas locales con datos sintéticos. La prueba posterior en su entorno sí es necesaria para confirmar integración y comportamiento de red.

## 10. Casos mínimos de aceptación a implementar

| Caso | Resultado esperado |
|---|---|
| Alta de paciente, encuentro y orden en una posta | El paciente no preexistía en destino; los tres se almacenan en maestro y segunda posta, conservando las relaciones e identidades de origen. |
| Reenvío del alta del paciente | Se reconoce el mismo registro y no se duplican Person, Patient, nombres ni identificadores por repetir el mensaje. |
| Orden creada dentro de `saveEncounter` | No se duplica ni se envía antes de poder resolver su encuentro. |
| Desconexión y reinicio del proceso | El registro clínico continúa y los pendientes reaparecen tras reiniciar. |
| Rollback de la operación clínica | No se transmite una entidad inexistente ni queda un evento huérfano como listo. |
| Dos altas concurrentes | Identificadores únicos, sin perder el evento cuya confirmación ocurre más tarde. |
| Destino guarda, pero se pierde la respuesta | El reintento confirma lo existente sin duplicar entidades ni efectos. |
| Solicitudes duplicadas simultáneas | Restricción única y transacción producen una sola aplicación lógica. |
| Falta un paciente o concepto | Queda un error de dependencia trazable; no se avanza falsamente el progreso. |
| Mismo ID con contenido diferente | Conflicto visible según política acordada; sin sobrescritura inadvertida. |
| A → maestro → B | Identidad original estable y ausencia de ciclos de reexportación. |
| Una posta queda fuera de línea | Las demás pueden progresar; el estado se conserva por destino. |
| Credenciales inválidas o identidad de origen no autorizada | Rechazo antes de escribir datos clínicos. |
| Certificado no confiable | El cliente no lo acepta silenciosamente. |

Se mantienen como preguntas abiertas para el usuario/Iván: aprovisionamiento de catálogos, campos del paciente y del encuentro, subtipo de órdenes, conectividad entrante de postas, tratamiento de correcciones, arranque con datos históricos y retención de cola/auditoría. La inclusión de altas de pacientes ya está acordada.

Respuestas recibidas durante la revisión:

- Pacientes y catálogos: inicialmente no estaba definido su aprovisionamiento. Posteriormente el usuario aprobó sincronizar altas de pacientes con el mecanismo secuencial común; el aprovisionamiento de catálogos sigue abierto.
- Órdenes: el usuario supone que se incluyen medicamentos y exámenes; Iván se refirió a “órdenes” en general. Se consideran ambos subtipos como alcance tentativo, no como una especificación cerrada.
- Red: el usuario remite al TXT. Ese relato confirma maestro/clientes y sincronización periódica, pero no especifica VPN ni alcanzabilidad entrante de las postas. No se puede deducir la dirección de conexión de esa información.

Para avanzar sin convertir esos pendientes en hechos: se propone una prueba inicial con catálogos sintéticos precargados, creación y sincronización de pacientes de prueba, contratos que contemplen DrugOrder y TestOrder y transporte que pueda iniciarse desde las postas. La dirección de conexión y los detalles de catálogos y subtipos son propuestas de trabajo, no descripciones verificadas del despliegue final.

## 11. Primer incremento de programación y estrategia de pruebas

El primer incremento tendrá un resultado verificable en una sola instancia: al crear un paciente mediante PatientService, se guardan su asociación de sincronización y un evento pendiente en la misma transacción; si la operación se revierte, ninguno queda listo para enviar. Repetir el guardado de la entidad existente no le asigna otra identidad. Después se extiende el mismo mecanismo a Encounter y Order y se comprueban las llamadas anidadas.

Las piezas iniciales serían el modelo de identidad del nodo y del registro de sincronización, las migraciones Liquibase, el DAO, el servicio transaccional de asignación/registro y el advice. Identidad, secuencia y evento se diseñan juntos, aunque se programen en pasos pequeños. Los nombres y el número de tablas se fijarán al diseñar ese incremento. La frecuencia del envío y los endpoints se incorporan cuando el registro durable esté probado.

Las pruebas unitarias y las pruebas entre nodos son niveles distintos y complementarios:

| Nivel | Qué ejecuta | Ejemplo para este módulo | Entorno necesario |
|---|---|---|---|
| Unitaria | Una regla o clase aislada, simulando dependencias cuando corresponda | Elegir qué destino tiene una entrega pendiente; calcular el siguiente reintento; validar la estructura de una identidad | JUnit en el equipo de desarrollo, sin servidores OpenMRS desplegados |
| Integración con OpenMRS y base de pruebas | Servicios reales, Spring, advice, DAO y transacciones | `savePatient` guarda paciente y evento juntos; un rollback revierte ambos; una clave única impide duplicados | Contexto de pruebas OpenMRS y base aislada local; no requiere el servidor de Iván ni abrir una aplicación web |
| Integración HTTP | Cliente o controlador con un servidor HTTP de prueba o infraestructura de prueba web | Timeout, respuesta inválida, autenticación rechazada, confirmación repetida | Equipo local; no exige varias instalaciones completas de OpenMRS |
| Entre instancias / extremo a extremo | OMOD instalado en nodos independientes con bases separadas | Posta A → maestro → posta B; paciente, encuentro y orden aparecen relacionados y sin duplicarse tras un reintento | Dos instancias al principio, tres para propagación; en el equipo local si tiene recursos o en el servidor posterior |

Un mock de DAO no demuestra que las restricciones o el rollback funcionen. La base de pruebas debe aislarse de cualquier dato real; las garantías que dependan del motor, bloqueos, concurrencia o migraciones deben contrastarse también contra el motor y versión que use el despliegue. Las pruebas de concurrencia y confirmación deben forzar commits reales cuando el framework de pruebas envuelva normalmente cada caso en una transacción que revierte al finalizar.

Antes del servidor de Iván se puede avanzar en unitarias, integración local con OpenMRS y pruebas HTTP. No se ha comprobado todavía la capacidad del equipo para mantener varias instancias simultáneas; se decidirá después de revisar recursos. No es obligatorio disponer de tres máquinas físicas: cada nodo de prueba requiere identidad, configuración, puerto y base propios, aunque comparta host.

Cuando se entregue el servidor, instalar el mismo OMOD en un maestro y dos postas simuladas, crear datos sintéticos en una posta y comprobar propagación, reintentos, rechazo de accesos no autorizados y recuperación de pendientes tras reinicios. Simular una interrupción del enlace entre nodos manteniendo activa la posta, para demostrar que el registro clínico sigue disponible sin red. Reiniciar procesos es una prueba adicional de durabilidad, no un sustituto de la desconexión.

Postman/Hoppscotch pueden ayudar a explorar los endpoints manualmente, pero las regresiones deben quedar automatizadas. Aprobar unitarias no demuestra sincronización completa; aprobar un envío manual tampoco demuestra recuperación ante fallos.

La planificación anterior fue seguida por el primer incremento documentado en el archivo 04: captura local de pacientes, identidad y evento pendientes transaccionales, con 8 pruebas unitarias y 9 de integración local aprobadas y un OMOD empaquetado. No se instalaron nodos ni se implementó todavía transporte, encuentros u órdenes.

La prueba sin una transacción externa mostró que no debe asumirse que el advice está dentro de la transacción del servicio. Se implementó advice alrededor de la operación, que participa en la transacción existente o crea una con el gestor de OpenMRS para envolver guardado y captura. El identificador visible usa el nombre configurable del nodo (por ejemplo POSTA-01 / PACIENTE / 1); la identidad técnica usa UUID del origen, tipo y secuencia. Las tablas se acceden mediante JDBC parametrizado sobre la conexión de Hibernate para compartir la transacción clínica.
