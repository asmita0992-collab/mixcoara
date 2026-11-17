#!/usr/bin/env python3
"""
mixcoara_info.py - Versión, descripción de la app y registro de cambios.

Para publicar una versión nueva: edita registro_cambios.txt (o sube VERSION y agrega un bloque ARRIBA de CAMBIOS_BASE).
"""

from pathlib import Path
from typing import List, Tuple
import mixcoara_config as cfgm

VERSION = "2.5.0"

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
     "• Acceso por biometría o credencial del dispositivo: guarda la frase en el Keystore de Android "
     "y la libera con tu huella, rostro, PIN, patrón, NIP o contraseña del teléfono."),

    ("Segundo plano, avisos y PiP",
     "Para transferencias pesadas, la app pide ignorar la optimización de batería y permiso de "
     "notificaciones. Mientras trabaja mantiene un WakeLock/WifiLock y un servicio en primer plano, "
     "permitiendo además ventana flotante de Pantalla en Pantalla (PiP) automática o manual."),

    ("Progreso y tiempos separados",
     "Las acciones previas a la subida (copia temporal y cifrado AES-GCM/SIV) tienen su propio "
     "contador de tiempo independiente para que el tiempo de subida mida exclusivamente la "
     "transferencia a la nube. En la bitácora ambos datos aparecen desglosados con claridad."),

    ("Temporales y limpieza",
     "Los temporales viven en Descargas/Mixcoara/.temporales (Android los borra de la caché privada "
     "durante tareas largas). Se eliminan al terminar una tarea, tanto si salió bien como si falló o "
     "se canceló, y también al abrir la app."),

    ("Registro de actividad",
     "Guarda subidas y descargas correctas (con tiempos separados de preparación/cifrado y subida "
     "exclusiva) y, con todo detalle, los errores: tipo de excepción, traceback, código HTTP del "
     "servidor, rutas, tamaños y espacio libre. Nunca guarda contraseñas ni frases."),

    ("Dónde se guarda cada cosa",
     "• Configuración (cifrada): ~/config_mixcoara/privado/\n"
     "• Registro: ~/config_mixcoara/privado/logs/\n"
     "• Registro de cambios editable: Descargas/Mixcoara/registro_cambios.txt\n"
     "• Temporales, cifrados locales y descifrados: Descargas/Mixcoara/\n"
     "• Paquete de certificados TLS: Descargas/Mixcoara/.datos/"),

    ("Límites conocidos",
     "• No sincroniza ni compara carpetas: sube y baja bajo demanda.\n"
     "• No borra nada del teléfono; tampoco ofrece borrar archivos de la bóveda.\n"
     "• Si pierdes la clave del Keystore (por ejemplo, al desinstalar), la configuración guardada "
     "no se puede leer y hay que capturar la conexión de nuevo; la bóveda en la nube no se afecta.\n"
     "• La velocidad depende de tu red y de tu servidor."),
]

# El más reciente primero. Formato: (versión, fecha, [cambios])
# Sincronizado con registro_cambios.txt
CAMBIOS_BASE = [
    ("2.5.0", "2026-11-17", [
        "Incorporación de los 8 temas visuales oficiales (Mixcoara, Mixco noche, Mixco día, Mixco oscuridad, Mixco esmeralda, Mixco ámbar, Mixco violeta y Mixco grafito).",
        "Soporte completo para Pantalla en Pantalla (PiP) automática y manual durante subidas y descargas.",
        "Corrección de permisos de notificaciones (Android 13+), exención de batería y bloqueo de suspensión (WakeLock/WifiLock) para transferencias continuas en segundo plano.",
        "Separación del cronómetro de preparación/cifrado y del cronómetro exclusivo de subida a la nube, con reporte detallado en la bitácora.",
        "Desbloqueo mediante biometría (huella o rostro) o credencial del dispositivo (PIN, patrón, NIP o contraseña).",
        "Lectura dinámica del historial de versiones desde el archivo de texto editable registro_cambios.txt.",
    ]),
    ("2.4.0", "2026-11-12", [
        "Ícono oficial sin recreaciones vectoriales y logotipo oficial en Acerca de; navegación inferior uniforme.",
        "Inicio disponible sin exigir permisos previos; elección directa entre WebDAV y bóvedas locales.",
        "Importación local de archivo o carpeta mediante diálogos, con selectores y ruta manual; pantalla local despejada.",
        "Visibilidad de archivos locales oculta por defecto y control para mostrar u ocultar.",
        "Biometría opcional ofrecida al desbloquear por primera vez, con cambios posteriores desde Configuración.",
        "Metadatos locales cifrados en .mixcoara/metadata.enc; migración de bóvedas existentes tras abrirlas con su contraseña.",
        "Registro de versiones revisado; correcciones de textos de seguridad y compatibilidad.",
    ]),
    ("2.2.1", "2026-11-08", [
        "Bóvedas locales independientes con creación, apertura, contraseña y recuperación propias; acceso desde Inicio sin WebDAV.",
        "Importación cifrada de directorios, explorador local y exportación de copias descifradas; conservación de archivos originales.",
        "Protección biométrica por bóveda con llaves de Android Keystore que requieren autenticación.",
        "Activación de huella con la contraseña ya verificada, sin inventar ni escribir una frase distinta.",
        "Compatibilidad conservada para archivos .mixcoara de la herramienta anterior.",
    ]),
    ("2.2.0", "2026-11-08", [
        "Restauración del acceso a Acerca de y Registro de cambios desde la navegación principal.",
        "Restauración de mostrar/ocultar contenido; nombres y selección no se componen mientras la vista está oculta.",
        "Vista de bóveda oculta por defecto y al bloquear o cambiar de conexión.",
        "Ícono de Mixcoara en tarjetas y cabecera; eliminación de imágenes decorativas genéricas y de la marca duplicada en tarjetas.",
        "Versión informativa tomada de BuildConfig, con versión de compilación 2.2.0.",
    ]),
    ("2.1.0", "", [
        "Selección nativa de carpetas con OpenDocumentTree y conservación de la ruta manual.",
        "Preparación de selecciones nativas en segundo plano y temporales por operación.",
        "Ajustes de progreso por etapas, cancelación, limpieza y tratamiento de errores nulos.",
    ]),
    ("2.0.0", "", [
        "Migración de la interfaz a Android nativo con Jetpack Compose y puente al núcleo Python mediante Chaquopy.",
        "Continuidad de la bóveda WebDAV, cifrado local, seguridad y bitácora.",
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


def parsear_registro_cambios_txt(texto: str) -> List[Tuple[str, str, List[str]]]:
    entradas: List[Tuple[str, str, List[str]]] = []
    ver_actual = None
    fecha_actual = ""
    cambios_actuales: List[str] = []

    def guardar():
        nonlocal ver_actual, fecha_actual, cambios_actuales
        if ver_actual and cambios_actuales:
            entradas.append((ver_actual, fecha_actual, list(cambios_actuales)))
        ver_actual = None
        fecha_actual = ""
        cambios_actuales = []

    for linea_cruda in texto.splitlines():
        linea = linea_cruda.strip()
        if not linea or linea.startswith("#"):
            continue
        if linea.startswith("[") and linea.endswith("]"):
            guardar()
            cab = linea[1:-1]
            partes = cab.split("|", 1)
            ver_actual = partes[0].strip().lstrip("vV")
            fecha_actual = partes[1].strip() if len(partes) > 1 else ""
        elif linea[0] in ("-", "•", "*"):
            item = linea[1:].strip()
            if item:
                if ver_actual is None:
                    ver_actual = VERSION
                cambios_actuales.append(item)
        elif ver_actual is not None:
            if not cambios_actuales:
                cambios_actuales.append(linea)
            else:
                cambios_actuales[-1] = f"{cambios_actuales[-1]} {linea}"
    guardar()
    return entradas


def cargar_cambios() -> List[Tuple[str, str, List[str]]]:
    candidatos = [
        cfgm.dir_descargas_mixcoara() / "registro_cambios.txt",
        Path(__file__).resolve().parent / "registro_cambios.txt",
    ]
    for ruta in candidatos:
        try:
            if ruta.is_file():
                leidos = parsear_registro_cambios_txt(ruta.read_text(encoding="utf-8"))
                if leidos:
                    return leidos
        except Exception:
            pass
    return CAMBIOS_BASE


CAMBIOS = cargar_cambios()


def texto_descripcion() -> str:
    return "\n\n".join(f"{t}\n{c}" for t, c in DESCRIPCION)