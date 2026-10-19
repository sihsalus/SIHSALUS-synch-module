# SynchronizationMR

Módulo de OpenMRS para el proyecto **SIH.SALUS**. Sincroniza pacientes, encuentros, observaciones, visitas asociadas y órdenes entre un maestro y las postas de una microrred de salud. Conserva eventos localmente y retoma su intercambio cuando la comunicación está disponible, manteniendo la identidad de origen y el historial de las operaciones admitidas.

La [documentación principal del componente](../README.md) describe sus capacidades, arquitectura, configuración, auditoría y alcance.

## Estructura

- **api:** captura de eventos, servicios, persistencia, contratos JSON, clientes HTTPS y trabajadores periódicos.
- **omod:** servlets, autorización de pares, descriptor de OpenMRS y empaquetado del archivo instalable.

## Construcción y pruebas

El proyecto compila contra **OpenMRS Platform 2.4.2**, con destino de bytecode Java 8. El entorno de construcción y validación utiliza **JDK 21** y Maven; el POM incluye un perfil de pruebas para JDK modernos.

Desde esta carpeta:

```shell
mvn clean install
```

El comando compila, ejecuta las pruebas y genera `omod/target/synchronizationmr-1.0.0-SNAPSHOT.omod`. También instala el artefacto en el repositorio Maven local; no lo despliega automáticamente en OpenMRS.

Para ejecutar las pruebas y empaquetar sin instalar el artefacto en el repositorio Maven local:

```shell
mvn package
```

Los reportes están en `api/target/surefire-reports` y `omod/target/surefire-reports`. La batería automatizada utiliza pruebas aisladas, contexto OpenMRS con H2, migraciones y solicitudes web simuladas; no requiere levantar las instancias del laboratorio.

## Instalación

Incorporar el archivo OMOD mediante **Administration → Manage Modules** o el procedimiento de instalación de módulos de la distribución. Configurar la identidad del nodo, su rol, las cuentas técnicas y el canal HTTPS según la [sección de instalación y configuración](../README.md#instalación-y-configuración).

El intercambio periódico y la preparación de datos existentes están desactivados por defecto. Las contraseñas se suministran mediante propiedades privadas de ejecución de OpenMRS. Configurar `server.id` antes de registrar datos con el módulo activo.
