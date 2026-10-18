package com.tvlyrics.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpotifyAuth.init(this)
        setContent { App() }
        // 登录回调（网络请求放后台线程）
        handleLoginRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLoginRedirect(intent)
    }

    private fun handleLoginRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "tvlyrics") Thread { SpotifyAuth.handleRedirect(data) }.start()
    }
}

private val DARK = Color(0xFF101014)
private val GREEN = Color(0xFF1DB954)
private val DIM = Color(0xFF6E6E78)
private val WHITE = Color(0xFFEDEDF2)

@Composable
private fun App() {
    if (!SpotifyAuth.isAuthorized) LoginScreen() else LyricsScreen()
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
private fun LyricsScreen() {
    var playback by remember { mutableStateOf(PlaybackState.EMPTY) }
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var status by remember { mutableStateOf("等待播放…") }

    // 轮询循环（IO 线程），429 自动退避
    LaunchedEffect(Unit) {
        var backoff = 0L
        while (true) {
            try {
                val state = withIO {
                    val token = SpotifyAuth.accessToken()
                        ?: return@withIO null
                    SpotifyApi.getPlayback(token)
                }
                if (state == null) { status = "登录过期，请重新登录"; break }
                playback = state
                backoff = 0
            } catch (e: Http.HttpException) {
                backoff = (e.retryAfterMs ?: SpotifyApi.POLL_INTERVAL_MS * 2).coerceAtMost(SpotifyApi.MAX_BACKOFF_MS)
            } catch (_: Exception) {
                backoff = SpotifyApi.POLL_INTERVAL_MS * 2
            }
            delay(if (backoff > 0) backoff else SpotifyApi.POLL_INTERVAL_MS)
        }
    }

    // 换歌时拉歌词
    LaunchedEffect(playback.track?.id) {
        val track = playback.track ?: return@LaunchedEffect
        lyrics = null
        status = "搜索歌词…"
        lyrics = withIO { LyricsAggregator.fetch(track) }
        status = lyrics?.let { "歌词来源: ${it.source}" } ?: "未找到歌词"
    }

    // UI 时钟：驱动进度插值（不消耗网络）
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(50)
            value = System.currentTimeMillis()
        }
    }

    val progress = playback.progressMs(now)
    val currentLine = lyrics?.lines?.lastOrNull { it.startMs <= progress }

    Column(
        modifier = Modifier.fillMaxSize().background(DARK).padding(horizontal = 120.dp, vertical = 80.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        playback.track?.let {
            Text("${it.title} — ${it.artists.joinToString(" / ")}", color = WHITE, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(it.album, color = DIM, fontSize = 20.sp)
            Spacer(Modifier.height(48.dp))
        }
        if (currentLine != null) {
            // 逐字高亮
            val words = currentLine.words
            if (words != null) {
                Row {
                    words.forEach { w ->
                        val active = progress >= w.startMs && progress < w.endMs
                        val done = progress >= w.endMs
                        Text(
                            w.text,
                            color = when {
                                active -> GREEN
                                done -> WHITE
                                else -> DIM
                            },
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else {
                Text(currentLine.text, color = WHITE, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            currentLine.translation?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = DIM, fontSize = 26.sp)
            }
        } else {
            Text(status, color = DIM, fontSize = 24.sp)
        }
    }
}

private suspend fun <T> withIO(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
