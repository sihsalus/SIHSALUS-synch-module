# 17. Estado del flujo de pacientes y pruebas entre instancias

El flujo implementado cubre eventos CREATE de pacientes nuevos y preparación de
pacientes activos existentes. No equivale a replicar toda la historia de cambios
de un paciente ni todas sus entidades relacionadas.

## Estado actual

| Parte | Estado |
| --- | --- |
| Identidad de establecimiento | `server.id`, sin UUID de nodo en el esquema final. |
| Alta local | Paciente, identidad y evento se confirman en la misma transacción. |
| Preparación inicial | Lotes reanudables; se habilitan explícitamente fuera del horario de atención. |
| JSON | Esquema 4; campos admitidos y límites en el documento 15. |
| Transporte | Posta envía sus creaciones y descarga otros orígenes desde el maestro. |
| Maestro como origen | Puede capturar altas propias para que las postas las descarguen. |
| Reintentos | Una recepción confirmada se reconoce por su origen, secuencia, UUID y contenido. |
| Registros independientes de una misma persona | No se concilian automáticamente; se mantiene la búsqueda previa por el personal como práctica operativa. |
| Ediciones, anulaciones y fusiones | No generan eventos de actualización en este incremento. |
| Relaciones, alergias y otros formatos de atributos | No están cubiertos por completo. |
| Prueba de extremo a extremo en las tres instancias | Pendiente. No se considera sustituida por Maven/H2. |

## Corrección de integridad durante la revisión

Al reconstruir las listas de nombres, identificadores y direcciones, los conjuntos
de OpenMRS pueden considerar equivalentes dos entradas con UUID diferentes y
descartar una. El receptor comprueba ahora que cada incorporación aumente el número
de entradas. Si no ocurre, rechaza el evento antes de guardar al paciente o confirmar
su recepción. No confirma silenciosamente una copia parcial.

Esto es distinto de reconocer un reintento del mismo evento: ese caso sigue
devolviendo la confirmación existente sin volver a reconstruir al paciente.

## Condiciones para las pruebas reales

Usar tres bases independientes, el mismo OMOD actualizado y distintos `server.id`.
Configurar roles, cuentas técnicas, permisos y transporte HTTPS. Los catálogos
referenciados por UUID deben estar disponibles y ser compatibles entre nodos:
tipos de identificador, ubicaciones, conceptos y tipos de atributos utilizados.
Las etiquetas de los campos de dirección deben corresponder a la misma configuración.

Los números internos pueden diferir; no basta con que los catálogos tengan nombres
parecidos si sus UUID son distintos. El módulo no copia automáticamente esos catálogos.

## Recorrido que comprobaremos

| Caso | Resultado esperado |
| --- | --- |
| Crear paciente en A y ejecutar su ciclo | Una recepción en el maestro, conservando UUID, origen y contenido admitido. |
| Ejecutar el ciclo de B | El paciente aparece en B y puede consultarse desde OpenMRS. |
| Repetir ambos ciclos sin nuevas altas | No aparecen más pacientes ni eventos de creación por ese registro. |
| Crear paciente en el maestro | A y B lo descargan conservando el origen del maestro. |
| Preparar pacientes anteriores al OMOD | Se añaden identidades y eventos; no se duplican sus filas clínicas locales. |
| Interrumpir y retomar la preparación | Continúa desde lo pendiente y conserva los lotes confirmados. |
| Perder conexión y restablecerla | Conserva eventos locales y continúa desde las confirmaciones. |
| Falta un catálogo o hay datos incompatibles | No confirma ni guarda parcialmente; requiere corregir la dependencia o los datos. |

Se compararán campos y UUID del paciente recibido, no solo contadores o respuestas
HTTP. La búsqueda del paciente que llegó desde otra posta se comprobará también
en la interfaz. Estas pruebas se realizarán cuando retomemos las instancias.

## Siguiente tramo

La base de creación y carga inicial de pacientes permite continuar con encuentros.
Su receptor necesitará localizar al paciente que ya existe en destino y controlar
la dependencia cuando todavía no haya llegado. Después siguen las órdenes como
entidad independiente. Los pendientes de pacientes de la tabla anterior permanecen
documentados; no se declaran completos por empezar otra entidad.


Validación local de esta revisión: `mvn -o package`, BUILD SUCCESS, 115 pruebas
sin fallos, errores ni omisiones. Incluye tres casos nuevos de entradas equivalentes
en nombres, identificadores y direcciones, sin guardado ni confirmación parcial.
