# 23. Encuentros: relaciones necesarias y asignación automática de visitas

> Incremento posterior: [25. Visita asociada al encuentro de la SPA](25_ENCUENTROS_CON_VISITA_SPA.md).
> El esquema 3 incorpora la visita básica como dependencia del encuentro. La
> descripción siguiente conserva el alcance histórico del guard de visitas.

## Alcance acordado

Se mantienen los tres flujos: pacientes, encuentros y órdenes. Las observaciones se transportan dentro del encuentro. Esta revisión no añade sincronización independiente ni creación remota de visitas, diagnósticos o condiciones.

La propuesta anterior de implementar automáticamente todas esas entidades relacionadas queda reemplazada por comprobar su uso y resolver dependencias concretas. Su presencia en el modelo de OpenMRS no las convierte automáticamente en nuevos flujos de la tesis.

## Comprobación en el código nativo

Se consultaron `EncounterValidator`, `EncounterServiceImpl` y `EncounterVisitHandler` del código fuente local de OpenMRS API 2.4.2, la versión contra la que compila el módulo.

- Un encuentro exige paciente, tipo y fecha. La visita es opcional en el modelo base.
- Si se indica visita, el paciente debe coincidir y la fecha del encuentro debe estar dentro de su intervalo.
- Diagnósticos y condiciones no son requisitos universales para guardar un encuentro.
- Una instalación puede tener reglas adicionales y un manejador de asignación automática de visita. Esa configuración debe comprobarse en las instancias reales; no se deduce de estas pruebas.

## Problema encontrado y corregido

`saveEncounter` consulta el manejador configurado de visitas para un encuentro nuevo. Durante una importación eso podía crear una visita local no indicada por el origen o sustituir una visita explícita por otra.

Se ajustó el advice de EncounterService para envolver el manejador únicamente durante `IncomingEncounterSave`. El manejador omite su asignación para el objeto exacto que se está importando. Para otros encuentros y para el registro local se conserva el comportamiento original. No se cambia la Global Property ni se desactiva el manejador globalmente.

| Mensaje recibido | Resultado |
| --- | --- |
| Sin visita | Se guarda sin visita, aunque el destino asigne visitas automáticamente a las altas locales. |
| Con visita disponible del mismo paciente | Se conserva esa visita; el manejador no la reemplaza. |
| Con visita ausente | Se rechaza sin guardar ni confirmar. No se inventa una visita ni se borra la referencia. |
| Con diagnósticos/condiciones aún no admitidos | Se mantiene el rechazo explícito del contrato actual. No se descarta contenido clínico para forzar una recepción. |

No cambia el JSON, Liquibase ni la identidad de los eventos. Tampoco introduce UPDATE.

## Pruebas

Se añadieron tres pruebas de integración con un manejador configurado que efectivamente crea/reemplaza visitas:

1. Importar sin visita no crea ninguna; el reintento tampoco; una creación local posterior sí sigue creando su visita.
2. Importar con una visita existente conserva su UUID y no aumenta el número de visitas.
3. Una visita desconocida no se reemplaza por una automática y no avanza la confirmación.

Compilación completa: **200 pruebas, 160 API y 40 OMOD, sin fallos, errores ni omisiones**.
Log: `synchronizationmr/api/target/encounter-visit-guard-build.log`.
OMOD: `synchronizationmr/omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`.

Son pruebas sobre OpenMRS/H2 y HTTP simulado, no sobre las tres instancias MariaDB. No se modificaron instancias ni contenedores.

## Pendiente de validación real

Todavía hay que comprobar si los formularios usados en las instancias generan visitas, diagnósticos o condiciones. Si esos datos aparecen, se debe resolver su transporte o aprovisionamiento antes de declarar cubiertos esos encuentros. La corrección de esta revisión evita que el receptor invente relaciones, pero no resuelve una visita que falte en destino.
