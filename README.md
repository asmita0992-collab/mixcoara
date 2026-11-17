# Mixcoara para Android

Aplicación privada de Mixcoara migrada a Jetpack Compose y Chaquopy. No está
planteada para tiendas ni repositorios públicos. El acceso directo a
`/storage/emulated/0/Download/Mixcoara` es deliberado para que certificados,
temporales y resultados sobrevivan a la limpieza de caché de Android.

## Abrir en Android Studio

1. Abre el proyecto en Android Studio.
2. Usa JDK 17 y deja que Android Studio instale Android SDK 35.
3. Sincroniza Gradle (se recomienda Gradle 8.9 - 8.11; Gradle 9+ no es compatible con versiones anteriores de AGP y Chaquopy debido a cambios en `VersionNumber`). La primera sincronización descargará Chaquopy,
   `pycryptodomex`, `requests` y `certifi`.
4. Ejecuta en un dispositivo Android 8 o posterior.
5. Autoriza "Acceso a todos los archivos" cuando la aplicación lo solicite.

El repositorio de esta sesión no contiene JDK ni Android SDK, por lo que aquí se
validan sintaxis y pruebas Python, pero la compilación final se realiza en
Android Studio.

## Compatibilidad conservada

- `masterkey.vaultdav`
- AES-256-GCM para contenido
- AES-256-SIV para nombres
- scrypt y HKDF
- clave de recuperación
- formato local `MIXCOARA-LOCAL-1`
- estructura WebDAV existente

## Funcionalidades completas implementadas

1. **Pestaña Bóveda (Explorador en la nube)**:
   - Navegación multinivel con barra de migas de pan (*breadcrumbs*) interactiva.
   - Subida de archivos sueltos y carpetas completas (tanto a la raíz como a subdirectorios).
   - Validación opcional post-subida (descarga y verificación de etiqueta GCM).
   - Modo de selección múltiple con descarga de elementos seleccionados.
   - Restauración y descarga de la bóveda completa.
   - Diálogo de telemetría en vivo: velocidad de transferencia en MB/s, porcentaje, tiempo transcurrido, ETA y botón de cancelación cooperativa.
   - Servicio en primer plano (`MixcoaraForegroundService`) con notificación persistente para evitar que Android detenga transferencias largas en segundo plano.

2. **Pestaña Local (Bóveda local portátil)**:
   - Cifrado local de archivos hacia `/storage/emulated/0/Download/Mixcoara/BovedaLocal/<archivo>.mixcoara` con formato `MIXCOARA-LOCAL-1`.
   - Descifrado de archivos hacia `/storage/emulated/0/Download/Mixcoara/Descifrados/`.

3. **Pestaña Seguridad**:
   - Cambio de frase segura sin recifrar archivos (reenvuelve la misma llave maestra en el servidor).
   - Regeneración de clave de recuperación Base32 y actualización verificada del encabezado.
   - Desbloqueo biométrico nativo con `BiometricPrompt` y sellado seguro de la frase en Android KeyStore.

4. **Pestaña Bitácora**:
   - Visor de terminal en tiempo real de las últimas líneas de `mixcoara.log`.
   - Copia al portapapeles, exportación a archivo y limpieza rotativa.
