package com.tvlyrics.app

import com.tvlyrics.app.codec.Parsers
import com.tvlyrics.app.codec.QrcDecrypt
import com.tvlyrics.app.codec.QqDesLike
import com.tvlyrics.app.domain.LyricsAggregator
import com.tvlyrics.app.domain.LyricSource
import com.tvlyrics.app.domain.Track
import com.tvlyrics.app.domain.Line
import com.tvlyrics.app.domain.Word
import com.tvlyrics.app.domain.Lyrics
import com.tvlyrics.app.provider.Http
import com.tvlyrics.app.provider.QQMusicApi
import com.tvlyrics.app.provider.NeteaseApi
import com.tvlyrics.app.provider.AppleMusicApi
import com.tvlyrics.app.provider.SpotifyApi
import com.tvlyrics.app.provider.PlaybackState
import com.tvlyrics.app.provider.SpotifyAuth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.ui.composed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var isAuthorized: MutableState<Boolean>
    lateinit var prefs: AppPrefs private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸模式：隐藏状态栏和导航栏（歌词应用全屏）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        SpotifyAuth.init(this)
        prefs = AppPrefs(this)
        AppleMusicApi.init(this)
        isAuthorized = mutableStateOf(SpotifyAuth.isAuthorized)
        setContent {
            // 全局字体：MiSans（最接近苹方的免费商用字体），中英文统一
            val misans = FontFamily(
                Font(R.font.misans_regular, FontWeight.Normal),
                Font(R.font.misans_medium, FontWeight.Medium),
                Font(R.font.misans_bold, FontWeight.Bold),
            )
            val typography = androidx.compose.material3.Typography(
                displayLarge = androidx.compose.material3.Typography().displayLarge.copy(fontFamily = misans),
                displayMedium = androidx.compose.material3.Typography().displayMedium.copy(fontFamily = misans),
                displaySmall = androidx.compose.material3.Typography().displaySmall.copy(fontFamily = misans),
                headlineLarge = androidx.compose.material3.Typography().headlineLarge.copy(fontFamily = misans),
                headlineMedium = androidx.compose.material3.Typography().headlineMedium.copy(fontFamily = misans),
                headlineSmall = androidx.compose.material3.Typography().headlineSmall.copy(fontFamily = misans),
                titleLarge = androidx.compose.material3.Typography().titleLarge.copy(fontFamily = misans),
                titleMedium = androidx.compose.material3.Typography().titleMedium.copy(fontFamily = misans),
                titleSmall = androidx.compose.material3.Typography().titleSmall.copy(fontFamily = misans),
                bodyLarge = androidx.compose.material3.Typography().bodyLarge.copy(fontFamily = misans),
                bodyMedium = androidx.compose.material3.Typography().bodyMedium.copy(fontFamily = misans),
                bodySmall = androidx.compose.material3.Typography().bodySmall.copy(fontFamily = misans),
                labelLarge = androidx.compose.material3.Typography().labelLarge.copy(fontFamily = misans),
                labelMedium = androidx.compose.material3.Typography().labelMedium.copy(fontFamily = misans),
                labelSmall = androidx.compose.material3.Typography().labelSmall.copy(fontFamily = misans),
            )
            androidx.compose.material3.MaterialTheme(typography = typography) {
                App(isAuthorized.value, prefs)
            }
        }
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

// 全局动态尺寸：按屏高百分比，手机/电视自适应
data class Dimens(
    val barTitle: androidx.compose.ui.unit.TextUnit,   // bar 歌名
    val barArtist: androidx.compose.ui.unit.TextUnit,   // bar 歌手
    val status: androidx.compose.ui.unit.TextUnit,      // 底部来源文字
    val icon: androidx.compose.ui.unit.Dp,               // 图标大小
    val button: androidx.compose.ui.unit.Dp,             // 按钮触控区
    val padH: androidx.compose.ui.unit.Dp,                // 水平内边距
    val padV: androidx.compose.ui.unit.Dp,               // 垂直内边距
    val gap: androidx.compose.ui.unit.Dp,                // bar与歌词间距
    val blur: androidx.compose.ui.unit.Dp,               // 背景模糊半径
    val topInset: androidx.compose.ui.unit.Dp,           // 竖屏顶部留空
) {
    companion object {
        fun from(c: android.content.res.Configuration): Dimens {
            val h = c.screenHeightDp.toFloat()
            return Dimens(
                barTitle = (h * 0.050f).sp,
                barArtist = (h * 0.034f).sp,
                status = (h * 0.030f).sp,
                icon = (h * 0.055f).dp,
                button = (h * 0.11f).dp,
                padH = (h * 0.05f).dp,
                padV = (h * 0.04f).dp,
                gap = (h * 0.035f).dp,
                blur = (h * 0.17f).dp,
                topInset = (h * 0.10f).dp,
            )
        }
    }
}

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
    var isLiked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isRefreshingSources by remember { mutableStateOf(false) }

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
        val sources = withIO { LyricsAggregator.fetchAll(track) }
        allSources = sources
        currentLyrics = sources.firstOrNull()?.let { Lyrics(it.lines, "${it.source} · ${it.track.title}") }
        status = currentLyrics?.source ?: "未找到歌词"

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

    // 全部动态尺寸：按屏高百分比（手机/电视自适应）
    val d = Dimens.from(configuration)

    val anyPanelOpen = showMenu || showSourceDialog || showPlaylist

    Box(modifier = Modifier.fillMaxSize()) {
        // 主内容层（面板打开时背景模糊，与沉浸式风格适配）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (anyPanelOpen && android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier)
        ) {
        // 封面取色（暗柔色，参考音乐播放器沉浸式背景方案）
    val context = androidx.compose.ui.platform.LocalContext.current
    var dominantColor by remember(track?.albumArtUrl) {
        mutableStateOf<Color?>(null)
    }
    // 封面 bitmap（Clamp 延展绘制用）
    var artBitmap by remember(track?.albumArtUrl) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(track?.albumArtUrl) {
        val url = track?.albumArtUrl ?: return@LaunchedEffect
        try {
            val loader = coil.Coil.imageLoader(context)
            val request = coil.request.ImageRequest.Builder(context)
                .data(url).allowHardware(false).build()
            val bitmap = (loader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bitmap != null) {
                artBitmap = bitmap
                val palette = androidx.palette.graphics.Palette.from(bitmap).generate()
                val rgb = palette.darkMutedSwatch?.rgb
                    ?: palette.darkVibrantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                    ?: 0xFF2B1B15.toInt()
                // HSL 调色：饱和度+0.12 丰富氛围，亮度-0.05 保歌词对比度
                val hsl = FloatArray(3)
                android.graphics.Color.colorToHSV(rgb, hsl)
                hsl[1] = (hsl[1] + 0.1f).coerceAtMost(1f)
                hsl[2] = (hsl[2] - 0.08f).coerceAtLeast(0f)
                dominantColor = Color(android.graphics.Color.HSVToColor(hsl))
            }
        } catch (_: Exception) { dominantColor = Color(0xFF2B1B15) }
    }
    val baseColor = dominantColor ?: DARK

    Box(modifier = Modifier.fillMaxSize()) {
        if (track?.albumArtUrl != null) {
            if (isLandscape) {
                // ===== V6：全屏模糊背景 + 左侧45%封面右缘羽化 + 氛围晕染 =====
                // ① 全屏模糊背景（同图模糊延伸，无裁剪接缝）
                AsyncImage(
                    model = track.albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(d.blur * 1.5f),
                    contentScale = ContentScale.Crop,
                )
                // ② 左侧封面层（固定 45% 宽，右边缘从 60% 处 DstIn 羽化融入模糊）
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.45f)
                        .align(Alignment.CenterStart)
                ) {
                    AsyncImage(
                        model = track.albumArtUrl,
                        contentDescription = "专辑封面",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                // 右边缘羽化：从 60% 处开始淡出，融入下方模糊
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(Color.Black, Color.Transparent),
                                        startX = size.width * 0.6f,
                                        endX = size.width,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                        contentScale = ContentScale.Crop,
                    )
                }
                // ③ 氛围晕染渐变（保歌词可读，纹理透出）
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.4f to baseColor.copy(alpha = 0.25f),
                                0.7f to baseColor.copy(alpha = 0.55f),
                                1.0f to baseColor.copy(alpha = 0.65f),
                            )
                        )
                    )
                )
            } else {
                // 竖屏：全屏模糊 + 垂直晕染
                AsyncImage(
                    model = track.albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(d.blur).alpha(0.7f),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.5f to baseColor.copy(alpha = 0.3f),
                                1.0f to baseColor.copy(alpha = 0.6f),
                            )
                        )
                    )
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(DARK))
        }

        // 歌词内容层
        if (isLandscape) {
            LandscapeLyrics(
                track = track,
                lyrics = currentLyrics,
                progress = progress,
                status = status,
                isPlaying = isPlaying,
                isLiked = isLiked,
                dims = d,
                
                onLike = {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                        isLiked = toggleLike(playback)
                    }
                },
                onPlayPause = {
                    togglePlayPause()
                    isPlaying = !isPlaying
                },
                onShowMenu = { showMenu = true },
                onShowPlaylist = { showPlaylist = true },
            )
        } else {
            PortraitLyrics(
                track = track,
                lyrics = currentLyrics,
                progress = progress,
                status = status,
                isPlaying = isPlaying,
                isLiked = isLiked,
                dims = d,
                
                onLike = {
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                        isLiked = toggleLike(playback)
                    }
                },
                onPlayPause = {
                    togglePlayPause()
                    isPlaying = !isPlaying
                },
                onShowMenu = { showMenu = true },
                onShowPlaylist = { showPlaylist = true },
            )
        }
        }
        } // 主内容层结束（模糊 Box 在此关闭，面板在外）

        // 设置：右侧滑入面板
        RightPanel(visible = showMenu, onClose = { showMenu = false }) {
            SettingsContent(
                prefs = prefs,
                onSwitchSource = {
                    showMenu = false
                    showSourceDialog = true
                }
            )
        }

        // 歌词源选择：右侧滑入面板
        RightPanel(visible = showSourceDialog, onClose = { showSourceDialog = false }) {
            SourceSelectContent(
                sources = allSources,
                currentSource = currentLyrics?.source ?: "",
                isRefreshing = isRefreshingSources,
                onSelect = { source ->
                    currentLyrics = Lyrics(source.lines, "${source.source} · ${source.track.title}")
                    status = currentLyrics?.source ?: ""
                    showSourceDialog = false
                },
                onRefresh = {
                    val t = playback.track
                    if (t != null) {
                        isRefreshingSources = true
                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                            val sources = LyricsAggregator.fetchAll(t)
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                allSources = sources
                                isRefreshingSources = false
                            }
                        }
                    }
                },
            )
        }

        // 播放队列：右侧滑入面板
        RightPanel(visible = showPlaylist, onClose = { showPlaylist = false }) {
            QueueContent(
                currentTrack = track,
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

// ---------- 右侧滑入面板（背景模糊 + 遮罩 + 焦点高亮适配遥控器） ----------
@Composable
private fun RightPanel(
    visible: Boolean,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(250)) { it } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250)),
        exit = androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(200)) { it } +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 遮罩：点击关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { onClose() }
            )
            // 面板：右侧 42% 宽，全高
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.42f)
                    .align(Alignment.CenterEnd)
                    .background(DARKER)
                    .clickable(enabled = false) {} // 拦截点击穿透
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 关闭按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = WHITE, modifier = Modifier.size(28.dp))
                        }
                    }
                    content()
                }
            }
        }
    }
}

// 焦点高亮（遥控器/键盘导航时可见选中项）
@Composable
private fun Modifier.focusHighlight(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .background(
            if (focused) Color.White.copy(alpha = 0.18f) else Color.Transparent,
            RoundedCornerShape(8.dp)
        )
}

private fun togglePlayPause() {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        try {
            val token = SpotifyAuth.accessToken() ?: return@launch
            val state = SpotifyApi.getPlayback(token)
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

// ---------- 顶部 Bar（横竖屏共用） ----------
@Composable
private fun TopBar(
    track: Track?,
    isPlaying: Boolean,
    isLiked: Boolean,
    dims: Dimens,
    
    onLike: () -> Unit,
    onPlayPause: () -> Unit,
    onShowMenu: () -> Unit,
    onShowPlaylist: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            track?.let {
                Text(it.title, color = WHITE, fontSize = dims.barTitle, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(it.artists.joinToString(" / "), color = WHITE.copy(alpha = 0.75f), fontSize = dims.barArtist, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row {
            IconButton(onClick = onPlayPause, modifier = Modifier.size(dims.button)) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "暂停", tint = WHITE, modifier = Modifier.size(dims.icon))
            }
            IconButton(onClick = onShowPlaylist, modifier = Modifier.size(dims.button)) {
                Icon(Icons.Filled.QueueMusic, contentDescription = "歌单", tint = WHITE, modifier = Modifier.size(dims.icon))
            }
            IconButton(onClick = onLike, modifier = Modifier.size(dims.button)) {
                Icon(if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "收藏", tint = WHITE, modifier = Modifier.size(dims.icon))
            }
            IconButton(onClick = onShowMenu, modifier = Modifier.size(dims.button)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = WHITE, modifier = Modifier.size(dims.icon))
            }
        }
    }
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
    dims: Dimens,
    
    onLike: () -> Unit,
    onPlayPause: () -> Unit,
    onShowMenu: () -> Unit,
    onShowPlaylist: () -> Unit
) {
    // 内容层占右侧（避开左侧清晰封面+羽化区）
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.55f)
                .align(Alignment.CenterEnd)
                .padding(horizontal = dims.padH, vertical = dims.padV),
        ) {
            TopBar(track, isPlaying, isLiked, dims, onLike, onPlayPause, onShowMenu, onShowPlaylist)
            Spacer(Modifier.height(dims.gap))
            LyricsList(lyrics = lyrics, progress = progress, status = status, modifier = Modifier.weight(1f))
            Text(status, color = WHITE.copy(alpha = 0.4f), fontSize = dims.status, modifier = Modifier.padding(top = dims.gap))
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
    dims: Dimens,
    
    onLike: () -> Unit,
    onPlayPause: () -> Unit,
    onShowMenu: () -> Unit,
    onShowPlaylist: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = dims.padH),
    ) {
        Spacer(Modifier.height(dims.topInset))
        TopBar(track, isPlaying, isLiked, dims, onLike, onPlayPause, onShowMenu, onShowPlaylist)
        Spacer(Modifier.height(dims.gap))
        LyricsList(lyrics = lyrics, progress = progress, status = status, modifier = Modifier.weight(1f))
        Text(status, color = WHITE.copy(alpha = 0.4f), fontSize = dims.status, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = dims.padV, top = dims.gap))
    }
}

// ---------- 歌词列表组件（当前行钉在固定锚点，顶部渐隐） ----------
@Composable
private fun LyricsList(
    lyrics: Lyrics?,
    progress: Long,
    status: String,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 当前行索引（换行立即触发，不再按秒轮询）
    val currentIdx = lyrics?.lines?.indexOfLast { it.startMs <= progress }?.coerceAtLeast(0) ?: 0

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 动态字号：按容器高度估算（约4行）
        val estLineHeight = maxHeight / 4
        val mainFontSize = (estLineHeight.value * 0.45f).sp
        val transFontSize = (estLineHeight.value * 0.24f).sp
        // 锚点：当前行顶部距歌词区顶部 1/4 高度（负偏移实现，不受前行高度影响）
        val anchorPx = with(androidx.compose.ui.platform.LocalDensity.current) { (maxHeight / 4).toPx() }

        // 歌词切换：直接定位（无动画）
        LaunchedEffect(lyrics) {
            if (lyrics != null) {
                // +1 跳过顶部占位项；负偏移把当前行顶部钉在锚点
                listState.scrollToItem(currentIdx + 1, -anchorPx.toInt())
            }
        }
        // 行变化：立即动画滚动到锚点
        LaunchedEffect(lyrics, currentIdx) {
            if (lyrics != null) {
                coroutineScope.launch {
                    listState.animateScrollToItem(currentIdx + 1, -anchorPx.toInt())
                }
            }
        }

        if (lyrics != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    // 顶部渐隐：从 bar 下方淡入，遮住上方溢出内容
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        val fade = size.height * 0.12f
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                                startY = 0f,
                                endY = fade,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                // 顶部空白占位 = 锚点高度，第一行也停在锚点
                item { Spacer(Modifier.height(maxHeight / 4)) }

                items(lyrics.lines.size) { index ->
                    val line = lyrics.lines[index]
                    val isCurrent = index == currentIdx
                    val alpha = when {
                        isCurrent -> 1f
                        index < currentIdx -> 0.4f
                        else -> 0.2f
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = estLineHeight / 6),
                    ) {
                        // 主歌词：长行自动换行
                        Text(
                            line.text,
                            color = WHITE.copy(alpha = alpha),
                            fontSize = mainFontSize,
                            lineHeight = mainFontSize * 1.2,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        )
                        if (line.translation != null) {
                            Text(
                                line.translation,
                                // 未高亮翻译与未高亮歌词同色（白 40%）
                                color = if (isCurrent) WHITE.copy(alpha = 0.85f) else WHITE.copy(alpha = 0.4f),
                                fontSize = transFontSize,
                                lineHeight = transFontSize * 1.2,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (line.romanization != null) {
                            Text(
                                line.romanization,
                                color = if (isCurrent) WHITE.copy(alpha = 0.7f) else WHITE.copy(alpha = 0.4f),
                                fontSize = transFontSize,
                                lineHeight = transFontSize * 1.2,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                // 底部空白占位，让最后一行也能停在锚点
                item { Spacer(Modifier.height(maxHeight / 2)) }
            }
        } else {
            Text(status, color = WHITE.copy(alpha = 0.6f), fontSize = 18.sp)
        }
    }
}

// ---------- 设置：底部滑入面板 ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.SettingsContent(
    prefs: AppPrefs,
    onSwitchSource: () -> Unit
) {
    var offset by remember { mutableFloatStateOf(prefs.lyricOffset.toFloat()) }
    var showOffsetSlider by remember { mutableStateOf(false) }

    Text("设置", color = WHITE, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(20.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .focusHighlight()
                .clickable { onSwitchSource() }
                .padding(12.dp)
        ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = "切换歌词源", tint = GREEN, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(4.dp))
            Text("切换歌词", color = GRAY, fontSize = 12.sp)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .focusHighlight()
                .clickable { showOffsetSlider = !showOffsetSlider }
                .padding(12.dp)
        ) {
            Icon(Icons.Filled.Tune, contentDescription = "歌词偏移", tint = if (prefs.lyricOffset != 0) GREEN else WHITE, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(4.dp))
            Text("偏移 ${prefs.lyricOffset}ms", color = GRAY, fontSize = 12.sp)
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
}

// ---------- 歌词源选择：底部滑入面板 ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.SourceSelectContent(
    sources: List<LyricSource>,
    currentSource: String,
    isRefreshing: Boolean,
    onSelect: (LyricSource) -> Unit,
    onRefresh: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }

    Text(if (showSearch) "搜索歌词" else "切换歌词", color = WHITE, fontSize = 20.sp, fontWeight = FontWeight.Bold)

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
                                try {
                                    QQMusicApi.search(searchQuery).forEach { results.add(SearchResultItem(it, "QQ音乐")) }
                                } catch (_: Exception) {}
                                try {
                                    NeteaseApi.search(searchQuery).forEach { results.add(SearchResultItem(it, "网易云")) }
                                } catch (_: Exception) {}
                                try {
                                    AppleMusicApi.search(AppleMusicApi.appContext, searchQuery).forEach { rec ->
                                        results.add(SearchResultItem(Track(rec.id, rec.title, rec.artists, rec.album, 0), "Apple Music"))
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
                        if (isSearching) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = WHITE, strokeWidth = 2.dp)
                        else Text("搜索", fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.weight(1f, fill = false).height(400.dp)) {
                    items(searchResults.size) { index ->
                        val result = searchResults[index]
                        SearchResultRow(result) {
                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                try {
                                    val lines = when (result.source) {
                                        "QQ音乐" -> {
                                            val (raw, trans, roma) = QQMusicApi.getLyrics(result.track.id)
                                            LyricsAggregator.parseQQForSearch(raw, trans, roma)
                                        }
                                        else -> {
                                            val (lrc, yrc, trans) = NeteaseApi.getLyrics(result.track.id)
                                            if (!yrc.isNullOrBlank()) Parsers.parseWordLyrics(yrc).let { Parsers.mergeTranslation(it, trans) }
                                            else Parsers.parseLrc(lrc).let { Parsers.mergeTranslation(it, trans) }
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
                    }
                }

                TextButton(onClick = { showSearch = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("返回自动结果", color = GREEN, fontSize = 13.sp)
                }
            } else {
                // 自动搜索结果列表
                if (sources.isEmpty()) {
                    Text("未找到歌词", color = DIM, fontSize = 14.sp)
                } else {
                    Text("自动找到 ${sources.size} 个版本：", color = GRAY, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.weight(1f, fill = false).height(400.dp)) {
                        items(sources.size) { index ->
                            val source = sources[index]
                            val isCurrent = "${source.source} · ${source.track.title}" == currentSource
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(source) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (source.source == "QQ音乐") Color(0xFF31C27C) else Color(0xFFDD001B))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(source.source, color = WHITE, fontSize = 10.sp)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        source.track.title,
                                        color = if (isCurrent) GREEN else WHITE,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${source.track.artists.joinToString(", ")} · ${source.track.album}",
                                        color = GRAY, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isCurrent) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = GREEN, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRefresh,
                        enabled = !isRefreshing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DARK)
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = GREEN, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = GREEN, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("重新搜索", color = GREEN, fontSize = 14.sp)
                    }
                    Button(
                        onClick = { showSearch = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DARK)
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = GREEN, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("手动搜索", color = GREEN, fontSize = 14.sp)
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
                .background(if (result.source == "QQ音乐") Color(0xFF31C27C) else Color(0xFFDD001B))
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(result.track.title, color = WHITE, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${result.track.artists.joinToString(", ")} · ${result.track.album}", color = GRAY, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(result.source, color = DIM, fontSize = 11.sp)
    }
}

// 搜索项数据
data class SearchResultItem(
    val track: Track,
    val source: String
)

// ---------- 播放队列：正在播放列表 ----------
@Composable
private fun ColumnScope.QueueContent(
    currentTrack: Track?,
    onPlayTrack: (String) -> Unit
) {
    var queue by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withIO {
            try {
                val token = SpotifyAuth.accessToken() ?: return@withIO
                val (_, items) = SpotifyApi.getQueue(token)
                queue = items
            } catch (_: Exception) {}
        }
        loading = false
    }

    Text("正在播放", color = WHITE, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))

    if (loading) {
        Text("加载中...", color = DIM, fontSize = 14.sp)
        return
    }

    LazyColumn(modifier = Modifier.weight(1f)) {
        if (currentTrack != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(GREEN.copy(alpha = 0.15f))
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = GREEN, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(currentTrack.title, color = GREEN, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(currentTrack.artists.joinToString(", "), color = GRAY, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (queue.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text("待播 " + queue.size + " 首", color = DIM, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }
            items(queue.size) { index ->
                val track = queue[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .focusHighlight()
                        .clickable { onPlayTrack(track.id) }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text((index + 1).toString(), color = DIM, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, color = WHITE, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artists.joinToString(", "), color = GRAY, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = DIM, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (currentTrack == null && queue.isEmpty()) {
            item { Text("队列为空", color = DIM, fontSize = 14.sp) }
        }
    }
}

private suspend fun <T> withIO(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
