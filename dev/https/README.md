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

Referencias: [RemoteIpValve de Tomcat](https://tomcat.apache.org/tomcat-9.0-doc/config/valve.html#Remote_IP_Valve),
[HTTPS en Nginx](https://nginx.org/en/docs/http/configuring_https_servers.html).
