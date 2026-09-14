# Decisiones técnicas de implementación

> Resumen de decisiones técnicas surgidas en reuniones con el equipo de Fase 1 del
> proyecto SIH.SALUS, relevantes para programar el componente. Se separan
> explícitamente las decisiones **confirmadas** de las que siguen **pendientes de
> validar**, para no tratar una hipótesis como si fuera definitiva.
>
> Este documento se actualiza cada vez que haya una reunión técnica nueva que
> aporte o cambie algo relevante para la implementación. No mezclar con contenido
> de la tesis (eso no le compete a este componente de código).

## A. Decisiones CONFIRMADAS

### Actualización acordada con el usuario: distribución completa en la microrred

Decisión explícita del usuario en esta conversación: por ahora todas las postas deben recibir
todos los registros de pacientes, encuentros y órdenes incluidos en el alcance del componente.
No se filtra por pacientes atendidos ni por establecimiento asignado. Esta decisión posterior
no se atribuye a la conversación original con Iván.

- Las postas se comunican con el maestro, nunca directamente entre ellas.
- Periódicamente entregan sus eventos locales faltantes al maestro y consultan al maestro los
  eventos que les faltan de los demás orígenes. La frecuencia todavía no está fijada.
- Cada receptor lleva una confirmación consecutiva por origen y tipo de entidad. La mayor
  secuencia disponible no sustituye esa confirmación. Los eventos se solicitan por páginas.
- Solo se avanza la confirmación después de guardar correctamente los datos y confirmar la
  transacción. Los reenvíos idénticos no deben duplicar registros.
- El maestro conserva y redistribuye el origen y el identificador de sincronización originales.
  No se devuelve a una posta su propio origen como si fuera una importación remota.
- Con conectividad y procesamiento satisfactorio, todos convergen a los mismos registros
  incluidos en el alcance. Durante una desconexión o un error puede haber diferencias temporales.

“Todos los registros” define la distribución, no amplía automáticamente los campos del JSON ni
da por implementadas las ediciones, la incorporación de pacientes anteriores, encuentros u órdenes.
Actualmente solo existe captura, consulta y recepción local de creaciones de pacientes; el
transporte HTTP y el ciclo periódico siguen pendientes. El estado global PENDING no representa
la entrega a todas las postas: la recepción de cada instalación se consulta por sus confirmaciones.

### A.1 Mecanismo de detección de eventos
Se implementa mediante **AOP Advice** de OpenMRS (interceptores `before()` /
`after()`), NO modificando directamente el código de los servicios existentes.
Se engancha sobre los métodos de los servicios de Paciente, Encounter y Order.
Referencia oficial: https://openmrs.atlassian.net/wiki/spaces/docs/pages/25520603/OpenMRS+AOP

### A.2 No se usa FHIR / HAPI FHIR / IPS
El diseño anterior basado en HL7 FHIR + HAPI FHIR + resumen tipo IPS quedó
descartado. Se trabaja directo con las entidades nativas de OpenMRS (Patient,
Encounter, Order) vía sus servicios internos. La API REST general de OpenMRS
(no la capa FHIR) sigue siendo relevante como referencia si se necesita acceso
vía HTTP: https://rest.openmrs.org/

### A.3 El componente es un OMOD embebido, no un microservicio aparte
No hay contenedor Docker propio ni base de datos separada. Todo corre dentro
del mismo proceso de OpenMRS, usando las capacidades de extensión de un módulo
(OMOD): se pueden agregar tablas nuevas vía Liquibase con llave foránea hacia
las tablas nativas, pero no se pueden modificar las tablas existentes.

### A.4 Topología maestro-cliente (no P2P puro)
- **Servidor maestro**: servidor de la microrred, agrupa únicamente a las postas.
- **Postas**: actúan como clientes del maestro.
- Las postas **no se comunican directamente entre sí**; todo pasa primero por el
  maestro, que redistribuye (analogía de la antena de telecomunicaciones).
- **El servidor propio del Hospital Santa Clotilde queda fuera de esta
  sincronización.** Es un servidor aparte; su integración futura con el servidor
  maestro de la microrred no es parte del alcance actual del componente.

### A.5 Identificador secuencial propio (no UUID nativo)
El UUID nativo de OpenMRS es un hash, no un contador — no sirve para saber qué
entidades faltan sincronizar. Se necesita un identificador secuencial propio por
nodo (ejemplo dado por el especialista, análogo a sistemas de gestor/bróker de
órdenes financieras): cada nodo numera sus propias entidades de forma
secuencial (ej. A-0, A-1, A-2...), y el nodo que sincroniza pregunta "¿hasta qué
número tienes tú?" para saber qué pedir.

Esto se implementa agregando una tabla nueva vía OMOD (ejemplo dado:
"Entidad Synch ID"), con llave foránea hacia paciente/encounter/order. La tabla
guarda el identificador secuencial y el nodo de origen; el ID interno
(autoincremental o UUID) de cada servidor puede ser distinto entre nodos, pero
el identificador de sincronización se conserva igual al copiarse.

### A.6 Cola de transacciones diferidas — SÍ es necesaria
Confirmado explícitamente por el especialista: si no hay conexión, los eventos
(ej. pacientes nuevos) deben acumularse en una cola hasta que la conexión
vuelva. Se aloja también vía tablas creadas por el OMOD.

### A.7 Tratamiento diferenciado por tipo de entidad
- **Paciente**: rara vez se actualiza tras su creación. Se puede manejar con una
  comparación simple por fecha de última actualización, sin necesidad del
  mecanismo de identificador secuencial.
- **Encuentros y órdenes**: siempre se están agregando (nunca se editan, solo se
  acumulan). Estos sí requieren el mecanismo de identificador secuencial
  completo, ya que representan la mayor parte del volumen de sincronización.

### A.8 Tipo de almacenamiento según necesidad
- Tablas persistentes de auditoría / identificadores → tablas normales del OMOD
  (motor de base de datos del propio OpenMRS).
- Tablas temporales / en memoria (si se necesitan) → se sugirió SQLite por
  rapidez, o tablas en memoria que se pierden al apagar el servidor, según
  convenga al caso de uso específico.

### A.9 Referencia técnica: módulo Sync nativo de OpenMRS
Usado solo como referencia de diseño, no como código a reutilizar directamente.
Aporta 3 ideas ya incorporadas a la arquitectura:
- Modelo jerárquico Parent/Child (equivalente al maestro/cliente aquí).
- Interceptor sobre la capa de persistencia para capturar cambios.
- Política de resolución de conflictos "last-in-wins" (el cambio más reciente
  prevalece) — se adopta el mismo criterio salvo indicación distinta.

**Actualización:** el repositorio `openmrs-module-sync` ya está clonado
localmente en el entorno de Codex, quien lo analizó directamente (no solo vía
deepwiki). Esto permite consultar el código fuente real del interceptor y del
mecanismo Parent/Child al momento de diseñar las clases equivalentes del
componente, en vez de basarse solo en la descripción de la wiki.

Referencias: https://github.com/openmrs/openmrs-module-sync y
https://openmrs.atlassian.net/wiki/spaces/docs/pages/25461419/Sync+Module

## B. Pendiente de validar (NO tratar como definitivo)

### B.1 Detalle exacto del mecanismo de comparación de contadores
El especialista lo describió en términos generales ("un contador, como eventos,
manejado por APIs en JSON"), pero no se ha confirmado el detalle exacto de
implementación (por ejemplo, si la comparación es siempre iniciada por el
maestro, o si también las postas pueden iniciar una comparación hacia el
maestro). El informe de arquitectura (flujo F2) sí describe un mecanismo
concreto: el maestro pregunta periódicamente a cada posta su último
identificador registrado, compara y solicita lo que falte. Esto puede usarse
como base de trabajo, pero conviene confirmar con el especialista si surge
alguna duda durante la implementación.

### B.2 Restricción de edición de datos personales del paciente
En una conversación se mencionó una regla del MINSA sobre no editar datos
personales del paciente hasta un día después de creada la historia clínica
(por reportes PDF estáticos que dependen de esos datos). Esto no quedó como
requisito funcional formal en la versión final de requisitos, pero es una
consideración técnica válida a tener en cuenta si se programa la sincronización
de ediciones al paciente.

### B.3 Recurso "Database Changes" de la API REST de OpenMRS
Se investigó como posible mecanismo de "feed de eventos" nativo, pero todo
indica que corresponde a cambios de esquema (tipo Liquibase), no a datos
clínicos. Se descarta como vía útil salvo nueva evidencia en contra.
