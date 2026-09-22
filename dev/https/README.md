# HTTPS local para las pruebas de sincronización

Preparado para el SDK 6.8.0 con Tomcat 9 integrado. No cambia el OMOD ni Liquibase.

## Estado del entorno

- Maestro HTTP: `localhost:8080`, acceso habitual de administración.
- Proxy Docker: `sihsalus_https`, publicado exclusivamente en `127.0.0.1:8443`.
- Imagen usada: `nginx@sha256:ef8676b33d681f272ba429b27658bdd7e640963279714c96bddf1dc76307f7b6`.
- Solo se publican por HTTPS los tres servlets de sincronización; `/` devuelve 404 intencionalmente.
- Certificado autofirmado RSA 3072, válido durante 365 días desde su generación, con SAN `localhost` y `127.0.0.1`.
- Certificado, clave privada y truststore de pruebas en `.local-sync-https/`, excluido de Git. No borrar esa carpeta mientras se use el proxy. No se modificó el almacén global de certificados del equipo ni del JDK.
- Nginx monta únicamente `nginx.conf`, `master.crt` y `master.key` como archivos de solo lectura.

## Ajuste del maestro

Se añadió a `META-INF/context.xml` del WAR local
`C:\Users\PC\openmrs\microrred_maestro\openmrs-2.8.10-SNAPSHOT.war`:

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       internalProxies="127\.0\.0\.1|::1|0:0:0:0:0:0:0:1"
       protocolHeader="X-Forwarded-Proto"
       portHeader="X-Forwarded-Port"
       httpsServerPort="8443" />
```

Respaldo original: `.local-sync-https/maestro-original-before-https.war.bak` en el repositorio, fuera de la instancia.
No dejar respaldos con nombre de WAR dentro de la carpeta de la instancia: el SDK los cuenta como otra copia de OpenMRS y rechaza el arranque.
El SDK reconstruye `tmp` al arrancar; por eso no se editó únicamente el contexto temporal.
Actualizar o reemplazar el WAR requiere revisar y volver a aplicar este ajuste.
No se modificó la copia de OpenMRS en Maven.

La confianza está limitada a loopback: no ampliar `internalProxies` a todas las direcciones.
Si Docker Desktop presenta otra dirección de origen, hay que identificarla y validar la frontera de confianza antes de cambiarla.
Nginx reemplaza las cabeceras de protocolo y puerto; el OMOD conserva su comprobación `request.isSecure()`.
Esta configuración es para pruebas locales en una sola PC, con proxy y backend en el mismo equipo.

## Validaciones y pendientes

- `nginx -t`: correcto.
- Cliente Java 21 con el truststore local: valida TLS y nombre `localhost`; `/` responde 404 esperado, sin omitir la validación de certificados.
- Maestro reiniciado: consulta HTTPS `patientSync?resource=node` con ambas cuentas remotas devuelve HTTP 200, identidad `microrred_maestro` y rol `MASTER`; sin credenciales devuelve HTTP 401. Estas consultas no envían eventos clínicos.
- El truststore local contiene también las autoridades de confianza del JDK, para conservar el acceso a servicios externos.
- Postas reiniciadas: se verificó que ambos procesos `run-tomcat` recibieron el parámetro del truststore mediante `jvmArgs`.
- Sincronización automática todavía desactivada al verificar el entorno; las tres bases tenían cero pacientes y cero eventos de pacientes.

## Primera prueba de pacientes

### Configuración CSRF del receptor

En la primera prueba real, el paciente y su evento CREATE se guardaron en A, pero el filtro CSRF del maestro bloqueó el POST antes de llegar al servlet. No había paciente ni confirmación en el maestro/B.
Se creó `C:\Users\PC\openmrs\microrred_maestro\csrfguard.properties` copiando la configuración completa de `WEB-INF/csrfguard.properties` de esa instalación y agregando únicamente:

```properties
org.owasp.csrfguard.unprotected.SyncMRPatient = %servletContext%/moduleServlet/synchronizationmr/patientSync
org.owasp.csrfguard.unprotected.SyncMREncounter = %servletContext%/moduleServlet/synchronizationmr/encounterSync
org.owasp.csrfguard.unprotected.SyncMROrder = %servletContext%/moduleServlet/synchronizationmr/orderSync
```

`org.owasp.csrfguard.Enabled=true` permanece intacto. No se excluye toda la aplicación ni todos los servlets. Estos tres endpoints exigen HTTPS, credenciales explícitas en cada petición, autorización del par y contenido JSON para recepción; no usan la sesión del navegador como autenticación.
OpenMRS carga este archivo desde su directorio de datos al iniciar. El 21/09/2026, después del reinicio del maestro, el evento pendiente llegó automáticamente al maestro y a B sin volver a registrar al paciente.
No se necesita esta excepción en las postas para descargar eventos: el proceso receptor local llama a servicios Java, no hace POST a su propio servlet.
Si se actualiza OpenMRS, revisar también los cambios del archivo CSRF base; la copia local sustituye al archivo empaquetado.

Referencia: [carga de CSRF en OpenMRS](https://github.com/openmrs/openmrs-core/blob/master/web/src/main/java/org/openmrs/web/Listener.java).

### Resultado observado el 21/09/2026

- Un paciente ficticio creado mediante la SPA en posta A, un paciente en cada base después de la entrega.
- Mismo UUID de paciente y mismo UUID de evento CREATE en las tres bases; identidad de sincronización con origen `posta_a` y secuencia `1`.
- Maestro y B confirman `posta_a / confirmed_sequence=1` en `synchronizationmr_patient_receipt`.
- Coinciden nombre, apellido, fecha de nacimiento, dirección e identificador clínico. La huella SHA-256 del JSON del evento coincide en las tres bases.
- Los identificadores numéricos locales son A=4, maestro=5, B=4; no se usan para identificar al paciente entre servidores.
- `state=PENDING` continúa en los eventos: la entrega se comprueba con los recibos por origen, no con ese campo.
- Segunda prueba: un paciente ficticio distinto creado en B llegó al maestro y después a A en el siguiente ciclo. Las tres bases tienen dos pacientes; maestro y A confirman el origen `posta_b` hasta la secuencia `1`. Coinciden UUID, fecha de nacimiento, dirección y SHA-256 del JSON del segundo evento. El primero permanece sin duplicarse.
- Antes de la tercera prueba se verificó que los tres generadores IDGen usaban la misma base inicial. El usuario configuró prefijos `1` (maestro), `2` (A) y `3` (B), con longitud mínima y máxima 8. Se comprobaron los valores en las bases y la conservación de los dos identificadores existentes. No se reiniciaron contadores. Esta configuración de laboratorio debe conservarse y revisarse al actualizar los archivos de Initializer.
- Tercera prueba: paciente ficticio creado en el maestro, identificador `1100000W`, origen `microrred_maestro`, secuencia `1`. Ambas postas lo descargaron y confirmaron. Hay tres pacientes con tres UUID distintos en cada base; coinciden UUID, identificador, fecha de nacimiento, dirección y SHA-256 del JSON del tercer evento.
- Cuarta prueba: segundo paciente local de A, identificador `2100002N` (prefijo 2, longitud 8), origen `posta_a`, secuencia `2`. Llegó al maestro y luego a B; ambos confirman `posta_a=2`. Se comprobaron cuatro pacientes con cuatro UUID distintos por base y coincidencia de UUID, fecha, dirección y huella del JSON del cuarto evento. La secuencia local de A avanzó de 1 a 2 sin contar los pacientes importados de otros orígenes.
- Quinta prueba (desconexión controlada): se detuvo únicamente `sihsalus_https`, manteniendo OpenMRS y las bases activos. El usuario creó en A el paciente ficticio `Prueba SinConexionA`, identificador `2100003L`, origen `posta_a`, secuencia `3`. Con el proxy detenido se verificó A=5 pacientes, maestro=4 y B=4; ambos receptores seguían confirmando hasta `posta_a=2`.
- Al reiniciar únicamente el proxy, los procesos periódicos entregaron el evento sin volver a guardar al paciente. Se verificaron cinco pacientes y cinco UUID distintos en cada base, recibos `posta_a=3` en maestro y B, y coincidencia de UUID del paciente, UUID del evento, identificador, nombre, fecha, dirección y SHA-256 del JSON. El proxy quedó en ejecución. Esto simula indisponibilidad del canal HTTPS compartido; no prueba aún pérdida de respuesta tras un commit remoto ni caída/reinicio de una posta.
- Esto valida creaciones sencillas A → maestro → B, B → maestro → A y maestro → ambas postas, además de recuperación de eventos pendientes tras restablecer el canal HTTPS. No valida todavía carga histórica, cambios posteriores, encuentros ni órdenes.
- Incidencia previa del formulario: `1990-01-01` a medianoche es una hora inexistente en America/Lima según Java; para este paciente ficticio se usó `1990-01-02`. Sigue pendiente corregir el tratamiento de fechas para registros reales.

Detener cada posta antes de volver a ejecutar su comando. Mantener el maestro y los contenedores encendidos.
Desde la raíz del repositorio, en terminales distintas:

```powershell
.\dev\https\Start-Posta.ps1 -ServerId posta_a -SincronizarPacientes
.\dev\https\Start-Posta.ps1 -ServerId posta_b -SincronizarPacientes
```

Cada comando solicita dos contraseñas sin mostrarlas: primero la de `sync_local_a`/`sync_local_b` en la posta y después la de `sync_posta_a`/`sync_posta_b` en el maestro.
Se transmiten al proceso mediante variables de entorno; no se guardan en el repositorio. Se eliminan del entorno de la terminal al finalizar normalmente el script.
El parámetro habilita solo el transporte de pacientes cada 60 segundos. La preparación histórica, encuentros y órdenes permanecen desactivados.
Sin el parámetro, el script solo configura TLS y deja desactivada la comunicación.
No crear pacientes hasta comprobar ciclos completados en las dos postas; a continuación crear un único paciente ficticio en A y verificar su entrega y sus relaciones en las tres bases.

URL inicial de pacientes:
`https://localhost:8443/openmrs/moduleServlet/synchronizationmr/patientSync`

Para detener/reanudar solo el proxy: `docker stop sihsalus_https` / `docker start sihsalus_https`.
Para revertir el ajuste del maestro, detenerlo y restaurar el WAR desde su respaldo antes de arrancar de nuevo.

### Preparación de pacientes existentes (prueba completada)

El 21/09/2026 se detuvo SynchronizationMR en A desde la interfaz. Después de la
recarga del contexto, IDGen falló con `EntityManagerFactory is closed`; el intento
no guardó al paciente. Reiniciar la instancia con `synchronizationmr.started=false`
permitió registrar los tres pacientes ficticios `PacienteExistenteA`,
`PacienteExistenteB` y `PacienteExistenteC`. Se verificaron ocho pacientes, cinco
eventos y cero eventos para cada uno de esos tres pacientes. La causa raíz de la
incidencia al detener el módulo en caliente sigue pendiente de diagnóstico.

El script admite ahora `-PrepararPacientesExistentes` y
`-TamanoLotePreparacion` (1–100; predeterminado 25). La preparación es independiente
del transporte y requiere que el usuario local tenga `Prepare Synchronization Records`
y `Get Patients`, y que el módulo esté iniciado. Encuentros y órdenes siguen
deshabilitados. La configuración se aplica al iniciar el proceso, no a la instancia
que ya está ejecutándose. No requiere recompilar el OMOD.

Comando previsto para la prueba, **después de conceder el permiso, detener A y
dejar habilitado el arranque del módulo**:

```powershell
.\dev\https\Start-Posta.ps1 -ServerId posta_a -SincronizarPacientes -PrepararPacientesExistentes -TamanoLotePreparacion 2
```

Esto prepara hasta dos pacientes por ciclo, con 60 segundos entre ciclos. No cambia
el límite de envío del cliente HTTP.

Resultado verificado el 21/09/2026: después de conceder el permiso al rol local y
habilitar `synchronizationmr.started=true` con A apagada, se ejecutó el comando.
El primer lote preparó dos eventos a las 13:35:31 y el segundo preparó el tercero
a las 13:36:31. Se observó el estado intermedio de siete eventos y luego ocho.
El maestro y B recibieron los tres pacientes y confirman `posta_a=6`. Las tres
bases tienen ocho pacientes, ocho UUID distintos y ocho eventos, sin duplicados
en esta prueba. Coinciden UUID del paciente y evento, apellido, fecha de nacimiento,
identificador clínico, dirección y SHA-256 del JSON. Los identificadores de los
tres pacientes son `2100004J`, `2100005G` y `2100006E`.
La preparación y el transporte quedaron habilitados en A. Esta prueba no cubre
la conciliación de una misma persona registrada independientemente en dos nodos.

### Configuración para probar encuentros

Incidencia del primer formulario legacy: rechazó `09/21/2026 10:00 AM`.
Se reprodujo con la biblioteca instalada y Java 21: el patrón inglés contiene
U+202F antes de AM/PM; tanto el espacio normal como omitirlo fallan. El formato
español `dd/MM/yyyy HH:mm` aceptó `21/09/2026 10:00` en la comprobación local.
Se propone cambiar el idioma del formulario a español para continuar; esto no
constituye una corrección del parser. El usuario confirmó después el guardado
usando el formulario en español.

Primera prueba real de encuentros completada el 21/09/2026: A creó un encuentro
`Consultation`, fecha `2026-09-21 10:00:00`, paciente `PacienteExistenteA`, ubicación
`Outpatient Clinic`, profesional `Unknown Provider`, rol `Clinician`, sin visita.
Se comprobó su llegada al maestro y, en el siguiente ciclo observado, a B.
Cada base contiene un encuentro y un evento. Coinciden el UUID del encuentro
`bfd0ac5e-700b-4b7e-8b66-83565fc351f0`, el UUID del paciente, fecha, ubicación,
tipo, UUID de la relación con el profesional y sus referencias de profesional y rol.
El evento `5f687a92-f3d0-4434-986b-c2e5bfaf393d` conserva origen `posta_a`,
secuencia `1` y SHA-256
`11e6153cc8642a8427d24c37da4924cad1a6d7dae5e9749de94901fd346b4b0b`
en las tres bases. Maestro y B confirman `posta_a=1` en el recibo de encuentros.
Esto valida un CREATE sencillo A → maestro → B; no valida todavía observaciones,
visitas, diagnósticos, condiciones, carga histórica ni modificaciones de encuentros.

El script admite `-SincronizarEncuentros` junto con `-SincronizarPacientes`.
Configura el endpoint HTTPS `encounterSync`; no activa órdenes ni preparación
histórica de encuentros. Se verificó la sintaxis PowerShell. La ejecución de la
comprobación de parámetros quedó bloqueada por la política de scripts de la
terminal del agente; no se cambió esa política. La entrega real de encuentros
ya se comprobó para el caso sencillo descrito arriba; los demás casos siguen pendientes.

Antes del reinicio de las postas, añadir mediante Administración de OpenMRS al
rol `Sincronizacion Microrred` de las tres instancias: `Get Encounters`,
`Add Encounters`, `Get Observations`, `Add Observations`, `Get Encounter Types`,
`Get Encounter Roles`, `Get Providers`, `Get Forms` y `Get Visits`, conservando los
permisos de pacientes. Se confirmó que esos privilegios existen y que las tres
bases tienen cero encuentros y cero eventos de encuentro. El usuario guardó los
permisos por interfaz y se verificaron en las tres bases: el rol conserva los
permisos de pacientes, incluye los nueve de encuentros y también
`Prepare Synchronization Records`. Se comprobó su asignación a `sync_posta_a`
y `sync_posta_b` en el maestro, a `sync_local_a` en A y a `sync_local_b` en B.
`Get Visits` solo permite consultar visitas;
no implementa su sincronización.

Después de guardar permisos y detener cada posta desde su terminal:

```powershell
.\dev\https\Start-Posta.ps1 -ServerId posta_a -SincronizarPacientes -SincronizarEncuentros
.\dev\https\Start-Posta.ps1 -ServerId posta_b -SincronizarPacientes -SincronizarEncuentros
```

No requieren reconstruir el OMOD. Para esta prueba la preparación histórica
queda desactivada; los pacientes ya preparados conservan sus identidades y eventos.
El maestro recibe peticiones; no necesita ejecutar este script.

### Siguiente prueba: encuentro de la SPA con visita

La SPA exigió una consulta/visita activa antes de registrar signos vitales. Se
preparó el cambio descrito en [documento 25](../../25_ENCUENTROS_CON_VISITA_SPA.md):
JSON de encuentro esquema 3 con una visita básica asociada, conservando recepción
de esquema 2. Este cambio de código **sí requiere actualizar el OMOD en las tres
instancias antes de crear encuentros nuevos**. No cambia Liquibase.

Primero añadir `Add Visits`, `Get Visit Types` y `Get Visit Attribute Types` al
rol técnico en las tres instancias, conservando `Get Visits`. Luego detener las
instancias desde sus terminales y actualizar sus OMOD. Los permisos se verificaron
y el 21/09/2026 se reemplazaron los tres OMOD con las instancias apagadas, tras
respaldarlos en `.local-sync-https/omod-before-visit-20260921-173649/` y comprobar
que las tres copias coinciden por SHA-256. Quedan pendientes el arranque de esta
versión y la prueba real de la SPA. No se sustituyeron módulos en ejecución.

Incidencia posterior al arranque: los tres OMOD instalados cambiaron a SHA-256
`FFF632C101767B289E93B428B9196E72A3323D2B2884DFCE4BE2DDCB186CD05A`.
El API cargado en el maestro no contiene `EncounterVisitSnapshot`, por lo que
copiar el archivo manualmente no bastó: el arranque del SDK lo sustituyó.
Se intentó `mvn -o install -DskipTests` para actualizar el repositorio Maven local,
pero la solicitud de permiso fue rechazada y el comando no se ejecutó.
Antes de volver a probar se debe instalar la versión nueva en Maven y reiniciar
las instancias; comprobar las clases del API cargado además del archivo OMOD.

El usuario ejecutó después `mvn install -DskipTests`. Se verificó que el paquete
instalado en Maven (`synchronizationmr-omod-1.0.0-SNAPSHOT.jar`, contenedor OMOD)
coincide con el `.omod` generado, SHA-256
`6B3AEE61A20E5993D1CB2B71877F7FEFF148F9FE22C9E94A5365A9AD70AC1B36`.
También coincide su API interno y las cuatro clases modificadas del API instalado
con la compilación nueva. Queda pendiente comprobarlas de nuevo tras el arranque.

Tras el siguiente arranque se comprobaron en las cachés de las tres instancias
las clases `EncounterVisitSnapshot`, `EncounterIncomingEvent`,
`EncounterCreationPayloadSerializer` y `EncounterReceiveDao`: todas coinciden
por SHA-256 con la compilación nueva. Los módulos están iniciados, los tres
puertos escuchan y cada base conserva ocho pacientes, un encuentro y su evento,
sin visitas todavía. Ya puede continuar la prueba de la SPA.

Prueba SPA iniciada: visita `608ecd07-40b1-4909-bb1f-537522d35492`, tipo
`Facility Visit`, sin atributos. La SPA guardó un encuentro `Vitals`
`92e68b5e-3002-40f2-81bd-0cdfc7285412` con diez observaciones, evento
`b7768317-438c-4a78-9ec6-837b8467df81`, origen `posta_a`, secuencia 2, esquema 3.
Su SHA-256 es `64c824c61bd2af40060b7044e5620ce609652bb1fa64f7adfe2d1255cfc23bea`.
El maestro devolvió 403 y no dejó visitas ni observaciones parciales. Se diagnosticó
mediante el depurador de la instancia de pruebas: el validador
`bedmanagement.VisitWithBedPatientAssignmentValidator` consulta
`getBedPatientAssignmentByVisit`, que exige **Get Admission Locations**. Falta
conceder este permiso al rol técnico en las tres instancias y verificar el reintento.
Esto no introduce sincronización de camas; es una dependencia de autorización de
un módulo instalado en la distribución. Una prueba aislada añadida con el rol
técnico pasa en OpenMRS 2.4.2 sin Bed Management; no sustituye la prueba real pendiente.

Tras reiniciar las tres instancias, el reintento sigue devolviendo 403, ahora por
`Get Beds`. Se comprobó la anotación del método `BedManagementService.getBedPatientAssignmentByVisit`
en el API instalado de Bed Management 7.2.0: exige **Get Admission Locations y Get Beds**,
con `requireAll=true`. Por tanto, ambos permisos de consulta son necesarios para
este validador. Queda pendiente conceder `Get Beds` al rol técnico en las tres
instancias y comprobar la recepción. A conserva el encuentro, la visita y diez
observaciones; maestro y B todavía confirman encuentros de A hasta la secuencia 1.

Resultado tras conceder ambos permisos y reiniciar las tres instancias: la prueba
SPA A → maestro → B se completó. Se verificó en las tres bases el encuentro
`92e68b5e-3002-40f2-81bd-0cdfc7285412`, asociado a la visita
`608ecd07-40b1-4909-bb1f-537522d35492`, con diez observaciones. Coinciden los UUID
de las observaciones, los conceptos, los valores numéricos y la nota de texto.
Coincide también el SHA-256 del JSON indicado arriba; maestro y B confirman la
secuencia 2 del origen `posta_a`. No fue necesario volver a registrar el encuentro.
Esta evidencia valida este CREATE con visita básica y signos vitales; no valida
modificaciones posteriores ni cierre de visitas. Falta la comprobación visual en
la SPA de destino.

El usuario confirmó también la visualización de los signos vitales en las tres SPA.

### Preparación de la prueba SOAP en B

El formulario `SOAP Note Template` falló antes de crear un encuentro con
`Cannot read properties of null (reading 'uuid')`. La cuenta admin no tenía
Provider asociado. En el motor instalado, la preparación del envío usa
`currentProvider.uuid` cuando el formulario no especifica un profesional.
Los cuatro conceptos del formulario y su tipo `Visit Note` existen en B.

El usuario creó en B `PROF-PRUEBA-B`, UUID
`b409a46b-1f9d-474e-9db4-425f72c24e63`, asociado a Super User, persona UUID
`5f87c042-6814-11e8-923f-e9a88dcb533f`. Se creó la misma referencia mediante
POST `/ws/rest/v1/provider` en maestro y A, conservando UUID, identificador y
persona. Se verificaron los tres recursos por GET y las sesiones REST nuevas de
admin devuelven ese UUID como `currentProvider`. Esta es una configuración del
catálogo de laboratorio, no un flujo automático de sincronización de Providers.
No se modificó el OMOD. Falta renovar la sesión del navegador de B, volver a abrir
el formulario, guardarlo y verificar el encuentro resultante B → maestro → A.

Resultado SOAP: tras renovar la sesión, el usuario guardó correctamente el
formulario en B. Se verificó el encuentro `30dd0418-2b2c-4913-adcb-ee55d974fb62`,
tipo `Visit Note`, fecha 21/09/2026 20:20:00, en B, maestro y A. Las tres copias
referencian al paciente `40b7cd1a-7ce6-45f0-ad5e-9d92f3cb73c9` y la visita
existente `608ecd07-40b1-4909-bb1f-537522d35492`. Coinciden los cuatro textos
SOAP, sus UUID de observación, el profesional y su rol Clinician. Maestro y A
confirman secuencia 1 de `posta_b`. SHA-256 del JSON igual en los tres nodos:
`50d8394022c36bd2cc5fbda59c0c88237d4221ac783200fee12eeb2601b2a413`.
La recepción en A ocurrió en el siguiente ciclo observado. Queda pendiente la
comprobación visual del SOAP en las SPA de destino. Esto valida un nuevo CREATE
en B asociado a una visita recibida previamente desde A; no prueba edición de notas.

### Prueba Structured SOAP desde el maestro

El usuario guardó `Structured SOAP note` en el maestro: encuentro
`f65bc81f-dd47-42b0-964b-014377219c63`, tipo `Consultation`, fecha
21/09/2026 20:31:35. Se verificó su recepción en A y B, con confirmación de
`microrred_maestro=1`. Coinciden paciente, visita activa, profesional y rol.
Hay seis filas de observaciones por copia: un grupo y sus tres miembros
(Headache codificado, inicio 21/09/2026, duración 1 día), más dos textos.
Se compararon UUID, relaciones padre-hijo y valores en las tres bases.
SHA-256 del JSON común:
`2501a8858c0f50f12469203ee912f5354ecf02b4e1b3a3b182c66bb48ebb98de`.
B lo recibió en el siguiente ciclo observado. Falta la comprobación visual
en las SPA de destino. No se registraron órdenes ni se probaron actualizaciones.

### Prueba de encuentro con el canal HTTPS detenido

El usuario detuvo `sihsalus_https` y creó en A una nueva nota SOAP a las
20:38:09 del 21/09/2026. Se comprobó el proxy detenido y el encuentro
`4ca24550-a3f5-4f60-b47f-1857772b4862` presente una sola vez en A, con cuatro
observaciones de texto, y ausente en maestro y B. Evento
`bc630e2e-3580-4e3a-903e-c6503af879e3`, origen `posta_a`, secuencia 3,
operación CREATE, estado PENDING. SHA-256 del JSON:
`2abfcc01866491d186be95385c207451e22e478706db7b686ee79359a336fa56`.
Maestro y B todavía confirman `posta_a=2`; A tiene cinco encuentros y cada
destino cuatro. Pendiente reactivar el proxy y verificar recuperación automática
sin volver a guardar el formulario y sin duplicados.

Resultado de recuperación: el usuario reactivó `sihsalus_https`. Se verificó
el mismo encuentro una sola vez en cada base, con los cuatro UUID de observación
y textos idénticos. Coinciden UUID del evento y SHA-256 del JSON; maestro y B
avanzaron su confirmación a `posta_a=3`. No se volvió a guardar el formulario.
Esto valida recuperación tras indisponibilidad del canal HTTPS; no prueba pérdida
de respuesta después de un commit ni toda posible situación de reintento.

### Preparación de encuentros existentes: prueba pendiente

`Start-Posta.ps1` admite `-PrepararEncuentrosExistentes`, que requiere
`-PrepararPacientesExistentes`. Activa la preparación de encuentros ya implementada
en el OMOD, después de agotar pacientes pendientes; no habilita órdenes. El lote
usa `-TamanoLotePreparacion` y el intervalo es de 60 segundos. Se validó la sintaxis
con el parser PowerShell; la ejecución de validación fue bloqueada por la política
de scripts del entorno del agente. No se arrancó ni detuvo ninguna instancia.

Procedimiento previsto: detener A desde su terminal; con A apagada deshabilitar
el arranque de SynchronizationMR, levantar A y crear encuentros de prueba con el
módulo inactivo. Verificar registros nativos sin eventos. Detener A, restaurar
el arranque del módulo y ejecutar desde la raíz del repositorio:

```powershell
.\dev\https\Start-Posta.ps1 -ServerId posta_a -SincronizarPacientes -SincronizarEncuentros -PrepararPacientesExistentes -PrepararEncuentrosExistentes -TamanoLotePreparacion 2
```

Comprobar preparación por lotes, recepción y ausencia de duplicados. Al terminar,
volver al comando sin preparación histórica en el siguiente arranque. Este ensayo
simula registros previos a activar el módulo; no es una instalación limpia del OMOD.
Estado del ensayo: el usuario detuvo A y se verificó que el puerto 8081 no tenía
listener. A conserva cinco encuentros y cinco eventos. Con A apagada se cambió
únicamente `synchronizationmr.started` de `true` a `false` en su base y se verificó
el valor. Próximo paso: arrancar A con `Start-Posta.ps1 -ServerId posta_a`, verificar
el módulo inactivo y registrar tres encuentros. Restaurar `true` con A apagada
antes de ejecutar el comando de preparación indicado arriba.

Con A arrancada se confirmó por REST que el módulo estaba detenido. El usuario
creó tres notas SOAP: `096d1627-be33-48dc-9a57-56ce8612be4c` (20:54:32),
`e617eda1-6582-41c5-8a64-3b9c0a39793c` (20:55:06) y
`a046466e-6172-464c-82bf-453b150e289c` (20:55:37), del 21/09/2026.
Se verificaron ocho encuentros y cinco eventos en A: cada nota nueva tiene cuatro
observaciones y ninguno de los tres encuentros tiene evento. Pendiente detener A,
restaurar el arranque del módulo y comprobar la preparación en lotes de dos.

Después de que el usuario detuviera A, se verificó de nuevo que no había listener
en 8081 y se restauró `synchronizationmr.started=true` únicamente en su base.
Se conservan ocho encuentros y cinco eventos; el rol técnico tiene
`Prepare Synchronization Records`, `Get Encounters` y `Get Patients`.
Pendiente arrancar con el comando de preparación de lote 2 y verificar resultados.
La activación administrativa de esta preparación sin reiniciar la instancia queda
pendiente para el despliegue; el usuario acordó continuar con el script en pruebas.

Maestro, B y contenedores permanecen encendidos. No detener el módulo desde la UI,
debido al fallo observado anteriormente al recargar el contexto en caliente.

Resultado de encuentros existentes: se verificaron ocho encuentros y ocho eventos
en cada instancia. A preparó los dos primeros a las 21:06:03 (secuencias 4 y 5)
y el tercero a las 21:07:03 (secuencia 6): lote 2 y luego 1, separados por un minuto.
Maestro y B confirman `posta_a=6`. Coinciden los UUID de las doce observaciones y
sus textos, con cuatro observaciones por encuentro en cada base. Huellas SHA-256
del JSON, iguales en las tres instancias, en orden de creación:

- `ef428484764d8aef299f889a6cdfc932233091a9c20e30ca7fa679e46a4cd36a`
- `e7f029b5e8ca0a83dded9f0f9b0ee84361dd1619148e453f2884703e09f8af44`
- `1d72dd5d41f6f6b0ec2ed684ed2db6439750b56a7a8bda93b83ca493620034c9`

El usuario confirmó que los veía sincronizados. La consulta inicial se realizó
antes de observar la preparación; no fue necesario corregir el comando de arranque.
Quedan comprobados los casos ensayados de CREATE, observaciones agrupadas,
recuperación del canal y preparación histórica. Ediciones, cierre de visitas y
activación administrativa sin reinicio siguen pendientes. Órdenes se probarán en
la siguiente sesión. En el próximo arranque normal de A, omitir los parámetros de
preparación histórica; no es necesario reiniciar ahora solo para retirarlos.

### Arranque para las pruebas de órdenes (22/09/2026)

El script admite `-SincronizarOrdenes` junto con `-SincronizarPacientes` y
`-SincronizarEncuentros`. Configura el endpoint HTTPS `orderSync` ya previsto por
el OMOD y lo elimina del entorno al terminar. Nginx y la excepción CSRF local del
maestro ya incluyen ese endpoint. No requiere recompilar el OMOD. Se verificó la
sintaxis mediante el parser PowerShell; la prueba de transporte de órdenes sigue
pendiente. Arranque normal sin preparación histórica:

```powershell
.\dev\https\Start-Posta.ps1 -ServerId posta_a -SincronizarPacientes -SincronizarEncuentros -SincronizarOrdenes
.\dev\https\Start-Posta.ps1 -ServerId posta_b -SincronizarPacientes -SincronizarEncuentros -SincronizarOrdenes
```

Antes de crear órdenes, verificar roles técnicos (`Add Orders`, `Get Orders`,
`Get Order Types`, `Get Care Settings` y `Get Order Frequencies`), catálogos y estado de las instancias. Las cuentas clínicas necesitan
Provider; la configuración del profesional de laboratorio del día anterior se
conserva. La visita de prueba se dejó abierta. No hay resultados de órdenes reales
validados todavía en este ensayo.

Revisión tras el arranque del 22/09: las tres bases tienen cero órdenes y cero
eventos de órdenes. La visita de prueba conserva su fecha de inicio y no tiene
fecha de cierre; los módulos están configurados para arrancar y las identidades
de nodos son correctas. Los UUID de los tipos Drug Order/Test Order y de los
care settings coinciden. Faltan los cinco permisos anteriores en el rol técnico
de las tres bases. Se contrastaron los permisos de consulta con las anotaciones
del OrderService 2.8.10 instalado. Pendiente asignarlos y validar la primera orden.

El usuario añadió los cinco permisos de órdenes a los tres roles técnicos. Se
verificaron en las bases; antes del reinicio las consultas REST de care settings
y frecuencias todavía devolvían 403 por esos mismos permisos. Tras reiniciar las
tres instancias, se comprobaron HTTP 200 para `ordertype`, `caresetting` y
`orderfrequency` con ambas cuentas remotas del maestro y las cuentas locales de
A y B. No se ha identificado aún la causa exacta de la autorización desactualizada
en memoria. Esta comprobación valida lectura de catálogos, no la recepción de una
orden clínica, que sigue pendiente de la primera prueba desde la SPA de A.

### Primera orden de laboratorio: A → maestro → B

El 22/09/2026 el usuario registró desde la SPA de A `Complete blood count`,
prioridad Routine, con instrucciones ficticias; confirmó con `Sign and close`.
Se verificó la orden `80853021-1c31-4e33-9f64-cea63ce5f3dd` en las tres bases,
activada a las 10:13:11, con las mismas instrucciones y paciente
`40b7cd1a-7ce6-45f0-ad5e-9d92f3cb73c9`. El evento
`4b219af9-2301-46b2-97f1-2da970f64aa8`, origen `posta_a`, secuencia 1, conserva
el mismo JSON en las tres instancias (SHA-256
`25f7fbd2e0b14e4720aa62e46358f110777e3e9ee279b1471070fd90a42c9d67`).
Maestro y B confirman `posta_a=1` para órdenes.

La SPA creó también el encuentro de tipo Order
`b8a9796f-2aca-42d2-b684-26731890898f`, asociado a la visita ya abierta
`608ecd07-40b1-4909-bb1f-537522d35492`; se verificó en los tres nodos y la orden
lo referencia correctamente. Las tablas de vínculos pendientes están vacías.
El número local resultó ORD-1 en los tres nodos en esta primera prueba; la
identidad compartida se verificó por UUID. Falta comprobar su visualización en
las SPA de destino; no hay resultados de laboratorio registrados en esta prueba.

### Resultados de ORD-1: limitación comprobada

El usuario guardó los doce valores del hemograma mediante `Orders → Add results`
en A. La base conserva ORD-1 sin anular (`voided=0`), con
`fulfiller_status=COMPLETED` y `date_stopped=2026-09-22 10:30:46`. Se añadieron
trece observaciones (doce numéricas y un grupo) al encuentro ya existente
`b8a9796f-2aca-42d2-b684-26731890898f`, todas vinculadas a ORD-1.
No se creó un encuentro nuevo para estos resultados. La captura actual de
EncounterCreationAdvice solo registra creaciones; por tanto, los resultados
añadidos posteriormente no se capturaron ni llegaron al maestro/B.

La SPA generó además una orden de acción DISCONTINUE,
`c467d095-4774-4969-803f-820dba6491a7`, evento de origen A secuencia 2. Maestro y B
confirman esa secuencia y muestran la fecha de detención de ORD-1, pero conservan
`fulfiller_status=NULL` y no tienen las trece observaciones. El usuario observó
que la orden dejó de aparecer en la lista de Orders de A. Se comprobó que no fue
eliminada; la condición exacta del filtro visual no se ha inspeccionado.

Esta prueba NO valida sincronización de resultados: evidencia dos pendientes,
captura de observaciones añadidas a encuentros existentes y propagación del estado
de cumplimiento de la orden. No repetir el guardado ni alterar el CREATE original
como solución. Los datos de A se conservan para una futura prueba de actualizaciones.

### Primera orden de medicamento: B → maestro → A

El usuario guardó Paracetamol 500mg en B el 22/09/2026 a las 10:43:58:
orden `72c349e6-edc5-44e8-9b63-3128231a7de2`, evento
`8f2e4da0-1b82-4bd2-a5be-606723f59e01`, origen `posta_b`, secuencia 1.
Dosis 1 Tablet, vía oral, frecuencia seleccionada UUID
`136ebdb7-e989-47cf-8ec2-4e8b2ffe0ab3`, duración 2 Days, cantidad 2 Tablet,
cero repeticiones. El formulario exigió Indication; se introdujo texto ficticio
en `orderReasonNonCoded`. Se conservan también las instrucciones de prueba.
La SPA creó el encuentro `f59ba50c-6d4f-4cfb-98b5-6659e9709f6c` en la visita activa.

La primera consulta fue previa a la entrega. En una comprobación posterior,
maestro y A tienen la orden y confirman `posta_b=1`. Se contrastaron dosis,
cantidad, duración, instrucciones e indicación en sus tablas nativas y el JSON
coincide con B: SHA-256
`2a2ef1fa6cde774d48d981571a06547bca78ea3f4e3c773579aa2f7550567312`.
Pendiente comprobación visual en las SPA de destino. No se probó dispensación ni
modificación de esta orden.

### Orden desde el maestro: Blood urea nitrogen

El usuario creó la solicitud ficticia `Blood urea nitrogen` en el maestro.
Se verificó la orden `f818e1e9-6559-4fad-8e05-477a5d61fa3b` (ORD-4 en estas
bases), encuentro `52686588-75c3-4faf-8f12-3fc69a3bf1b1`, con las mismas
instrucciones y accession_number NULL en maestro, A y B. Ambas postas confirman
`microrred_maestro=1` para órdenes. El JSON coincide en los tres nodos: SHA-256
`3481d7bfe0c364ff575f9f392108cf0959d2d89fa003919f743cef66c4ea7bbc`.
La entrega se observó después de la consulta inicial. Se comprobó en el frontend
instalado que `Reference number` se mapea a `accessionNumber`; no es el UUID ni
el número de orden generado por OpenMRS. En este caso se dejó vacío; aún no se
ha probado ese campo con valor. No se registraron resultados de esta solicitud.

### Orden creada con HTTPS detenido: referencia de laboratorio

El usuario detuvo `sihsalus_https` y registró otra solicitud de Blood urea nitrogen
en A con referencia `PRUEBA-A-OFFLINE-001`. Se verificó el proxy detenido y la
orden presente solo en A: UUID `e7c39d7b-cfad-47ab-8437-0b3c59202d17`, número
local ORD-5, encuentro `2ee3390f-b46b-49b0-9175-7f877a6b5f31`, evento
`b4242f7a-c1b1-4399-bc5d-baa10e73a584`, origen A secuencia 3. Referencia e
instrucciones coinciden con los datos ingresados. SHA-256 del JSON:
`9826dfa65bcaea926ce3a291eec571ccef42274ceb447d9ea72ca3e14a870082`.
Maestro y B no tienen esa referencia y todavía confirman `posta_a=2` para órdenes.
Pendiente reactivar HTTPS y comprobar recuperación, conservación de la referencia
y ausencia de duplicados sin volver a guardar la solicitud.

Referencias: [RemoteIpValve de Tomcat](https://tomcat.apache.org/tomcat-9.0-doc/config/valve.html#Remote_IP_Valve),
[HTTPS en Nginx](https://nginx.org/en/docs/http/configuring_https_servers.html).
