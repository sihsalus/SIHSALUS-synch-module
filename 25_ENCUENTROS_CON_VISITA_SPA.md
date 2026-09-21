# 25. Visita asociada al encuentro de la SPA

## Motivo

La prueba real de un encuentro simple A → maestro → B funcionó. Al abrir el
registro de signos vitales en la SPA, OpenMRS exigió una visita activa. Hasta
ahora el receptor solo admitía visitas ya presentes en el destino. Por ello se
detuvo la prueba antes de iniciar la visita.

Este ajuste mantiene los flujos PACIENTE, ENCUENTRO y ORDEN. La visita viaja como
dependencia del encuentro; no tiene cola, endpoint ni secuencia independiente.

## Contrato y recepción

- Los nuevos encuentros usan JSON esquema 3, con `visitUuid` y `visit` (null si no
  hay visita). Se incluyen UUID de visita, paciente, tipo, ubicación, indicación,
  inicio, fin y estado de anulación. La recepción esquema 3 usa precisión de
  segundos para fecha del encuentro e inicio/fin de visita, compatible con las
  columnas nativas. También compara visitas a esa precisión; no reescribe las
  fechas del JSON original. Así un encuentro en el instante exacto del cierre
  no queda fuera del intervalo tras persistir la visita.
- Se siguen aceptando eventos esquema 2. No se reescriben eventos antiguos ni se
  reconstruye su contenido con datos actuales. Un evento antiguo con visita
  ausente en destino continúa requiriendo resolver esa dependencia.
- El paciente debe existir. Los catálogos se resuelven por UUID y deben estar
  activos. La visita debe pertenecer al paciente del encuentro y contener su fecha.
- Una visita ausente se guarda mediante VisitService dentro de la misma
  transacción que el encuentro, observaciones, evento y recibo. Un fallo revierte
  toda la recepción. El contenido JSON original se conserva para redistribuirlo.
- Una visita existente se reutiliza solo si coincide su contenido admitido.
  Un conflicto no cambia la visita ni confirma el evento. El manejador automático
  de visitas sigue sin reemplazar la referencia importada.
- Un reintento confirmado reutiliza el recibo y no crea otra visita ni encuentro.

No hay cambios en Liquibase ni en las tablas de sincronización.

## Límites explícitos

Esto no implementa actualizaciones de visitas. Cerrar una visita después de
capturar un encuentro no produce un evento nuevo; una instantánea posterior
incompatible se rechaza, no sobrescribe el destino. Tampoco se envían visitas
vacías que aún no tengan encuentros.

Las visitas anuladas y las que contienen atributos personalizados no se reciben
en este incremento. El JSON marca `unsupportedAttributes`; se rechazan para no
descartar datos ni interpretar referencias locales como identificadores globales.
Diagnósticos, condiciones, archivos complejos y observaciones añadidas después
del CREATE siguen con los límites documentados previamente.

## Instalación y permisos

Actualizar el OMOD en **las tres instancias antes de crear encuentros nuevos**:
el receptor anterior solo reconoce esquema 2. Los pacientes y eventos ya guardados
permanecen. Durante esta prueba, actualizar con las instancias detenidas desde
sus terminales para evitar la incidencia conocida al detener módulos en caliente.

Para los usuarios receptores, mantener los permisos existentes y añadir
`Add Visits`, `Get Visit Types` y `Get Visit Attribute Types` al rol
`Sincronizacion Microrred` de maestro, A y B. `Get Visits` ya estaba concedido.
No se necesita `Edit Visits`: el receptor no modifica visitas existentes.

En la distribución real con Bed Management se identificó además el permiso
`Get Admission Locations`: su validador de visitas consulta asignaciones de camas
incluso al guardar esta visita ambulatoria. La primera recepción SPA devolvió 403
por ese permiso, sin persistir visita ni observaciones parciales. Se debe añadir
al rol receptor; no es un nuevo flujo de sincronización de camas.

## Validación

Pruebas de integración añadidas: visita y observación nuevas, reintento,
segundo encuentro de la misma visita sin duplicación, visita cerrada, conflicto
de identidad o contenido, catálogo ausente, atributos no admitidos, rollback
de visita cuando falla la inserción del evento y compatibilidad esquema 2.

La prueba real de signos vitales en la SPA sigue pendiente. Durante la implementación
no se modificaron las instancias. Después, el usuario añadió los permisos y se
verificaron en las tres bases.

El 21/09/2026, tras comprobar que no había listeners en 8080–8082 ni procesos Java
asociados a las tres instancias, se respaldaron y reemplazaron sus OMOD.
Respaldos locales: `.local-sync-https/omod-before-visit-20260921-173649/`.
SHA-256 del nuevo OMOD, comprobado en maestro, A y B:
`27830BCEF0A2F5CC46D9343530EDE3821F92F012466C052D05E0AD2ECD46B909`.
Faltan el arranque con esta versión y la comprobación del encuentro desde la SPA.

Compilación completa `mvn -o package`: **208 pruebas (168 API + 40 OMOD), sin
fallos, errores ni omisiones**. Registro:
`synchronizationmr/api/target/encounter-visit-snapshot-build.log`.
Artefacto generado: `synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`.
Las pruebas usan OpenMRS 2.4.2/H2 y transporte simulado; falta validar esta versión
en las instancias de laboratorio 2.8.10-SNAPSHOT/MariaDB.
