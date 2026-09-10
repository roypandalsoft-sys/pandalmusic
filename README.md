# Pandal Music v0.4.0

Aplicación Android en Kotlin + Jetpack Compose + Media3.

## Funciones principales

- Biblioteca local MP3/FLAC y formatos compatibles con Media3.
- Reproducción en segundo plano mediante `MediaSessionService`.
- Continúa al minimizar, cambiar de app y apagar/bloquear la pantalla.
- Controles multimedia desde notificación y dispositivos Bluetooth compatibles.
- Reproductor completo con carátula, progreso, anterior/siguiente, aleatorio y repetir.
- Favoritos y playlists persistentes.
- Ecualizador de 5 bandas con presets.
- Búsqueda de YouTube **dentro de Pandal Music** con miniaturas, título y canal.
- Botón **Reproducir** que abre el video en un reproductor embebido dentro de la app.

## Configurar YouTube

La búsqueda usa la API oficial **YouTube Data API v3**. Por seguridad, la clave no va incluida en el proyecto.

1. Crea/usa un proyecto en Google Cloud Console.
2. Activa **YouTube Data API v3**.
3. Crea una API key y, de preferencia, restríngela para Android/API.
4. En el archivo `local.properties` del proyecto agrega:

```properties
YOUTUBE_API_KEY=TU_CLAVE_AQUI
```

El proyecto compila aunque no exista la clave; la pantalla YouTube mostrará un aviso hasta configurarla.

## Importante sobre segundo plano

El servicio de segundo plano de Pandal Music está implementado para la biblioteca local y otras fuentes cuya reproducción en segundo plano esté permitida. El contenido de YouTube se reproduce mediante su reproductor embebido y no se extrae el audio del servicio.

## Requisitos

- Android Studio reciente
- JDK 17
- Android SDK 35
- minSdk 26
