package com.pandal.music

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.pandal.music.data.LocalAudioRepository
import com.pandal.music.player.PlaybackService
import com.pandal.music.youtube.YouTubeApi
import com.pandal.music.youtube.YouTubeVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controllerState = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controllerState.value = controllerFuture?.get()
        }, ContextCompat.getMainExecutor(this))

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PandalMusicApp(
                    controller = controllerState.value,
                    loadSongs = { LocalAudioRepository.load(this) }
                )
            }
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerState.value = null
        super.onDestroy()
    }
}

private enum class Section(val label: String) {
    LIBRARY("Biblioteca"), FAVORITES("Favoritos"), PLAYLISTS("Playlists"), YOUTUBE("YouTube")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PandalMusicApp(
    controller: MediaController?,
    loadSongs: () -> List<MediaItem>
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pandal_music", android.content.Context.MODE_PRIVATE) }

    var songs by remember { mutableStateOf(emptyList<MediaItem>()) }
    var section by remember { mutableStateOf(Section.LIBRARY) }
    var showPlayer by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var playlistNames by remember { mutableStateOf(prefs.getStringSet("playlist_names", emptySet())?.toSet() ?: emptySet()) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    var currentItem by remember { mutableStateOf<MediaItem?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) songs = loadSongs()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) {
            songs = loadSongs()
        } else {
            permissionLauncher.launch(audioPermission)
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(controller) {
        while (controller != null) {
            currentItem = controller.currentMediaItem
            isPlaying = controller.isPlaying
            position = controller.currentPosition.coerceAtLeast(0L)
            duration = controller.duration.takeIf { it > 0 } ?: 0L
            shuffle = controller.shuffleModeEnabled
            repeatMode = controller.repeatMode
            delay(400)
        }
    }

    fun playList(list: List<MediaItem>, startIndex: Int) {
        if (list.isEmpty()) return
        controller?.apply {
            setMediaItems(list, startIndex.coerceIn(0, list.lastIndex), 0L)
            prepare()
            play()
        }
    }

    fun toggleFavorite(item: MediaItem) {
        val id = item.mediaId
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    fun addCurrentToPlaylist(name: String) {
        val item = currentItem ?: return
        val key = "playlist_$name"
        val ids = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        ids += item.mediaId
        prefs.edit().putStringSet(key, ids).apply()
    }

    if (showPlayer) {
        FullPlayerScreen(
            controller = controller,
            item = currentItem,
            isPlaying = isPlaying,
            position = position,
            duration = duration,
            shuffle = shuffle,
            repeatMode = repeatMode,
            isFavorite = currentItem?.mediaId in favorites,
            playlistNames = playlistNames,
            onBack = { showPlayer = false },
            onToggleFavorite = { currentItem?.let(::toggleFavorite) },
            onCreatePlaylist = { showCreatePlaylist = true },
            onAddToPlaylist = ::addCurrentToPlaylist
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Pandal Music", fontWeight = FontWeight.Bold)
                            Text("Segundo plano • MP3 • FLAC", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            },
            bottomBar = {
                Column {
                    if (currentItem != null) {
                        MiniPlayer(
                            item = currentItem!!,
                            isPlaying = isPlaying,
                            onOpen = { showPlayer = true },
                            onPrevious = { controller?.seekToPreviousMediaItem() },
                            onPlayPause = { if (controller?.isPlaying == true) controller.pause() else controller?.play() },
                            onNext = { controller?.seekToNextMediaItem() }
                        )
                    }
                    NavigationBar {
                        Section.entries.forEach { target ->
                            NavigationBarItem(
                                selected = section == target,
                                onClick = { section = target; selectedPlaylist = null },
                                icon = { Text(sectionIcon(target)) },
                                label = { Text(target.label) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (section) {
                    Section.LIBRARY -> SongListScreen(
                        title = "Biblioteca local",
                        subtitle = "La música continúa al minimizar o apagar la pantalla.",
                        songs = songs,
                        favorites = favorites,
                        onPlay = { item -> playList(songs, songs.indexOf(item)) },
                        onFavorite = ::toggleFavorite,
                        onRequestPermission = { permissionLauncher.launch(audioPermission) }
                    )
                    Section.FAVORITES -> {
                        val favSongs = songs.filter { it.mediaId in favorites }
                        SongListScreen(
                            title = "Favoritos",
                            subtitle = "Tus canciones marcadas con ♥",
                            songs = favSongs,
                            favorites = favorites,
                            onPlay = { item -> playList(favSongs, favSongs.indexOf(item)) },
                            onFavorite = ::toggleFavorite,
                            onRequestPermission = { permissionLauncher.launch(audioPermission) }
                        )
                    }
                    Section.PLAYLISTS -> PlaylistScreen(
                        names = playlistNames,
                        selected = selectedPlaylist,
                        songs = songs,
                        getIds = { name -> prefs.getStringSet("playlist_$name", emptySet())?.toSet() ?: emptySet() },
                        onSelect = { selectedPlaylist = it },
                        onBack = { selectedPlaylist = null },
                        onPlay = { list, item -> playList(list, list.indexOf(item)) },
                        onCreate = { showCreatePlaylist = true }
                    )
                    Section.YOUTUBE -> YouTubeSearchScreen()
                }
            }
        }
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false; newPlaylistName = "" },
            title = { Text("Nueva playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Nombre") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newPlaylistName.trim()
                    if (name.isNotEmpty()) {
                        playlistNames = playlistNames + name
                        prefs.edit().putStringSet("playlist_names", playlistNames).apply()
                        if (currentItem != null) addCurrentToPlaylist(name)
                    }
                    showCreatePlaylist = false
                    newPlaylistName = ""
                }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancelar") } }
        )
    }
}

private fun sectionIcon(section: Section): String = when (section) {
    Section.LIBRARY -> "♫"
    Section.FAVORITES -> "♥"
    Section.PLAYLISTS -> "☷"
    Section.YOUTUBE -> "▶"
}

@Composable
private fun SongListScreen(
    title: String,
    subtitle: String,
    songs: List<MediaItem>,
    favorites: Set<String>,
    onPlay: (MediaItem) -> Unit,
    onFavorite: (MediaItem) -> Unit,
    onRequestPermission: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        if (songs.isEmpty()) {
            Button(onClick = onRequestPermission) { Text("Cargar música del teléfono") }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(songs, key = { it.mediaId }) { item ->
                    SongRow(item, item.mediaId in favorites, { onPlay(item) }, { onFavorite(item) })
                }
            }
        }
    }
}

@Composable
private fun SongRow(item: MediaItem, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit) {
    ListItem(
        leadingContent = {
            AsyncImage(
                model = item.mediaMetadata.artworkUri,
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        },
        headlineContent = { Text(item.mediaMetadata.title?.toString() ?: "Sin título", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(item.mediaMetadata.artist?.toString() ?: "Desconocido", maxLines = 1) },
        trailingContent = { TextButton(onClick = onFavorite) { Text(if (favorite) "♥" else "♡") } },
        modifier = Modifier.clickable(onClick = onPlay)
    )
    HorizontalDivider()
}

@Composable
private fun MiniPlayer(
    item: MediaItem,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Surface(tonalElevation = 8.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.mediaMetadata.artworkUri,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(item.mediaMetadata.title?.toString() ?: "Sin título", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(item.mediaMetadata.artist?.toString() ?: "", maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onPrevious) { Text("⏮") }
            TextButton(onClick = onPlayPause) { Text(if (isPlaying) "⏸" else "▶") }
            TextButton(onClick = onNext) { Text("⏭") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullPlayerScreen(
    controller: MediaController?,
    item: MediaItem?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    shuffle: Boolean,
    repeatMode: Int,
    isFavorite: Boolean,
    playlistNames: Set<String>,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onAddToPlaylist: (String) -> Unit
) {
    var showEq by remember { mutableStateOf(false) }
    var showPlaylistMenu by remember { mutableStateOf(false) }
    val progress = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Reproduciendo") },
            navigationIcon = { TextButton(onClick = onBack) { Text("←") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            AsyncImage(
                model = item?.mediaMetadata?.artworkUri,
                contentDescription = "Carátula",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.height(20.dp))
            Text(item?.mediaMetadata?.title?.toString() ?: "Nada reproduciéndose", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(item?.mediaMetadata?.artist?.toString() ?: "", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Slider(
                value = progress,
                onValueChange = { fraction -> if (duration > 0) controller?.seekTo((duration * fraction).toLong()) }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), style = MaterialTheme.typography.labelSmall)
                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { controller?.shuffleModeEnabled = !shuffle }) { Text(if (shuffle) "🔀✓" else "🔀") }
                TextButton(onClick = { controller?.seekToPreviousMediaItem() }) { Text("⏮", style = MaterialTheme.typography.headlineSmall) }
                FilledIconButton(onClick = { if (controller?.isPlaying == true) controller.pause() else controller?.play() }, modifier = Modifier.size(64.dp)) {
                    Text(if (isPlaying) "⏸" else "▶", style = MaterialTheme.typography.headlineSmall)
                }
                TextButton(onClick = { controller?.seekToNextMediaItem() }) { Text("⏭", style = MaterialTheme.typography.headlineSmall) }
                TextButton(onClick = {
                    controller?.repeatMode = when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Text(when (repeatMode) {
                        Player.REPEAT_MODE_ALL -> "🔁✓"
                        Player.REPEAT_MODE_ONE -> "🔂"
                        else -> "🔁"
                    })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                AssistChip(onClick = onToggleFavorite, label = { Text(if (isFavorite) "♥ Favorito" else "♡ Favorito") })
                Box {
                    AssistChip(onClick = { showPlaylistMenu = true }, label = { Text("＋ Playlist") })
                    DropdownMenu(expanded = showPlaylistMenu, onDismissRequest = { showPlaylistMenu = false }) {
                        playlistNames.sorted().forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { onAddToPlaylist(name); showPlaylistMenu = false })
                        }
                        DropdownMenuItem(text = { Text("Nueva playlist…") }, onClick = { showPlaylistMenu = false; onCreatePlaylist() })
                    }
                }
                AssistChip(onClick = { showEq = !showEq }, label = { Text("EQ") })
            }
            if (showEq) {
                Spacer(Modifier.height(12.dp))
                EqualizerPanel(controller)
            }
        }
    }
}

@Composable
private fun EqualizerPanel(controller: MediaController?) {
    val labels = listOf("60", "230", "910", "3.6K", "14K")
    var enabled by remember { mutableStateOf(true) }
    var levels by remember { mutableStateOf(List(5) { 0f }) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ecualizador · 5 bandas", fontWeight = FontWeight.Bold)
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    val args = Bundle().apply { putBoolean(PlaybackService.EXTRA_ENABLED, it) }
                    controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_EQ_ENABLED, Bundle.EMPTY), args)
                })
            }
            labels.forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$label Hz", modifier = Modifier.width(58.dp), style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = levels[index],
                        onValueChange = { value ->
                            levels = levels.toMutableList().also { it[index] = value }
                            val args = Bundle().apply {
                                putInt(PlaybackService.EXTRA_BAND, index)
                                putInt(PlaybackService.EXTRA_LEVEL, (value * 1500f).toInt())
                            }
                            controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_EQ_BAND, Bundle.EMPTY), args)
                        },
                        valueRange = -1f..1f,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Plano" to listOf(0f,0f,0f,0f,0f), "Graves" to listOf(.7f,.45f,.1f,0f,-.1f), "Vocal" to listOf(-.2f,0f,.45f,.6f,.25f)).forEach { preset ->
                    TextButton(onClick = {
                        levels = preset.second
                        levels.forEachIndexed { index, value ->
                            val args = Bundle().apply {
                                putInt(PlaybackService.EXTRA_BAND, index)
                                putInt(PlaybackService.EXTRA_LEVEL, (value * 1500f).toInt())
                            }
                            controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_EQ_BAND, Bundle.EMPTY), args)
                        }
                    }) { Text(preset.first) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistScreen(
    names: Set<String>,
    selected: String?,
    songs: List<MediaItem>,
    getIds: (String) -> Set<String>,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onPlay: (List<MediaItem>, MediaItem) -> Unit,
    onCreate: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (selected == null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Playlists", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Button(onClick = onCreate) { Text("Nueva") }
            }
            Spacer(Modifier.height(8.dp))
            if (names.isEmpty()) Text("Aún no tienes playlists.")
            LazyColumn {
                items(names.sorted()) { name ->
                    val count = getIds(name).size
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text("$count canciones") },
                        leadingContent = { Text("☷") },
                        modifier = Modifier.clickable { onSelect(name) }
                    )
                    HorizontalDivider()
                }
            }
        } else {
            TextButton(onClick = onBack) { Text("← Playlists") }
            Text(selected, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            val ids = getIds(selected)
            val list = songs.filter { it.mediaId in ids }
            LazyColumn {
                items(list, key = { it.mediaId }) { item ->
                    SongRow(item, false, { onPlay(list, item) }, {})
                }
            }
        }
    }
}

@Composable
private fun YouTubeSearchScreen() {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<YouTubeVideo>()) }
    var selectedVideo by remember { mutableStateOf<YouTubeVideo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun doSearch() {
        val text = query.trim()
        if (text.isBlank() || loading) return
        loading = true
        error = null
        selectedVideo = null
        scope.launch {
            YouTubeApi.search(text).fold(
                onSuccess = { videos ->
                    results = videos
                    if (videos.isEmpty()) error = "No se encontraron videos."
                },
                onFailure = { e -> error = e.message ?: "No se pudo buscar en YouTube." }
            )
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("YouTube", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Resultados dentro de Pandal Music: miniatura, título, canal y reproducción embebida.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Canción, artista o video") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::doSearch, enabled = query.isNotBlank() && !loading) {
                Text(if (loading) "…" else "Buscar")
            }
        }

        if (!YouTubeApi.hasApiKey) {
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Falta configurar YouTube", fontWeight = FontWeight.Bold)
                    Text(
                        "Agrega YOUTUBE_API_KEY=TU_CLAVE en local.properties y activa YouTube Data API v3.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            selectedVideo?.let { video ->
                item(key = "player_${video.videoId}") {
                    YouTubeEmbeddedPlayer(video)
                    Spacer(Modifier.height(12.dp))
                }
            }

            items(results, key = { it.videoId }) { video ->
                YouTubeResultRow(
                    video = video,
                    isSelected = selectedVideo?.videoId == video.videoId,
                    onPlay = {
                        selectedVideo = video
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                )
                HorizontalDivider()
            }

            if (results.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "La reproducción de YouTube usa su reproductor embebido. El segundo plano completo continúa disponible para MP3/FLAC y otras fuentes permitidas.",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun YouTubeResultRow(
    video: YouTubeVideo,
    isSelected: Boolean,
    onPlay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = video.title,
                modifier = Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    video.channel,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = onPlay) { Text(if (isSelected) "Reproduciendo" else "▶ Reproducir") }
            }
        }
    }
}

@Suppress("SetJavaScriptEnabled")
@Composable
private fun YouTubeEmbeddedPlayer(video: YouTubeVideo) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            AndroidView(
                factory = { context ->
                    android.webkit.WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = android.webkit.WebChromeClient()
                        webViewClient = android.webkit.WebViewClient()
                    }
                },
                update = { webView ->
                    val html = """
                        <!doctype html>
                        <html><head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>html,body{margin:0;background:#000;height:100%;overflow:hidden}iframe{width:100%;height:100%;border:0}</style>
                        </head><body>
                        <iframe
                          src="https://www.youtube.com/embed/${video.videoId}?autoplay=1&playsinline=1"
                          title="YouTube video player"
                          allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                          allowfullscreen></iframe>
                        </body></html>
                    """.trimIndent()
                    webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null)
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
            Column(Modifier.padding(10.dp)) {
                Text(video.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(video.channel, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
