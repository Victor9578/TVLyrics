# TV 歌词 (TVLyrics)

Android TV 上的 Spotify 歌词显示，为低端盒子（Amlogic S905X4 / Mali-G31）优化。

## 功能
- **自定义 Client ID 登录**（PKCE，无需 secret，任何人可用）
- **歌词源**：QQ 音乐（QRC 逐字 + 翻译）→ 网易云音乐（YRC 逐字 + 翻译）→ LRC 降级
- **播放进度**：10s 轮询 `/currently-playing` + 本地时钟插值，逐字动画零网络开销；429 自动按 Retry-After 退避
- **性能**：纯 Compose 文字渲染，无模糊、无渐变背景、无逐帧重绘整屏 —— 修复 Lyricify Mobile 在低端电视上的卡顿和色带问题

## 登录步骤
1. 到 [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) 创建 App
2. Redirect URI 填 `tvlyrics://callback`
3. 把 Client ID 填入 App 内，按提示完成浏览器登录

## 构建
```bash
gradle assembleDebug
```
或直接 push 到 GitHub，Actions 会自动打包 APK（Artifacts 里下载）。

## 测试
```bash
gradle testDebugUnitTest
```

## 已知限制
- Spotify 无 WebSocket，只能轮询；切歌最长延迟 ~10s（可调 `SpotifyApi.POLL_INTERVAL_MS`）
- 网易云搜索接口在部分网络环境可能被风控（返回 -460），此时自动降级 QQ
- QRC 加密歌词解密失败时自动回退 LRC
