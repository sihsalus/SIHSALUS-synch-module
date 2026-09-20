# 21. Revisión de cierre del flujo de pacientes

Esta revisión inicia el cierre del flujo de pacientes. No lo declara final.
Se contrastaron el relato de Iván, los requisitos 01, las decisiones 02, los contratos 15–17 y el código de captura, preparación, recepción y transporte.

## Resultado principal

El recorrido de creación y preparación inicial tiene implementación y pruebas. Falta sincronizar modificaciones posteriores: no es solo una mejora opcional, porque RF-01 y RF-07 del documento 01 las exigen. La sección A.7 del documento 02 también contempla actualizaciones de pacientes.

Ejemplo comprobado: A registra a Ana, el maestro recibe su ficha, y después se corrige la dirección de Ana. La corrección queda en la base donde se realizó, pero el flujo CREATE no la distribuye. Reenviar el evento original no debe deshacer esa corrección y tampoco sirve para propagarla.

## Matriz de cierre

| Caso | Estado del código | Evidencia / pendiente |
| --- | --- | --- |
| Identidad de nodo por `server.id` | Implementado | Global Property y bloqueo de identidad local; probar configuración real de cada instancia |
| Registrar paciente sin conexión | Implementado | Advice y evento persistente dentro de la transacción local; sin llamada HTTP durante el guardado |
| Incorporar pacientes anteriores al OMOD | Implementado por lotes | Preparación, idempotencia y rollback de lotes comprobados |
| Detección automática de primera instalación | No implementada como ciclo de vida persistente | Se habilita la tarea explícitamente; no existe un marcador de primera carga terminada |
| Ejecutar carga inicial de noche | Activación manual disponible | No existe calendario nocturno automático; la ventana se administra al desplegar |
| Paciente registrado en el maestro | Permitido por el diseño | La captura no depende del rol; falta comprobar recorrido maestro → ambas postas en las instancias |
| Enviar A → maestro → B | Implementado | Cliente, receptor y HTTP probados por componentes; recorrido real pendiente |
| Reintentar el mismo evento | Implementado | No duplica ni sobrescribe una corrección local posterior |
| Paciente con el mismo UUID ya existente sin recibo | Conflicto explícito | No se sobrescribe ni se confirma; requiere conciliación |
| Misma persona registrada con UUID distintos | Sin conciliación automática | La identidad de sincronización no identifica por sí sola a una persona; no se fusiona por nombre |
| Direcciones y nombres múltiples | Implementado y comprobado | Nueva prueba de persistencia y recarga, incluyendo `address1..15` y geografía |
| Significado local de provincia, distrito, barrio, etc. | Pendiente de instancias | Deben coincidir las etiquetas/configuraciones; no se deduce una correspondencia por el nombre de una columna |
| Catálogos con IDs locales diferentes | Resolución por UUID | Prueba existente de atributos con ubicación; verificar todos los catálogos usados en campo |
| Catálogo ausente | Rechazo sin confirmar ni guardar parcialmente | Requiere corregir la dependencia, no esperar indefinidamente |
| Correcciones posteriores de datos del paciente | Falta implementar | Requerido para el alcance final RF-01/RF-07; no tiene prioridad sobre encuentros/órdenes por el relato anterior |
| Anulaciones, desanulaciones y fusiones | Falta definir contrato e implementar | No tratarlas como una nueva creación ni eliminar historia silenciosamente |
| Alergias, relaciones y otros formatos personalizados | Cobertura pendiente | No están especificados exhaustivamente en el TXT; inventariar los datos realmente usados antes de afirmar réplica completa |
| Auditoría por destino y resultado | Revisión transversal pendiente | Los eventos y recibos actuales no equivalen por sí solos a toda la auditoría RF-15 |

“Implementado” no significa probado en las tres instancias. Preparados=0 pendientes tampoco significa recibido por todos los destinos.

## Pruebas añadidas en esta revisión

En `PatientReceiveIntegrationTest`:

1. Se recibe un paciente ficticio con dos nombres y dos direcciones; se hace flush y se vacía la sesión de Hibernate. Al volver a consultar la base se comparan todos los campos de nombres, direcciones e identificadores por UUID. Incluye `address1..15`, país, región, distrito, villa, código postal, coordenadas y fechas. También comprueba la fecha civil de nacimiento.
2. Se recibe un paciente, se corrige su dirección localmente y se reenvía el mismo CREATE. Se conserva la corrección local, el ID del paciente, la secuencia y el JSON original, sin generar otro evento del mismo origen.

Resultado: **100 pruebas seleccionadas de pacientes y sus componentes compartidos: 84 API y 16 OMOD, sin fallos, errores ni omisiones**. La selección fue `Patient*Test` en el reactor Maven. No se reejecutó toda la suite de encuentros y órdenes porque esta revisión solo añade pruebas y documentación.

Log: `synchronizationmr/api/target/patient-closure-review.log`.
Entorno: contexto real de servicios OpenMRS y base H2 de pruebas; HTTP simulado en las pruebas web. No se usaron las bases ni contenedores del usuario. No se cambió el comportamiento productivo, Liquibase ni el contrato JSON en esta revisión; no se generó un OMOD nuevo.

## Aclaración al releer la reunión anterior

Se releyó `Explicación_Ivan_Aclaraciones_Componente_Sincronización.txt`, aportado de nuevo por el usuario después de esta revisión. Es evidencia de una reunión anterior, no una instrucción nueva para ejecutar automáticamente todas sus propuestas.

El relato reconoce que las ediciones deben propagarse, pero al final pide no enfocar todavía el trabajo en datos personales y priorizar encuentros y órdenes. La reunión posterior incorpora la preparación de entidades existentes y `server.id`. Ninguno de los dos relatos define una política completa de conflictos entre ediciones concurrentes.

Por tanto, se corrige la prioridad anunciada inicialmente en esta revisión: las actualizaciones de pacientes son una brecha frente al alcance final de RF-01/RF-07, pero no se deduce que deban ser el siguiente incremento antes de cerrar las altas, la carga inicial y los casos de encuentros/órdenes. No se modifica el texto de esos requisitos ni se elimina el trabajo pendiente.

La mención de copiar datos del maestro es una alternativa sugerida en el relato; no establece por sí sola que el maestro sea la única autoridad para editar pacientes. Tampoco se toma la referencia verbal a una regla del MINSA como una norma verificada o como autorización para bloquear ediciones clínicas.

Se confirmó la existencia del repositorio hermano `C:/Users/PC/Documents/GitHub/openmrs-module-sync` y se consultó la recepción transaccional y el reconocimiento de `ALREADY_COMMITTED` en `SyncIngestServiceImpl`. El análisis previo está en el documento 03. Se usará como referencia de arquitectura y pruebas, sin trasladar automáticamente dependencias antiguas, transporte, identificadores ni reglas de negocio.

## Diseño pendiente para las actualizaciones de pacientes

Una posible implementación debe conservar la identidad del paciente y representar cada cambio por separado; la cola de eventos es la propuesta técnica de esta revisión, no un formato exigido por Iván. Actualmente la tabla de eventos tiene una restricción única por paciente y las consultas obtienen la secuencia desde su identidad: no basta con cambiar CREATE por UPDATE en el advice.

El incremento debe resolver conjuntamente:

- Separar la identidad estable del paciente del orden/secuencia de los eventos de cambios.
- Capturar las modificaciones que realmente usan los formularios de OpenMRS, incluyendo guardados por servicios de Person cuando corresponda.
- Mantener el CREATE original inmutable y conservar su compatibilidad.
- Recibir una corrección sobre la ficha correspondiente, sin crear otro paciente ni generar otro cambio local por la importación.
- Aplicar una política explícita para cambios concurrentes. El documento 02 propone prevalencia del más reciente; hay que concretar desempates y el efecto de relojes desajustados antes de implementarla.
- Probar desconexión, reintentos de versiones antiguas, ediciones desde otra posta y cambios de nombres, direcciones, identificadores y atributos, sin pérdida de historia ni de datos omitidos por contratos anteriores.

El cierre de la carga inicial y los casos de encuentros/órdenes puede continuar antes de implementar estas actualizaciones. La conciliación entre fichas independientes requiere su propio mecanismo de correspondencia; no se resuelve reasignando UUID automáticamente ni sincronizando un encuentro primero.
