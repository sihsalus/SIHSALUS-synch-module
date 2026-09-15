# 10. Ejecución periódica del cliente

La preparación explícita de pacientes antiguos se añadió posteriormente en
[11_PACIENTES_PREEXISTENTES.md](11_PACIENTES_PREEXISTENTES.md). No activa una carga masiva
ni resuelve la reconciliación de personas registradas independientemente en varias instancias.

## Qué cambia

El OMOD ahora puede programar el cliente de pacientes al iniciar. La programación está
**desactivada por defecto**: instalar el módulo o ejecutar Maven no inicia conexiones.

`SynchronizationMRActivator` lee la configuración del proceso y arranca un
`PatientSyncScheduler`. Este mantiene un único trabajador y espera el intervalo configurado
antes del primer ciclo y después de terminar cada ciclo posterior.

Por ejemplo, con un intervalo de 60 segundos y un ciclo que tarda 8 segundos:

```text
Esperar 60 s → ejecutar 8 s → esperar 60 s → ejecutar el siguiente ciclo
```

No se inicia otro ciclo mientras el anterior sigue ejecutándose. Esta exclusión corresponde
al trabajador de esta instancia del módulo; no es un bloqueo distribuido entre varios
procesos OpenMRS que compartan la misma base de datos.

## Qué hace cada ejecución

1. Abre una sesión de OpenMRS en el hilo del trabajador.
2. Autentica la cuenta técnica **local**.
3. Construye el cliente, que verifica que la instalación tenga el rol `POSTA`.
4. Ejecuta el envío y la recepción del incremento 09, usando la cuenta **remota** ante el maestro.
5. Registra las cantidades de envíos y recepciones confirmados si finalizó correctamente.
6. Cierra la sesión local, también cuando hubo un error.

No requiere que una persona esté conectada a la interfaz gráfica. La cuenta local y la cuenta
remota tienen funciones distintas: una permite operar dentro de la posta y la otra identifica
esa posta ante el maestro.

Si falla la conexión, la autenticación, un catálogo o un evento, el ciclo se interrumpe y se
vuelve a intentar después del intervalo. El siguiente intento consulta nuevamente las
confirmaciones persistentes. No elimina registros ni salta el evento que produjo un conflicto.
Los errores de configuración o de datos requieren corrección: repetir no los resuelve por sí solo.

Los registros de diagnóstico están en español y no incluyen contraseñas, JSON clínicos ni
el texto de las excepciones. Un aviso de fallo no implica que se haya deshecho todo el ciclo:
las recepciones cuyas transacciones ya terminaron permanecen guardadas.

## Configuración para cuando tengamos las instancias

Se utilizan variables de entorno del **proceso que ejecuta OpenMRS**:

| Variable | Uso |
| --- | --- |
| `SYNCMR_ENABLED` | `true` activa; `false` desactiva. Por defecto `false`. |
| `SYNCMR_INTERVAL_SECONDS` | Espera entre ciclos; por defecto 60. Acepta de 10 a 86400. |
| `SYNCMR_MASTER_ENDPOINT` | URL HTTPS completa del servlet del maestro, sin parámetros. |
| `SYNCMR_MASTER_UUID` | UUID esperado del maestro. |
| `SYNCMR_LOCAL_USERNAME` | Cuenta técnica en la base de OpenMRS de esta posta. |
| `SYNCMR_LOCAL_PASSWORD` | Contraseña de esa cuenta local. |
| `SYNCMR_REMOTE_USERNAME` | Cuenta que representa esta posta en el maestro. |
| `SYNCMR_REMOTE_PASSWORD` | Contraseña de la cuenta remota. |

Ejemplo de ruta, reemplazando el servidor y el contexto según el despliegue:

```text
https://SERVIDOR/openmrs/moduleServlet/synchronizationmr/patientSync
```

Además, se mantiene la configuración de la guía 08:

- Propiedad global `synchronizationmr.nodeRole=POSTA` en la posta y `MASTER` en el maestro.
- La cuenta remota en el maestro tiene las propiedades de usuario `synchronizationmr.peerNodeUuid`
  y `synchronizationmr.peerRole=POSTA`.
- Ambas cuentas necesitan los permisos correspondientes a los servicios que utilizan.
- Los catálogos referenciados tienen que existir con los UUID esperados en el destino.

El maestro no necesita activar este cliente periódico: las postas inician las conexiones.

No guardes contraseñas en los documentos o en el repositorio. Se deben proporcionar mediante
la configuración del servicio o contenedor del servidor. Una variable definida en una terminal
no cambia el entorno de un servicio OpenMRS que ya está ejecutándose. Los cambios del entorno
requieren reiniciar el proceso con la nueva configuración.

Si falta una variable obligatoria o el intervalo es inválido, se registra que no pudo iniciarse
la programación. El módulo conserva sus demás funciones. Una URL, UUID, cuenta o rol incorrectos
pueden detectarse al ejecutar el ciclo, que registrará el fallo y volverá a intentarlo.

## Parada del módulo

`willStop()` detiene el trabajador antes de retirar los servicios del módulo. También se invoca
la parada desde `shutdown()`. Se cancelan nuevas ejecuciones y se solicita interrumpir la actual.
El cliente comprueba esa interrupción entre operaciones, sin saltar las transacciones ya guardadas.

La parada espera hasta 45 segundos. Una operación de red o base de datos puede tardar en responder
a la interrupción; si el trabajador no termina, se registra un aviso y esa instancia del
programador no permite arrancar otro trabajador mientras siga activo. El despliegue debe
verificar los tiempos reales y el comportamiento al detener o actualizar el módulo.

## Cómo probarlo ahora

Desde `synchronizationmr`, ejecuta:

```powershell
mvn -pl api '-Dtest=PatientSyncSchedulerTest' test
```

Son cinco pruebas que verifican:

1. Estar desactivado por defecto, sin exigir contraseñas.
2. Rechazar configuración incompleta, intervalo inválido o activación mal escrita.
3. Ejecutar un intento posterior aunque el primer ciclo falle.
4. Impedir un segundo trabajador y solicitar interrupción al detenerlo.
5. Permitir un nuevo arranque después de una parada completa.

Las pruebas usan un trabajador real con intervalos de milisegundos y ciclos simulados.
No necesitan configurar variables ni se conectan al maestro. No prueban las cuentas ni el
ciclo de vida completo del OMOD en un servidor; esa comprobación sigue pendiente.

Para ejecutar todas las pruebas y generar el OMOD:

```powershell
mvn package
```

## Qué falta

Probar dos o más instancias con HTTPS, permisos restringidos, catálogos compartidos y cortes
de conexión. Aún faltan encuentros, órdenes y el tratamiento de pacientes preexistentes sin
identidad de sincronización. Los conflictos se detienen y se vuelven a intentar: todavía no
hay una interfaz ni un mecanismo automático para reconciliarlos.
