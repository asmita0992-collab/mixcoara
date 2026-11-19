#!/usr/bin/env python3
"""
mixcoara_info.py - Versión, descripción de la app y registro de cambios.

Para publicar una versión nueva: sube VERSION y agrega un bloque ARRIBA de CAMBIOS.
"""

VERSION = "2.7.0"

DESCRIPCION = [
    ("¿Qué es Mixcoara?",
     "Mixcoara es una bóveda cifrada personal. Cifra tus archivos en el teléfono y los sube a tu "
     "propia nube mediante WebDAV (por ejemplo Nextcloud), de modo que el servidor solo guarda "
     "datos ilegibles. Está pensada para uso personal y se inspira en el esquema de Cryptomator, "
     "pero no es compatible con él."),

    ("Cómo protege tus datos",
     "• Tu frase (mínimo 12 caracteres) se transforma con scrypt en una llave que abre la llave "
     "maestra de la bóveda.\n"
     "• La clave de recuperación (Base32) abre esa misma llave maestra si olvidas la frase. "
     "Sin la frase y sin esa clave, los archivos no se pueden recuperar.\n"
     "• Cada archivo se cifra completo con AES-256-GCM y una etiqueta de autenticación: si alguien "
     "lo altera, el descifrado falla en lugar de entregar datos corruptos.\n"
     "• Los nombres de archivos y carpetas se cifran con AES-256-SIV, así que el servidor tampoco "
     "ve cómo se llaman.\n"
     "• La contraseña de WebDAV se guarda en la carpeta privada de la app, cifrada con una clave "
     "que vive en el Keystore de Android."),

    ("Subir carpeta",
     "Cifra y sube una carpeta completa conservando su estructura. Antes de empezar escanea cuántos "
     "archivos y bytes hay. Omite archivos ocultos y enlaces. Si ya existe una carpeta con el mismo "
     "nombre en la bóveda, no la sobrescribe. La validación posterior es opcional: descarga lo "
     "subido, lo descifra y compara tamaño y etiqueta GCM."),

    ("Subir archivo",
     "Cifra y sube un archivo suelto a la raíz de la bóveda. Si el selector entrega una copia en la "
     "caché de Android, la app la pasa a Descargas/Mixcoara/.temporales y la elimina al terminar. "
     "También tiene validación opcional."),

    ("Explorar y descargar",
     "Navega la bóveda con los nombres ya descifrados, marca carpetas o archivos y descárgalos "
     "descifrados en la carpeta que elijas. «Descargar todo» restaura la bóveda completa. "
     "Cada archivo se baja a un temporal, se descifra y solo entonces pasa a su destino; si la "
     "etiqueta GCM no coincide, no se conserva nada."),

    ("Cifrado local",
     "Cifra o descifra un archivo sin subirlo a la nube, usando la llave de contenido de tu bóveda. "
     "Los cifrados quedan en Descargas/Mixcoara/BovedaLocal y los descifrados en "
     "Descargas/Mixcoara/Descifrados."),

    ("Seguridad",
     "• Cambiar frase: reenvuelve la misma llave maestra, sin recifrar archivos.\n"
     "• Nueva clave de recuperación: genera otra y la anterior deja de servir en el archivo actual.\n"
     "• Acceso biométrico: guarda la frase en el Keystore de Android y la libera solo con tu huella "
     "o rostro. Si cambias la frase, se actualiza sola."),

    ("Segundo plano y avisos",
     "Para transferencias pesadas, la app pide ignorar la optimización de batería, permiso de "
     "notificaciones y permiso de superposición. Mientras trabaja muestra una notificación fija "
     "(servicio en primer plano) y, si estabas en otra app, te avisa cuando la tarea termina o falla. "
     "La ventana de imagen en imagen (PiP) no está disponible porque requiere código nativo."),

    ("Progreso y avisos de inactividad",
     "La barra mide los bytes transferidos de la operación en curso. El cifrado y el descifrado no "
     "cuentan como inactividad; el aviso «sin avance» aparece si pasan 45 segundos sin movimiento "
     "en las demás fases."),

    ("Temporales y limpieza",
     "Los temporales viven en Descargas/Mixcoara/.temporales (Android los borra de la caché privada "
     "durante tareas largas). Se eliminan al terminar una tarea, tanto si salió bien como si falló o "
     "se canceló, y también al abrir la app. En Ajustes puedes limpiarlos manualmente."),

    ("Registro de actividad",
     "Guarda subidas y descargas correctas y, con todo detalle, los errores: tipo de excepción, "
     "traceback, código HTTP del servidor, rutas, tamaños y espacio libre. Nunca guarda contraseñas "
     "ni frases. Se puede ver en la app, exportar a Descargas/Mixcoara/registros y borrar. "
     "Tiene rotación automática (hasta unos 5 MB)."),

    ("Dónde se guarda cada cosa",
     "• Configuración (cifrada): ~/config_mixcoara/privado/\n"
     "• Registro: ~/config_mixcoara/privado/logs/\n"
     "• Temporales, cifrados locales y descifrados: Descargas/Mixcoara/\n"
     "• Paquete de certificados TLS: Descargas/Mixcoara/.datos/ (copia de los certificados "
     "raíz públicos; si no se puede escribir ahí, se usa la carpeta privada)"),

    ("Límites conocidos",
     "• No sincroniza ni compara carpetas: sube y baja bajo demanda.\n"
     "• No borra nada del teléfono; tampoco ofrece borrar archivos de la bóveda.\n"
     "• Si pierdes la clave del Keystore (por ejemplo, al desinstalar), la configuración guardada "
     "no se puede leer y hay que capturar la conexión de nuevo; la bóveda en la nube no se afecta.\n"
     "• La velocidad depende de tu red y de tu servidor."),
]

# El más reciente primero. Formato: (versión, fecha, [cambios])
CAMBIOS = [
    ("2.7.0", "2026-11-19", [
        "Nuevo reporte de bóveda exportable a .txt desde Configuración → Bóveda en la nube: "
        "incluye el árbol de carpetas y una tabla UUID → ruta lógica de todo lo que hay en la nube, "
        "para recordar dónde estaba cada archivo aunque sus nombres estén cifrados. "
        "Se guarda en Descargas/Mixcoara/reportes/.",
        "Reporte incremental durante las subidas: cada archivo subido (suelto o dentro de una carpeta) "
        "queda anotado automáticamente en Descargas/Mixcoara/reportes/boveda_ultimo.txt.",
        "Navegación con el botón atrás de Android dentro de la bóveda WebDAV: "
        "atrás sube un nivel de carpeta y, en la raíz, vuelve a Inicio (cierra la sesión de la bóveda).",
        "Navegación con el botón atrás de Android dentro de la bóveda local: "
        "atrás sube un nivel de carpeta, en la raíz vuelve a Inicio y, "
        "en la vista de compatibilidad .mixcoara, vuelve a la lista de bóvedas locales.",
        "El botón atrás no interrumpe transferencias u operaciones en curso.",
        "Tema «Mixco grafito» rediseñado con una paleta de grises neutros (estilo Zinc/Neutral) "
        "más coherente con el resto de temas oficiales.",
        "Se restaura la solicitud de exención de optimización de batería: la app vuelve a pedir "
        "el permiso con un diálogo del sistema la primera vez que se abre una bóveda WebDAV, "
        "para que las transferencias largas no se interrumpan en segundo plano. Incluye un aviso "
        "en Configuración con el estado actual y un botón para abrir los ajustes si el usuario "
        "lo rechazó antes.",
    ]),
    ("2.6.0", "2026-11-17", [
        "Diseño visual de la 2.4.0 restaurado: íconos y tipografía uniformes en la navegación inferior.",
        "Configuración unificada en una sola pantalla, con los temas en una pestaña aparte.",
        "Acerca de rediseñado: logotipo centrado, nombre debajo y registro de cambios en tarjetas.",
        "Biometría completa: se puede activar sin volver a escribir la contraseña, en la nube y en bóvedas locales.",
        "Acceso a la app sin iniciar sesión obligatoria en la bóveda en línea.",
        "Exención de optimización de batería eliminada; la app ya no solicita ese permiso.",
        "Se conservan Pantalla en Pantalla (PiP), notificaciones, subida más rápida y bloqueo de "
        "suspensión en Wi-Fi para transferencias en segundo plano.",
        "Los 8 temas visuales se mantienen, con ajustes de contraste.",
    ]),
    ("2.5.0", "2026-11-17", [
        "Incorporación de los 8 temas visuales oficiales (Mixcoara, Mixco noche, Mixco día, "
        "Mixco oscuridad, Mixco esmeralda, Mixco ámbar, Mixco violeta y Mixco grafito).",
        "Soporte completo para Pantalla en Pantalla (PiP) automática y manual durante subidas y descargas.",
        "Corrección de permisos de notificaciones (Android 13+) y bloqueo de suspensión "
        "(WakeLock/WifiLock) para transferencias continuas en segundo plano.",
        "Separación del cronómetro de preparación/cifrado y del cronómetro exclusivo de subida a la nube, "
        "con reporte detallado en la bitácora.",
        "Desbloqueo mediante biometría (huella o rostro) o credencial del dispositivo "
        "(PIN, patrón, NIP o contraseña).",
        "Lectura dinámica del historial de versiones desde el archivo de texto editable registro_cambios.txt.",
    ]),
    ("1.7.0", "2026-11-05", [
        "La configuración pasó a la carpeta privada ~/config_mixcoara/privado/ y se cifra con una "
        "clave guardada en el Keystore de Android. La copia vieja de Descargas se migra y se borra.",
        "El paquete de certificados TLS se verifica antes de cada transferencia y de cada petición; "
        "si no se puede preparar, la app lo informa en lugar de fallar en silencio.",
        "El cifrado y el descifrado ya no cuentan como inactividad; el aviso «sin avance» sale a los 45 s.",
        "Los temporales se eliminan también cuando una tarea falla o se cancela, y al abrir la app. "
        "Nuevo botón para limpiarlos desde Ajustes.",
        "Nuevo registro de actividad con errores detallados (excepción, traceback, HTTP, rutas, "
        "tamaños, espacio libre), con visor, exportación y borrado.",
        "Nuevas pantallas «Acerca de Mixcoara» y «Registro de cambios».",
        "Los mensajes de error de subida y descarga ahora incluyen la causa (código HTTP o excepción).",
        "Corregido: una cancelación durante el cifrado de una carpeta podía perderse y la subida seguía.",
    ]),
    ("1.4.0 a 1.6.0", "2026-10 / 2026-11", [
        "Se usa requests en lugar de urllib para hablar con WebDAV.",
        "El mensaje de ayuda de la pantalla de conexión se ve completo.",
        "Temporales y caché en Descargas/Mixcoara; paquete de certificados en una ruta estable.",
        "La validación posterior a la subida es opcional.",
        "La app no elimina nada del dispositivo.",
        "La medición y el porcentaje se calculan por separado en subidas y en descargas.",
        "Nueva herramienta de cifrado local (bóveda local) y menú de Ajustes reorganizado.",
        "Compilación con el parche de desugaring para las notificaciones.",
    ]),
    ("1.3.0", "2026-10-29", [
        "Acceso biométrico con la frase guardada en el Keystore de Android.",
        "Permisos de segundo plano: ignorar optimización de batería, notificaciones y superposición.",
        "Servicio en primer plano durante transferencias y aviso de resultado si la app está en segundo plano.",
    ]),
    ("1.2.0", "anterior a 2026-10", [
        "Versión base: bóveda cifrada sobre WebDAV, explorador, descarga selectiva, cambio de frase "
        "y clave de recuperación.",
    ]),
]


def texto_descripcion() -> str:
    return "\n\n".join(f"{t}\n{c}" for t, c in DESCRIPCION)
