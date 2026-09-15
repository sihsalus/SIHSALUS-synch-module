# 08. Consulta y recepción de pacientes por HTTP

Este incremento permite acceder desde una petición HTTP a los servicios que ya teníamos:
consultar registros y recibir el JSON de creación de un paciente. Todavía no hay un cliente
que haga esas peticiones periódicamente ni una prueba entre dos servidores desplegados.

## 1. Cómo encaja en la sincronización

Una posta podrá preguntar al maestro qué registros tiene y enviarle sus creaciones locales.
El maestro conserva el origen de cada registro para que las demás postas puedan descargarlo.
Mantenemos la decisión de compartir todos los registros incluidos entre las postas, a través
del maestro de la microrred. La consolidación con el hospital queda para después.

Ejemplo del recorrido de recepción:

1. Llega una petición con las credenciales de una cuenta de OpenMRS y el JSON del evento.
2. `PatientSyncPeerSession` autentica esa cuenta y comprueba qué nodo representa.
3. `PatientSyncHttpServlet` valida la petición y llama a `PatientReceiveService`.
4. El servicio guarda el paciente, su identidad de sincronización, el evento y la confirmación.
5. Una vez terminada la transacción, el servlet devuelve `confirmedSequence`.

Si se pierde esa respuesta, el emisor podrá reenviar el mismo evento. La recepción existente
reconoce el duplicado y devuelve la confirmación sin crear otro paciente. Un evento con el mismo
identificador y contenido diferente se rechaza. El UUID del paciente y el origen se conservan.

## 2. Qué peticiones ofrece

Ruta del módulo, suponiendo que OpenMRS se despliega en `/openmrs`:

```text
/openmrs/moduleServlet/synchronizationmr/patientSync
```

Esta es una ruta propia del OMOD; no pertenece a `/ws/rest/v1/patient`.

| Método y parámetro `resource` | Para qué sirve | Parámetros adicionales |
| --- | --- | --- |
| GET `node` | Obtener UUID y rol del servidor consultado | Ninguno |
| GET `origins` | Descubrir los orígenes con registros de pacientes disponibles | `afterOrigin` opcional, `limit` |
| GET `status` | Consultar el máximo disponible y lo recibido consecutivamente | `origin` |
| GET `events` | Obtener una página de eventos con su JSON | `origin`, `after`, `limit` |
| POST `receive` | Recibir un evento y devolver su confirmación | Cuerpo JSON, `Content-Type: application/json` |

`limit` vale 25 por defecto y acepta de 1 a 100. `after` vale 0 por defecto.
Los orígenes se paginan por UUID ordenado: el último de una página sirve como `afterOrigin`
de la siguiente. Así una posta podrá descubrir eventos creados en otras postas.

En `status`, `highestSequence` es el máximo disponible y `confirmedSequence` es el avance
consecutivo recibido desde ese origen. No son intercambiables: el nodo que crea sus propios
eventos puede tener registros disponibles sin haberlos recibido de otro nodo.

En `events`, `lastReturnedSequence` indica hasta dónde llega la página entregada; **no confirma
que el otro nodo la haya guardado**. El futuro cliente deberá avanzar según la recepción
confirmada, procesando los eventos en orden. Cada origen mantiene su propia secuencia.

El estado `PENDING` del evento sigue sin representar la entrega a todas las postas. Recibirlo
localmente no impide que el maestro lo ofrezca a las demás.

## 3. Configuración prevista para el despliegue

El nuevo punto de acceso exige HTTPS y autenticación Basic en cada petición. No reutiliza una
sesión del navegador. El servidor debe reconocer la petición como segura; si hay un proxy TLS,
habrá que configurar correctamente el contenedor y comprobarlo al desplegar.

La propiedad global `synchronizationmr.nodeRole` indica el rol del servidor local:
`MASTER` o `POSTA`. Su valor inicial es `UNCONFIGURED`; hasta configurarlo, el acceso se rechaza.

En el servidor receptor, cada cuenta remota debe tener estas propiedades de usuario:

| Propiedad de usuario | Valor |
| --- | --- |
| `synchronizationmr.peerNodeUuid` | UUID real del nodo remoto, en minúsculas |
| `synchronizationmr.peerRole` | `POSTA` si se conecta una posta al maestro; `MASTER` en el sentido contrario |

Estas propiedades pertenecen a la **cuenta remota**, no son propiedades globales del servidor.
No se generan cuentas ni contraseñas automáticamente. Cada posta necesita su propia cuenta
en el maestro, asociada a su UUID. Una posta autenticada solo puede enviar al maestro eventos
de su propio origen. Un maestro autorizado puede entregar a una posta eventos de otros orígenes.

Las consultas exigen `View Synchronization Records` y `Get Patients`. La recepción exige además
`Receive Synchronization Records` y `Add Patients`. Los servicios internos de OpenMRS también
aplican sus permisos al resolver personas y catálogos; la cuenta de despliegue deberá tener los
permisos necesarios para esos servicios. Las pruebas usan el usuario administrador del entorno
de pruebas, por lo que aún falta validar un rol restringido en el servidor real.

Los tipos de identificador, ubicaciones y conceptos referenciados deben existir en el destino
con los UUID esperados. Este incremento no copia esos catálogos.

## 4. Cómo probarlo ahora en la terminal

Desde la carpeta `synchronizationmr`, donde está el `pom.xml` que contiene `api` y `omod`:

```powershell
mvn package
```

Para ejecutar solamente las pruebas nuevas del transporte:

```powershell
mvn '-Dtest=PatientSyncHttpServletTest,PatientSyncHttpIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' package
```

Se usa `package` para construir también el JAR de `api` que necesita `omod` en esta ejecución.
Los resultados nuevos se guardan en `omod/target/surefire-reports`.

Validación de este incremento: las 45 pruebas de `api` pasaron y, después de ajustar la
compatibilidad de las dependencias de pruebas web, pasaron las 15 nuevas de `omod`.
El empaquetado terminó con `BUILD SUCCESS` y generó el archivo `.omod`.

`PatientSyncHttpServletTest` comprueba las rutas, los parámetros, credenciales ausentes,
permisos denegados, el tamaño y tipo de cuerpo, la respuesta JSON y que una consulta no
confirma recepción. Usa servicios simulados.

`PatientSyncHttpIntegrationTest` usa autenticación real de OpenMRS y la base H2 de pruebas:
comprueba que las credenciales incorrectas no aprovechan una sesión previa, que el contexto
del usuario se restaura, que una posta no suplanta otro origen y que la recepción confirma
después de guardar, admitiendo el reintento sin duplicar al paciente.

Las peticiones de ambas clases son simuladas: no levantan un puerto HTTPS ni comprueban los
filtros web, certificados o configuración de un servidor desplegado. Eso queda para la prueba
entre instancias. Los datos usados son ficticios.

## 5. Qué sigue

El siguiente incremento será el cliente de la posta: conectar al maestro, enviar sus eventos,
consultar los orígenes disponibles y recibir las páginas en orden. Después podremos programar
ese ciclo y probar interrupciones y reintentos. Sigue pendiente incorporar encuentros, órdenes
y resolver los pacientes preexistentes que no tienen una identidad de sincronización registrada.

`protocolVersion: 1` en la respuesta `node` identifica esta primera interfaz HTTP. Es distinto
de `schemaVersion: 1/2` del JSON del paciente, que sigue describiendo las versiones del contenido
ya explicadas en las guías anteriores.
