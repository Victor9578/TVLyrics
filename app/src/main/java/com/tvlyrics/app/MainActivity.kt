package com.tvlyrics.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var isAuthorized: MutableState<Boolean>
    lateinit var prefs: AppPrefs private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpotifyAuth.init(this)
        prefs = AppPrefs(this)
        isAuthorized = mutableStateOf(SpotifyAuth.isAuthorized)
        setContent { App(isAuthorized.value, prefs) }
        handleLoginRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLoginRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        isAuthorized.value = SpotifyAuth.isAuthorized
    }

    private fun handleLoginRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "tvlyrics") Thread {
            val ok = SpotifyAuth.handleRedirect(data)
            if (ok) isAuthorized.value = true
        }.start()
    }
}

private val DARK = Color(0xFF101014)
private val DARKER = Color(0xFF08080C)
private val GREEN = Color(0xFF1DB954)
private val DIM = Color(0xFF6E6E78)
private val WHITE = Color(0xFFEDEDF2)
private val GRAY = Color(0xFF888888)
private val RED_HEART = Color(0xFFE9143B)

@Composable
private fun App(authorized: Boolean, prefs: AppPrefs) {
    if (!authorized) LoginScreen() else LyricsScreen(prefs)
}

// ---------- 登录 ----------

@Composable
private fun LoginScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var clientId by remember { mutableStateOf(SpotifyAuth.clientId) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().background(DARK).padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TV 歌词", color = WHITE, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text(
            "1. 打开 developer.spotify.com 创建 App\n" +
                "2. Redirect URI 填写: ${SpotifyAuth.REDIRECT_URI}\n" +
                "3. 粘贴你的 Client ID",
            color = DIM, fontSize = 20.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID", color = DIM) },
            modifier = Modifier.width(480.dp),
            textStyle = LocalTextStyle.current.copy(color = WHITE, fontSize = 20.sp),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (clientId.isBlank()) {
                    error = "请填写 Client ID"
                } else {
                    SpotifyAuth.clientId = clientId.trim()
                    scope.launch(Dispatchers.IO) {
                        try {
                            val url = SpotifyAuth.loginUrl()
                            launch(Dispatchers.Main) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = GREEN),
        ) { Text("登录 Spotify", fontSize = 20.sp) }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Color(0xFFFF5A5A), fontSize = 18.sp)
        }
    }
}

// ---------- 歌词 ----------

@Composable
private fun LyricsScreen(prefs: AppPrefs) {
    var playback by remember { mutableStateOf(PlaybackState.EMPTY) }
    var currentLyrics by remember { mutableStateOf<Lyrics?>(null) }
    var allSources by remember { mutableStateOf<List<LyricSource>>(emptyList()) }
    var status by remember { mutableStateOf("等待播放…") }
    var showMenu by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var isLiked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    // 轮询循环
    LaunchedEffect(Unit) {
        var backoff = 0L
        while (true) {
            try {
                val state = withIO {
                    val token = SpotifyAuth.accessToken() ?: return@withIO null
                    SpotifyApi.getPlayback(token)
                }
                if (state == null) { status = "登录过期，请重新登录"; break }
                playback = state
                isPlaying = state.isPlaying
                backoff = 0
            } catch (e: Http.HttpException) {
                backoff = (e.retryAfterMs ?: SpotifyApi.POLL_INTERVAL_MS * 2).coerceAtMost(SpotifyApi.MAX_BACKOFF_MS)
            } catch (_: Exception) {
                backoff = SpotifyApi.POLL_INTERVAL_MS * 2
            }
            delay(if (backoff > 0) backoff else SpotifyApi.POLL_INTERVAL_MS)
        }
    }

    // 换歌时拉歌词 + 检查收藏
    LaunchedEffect(playback.track?.id) {
        val track = playback.track ?: return@LaunchedEffect
        currentLyrics = null
        status = "搜索歌词…"
        
        // 获取所有歌词源
        val sources = withIO { LyricsAggregator.fetchAll(track) }
        allSources = sources
        currentLyrics = sources.firstOrNull()?.let { Lyrics(it.lines, it.source) }
        status = currentLyrics?.source ?: "未找到歌词"
        
        // 检查收藏状态
        withIO {
            try {
                val token = SpotifyAuth.accessToken() ?: return@withIO
                isLiked = SpotifyApi.isLiked(token, track.id)
            } catch (_: Exception) { isLiked = false }
        }
    }

    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(50); value = System.currentTimeMillis() }
    }

    val progress = playback.progressMs(now)
    val track = playback.track
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    Box(modifier = Modifier.fillMaxSize()) {
        // 全屏模糊背景
        if (track?.albumArtUrl != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = track.albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(60.dp),
                    contentScale = ContentScale.Crop,
                )
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(DARK))
        }

        // 内容层
        if (isLandscape) {
            LandscapeLyrics(
                track = track,
                lyrics = currentLyrics,
                progress = progress,
                status = status,
                isPlaying = isPlaying,
                isLiked = isLiked,
                onLike = {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { isLiked = toggleLike(playback) }
                },
                onPlayPause = {
                    togglePlayPause()
                    isPlaying = !isPlaying
                },
                onShowMenu = { showMenu = true },
                onShowPlaylist = { showPlaylist = true },
                onSwitchSource = { showSourceDialog = true }
            )
        } else {
            PortraitLyrics(
                track = track,
                lyrics = currentLyrics,
                progress = progress,
                status = status,
                isPlaying = isPlaying,
                isLiked = isLiked,
                onLike = {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { isLiked = toggleLike(playback) }
                },
                onPlayPause = {
                    togglePlayPause()
                    isPlaying = !isPlaying
                },
                onShowMenu = { showMenu = true },
                onShowPlaylist = { showPlaylist = true },
                onSwitchSource = { showSourceDialog = true }
            )
        }

        // 设置弹窗
        if (showMenu) {
            SettingsDialog(
                prefs = prefs,
                onDismiss = { showMenu = false },
                onSwitchSource = { 
                    showMenu = false
                    showSourceDialog = true
                },
                onSearchLyrics = {
                    showMenu = false
                    showSearchDialog = true
                }
            )
        }
        
        // 歌词搜索弹窗
        if (showSearchDialog) {
            LyricsSearchDialog(
                currentTrack = playback.track,
                onSelectLyrics = { lines, source ->
                    currentLyrics = Lyrics(lines, source)
                    status = source
                    showSearchDialog = false
                },
                onDismiss = { showSearchDialog = false }
            )
        }

        // 歌词源选择弹窗
        if (showSourceDialog) {
            SourceSelectDialog(
                sources = allSources,
                currentSource = currentLyrics?.source ?: "",
                onSelect = { source ->
                    currentLyrics = Lyrics(source.lines, source.source)
                    status = source.source
                    showSourceDialog = false
                },
                onDismiss = { showSourceDialog = false }
            )
        }

        // 播放列表弹窗
        if (showPlaylist) {
            PlaylistDialog(
                onDismiss = { showPlaylist = false },
                onPlayTrack = { trackId ->
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                        try {
                            val token = SpotifyAuth.accessToken() ?: return@launch
                            SpotifyApi.playTrack(token, "spotify:track:$trackId")
                        } catch (_: Exception) {}
                    }
                    showPlaylist = false
                }
            )
        }
    }
}

private fun togglePlayPause() {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        try {
            val token = SpotifyAuth.accessToken() ?: return@launch
            val state = SpotifyApi.getPlayback(token) ?: return@launch
            if (state.isPlaying) SpotifyApi.pause(token) else SpotifyApi.resume(token)
        } catch (_: Exception) {}
    }
}

private suspend fun toggleLike(state: PlaybackState): Boolean = withIO {
    try {
        val token = SpotifyAuth.accessToken() ?: return@withIO false
        val trackId = state.track?.id ?: return@withIO false
        SpotifyApi.toggleLike(token, trackId)
    } catch (_: Exception) { false }
}

// ---------- 横屏布局 ----------
@Composable
private fun LandscapeLyrics(
    track: Track?,
    lyrics: Lyrics?,
    progress: Long,
    status: String,
    isPlaying: Boolean,
    isLiked: Boolean,
    onLike: () -> Unit,
    onPlayPause: () -> Unit,
    onShowMenu: () -> Unit,
    onShowPlaylist: () -> Unit,
    onSwitchSource: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // 左侧：专辑封面（占满左边全部空间）
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.5f)
                .clip(RoundedCornerShape(0.dp))
        ) {
            if (track?.albumArtUrl != null) {
                AsyncImage(
                    model = track.albumArtUrl,
                    contentDescription = "专辑封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(DARKER.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = DIM, modifier = Modifier.size(80.dp))
                }
            }
        }

        Spacer(Modifier.width(20.dp))

        // 右侧区域
        Column(
            modifier = Modifier.weight(0.5f).fillMaxHeight().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 右上 Bar：歌曲名+歌手（左）+ 按钮（右）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    track?.let {
                        Text(it.title, color = WHITE, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(it.artists.joinToString(" / "), color = GRAY, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                        Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "暂停", tint = WHITE, modifier = Modifier.size(24.dp))
                    }
                    IconButton(onClick = onShowPlaylist, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = "歌单", tint = WHITE, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = onLike, modifier = Modifier.size(40.dp)) {
                        Icon(if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "收藏", tint = if (isLiked) RED_HEART else WHITE, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = onShowMenu, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = WHITE, modifier = Modifier.size(22.dp))
                    }
                }
            }

            // 右下歌词区
            LyricsList(lyrics = lyrics, progress = progress, status = status)
        }
    }
}

// ---------- 竖屏布局 ----------
@Composable
private fun PortraitLyrics(
    track: Track?,
    lyrics: Lyrics?,
    progress: Long,
    status: String,
    isPlaying: Boolean,
    isLiked: Boolean,
    onLike: () -> Unit,
    onPlayPause: () -> Unit,
    onShowMenu: () -> Unit,
    onShowPlaylist: () -> Unit,
    onSwitchSource: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        
        // Bar：歌曲名+歌手（左）+ 按钮（右）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                track?.let {
                    Text(it.title, color = WHITE, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it.artists.joinToString(" / "), color = GRAY, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "暂停", tint = WHITE, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = onShowPlaylist, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = "歌单", tint = WHITE, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onLike, modifier = Modifier.size(40.dp)) {
                    Icon(if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "收藏", tint = if (isLiked) RED_HEART else WHITE, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onShowMenu, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = WHITE, modifier = Modifier.size(22.dp))
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // 歌词区
        LyricsList(lyrics = lyrics, progress = progress, status = status, modifier = Modifier.weight(1f))

        // 底部来源
        Text(status, color = WHITE.copy(alpha = 0.4f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 24.dp))
    }
}

// ---------- 歌词列表组件 ----------
@Composable
private fun LyricsList(
    lyrics: Lyrics?,
    progress: Long,
    status: String,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val lineHeight = 52.dp  // 固定每行高度，确保四行
    
    // 自动滚动到当前行，保持当前行在固定锚点（中间偏下位置）
    LaunchedEffect(progress, lyrics) {
        if (lyrics != null) {
            val currentIndex = lyrics.lines.indexOfLast { it.startMs <= progress }.coerceAtLeast(0)
            if (currentIndex >= 0) {
                coroutineScope.launch {
                    // 滚动到当前行，使其位于锚点位置（预留2个空白行）
                    listState.animateScrollToItem(maxOf(0, currentIndex + 2))
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (lyrics != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 顶部空白占位（2行高度），确保第一首歌开始时光标位置正确
                item {
                    Spacer(Modifier.height(lineHeight * 2))
                }
                
                items(lyrics.lines.size) { index ->
                    val line = lyrics.lines[index]
                    val isCurrent = index == lyrics.lines.indexOfLast { it.startMs <= progress }
                    val alpha = when {
                        isCurrent -> 1f
                        index < lyrics.lines.indexOfLast { it.startMs <= progress } -> 0.4f
                        else -> 0.2f
                    }
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(lineHeight),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Column {
                            Text(
                                line.text,
                                color = WHITE.copy(alpha = alpha),
                                fontSize = 22.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (line.translation != null) {
                                Text(
                                    line.translation,
                                    color = if (isCurrent) GRAY.copy(alpha = 0.9f) else GRAY.copy(alpha = 0.4f),
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
                
                // 底部空白占位（2行高度），确保最后一首歌结束时光标位置正确
                item {
                    Spacer(Modifier.height(lineHeight * 2))
                }
            }
        } else {
            Text(status, color = WHITE.copy(alpha = 0.6f), fontSize = 18.sp)
        }
    }
}

// ---------- 设置弹窗 ----------
@Composable
private fun SettingsDialog(
    prefs: AppPrefs,
    onDismiss: () -> Unit,
    onSwitchSource: () -> Unit,
    onSearchLyrics: () -> Unit = {}
) {
    var offset by remember { mutableFloatStateOf(prefs.lyricOffset.toFloat()) }
    var showOffsetSlider by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f),
            colors = CardDefaults.cardColors(containerColor = DARKER.copy(alpha = 0.95f))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("设置", color = WHITE, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onSwitchSource() }
                    ) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "切换歌词源", tint = GREEN, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("切换歌词", color = GRAY, fontSize = 12.sp)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { showOffsetSlider = !showOffsetSlider }
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = "歌词偏移", tint = if (prefs.lyricOffset != 0) GREEN else DIM, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("偏移${prefs.lyricOffset}ms", color = GRAY, fontSize = 12.sp)
                    }
                }

                if (showOffsetSlider) {
                    Spacer(Modifier.height(16.dp))
                    Slider(
                        value = offset,
                        onValueChange = { offset = it },
                        valueRange = -3000f..3000f,
                        onValueChangeFinished = { prefs.lyricOffset = offset.toInt() },
                        colors = SliderDefaults.colors(thumbColor = GREEN, activeTrackColor = GREEN)
                    )
                    Text("当前: ${prefs.lyricOffset}ms", color = DIM, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("关闭", color = GREEN)
                }
            }
        }
    }
}

// ---------- 歌词源选择弹窗（含搜索） ----------
@Composable
private fun SourceSelectDialog(
    sources: List<LyricSource>,
    currentSource: String,
    onSelect: (LyricSource) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            colors = CardDefaults.cardColors(containerColor = DARKER.copy(alpha = 0.98f))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (showSearch) "搜索歌词" else "选择歌词源", color = WHITE, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = GREEN, fontSize = 14.sp)
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                if (showSearch) {
                    // 搜索模式
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("输入歌曲名搜索...", color = DIM) },
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(color = WHITE, fontSize = 15.sp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GREEN,
                                unfocusedBorderColor = DIM,
                                focusedContainerColor = DARK,
                                unfocusedContainerColor = DARK
                            )
                        )
                        Button(
                            onClick = {
                                isSearching = true
                                searchResults = emptyList()
                                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                    val results = mutableListOf<SearchResultItem>()
                                    // QQ音乐
                                    try {
                                        val qqTracks = QQMusicApi.search(searchQuery)
                                        qqTracks.forEach { track ->
                                            results.add(SearchResultItem(track, "QQ音乐"))
                                        }
                                    } catch (_: Exception) {}
                                    // 网易云
                                    try {
                                        val neTracks = NeteaseApi.search(searchQuery)
                                        neTracks.forEach { track ->
                                            results.add(SearchResultItem(track, "网易云"))
                                        }
                                    } catch (_: Exception) {}
                                    with(kotlinx.coroutines.Dispatchers.Main) {
                                        searchResults = results
                                        isSearching = false
                                    }
                                }
                            },
                            enabled = !isSearching && searchQuery.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = GREEN)
                        ) {
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = WHITE, strokeWidth = 2.dp)
                            } else {
                                Text("搜索", fontSize = 13.sp)
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    // 搜索结果列表
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (searchResults.isNotEmpty()) {
                            items(searchResults.size) { index ->
                                val result = searchResults[index]
                                SearchResultRow(
                                    result = result,
                                    onClick = {
                                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                            try {
                                                val lines = when (result.source) {
                                                    "QQ音乐" -> {
                                                        val (raw, trans) = QQMusicApi.getLyrics(result.track.id)
                                                        LyricsAggregator.parseQQForSearch(raw, trans)
                                                    }
                                                    else -> {
                                                        val (lrc, yrc, trans) = NeteaseApi.getLyrics(result.track.id)
                                                        if (!yrc.isNullOrBlank()) {
                                                            Parsers.parseWordLyrics(yrc).let { Parsers.mergeTranslation(it, trans) }
                                                        } else {
                                                            Parsers.parseLrc(lrc).let { Parsers.mergeTranslation(it, trans) }
                                                        }
                                                    }
                                                }
                                                if (lines.isNotEmpty()) {
                                                    with(kotlinx.coroutines.Dispatchers.Main) {
                                                        onSelect(LyricSource(result.track, "${result.source} · ${result.track.title}", lines))
                                                    }
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                )
                            }
                        } else if (!isSearching && searchQuery.isNotBlank()) {
                            item {
                                Text("未找到结果", color = DIM, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
                            }
                        }
                    }
                    
                    TextButton(
                        onClick = { showSearch = false },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("返回源列表", color = GREEN, fontSize = 13.sp)
                    }
                } else {
                    // 源列表模式
                    if (sources.isEmpty()) {
                        Text("未找到歌词", color = DIM, fontSize = 14.sp)
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(sources.size) { index ->
                                val source = sources[index]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(source) }
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        source.source,
                                        color = if (source.source == currentSource) GREEN else WHITE,
                                        fontSize = 16.sp
                                    )
                                    if (source.source == currentSource) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = GREEN, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Button(
                        onClick = { showSearch = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GREEN)
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = WHITE, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("搜索其他歌词", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// 搜索结果行
@Composable
private fun SearchResultRow(
    result: SearchResultItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(GREEN)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.track.title,
                color = WHITE,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${result.track.artists.joinToString(", ")} · ${result.track.album}",
                color = GRAY,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(result.source, color = DIM, fontSize = 11.sp)
    }
}

// 搜索项数据
data class SearchResultItem(
    val track: Track,
    val source: String
)

// ---------- 播放列表弹窗 ----------
@Composable
private fun PlaylistDialog(
    onDismiss: () -> Unit,
    onPlayTrack: (String) -> Unit
) {
    var playlists by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var playlistTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        withIO {
            try {
                val token = SpotifyAuth.accessToken() ?: return@withIO
                playlists = SpotifyApi.getUserPlaylists(token)
            } catch (_: Exception) {}
        }
        loading = false
    }

    LaunchedEffect(selectedPlaylist) {
        if (selectedPlaylist != null) {
            withIO {
                try {
                    val token = SpotifyAuth.accessToken() ?: return@withIO
                    playlistTracks = SpotifyApi.getPlaylistTracks(token, selectedPlaylist!!)
                } catch (_: Exception) {}
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.7f),
            colors = CardDefaults.cardColors(containerColor = DARKER.copy(alpha = 0.95f))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("播放列表", color = WHITE, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (selectedPlaylist != null) {
                        TextButton(onClick = { selectedPlaylist = null }) {
                            Text("返回", color = GREEN)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                if (loading) {
                    Text("加载中...", color = DIM, fontSize = 14.sp)
                } else if (selectedPlaylist == null) {
                    // 显示播放列表
                    if (playlists.isEmpty()) {
                        Text("未找到播放列表", color = DIM, fontSize = 14.sp)
                    } else {
                        LazyColumn {
                            items(playlists.size) { index ->
                                val (name, id) = playlists[index]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedPlaylist = id }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = WHITE, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(name, color = WHITE, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                } else {
                    // 显示播放列表歌曲
                    LazyColumn {
                        items(playlistTracks.size) { index ->
                            val track = playlistTracks[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlayTrack(track.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.title, color = WHITE, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artists.joinToString(", "), color = GRAY, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = WHITE, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("关闭", color = GREEN)
                }
            }
        }
    }
}

// ---------- 歌词搜索弹窗 ----------
@Composable
private fun LyricsSearchDialog(
    currentTrack: Track?,
    onSelectLyrics: (List<Line>, String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf(currentTrack?.let { "${it.title} ${it.artists.firstOrNull() ?: ""}" } ?: "") }
    var isSearching by remember { mutableStateOf(false) }
    var qqResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var neteaseResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            colors = CardDefaults.cardColors(containerColor = DARKER.copy(alpha = 0.98f))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("搜索歌词", color = WHITE, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = GREEN, fontSize = 14.sp)
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // 搜索框
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索歌曲...", color = DIM) },
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(color = WHITE, fontSize = 16.sp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GREEN,
                            unfocusedBorderColor = DIM,
                            focusedContainerColor = DARK,
                            unfocusedContainerColor = DARK
                        )
                    )
                    Button(
                        onClick = {
                            isSearching = true
                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                // 搜索QQ音乐
                                try {
                                    val qqTracks = QQMusicApi.search(searchQuery)
                                    val qqItems = qqTracks.map { track ->
                                        SearchResult(
                                            track = track,
                                            source = "QQ音乐",
                                            hasLyrics = true
                                        )
                                    }
                                    with(kotlinx.coroutines.Dispatchers.Main) {
                                        qqResults = qqItems
                                    }
                                } catch (_: Exception) {}
                                
                                // 搜索网易云
                                try {
                                    val neTracks = NeteaseApi.search(searchQuery)
                                    val neItems = neTracks.map { track ->
                                        SearchResult(
                                            track = track,
                                            source = "网易云",
                                            hasLyrics = true
                                        )
                                    }
                                    with(kotlinx.coroutines.Dispatchers.Main) {
                                        neteaseResults = neItems
                                    }
                                } catch (_: Exception) {}
                                
                                with(kotlinx.coroutines.Dispatchers.Main) {
                                    isSearching = false
                                }
                            }
                        },
                        enabled = !isSearching && searchQuery.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = GREEN)
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = WHITE, strokeWidth = 2.dp)
                        } else {
                            Text("搜索", fontSize = 14.sp)
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // 搜索结果列表
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // QQ音乐结果
                    if (qqResults.isNotEmpty()) {
                        item {
                            Text("QQ音乐", color = GREEN, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        items(qqResults) { result ->
                            SearchResultItem(
                                result = result,
                                onClick = {
                                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                        try {
                                            val (raw, trans) = QQMusicApi.getLyrics(result.track.id)
                                            val lines = LyricsAggregator.parseQQForSearch(raw, trans)
                                            if (lines.isNotEmpty()) {
                                                with(kotlinx.coroutines.Dispatchers.Main) {
                                                    onSelectLyrics(lines, "QQ音乐 · ${result.track.title}")
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            )
                        }
                    }
                    
                    // 网易云结果
                    if (neteaseResults.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text("网易云音乐", color = GREEN, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        items(neteaseResults) { result ->
                            SearchResultItem(
                                result = result,
                                onClick = {
                                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                        try {
                                            val (lrc, yrc, trans) = NeteaseApi.getLyrics(result.track.id)
                                            val lines = if (!yrc.isNullOrBlank()) {
                                                Parsers.parseWordLyrics(yrc).let { 
                                                    Parsers.mergeTranslation(it, trans) 
                                                }
                                            } else {
                                                Parsers.parseLrc(lrc).let { 
                                                    Parsers.mergeTranslation(it, trans) 
                                                }
                                            }
                                            if (lines.isNotEmpty()) {
                                                with(kotlinx.coroutines.Dispatchers.Main) {
                                                    onSelectLyrics(lines, "网易云 · ${result.track.title}")
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            )
                        }
                    }
                    
                    if (!isSearching && qqResults.isEmpty() && neteaseResults.isEmpty() && searchQuery.isNotBlank()) {
                        item {
                            Text("未找到结果", color = DIM, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
                        }
                    }
                }
            }
        }
    }
}

// 搜索结果项
@Composable
private fun SearchResultItem(
    result: SearchResult,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态指示点
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (result.hasLyrics) GREEN else Color.Red)
        )
        Spacer(Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.track.title,
                color = WHITE,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${result.track.artists.joinToString(", ")} · ${result.track.album}",
                color = GRAY,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        Text(
            result.source,
            color = DIM,
            fontSize = 11.sp
        )
    }
}

// 搜索结果数据类
data class SearchResult(
    val track: Track,
    val source: String,
    val hasLyrics: Boolean
)

private suspend fun <T> withIO(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
