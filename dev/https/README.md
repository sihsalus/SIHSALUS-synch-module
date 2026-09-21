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

Referencias: [RemoteIpValve de Tomcat](https://tomcat.apache.org/tomcat-9.0-doc/config/valve.html#Remote_IP_Valve),
[HTTPS en Nginx](https://nginx.org/en/docs/http/configuring_https_servers.html).
