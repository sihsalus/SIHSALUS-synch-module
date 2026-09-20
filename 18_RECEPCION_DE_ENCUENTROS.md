# 18. Recepción de encuentros y observaciones

> Incremento posterior: [historial de observaciones](22_ENCUENTROS_HISTORIAL_DE_OBSERVACIONES.md). Ya se admiten versiones anteriores incluidas en el mensaje o disponibles en destino; las restricciones históricas sobre ese caso se actualizan allí.


> Actualización: [órdenes y relación entre las tres entidades](20_FLUJO_DE_ORDENES.md). Las referencias a órdenes ya se reciben y se completan mediante vínculos persistentes; la restricción histórica de rechazar toda referencia a una orden queda superada.


> Incremento posterior: [flujo de encuentros](19_FLUJO_DE_ENCUENTROS.md).
> Ya se implementaron el cliente periódico opcional y la preparación por lotes.
> Los demás límites de contenido y dependencias descritos aquí siguen vigentes.


## Acuerdo de la reunión y arquitectura

En el relato de Iván, las observaciones se incluyen dentro del encuentro. Las
órdenes se manejan como entidad independiente. La comunicación de la tesis utiliza
solicitudes REST; la configuración posterior de VPN corresponde a infraestructura.

El OMOD es un módulo Java integrado en OpenMRS. Utiliza el contexto Spring y sus
servicios transaccionales; no arranca una aplicación Spring Boot separada. El
controller existente sirve la pantalla del módulo. Los endpoints de sincronización
son servlets registrados en `config.xml`, con solicitudes HTTPS GET/POST y JSON.
No se conectan a las bases remotas ni se publica este contrato mediante el módulo
estándar webservices.rest de OpenMRS.

## Incremento implementado

- Recepción transaccional mediante `EncounterReceiveService.receiveEncounter`.
- Reconstrucción del encuentro, proveedores y observaciones, incluidos grupos.
- Resolución del paciente y catálogos por UUID, conservando los IDs locales del destino.
- Persistencia del evento original y su origen para posterior redistribución.
- Confirmaciones consecutivas por origen, independientes de las de pacientes.
- Reintentos idénticos sin duplicar encuentros ni observaciones.
- Exclusión del encuentro importado en el advice: no se recaptura como creación local.
- Consulta paginada de orígenes y eventos y consulta de confirmaciones mediante HTTPS.

El encuentro, sus observaciones, el evento y la confirmación se confirman juntos.
Si falla cualquiera, la transacción se revierte. La recepción no incrementa el
contador de creaciones locales ni prepara o crea al paciente.

## Endpoint

Ruta dentro de OpenMRS:

```text
/moduleServlet/synchronizationmr/encounterSync
```

| Solicitud | Función |
| --- | --- |
| `GET ?resource=node` | Identidad `server.id`, rol y protocolo 2. |
| `GET ?resource=origins&limit=25` | Orígenes disponibles; admite `afterOrigin`. |
| `GET ?resource=status&origin=posta_A` | Mayor secuencia y última recepción consecutiva. |
| `GET ?resource=events&origin=posta_A&after=0&limit=25` | JSON de encuentros posteriores a la secuencia indicada. |
| `POST ?resource=receive` | Recibe un evento JSON ENCOUNTER de esquema 2. |

Las respuestas identifican `entityType: ENCOUNTER`. Se exige HTTPS, autenticación
Basic por petición y cuenta técnica con rol de par configurado. En el maestro,
una posta solo puede enviar su propio origen. La recepción requiere privilegios
`Receive Synchronization Records`, `Add Encounters`, `Add Observations` y `Get Encounters`;
las consultas clínicas y de catálogos requieren sus permisos correspondientes.
La sesión de par compartida exige `View Synchronization Records` y `Get Patients`.

Los errores de dependencias o contenido producen HTTP 409 sin confirmar el evento;
la respuesta no contiene datos clínicos ni el detalle interno de la excepción.
La validación estructural inicial rechazada produce HTTP 400. El servicio Java
identifica el caso de paciente ausente con `PATIENT_NOT_AVAILABLE`; ese detalle
todavía no tiene un código HTTP propio.

## Dependencias y límites explícitos

El paciente debe existir y estar activo en destino. La visita, si se referencia,
debe existir, estar activa y pertenecer al mismo paciente. No se crean visitas
implícitamente. Tipos de encuentro, ubicaciones, formularios, profesionales, roles,
conceptos, nombres codificados y medicamentos utilizados se resuelven por UUID.

Las observaciones numéricas, de texto, fecha y valores codificados mantienen sus
campos explícitos. Los grupos conservan relaciones entre padre e hijos. Se rechazan
UUID de observaciones repetidos, ya ocupados y estructuras excesivas. Se limita
cada lista a 100 entradas, la profundidad a 50 y el total a 1000 observaciones;
estos límites son límites del receptor, no una afirmación sobre toda la base clínica.

Este receptor todavía rechaza explícitamente:

- Encuentros con `orderUuids` o una observación con `orderUuid`, hasta completar el
  flujo de órdenes y su coordinación. No se trasladan órdenes existentes de un encuentro a otro.
- Observaciones con referencia a una versión anterior; requiere el flujo de revisiones.
- Archivos u observaciones de tipo complejo, aunque solo se envíe su referencia.
- Diagnósticos o condiciones marcados en `unsupportedContent`.
- Visitas u otros catálogos ausentes y conflictos con encuentros locales ya existentes.

Así no se confirma un encuentro como completo omitiendo contenido que todavía no
podemos recibir. Estos rechazos pueden detener el avance de ese origen hasta
resolver la dependencia o ampliar su soporte.

## Liquibase

Se añaden dos changesets, sin reescribir los anteriores:
`synchronizationmr_encounter_receipt` guarda origen y confirmación; en MySQL/MariaDB
se aplica comparación binaria a `origin_server_id`, como en pacientes.
Las bases reales y los contenedores del usuario no se modificaron.

## Qué sigue pendiente

El endpoint ya permite consultar y recibir encuentros. El cliente periódico sigue
intercambiando solo pacientes: falta conectarle el envío/descarga de encuentros y
su coordinación con los pacientes. También falta la preparación inicial por lotes
de encuentros existentes y el flujo independiente de órdenes.

Este incremento no declara terminados encuentros ni el OMOD completo. Las pruebas
en las tres instancias, su configuración de contenedores y los pendientes de los
documentos 15 y 17 se mantienen para los siguientes pasos.


## Validación

`mvn -o package`: BUILD SUCCESS, 132 pruebas sin fallos, errores ni omisiones.
Las pruebas nuevas de integración comprueban grupos de observaciones, conservación
del origen y JSON, ausencia de recaptura local, paciente y concepto ausentes,
contenido no soportado, secuencias faltantes, reintentos contradictorios y rollback
real ante un UUID de evento duplicado. Las pruebas HTTP comprueban autenticación,
HTTPS, origen autorizado, paginación, confirmaciones y límites de las solicitudes.
Son pruebas H2 y solicitudes HTTP simuladas, no tres servidores reales.
