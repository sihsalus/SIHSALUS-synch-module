# Diagnósticos asociados al encuentro

## Estado vigente: función retirada del código a petición del usuario

Comprobación posterior al reinicio, 27/09/2026 22:53: las tres aplicaciones
responden HTTP 200 y conservan el hash final del OMOD indicado más abajo.
El autor confirmó el módulo iniciado en las tres interfaces. La revisión SELECT
encontró los mismos 14 encuentros y 55 observaciones activas del paciente de
prueba, comparando UUID, conceptos, valores y relaciones. El hemograma conserva
sus 12 valores y grupo, y los dos resultados de urea los valores 5 y 6, sin
enlaces pendientes a órdenes. No se modificaron datos. Sigue pendiente un evento
nuevo para comprobar la sincronización después de esta actualización.
Evidencia: `evidencias-tesis/2026-09-27/verificacion-datos-tras-reinicio.json`.

El usuario decidió posponer la sincronización de diagnósticos nativos hasta
definir una pantalla o formulario que la utilice. Se eliminaron el interceptor,
el serializador específico, ADD_DIAGNOSES/esquema 5, la recepción y las pruebas
exclusivas de esa función. Se restauró el contrato anterior de encuentros y sus
permisos. Se conserva toda la sincronización de pacientes, encuentros, órdenes,
observaciones y recuperación de resultados antiguos.

La comprobación previa en las tres bases encontró cero diagnósticos nativos y
cero eventos de esquema 5. No se eliminaron datos clínicos ni eventos. Malaria,
guardada mediante Structured SOAP note, permanece como observaciones sincronizadas.

El 27/09/2026 a las 22:12 se verificaron las tres instancias detenidas y se
sustituyeron sus OMOD por el paquete sin diagnósticos nativos. Se retiraron
únicamente `Get Diagnoses` y `Edit Diagnoses` del rol `Sincronizacion Microrred`
en las tres bases. Se verificaron hashes de los OMOD y ausencia de esos permisos.
Respaldo previo de módulos y permisos:
`.local-sync-https/omod-before-removing-diagnoses-20260927-221208/`.
Pendiente arrancar y comprobar el funcionamiento tras esta retirada.

Las secciones siguientes se conservan como historial de la implementación
retirada; no describen el alcance vigente ni instrucciones para activarla.

La retirada compiló con `mvn clean install` (BUILD SUCCESS, 27/09/2026 22:07:46):
226 pruebas aprobadas, cero fallos, errores u omisiones. Se verificó ausencia de
las dos clases retiradas en el JAR generado. OMOD de `omod/target` y Maven local:
SHA-256 `EECB454C7DA80E0AB0387CCAB6BFE19F9194B1995CD5F0E84FB802C8059A8A5E`.
Log: `.local-sync-https/remove-native-diagnoses-build.log`.

Fecha: 27/09/2026. Implementación y pruebas automatizadas completadas; pendiente
arrancar el laboratorio y probar desde el SPA. El OMOD fue actualizado con las
tres instancias detenidas el 27/09/2026 a las 21:32.

Se comprobó ausencia de listeners 8080/8081/8082 y procesos Java de los tres
servidores. Las tres copias de `modules`, el paquete de `omod/target` y Maven
local coinciden con el hash indicado al final. Respaldo de OMOD y permisos
anteriores: `.local-sync-https/omod-before-diagnoses-20260927-213252/`.
Con las aplicaciones apagadas se añadieron mediante una transacción por base
solo `Get Diagnoses` y `Edit Diagnoses` al rol `Sincronizacion Microrred`;
ambos privilegios nativos ya existían. Se verificó su asignación en las tres
bases. No se cambiaron registros clínicos ni se concedieron roles de administrador.

## Alcance y contrato

- Captura de creación mediante `DiagnosisService.save`, incluyendo el recurso
  REST que utilizan los formularios. El diagnóstico y su evento se confirman en
  la misma transacción; si falla la captura, ambos se deshacen.
- CREATE de encuentro con diagnósticos usa esquema 5 y una colección `diagnoses`.
  CREATE sin diagnósticos conserva esquema 3. Se siguen leyendo esquemas 2/3 y
  ADD_OBS esquema 4, sin reescribir eventos existentes.
- Un diagnóstico nuevo de un encuentro ya publicado genera `ADD_DIAGNOSES`,
  esquema 5. Usa la tabla de adiciones, secuencia y transporte de encuentros
  existentes. No requiere otra tabla, temporizador ni endpoint.
- Preserva UUID, concepto, nombre específico si existe, texto libre, certeza,
  prioridad, paciente y encuentro. La fecha clínica sigue siendo la del encuentro.
  En CREATE se preservan también la marca y razón de anulación del diagnóstico
  histórico; ADD_DIAGNOSES solo admite diagnósticos activos.
- En OpenMRS recientes se conserva `formNamespaceAndPath`. Si el destino carece
  de ese campo y el evento lo usa, se rechaza en lugar de descartarlo. La API de
  compilación sigue siendo 2.4.2; se inspeccionaron los métodos de la versión
  2.8.10-SNAPSHOT instalada para comprobar compatibilidad.
- Una referencia a `Condition` se resuelve por UUID contra una condición activa
  del mismo paciente. No crea ni sincroniza la condición. Si falta, el evento
  permanece pendiente. Los conceptos y nombres también deben existir en destino.

La recepción valida todos los diagnósticos antes de guardar. Rechaza UUID ya
existentes, duplicados dentro del evento, paciente incorrecto, referencias
inválidas, prioridad inválida, certeza desconocida y campos fuera del contrato.
Un reintento exacto devuelve el recibo confirmado sin guardar otra copia. Una
recepción no produce eventos locales de eco. Diagnósticos, evento y recibo se
confirman en una única transacción.

## Límites

No incluye editar/anular diagnósticos ya publicados, sincronizar condiciones
longitudinales ni atributos personalizados de diagnósticos. Cuando hay atributos
activos en una versión nueva, el evento los señala como contenido no soportado y
el receptor lo rechaza explícitamente; no se omiten como si estuvieran completos.

La preparación inicial de encuentros sin publicar incluye sus diagnósticos ya
guardados. No se añadió un barrido de diagnósticos para encuentros que ya tenían
CREATE publicado antes de este cambio. `-PrepararResultadosExistentes` sigue
revisando observaciones, no diagnósticos. Eventos antiguos que solo indicaban
DIAGNOSES en `unsupportedContent` carecen de los datos necesarios y no se alteran.

## Permisos y despliegue

Las cuentas técnicas necesitan `Get Diagnoses` para exportar eventos y preparar
encuentros. Para importar diagnósticos necesitan `Get Diagnoses` y
`Edit Diagnoses`, además de los permisos de encuentros y catálogos existentes.
El servicio nativo exige `Edit Diagnoses` incluso al crear; el receptor del módulo
solo inserta diagnósticos nuevos, nunca utiliza ese permiso para sobreescribir
UUID existentes. Si se usan referencias a condiciones, también hace falta el
permiso nativo de consulta de condiciones.

Actualizar los tres nodos antes de registrar diagnósticos de prueba: los OMOD
anteriores no entienden esquema 5. No modificar las cuentas clínicas ni asignar
roles de administrador a las cuentas técnicas. Revisar y añadir los permisos
específicos al rol técnico antes de reanudar el transporte con el nuevo paquete.

Se consultaron las tres bases del laboratorio: no existen diagnósticos todavía
y el rol `Sincronizacion Microrred` no tiene ninguno de esos dos permisos.
La asignación posterior de esos dos permisos queda registrada al inicio de
este documento; no se cambiaron datos clínicos durante la implementación.

## Validación

`mvn clean install` terminó con BUILD SUCCESS el 27/09/2026 a las 21:08:20:
239 pruebas aprobadas (199 API y 40 OMOD), cero errores, fallos u omisiones.
Las 13 pruebas nuevas de integración cubren creación directa, guardado sin
transacción externa, CREATE histórico, texto libre y concepto/nombre, visita,
ausencia de eco, reintentos, UUID en conflicto, paciente incorrecto, dependencias,
validación completa del lote, rollback, exclusión de modificaciones y permisos
de una cuenta técnica sin `Edit Encounters`.

OMOD convencional:
`synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`.
SHA-256, idéntico al artefacto instalado en Maven:
`93CBECDC20DD695A1A97BEF04204EE4314D832F18650333D379291FDC1496668`.
Log local: `.local-sync-https/diagnoses-build.log`.

La prueba manual pendiente será registrar un diagnóstico ficticio desde un
formulario del SPA y comparar UUID, contenido, paciente, encuentro y recibos
en A, maestro y B. Después comprobar reintento/reconexión sin duplicados.

## Primer formulario SPA: no ejercitó el diagnóstico nativo

El usuario registró Malaria, Confirmed diagnosis y Primary en Structured SOAP
note. El formulario guardó el encuentro `072aa51e-280d-4bb2-bd4d-72e11aadac86`,
CREATE de A secuencia 13, evento `0b6600af-60b5-40dd-ae23-6b5daae985c6`.
Maestro y B confirmaron A=13, pero las tres tablas `encounter_diagnosis`
continúan vacías para el paciente de prueba. El formulario local define
Visit Diagnosis como `obsGroup` (concepto 159947) con tres `obs`: diagnóstico
(1284, respuesta 116128 Malaria), certeza y prioridad. No llama al registro
nativo Diagnosis. El mensaje de guardado correcto no prueba ADD_DIAGNOSES.

No se convirtió automáticamente el grupo de observaciones en un diagnóstico
nativo ni se modificó el formulario. Para validar el código nuevo sigue
pendiente usar una entrada que guarde mediante DiagnosisService/REST diagnosis.
