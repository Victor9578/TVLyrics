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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var isAuthorized: MutableState<Boolean>
    lateinit var prefs: AppPrefs private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 播放中保持亮屏（歌词滚动时不息屏）
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            // 全局字体：PingFang HK（用户提供）
            // 混合字体栈：英文 Inter（GPOS kerning，间距正常）+ 中文犬神志苹方 Heavy（iOS 原版直接提取）
            // Inter 无 CJK 字形，英文走它；中文回退到后面的苹方
            val appFont = FontFamily(
                Font(R.font.inter_regular, FontWeight.Normal),   // Inter 400
                Font(R.font.inter_semibold, FontWeight.Medium),   // Inter 600
                Font(R.font.inter_black, FontWeight.Bold),         // Inter 900（英文最粗）
                Font(R.font.inter_black, FontWeight.Black),
                Font(R.font.pf_normal, FontWeight.Normal),       // 苹方 Regular（中文回退）
                Font(R.font.pf_medium, FontWeight.Medium),       // 苹方 Medium
                Font(R.font.pf_bold, FontWeight.Bold),             // 苹方 Heavy（中文最粗，原版直接提取）
                Font(R.font.pf_bold, FontWeight.Black),
            )
            val typography = androidx.compose.material3.Typography(
                displayLarge = androidx.compose.material3.Typography().displayLarge.copy(fontFamily = appFont),
                displayMedium = androidx.compose.material3.Typography().displayMedium.copy(fontFamily = appFont),
                displaySmall = androidx.compose.material3.Typography().displaySmall.copy(fontFamily = appFont),
                headlineLarge = androidx.compose.material3.Typography().headlineLarge.copy(fontFamily = appFont),
                headlineMedium = androidx.compose.material3.Typography().headlineMedium.copy(fontFamily = appFont),
                headlineSmall = androidx.compose.material3.Typography().headlineSmall.copy(fontFamily = appFont),
                titleLarge = androidx.compose.material3.Typography().titleLarge.copy(fontFamily = appFont),
                titleMedium = androidx.compose.material3.Typography().titleMedium.copy(fontFamily = appFont),
                titleSmall = androidx.compose.material3.Typography().titleSmall.copy(fontFamily = appFont),
                bodyLarge = androidx.compose.material3.Typography().bodyLarge.copy(fontFamily = appFont),
                bodyMedium = androidx.compose.material3.Typography().bodyMedium.copy(fontFamily = appFont),
                bodySmall = androidx.compose.material3.Typography().bodySmall.copy(fontFamily = appFont),
                labelLarge = androidx.compose.material3.Typography().labelLarge.copy(fontFamily = appFont),
                labelMedium = androidx.compose.material3.Typography().labelMedium.copy(fontFamily = appFont),
                labelSmall = androidx.compose.material3.Typography().labelSmall.copy(fontFamily = appFont),
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
                barTitle = (h * 0.042f).sp,
                barArtist = (h * 0.028f).sp,
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
    var selectedSource by remember { mutableStateOf<LyricSource?>(null) }  // 引用比较打勾（字符串匹配同名版本会全勾）
    var pendingSource by remember { mutableStateOf<LyricSource?>(null) }   // 网易云先到时暂存，等 QQ 1.5s
    var allSources by remember { mutableStateOf<List<LyricSource>>(emptyList()) }
    var status by remember { mutableStateOf("等待播放…") }
    var showMenu by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var isLiked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isRefreshingSources by remember { mutableStateOf(false) }
    var sourcesFetchedFor by remember { mutableStateOf<String?>(null) }  // 全量列表已拉取的歌（每首一次）

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
        selectedSource = null  // 关键：换歌重置，否则回调的 null 检查失败 → 歌词不显示
        pendingSource = null
        allSources = emptyList()
        status = "搜索歌词…"
        // 快速路径（对齐参考App）：QQ 搜索+下载1条，失败→网易云1条。切歌 2-3 个请求秒显
        val quick = LyricsAggregator.fetchQuick(track)
        if (quick != null) {
            selectedSource = quick
            currentLyrics = Lyrics(quick.lines, "${quick.source} · ${quick.track.title}")
            allSources = listOf(quick)
            status = currentLyrics?.source ?: ""
        } else {
            allSources = emptyList()
            status = "未找到歌词"
        }

        withIO {
            try {
                val token = SpotifyAuth.accessToken() ?: return@withIO
                isLiked = SpotifyApi.isLiked(token, track.id)
            } catch (_: Exception) { isLiked = false }
        }
    }

    val nowState = produceState(System.currentTimeMillis()) {
        while (true) { delay(50); value = System.currentTimeMillis() }
    }
    // 注意：此处不读 nowState.value —— 50ms 时钟不再触发整个 LyricsScreen 重组
    // currentIdx 在 LyricsList 内用 derivedStateOf 惰性计算，只在换行时重组歌词项

    val track = playback.track
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    // 全部动态尺寸：按屏高百分比（手机/电视自适应）
    val d = Dimens.from(configuration)

    val anyPanelOpen = showMenu || showPlaylist

    Box(modifier = Modifier.fillMaxSize()) {
        // 主内容层（面板打开时不模糊，右侧 65% 保持原样）
        Box(
            modifier = Modifier.fillMaxSize()
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
                // 最多颜色（dominantSwatch）优先，亮度太低时回退 vibrant/muted
                val rgb = palette.dominantSwatch?.rgb
                    ?: palette.vibrantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                    ?: palette.darkVibrantSwatch?.rgb
                    ?: 0xFF2B1B15.toInt()
                // HSL 调色：饱和度+0.1 丰富氛围，亮度钳到 0.12-0.30（深但可见，纯黑看不出遮罩）
                val hsl = FloatArray(3)
                android.graphics.Color.colorToHSV(rgb, hsl)
                hsl[1] = if (hsl[1] < 0.08f) hsl[1] else (hsl[1] + 0.1f).coerceAtMost(1f) // 灰色不加饱和度（纯灰色相无意义，加了会偏色）
                hsl[2] = hsl[2].coerceIn(0.15f, 0.60f)
                dominantColor = Color(android.graphics.Color.HSVToColor(hsl))
            }
        } catch (_: Exception) { dominantColor = Color(0xFF2B1B15) }
    }
    val baseColor = dominantColor ?: DARK

    Box(modifier = Modifier.fillMaxSize()) {
        if (track?.albumArtUrl != null) {
            if (isLandscape) {
                // 沉浸式背景（独立 Composable：参数稳定时跳过重组，避免 50ms 时钟重建 blur modifier 导致卡顿）
                ImmersionBackground(track.albumArtUrl, baseColor, d.blur, artBitmap)
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
                nowState = nowState,
                playback = playback,
                offset = prefs.lyricOffset,
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
                onNext = { nextTrack() },
                onShowMenu = { showMenu = true },
                onShowPlaylist = { showPlaylist = true },
            )
        } else {
            PortraitLyrics(
                track = track,
                lyrics = currentLyrics,
                nowState = nowState,
                playback = playback,
                offset = prefs.lyricOffset,
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
                onNext = { nextTrack() },
                onShowMenu = { showMenu = true },
                onShowPlaylist = { showPlaylist = true },
            )
        }
        }
        } // 主内容层结束（模糊 Box 在此关闭，面板在外）

        // 打开设置页：拉全量版本列表（对齐参考App：切歌只下1条，开设置才下前几条）
        LaunchedEffect(showMenu, playback.track?.id) {
            if (!showMenu) return@LaunchedEffect
            val track = playback.track ?: return@LaunchedEffect
            if (sourcesFetchedFor == track.id) return@LaunchedEffect
            sourcesFetchedFor = track.id
            isRefreshingSources = true
            val sources = LyricsAggregator.fetchAll(track)
            allSources = sources
            isRefreshingSources = false
        }

        // 设置：单页面板（延迟滑块 + 搜索 + 歌词版本）
        RightPanel(visible = showMenu, onClose = { showMenu = false }) {
            SettingsContent(
                prefs = prefs,
                sources = allSources,
                selectedSource = selectedSource,
                isRefreshing = isRefreshingSources,
                onSelect = { source ->
                    selectedSource = source
                    currentLyrics = Lyrics(source.lines, "${source.source} · ${source.track.title}")
                    status = currentLyrics?.source ?: ""
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

        // 播放队列：左侧滑入面板
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

// ---------- 左侧滑入面板（白色透明磨砂玻璃 + 遮罩 + 焦点高亮 + 返回键关闭） ----------
@Composable
private fun RightPanel(
    visible: Boolean,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    // 遥控器/手机返回键：关闭面板而不是退出应用
    androidx.activity.compose.BackHandler(enabled = visible) { onClose() }

    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(250)) { -it } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250)),
        exit = androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(200)) { -it } +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 面板：左侧滑出，占屏宽 45%，深色磨砂（右侧 65% 完全透明不动，歌词正常显示）
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.45f)
                    .align(Alignment.CenterStart)
                    .background(DARKER.copy(alpha = 0.92f))
                    .clickable(enabled = false) {} // 拦截点击穿透
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 标题栏：关闭按钮
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

// ---------- 沉浸式背景（横屏）：官方 Progressive Blur + 1/4 分辨率渲染 ----------
// 性能关键：AGSL shader 在 1/4 分辨率层上执行（像素 1/16，150ms→~10ms），
// 放大 4 倍显示（模糊内容放大无损）；左侧清晰区用全分辨率底层 + DstIn 遮罩
@Composable
private fun ImmersionBackground(artUrl: String, baseColor: Color, blurDp: androidx.compose.ui.unit.Dp, artBitmap: android.graphics.Bitmap?) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 用实际容器尺寸（configuration.screenHeightDp 和真实高度有偏差，底部会漏一条）
        val screenW = maxWidth
        val screenH = maxHeight
        // ① 全屏清晰内容（全分辨率）
        BackgroundContent(artUrl, artBitmap)
        // ② 渐进磨砂层：1/4 布局尺寸 + 官方 Progressive Blur（半径/4 补偿）+ 放大 4x + DstIn 30-35% 渐入
        Box(
            modifier = Modifier
                .width(screenW / 4)
                .height(screenH / 4)
                .graphicsLayer {
                    scaleX = 4f
                    scaleY = 4f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
                .blur(
                    // 官方渐进磨砂（半径 /4：1/4 分辨率下补偿，放大后视觉等效 10→50dp）
                    blurRadiusSpec = androidx.compose.ui.graphics.blur.BlurRadiusSpec.horizontalGradient(
                        stops = listOf(
                            androidx.compose.ui.graphics.blur.BlurStop(fraction = 0.32f, radius = 2.5.dp),
                            androidx.compose.ui.graphics.blur.BlurStop(fraction = 0.38f, radius = 3.5.dp),
                            androidx.compose.ui.graphics.blur.BlurStop(fraction = 0.44f, radius = 5.5.dp),
                            androidx.compose.ui.graphics.blur.BlurStop(fraction = 0.52f, radius = 8.5.dp),
                            androidx.compose.ui.graphics.blur.BlurStop(fraction = 0.62f, radius = 11.dp),
                            androidx.compose.ui.graphics.blur.BlurStop(fraction = 0.75f, radius = 12.5.dp),
                            androidx.compose.ui.graphics.blur.BlurStop(fraction = 1.0f, radius = 12.5.dp),
                        )
                    ),
                    edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle,
                )
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    // DstIn：30% 前透明（露出下方全分辨率清晰内容），30-35% 渐入
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.30f to Color.Transparent,
                                0.35f to Color.Black,
                                1.0f to Color.Black,
                            )
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
        ) {
            BackgroundContent(artUrl, artBitmap)  // 1/4 尺寸渲染（几何分数一致）
        }
        // ③ 取色遮罩：35-50% 羽化渐入 α80% → 100% α100%
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.35f to Color.Transparent,
                        0.42f to baseColor.copy(alpha = 0.30f),
                        0.50f to baseColor.copy(alpha = 0.55f),
                        0.60f to baseColor.copy(alpha = 0.75f),
                        0.72f to baseColor.copy(alpha = 0.88f),
                        0.85f to baseColor.copy(alpha = 0.95f),
                        1.0f to baseColor.copy(alpha = 1.0f),
                    )
                )
            )
        )
    }
}

// 背景内容：右 55% 镜像裁切封面 + 左 45% 封面（清晰层和模糊层共用）
@Composable
private fun BackgroundContent(artUrl: String, artBitmap: android.graphics.Bitmap?) {
    Box(modifier = Modifier.fillMaxSize()) {
    // 右 55%：镜像①裁切后的封面（逐瓦片，45% 处像素级无缝）
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.55f)
            .align(Alignment.CenterEnd)
    ) {
        val bmp = artBitmap ?: return@Canvas
        val cw = size.width * (0.45f / 0.55f)
        val ch = size.height
        val s = maxOf(cw / bmp.width, ch / bmp.height)
        val dw = bmp.width * s
        val dh = bmp.height * s
        val ox = -(dw - cw) / 2f
        val oy = -(dh - ch) / 2f
        val paint = android.graphics.Paint()
        val nc = drawContext.canvas.nativeCanvas
        // 瓦片1（45-90%）：镜像
        nc.save()
        nc.clipRect(0f, 0f, cw, ch)
        nc.drawBitmap(bmp, android.graphics.Matrix().apply {
            setScale(-s, s)
            postTranslate(cw - ox, oy)
        }, paint)
        nc.restore()
        // 瓦片2（90-135%）：原样
        nc.save()
        nc.clipRect(cw, 0f, 2f * cw, ch)
        nc.drawBitmap(bmp, android.graphics.Matrix().apply {
            setScale(s, s)
            postTranslate(ox + cw, oy)
        }, paint)
        nc.restore()
    }
    // 左 45% 封面（清晰层用；模糊层里它被上层清晰封面盖住）
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.45f)
            .align(Alignment.CenterStart)
    ) {
        AsyncImage(
            model = artUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
    }
}

private fun nextTrack() {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        try {
            val token = SpotifyAuth.accessToken() ?: return@launch
            SpotifyApi.next(token)
        } catch (_: Exception) {}
    }
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
    onNext: () -> Unit,
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
                Text(it.title, color = WHITE, fontSize = dims.barTitle, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = dims.barTitle * 1.1)
                Text(it.artists.joinToString(" / "), color = WHITE.copy(alpha = 0.75f), fontSize = dims.barArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = dims.barArtist * 1.2)
            }
        }
        Row {
            IconButton(onClick = onPlayPause, modifier = Modifier.size(dims.button)) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "暂停", tint = WHITE, modifier = Modifier.size(dims.icon))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(dims.button)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首", tint = WHITE, modifier = Modifier.size(dims.icon))
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
    nowState: State<Long>,
    playback: PlaybackState,
    offset: Int,
    status: String,
    isPlaying: Boolean,
    isLiked: Boolean,
    dims: Dimens,
    
    onLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
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
            TopBar(track, isPlaying, isLiked, dims, onLike, onPlayPause, onNext, onShowMenu, onShowPlaylist)
            Spacer(Modifier.height(dims.gap))
            LyricsList(lyrics = lyrics, nowState = nowState, offset = offset,
                playback = playback, status = status, modifier = Modifier.weight(1f))
            // 来源文字：与 bar 距屏幕顶部对称（padV 收尾）
            Text(status, color = WHITE.copy(alpha = 0.4f), fontSize = dims.status, modifier = Modifier.padding(top = dims.gap / 2))
        }
    }
}

// ---------- 竖屏布局 ----------
@Composable
private fun PortraitLyrics(
    track: Track?,
    lyrics: Lyrics?,
    nowState: State<Long>,
    playback: PlaybackState,
    offset: Int,
    status: String,
    isPlaying: Boolean,
    isLiked: Boolean,
    dims: Dimens,
    
    onLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShowMenu: () -> Unit,
    onShowPlaylist: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = dims.padH),
    ) {
        Spacer(Modifier.height(dims.topInset))
        TopBar(track, isPlaying, isLiked, dims, onLike, onPlayPause, onNext, onShowMenu, onShowPlaylist)
        Spacer(Modifier.height(dims.gap))
        LyricsList(lyrics = lyrics, nowState = nowState, offset = offset,
                playback = playback, status = status, modifier = Modifier.weight(1f))
        Text(status, color = WHITE.copy(alpha = 0.4f), fontSize = dims.status, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = dims.padV, top = dims.gap))
    }
}

// ---------- 歌词列表组件（当前行钉在固定锚点，顶部渐隐） ----------
@Composable
private fun LyricsList(
    lyrics: Lyrics?,
    nowState: State<Long>,
    playback: PlaybackState,
    offset: Int,
    status: String,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 当前行索引：derivedStateOf 惰性计算 —— 50ms 时钟只更新 State，
    // 只有行索引真正变化时才重组歌词项（性能关键）
    // offset：正数=歌词延后显示，负数=提前
    val currentIdx by remember(lyrics, playback, offset) {
        derivedStateOf {
            val p = playback.progressMs(nowState.value) - offset
            lyrics?.lines?.indexOfLast { it.startMs <= p }?.coerceAtLeast(0) ?: 0
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 动态字号：按容器高度估算（约4行）
        val estLineHeight = maxHeight / 4
        val mainFontSize = (estLineHeight.value * 0.40f).sp
        val transFontSize = (estLineHeight.value * 0.22f).sp
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
                    // 顶部+底部渐隐：上下对称淡入淡出，遮住溢出内容
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        val fade = size.height * 0.12f
                        // 顶部渐隐
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                                startY = 0f,
                                endY = fade,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                        // 底部渐隐（与顶部对称）
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Black, Color.Transparent),
                                startY = size.height - fade,
                                endY = size.height,
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
                    // 颜色平滑过渡：变白/变暗用 600ms 动画，不突变
                    val targetAlpha = if (isCurrent) 1f else if (index < currentIdx) 0.4f else 0.2f
                    val alpha by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
                        label = "lyricAlpha",
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = estLineHeight / 6),
                    ) {
                        // 主歌词：PingFang Black(900) + 负 letterSpacing 抵消 wdth 拉伸的字距放大
                        Text(
                            line.text,
                            color = WHITE.copy(alpha = alpha),
                            fontSize = mainFontSize,
                            lineHeight = mainFontSize * 1.2,
                            fontWeight = FontWeight.Bold,
                            
                        )
                        if (line.translation != null) {
                            Text(
                                line.translation,
                                // 未高亮翻译与未高亮歌词同色（白 40%）
                                color = WHITE.copy(alpha = alpha * 0.85f), // 跟随主歌词动画 alpha，颜色统一
                                fontSize = transFontSize,
                                lineHeight = transFontSize * 1.2,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (line.romanization != null) {
                            Text(
                                line.romanization,
                                color = WHITE.copy(alpha = alpha * 0.7f), // 跟随主歌词动画 alpha
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

// ---------- 设置：单页面板（延迟滑块 + 搜索按钮 + 歌词版本列表） ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.SettingsContent(
    prefs: AppPrefs,
    sources: List<LyricSource>,
    selectedSource: LyricSource?,
    isRefreshing: Boolean,
    onSelect: (LyricSource) -> Unit,
    onRefresh: () -> Unit
) {
    var offset by remember { mutableFloatStateOf(prefs.lyricOffset.toFloat()) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("设置", color = WHITE, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        // 版本号（从 PackageManager 读取）
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val versionName = remember {
            try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
        }
        Text("v$versionName", color = DIM, fontSize = 12.sp)
    }
    Spacer(Modifier.height(16.dp))

    if (showSearch) {
        // ===== 手动搜索模式 =====
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
                        try { QQMusicApi.search(searchQuery).forEach { results.add(SearchResultItem(it, "QQ音乐")) } } catch (_: Exception) {}
                        try { NeteaseApi.search(searchQuery).forEach { results.add(SearchResultItem(it, "网易云")) } } catch (_: Exception) {}
                        try {
                            AppleMusicApi.search(AppleMusicApi.appContext, searchQuery).forEach { rec ->
                                results.add(SearchResultItem(Track(rec.id, rec.title, rec.artists, rec.album, 0), "Apple Music"))
                            }
                        } catch (_: Exception) {}
                        with(kotlinx.coroutines.Dispatchers.Main) { searchResults = results; isSearching = false }
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
        LazyColumn(modifier = Modifier.weight(1f)) {
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
            Text("返回设置", color = GREEN, fontSize = 13.sp)
        }
    } else {
        // ===== 主页：延迟滑块 + 搜索按钮 + 歌词版本列表 =====
        // 第一行：歌词延迟调节（遥控器可聚焦拖动，ms 实时同步）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("歌词延迟", color = WHITE, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("${offset.toInt()}ms", color = GREEN, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = offset,
            onValueChange = { offset = it },
            valueRange = -3000f..3000f,
            onValueChangeFinished = { prefs.lyricOffset = offset.toInt() },
            colors = SliderDefaults.colors(
                thumbColor = GREEN,
                activeTrackColor = GREEN,
                inactiveTrackColor = DIM,
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        // 第二行：重新搜索 + 手动搜索（描边风格，与整体统一）
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .focusHighlight(),
                border = androidx.compose.foundation.BorderStroke(1.dp, GREEN.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = GREEN,
                )
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = GREEN, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = GREEN, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("重新搜索", fontSize = 14.sp)
            }
            OutlinedButton(
                onClick = { showSearch = true },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .focusHighlight(),
                border = androidx.compose.foundation.BorderStroke(1.dp, GREEN.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = GREEN,
                )
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = GREEN, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("手动搜索", fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(16.dp))

        // 歌词版本列表（焦点高亮适配遥控器）
        if (sources.isEmpty()) {
            Text("未找到歌词", color = DIM, fontSize = 14.sp)
        } else {
            Text("歌词版本", color = GRAY, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(sources.size) { index ->
                    val source = sources[index]
                    val isCurrent = source === selectedSource  // 引用比较：只有选中的那个打勾
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .focusHighlight()
                            .clickable { onSelect(source) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
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
