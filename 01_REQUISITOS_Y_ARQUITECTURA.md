# Componente de sincronización — Requisitos y Arquitectura

> Documento de referencia técnica para el desarrollo del componente. No contiene
> información de la tesis (capítulos, redacción académica, etc.) — solo lo necesario
> para programar. Este documento se mantiene estable; los detalles de implementación
> que van cambiando con cada reunión técnica están en
> `02_DECISIONES_TECNICAS_IMPLEMENTACION.md`.

## 1. Qué es el componente

Módulo de sincronización de historias clínicas para la Microrred de Salud del Napo
(proyecto SIH.SALUS, Hospital Santa Clotilde). Sincroniza pacientes, encuentros y
órdenes entre un **servidor maestro** (microrred, agrupa las postas) y varias
**postas clientes**, operando dentro de condiciones de conectividad satelital
intermitente (Starlink).

**No usa HL7 FHIR, HAPI FHIR ni el concepto de IPS/resumen clínico.** Trabaja
directamente sobre las entidades nativas de OpenMRS (Patient, Encounter, Order),
implementado como un **módulo (OMOD) embebido dentro del propio proceso de
OpenMRS** — no es un microservicio ni un contenedor aparte.

**El servidor propio del Hospital Santa Clotilde NO participa de esta
sincronización.** Es un servidor separado; su integración con el servidor maestro
de la microrred queda fuera de alcance (trabajo futuro, según indicación explícita
del especialista del proyecto).

## 2. Datos del repositorio actual

- Paquete base: `org.openmrs.module.synchronizationmr`
- Plataforma objetivo: OpenMRS Platform **2.4.2**
- Estructura: proyecto Maven con submódulos `api` (modelo, servicios, DAO) y `omod`
  (controladores, config del módulo instalable)
- El proyecto viene con una clase de ejemplo (`SynchronizationMR`,
  `SynchronizationMRService`, etc.) generada por el arquetipo oficial de OpenMRS.
  Se mantiene en el repositorio sin usarse como base real; las entidades y
  servicios del componente se crean aparte, con nombres propios por componente.

## 3. Requisitos Funcionales (16)

| ID | Requisito | Descripción | Prioridad |
|---|---|---|---|
| RF-01 | Detección de eventos clínicos | Detectar automáticamente la creación o modificación de un paciente, encuentro u orden. | 10 |
| RF-02 | Asignación de identificador secuencial de sincronización | Asignar a cada entidad nueva un identificador secuencial propio del nodo de origen, distinto del identificador interno del sistema. | 9 |
| RF-03 | Registro interno de sincronización | Mantener un registro interno que asocie cada entidad con su identificador de sincronización, sin alterar las tablas nativas. | 9 |
| RF-04 | Configuración del rol del nodo | Permitir configurar la instancia como cliente (posta) o maestro (servidor de la microrred). | 8 |
| RF-05 | Comparación de identificadores de sincronización | Comparar periódicamente los identificadores locales con los del nodo homólogo para determinar qué falta. | 9 |
| RF-06 | Solicitud y recepción de entidades faltantes | Solicitar y recibir del nodo correspondiente las entidades que falten, conservando su identificador de origen. | 9 |
| RF-07 | Envío de entidades nuevas o modificadas | Enviar al nodo con el que sincroniza las entidades generadas localmente. | 9 |
| RF-08 | Propagación desde el servidor maestro hacia las postas | Distribuir hacia las postas clientes las entidades recibidas de otras postas. | 8 |
| RF-09 | Confirmación de recepción y almacenamiento | Enviar confirmación al nodo emisor tras recibir y almacenar exitosamente. | 7 |
| RF-10 | Detección periódica de conectividad | Verificar constantemente la disponibilidad de conexión con el nodo homólogo. | 9 |
| RF-11 | Continuidad del registro sin conexión | Permitir que el registro clínico local continúe sin interrupciones aunque no haya conexión. | 10 |
| RF-12 | Cola de transacciones diferidas | Acumular en una cola las entidades no sincronizadas durante la desconexión, preservando orden cronológico. | 9 |
| RF-13 | Sincronización y reintento al reconectar | Procesar primero la cola pendiente al recuperar la conexión y reintentar envíos fallidos. | 9 |
| RF-14 | Consulta de entidad por identificador de sincronización | Permitir consultar una entidad específica entre nodos mediante su identificador de sincronización. | 5 |
| RF-15 | Registro de auditoría por transacción | Registrar nodo origen, destino, fecha, hora, tipo de entidad y resultado de cada transacción. | 7 |
| RF-16 | Cifrado y autenticación entre nodos | Cifrar toda entidad transmitida entre nodos y exigir autenticación antes de aceptar solicitudes. | 8 |

## 4. Requisitos No Funcionales (5)

| ID | Atributo | Requisito | Descripción | Prioridad |
|---|---|---|---|---|
| RNF-01 | Disponibilidad | Disponibilidad del registro clínico local | Debe permanecer disponible independientemente del estado de la conexión con otros nodos. | 10 |
| RNF-02 | Eficiencia/Portabilidad | Ligereza del componente | Ejecutarse como módulo dentro del proceso de OpenMRS, sin servicios ni contenedores adicionales. | 8 |
| RNF-03 | Eficiencia de desempeño | Uso controlado de recursos | No degradar perceptiblemente el desempeño de OpenMRS ni de otros servicios del host. | 5 |
| RNF-04 | Portabilidad | Integrabilidad con la distribución existente | Distribuirse en formato compatible con la distribución de OpenMRS del SIH.SALUS. | 8 |
| RNF-05 | Integridad | Integridad de los registros históricos | Los encuentros y órdenes ya sincronizados no deben sobrescribirse. | 7 |

## 5. Componentes de la arquitectura (Nivel 3 — C4)

Todos viven dentro del módulo OMOD, embebidos en el contenedor Backend OpenMRS.

| Componente | RF que cubre | Notas de implementación |
|---|---|---|
| Interceptor de eventos clínicos | RF-01, RF-11 | Mecanismo AOP Advice (before/after) sobre los servicios de Paciente, Encounter y Order. Ver documento 02. |
| Gestor de identificadores de sincronización | RF-02, RF-03 | Tabla propia vía OMOD/Liquibase, con llave foránea hacia las tablas nativas. No usa el UUID nativo (es un hash, no sirve para comparar secuencialmente). |
| Configurador de rol del nodo | RF-04 | Vía Global Properties de OpenMRS (cliente/maestro). |
| Motor de comparación y sincronización | RF-05, RF-06 | Compara identificadores propios vs. los del nodo homólogo (patrón "pregúntale al otro hasta dónde tiene"). |
| Servicio de envío y propagación | RF-07, RF-08 | Cliente HTTP saliente; también reenvía hacia postas cuando el nodo es maestro. |
| Servicio de recepción y confirmación | RF-09 | Endpoint REST que recibe entidades entrantes, valida y confirma. |
| Detector de conectividad y cola de transacciones diferidas | RF-10, RF-12, RF-13 | Verifica conexión periódicamente; cola persistente vía OMOD. Confirmado como necesario por el especialista del proyecto. |
| API de consulta protegida | RF-14 | Endpoint REST de solo consulta, exige autenticación. |
| Módulo de seguridad y auditoría | RF-15, RF-16 | Cifra/autentica toda comunicación saliente/entrante; punto centralizado de seguridad (todo el tráfico pasa por aquí antes de llegar al gateway de salida del establecimiento). |

## 6. Orden de implementación sugerido

1. Gestor de identificadores de sincronización (tabla base — sin esto no se puede probar nada más)
2. Interceptor de eventos clínicos
3. Configurador de rol del nodo
4. Servicio de recepción y confirmación (probar manual con Postman/Hoppscotch primero)
5. Servicio de envío y propagación
6. Motor de comparación
7. Detector de conectividad y cola de transacciones diferidas
8. Módulo de seguridad y auditoría + API de consulta protegida
