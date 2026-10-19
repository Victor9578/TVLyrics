# TVLyrics 项目进度

## 项目概述
TV端Spotify歌词应用，原生Kotlin + Compose，支持横竖屏自适应UI，歌词源：QQ音乐/网易云。

---

## ✅ 已完成

### 核心功能
- [x] Spotify PKCE登录（自定义Client ID）
- [x] 播放状态轮询（10s间隔 + 429退避）
- [x] 本地时钟插值驱动歌词进度
- [x] QQ音乐搜索 + QRC解密（3DES+zlib）
- [x] 网易云EAPI搜索+歌词（AES-ECB加密）
- [x] LRC/QRC/YRC解析器 + 翻译合并
- [x] 歌词聚合：自动获取多源歌词

### UI布局
- [x] 全屏专辑封面模糊背景
- [x] 横屏：左侧封面占满50% + 右侧bar+歌词
- [x] 竖屏：全屏背景 + 中间bar + 下方歌词
- [x] 横竖屏统一bar样式（歌名歌手左，按钮右）
- [x] 按钮顺序：暂停 | 歌单 | 收藏 | 更多
- [x] 歌词区：固定行高52dp，显示4行，当前行白色/其他渐隐
- [x] 歌词前后空白占位，当前行始终在同一锚点

### 交互功能
- [x] 暂停/播放（调用Spotify API）
- [x] 收藏/取消收藏（红心状态实时更新）
- [x] 歌单弹窗（显示用户播放列表，点击切歌）
- [x] 歌词源切换弹窗（显示自动搜索的所有版本）
- [x] 歌词源搜索（手动输入搜索其他歌词）
- [x] 歌词时间偏移滑块（-3000ms ~ +3000ms）

### 工程/构建
- [x] GitHub Actions自动构建APK
- [x] 无线adb调试流程
- [x] 5个单元测试通过

---

## 🔄 待优化

### UI细节
- [ ] 横屏专辑封面与背景融合更自然（现在左侧清晰封面，右侧模糊背景）
- [ ] 竖屏按钮栏位置微调（参考图中bar更靠上）
- [ ] 歌词翻译行位置优化（当前紧挨主歌词下方）

### 功能增强
- [ ] 歌词逐字高亮（QRC/YRC有逐字时间戳，当前只做到行级）
- [ ] TV遥控器焦点导航（D-Pad/方向键支持）
- [ ] 播放列表搜索/筛选
- [ ] 歌词缓存（避免重复请求）
- [ ] 离线模式（缓存最近歌曲歌词）

### 稳定性
- [ ] Spotify token刷新（当前登录后7天过期需重登）
- [ ] 网络异常重试机制
- [ ] 内存优化（大图缓存管理）

---

## 📋 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose |
| 网络 | HttpURLConnection（原生，无额外依赖） |
| 图片 | Coil |
| 构建 | Gradle 8.7 + JDK 17 |
| CI | GitHub Actions |

---

## 🔧 构建命令

```bash
export JAVA_HOME="C:\Users\Jw\AppData\Local\Temp\androidbuild\jdk\jdk-17.0.20.1+1"
export PATH="/tmp/androidbuild/gradle-8.7/bin:$PATH"
cd D:/0Code/TVLyrics
gradle assembleDebug --no-daemon -q
```

APK输出：`app/build/outputs/apk/debug/app-debug.apk`

---

## 📱 调试

无线adb连接：
```bash
adb pair 192.168.3.99:42073  # 配对码见手机屏幕
adb connect 192.168.3.99:42615
adb install -r app-debug.apk
```

---

## 📁 关键文件

| 文件 | 说明 |
|---|---|
| `MainActivity.kt` | UI主界面（横竖屏布局） |
| `SpotifyApi.kt` | Spotify API封装 |
| `SpotifyAuth.kt` | PKCE登录流程 |
| `QQMusicApi.kt` | QQ音乐搜索+歌词 |
| `NeteaseApi.kt` | 网易云EAPI搜索+歌词 |
| `Parsers.kt` | LRC/QRC/YRC解析 |
| `LyricsAggregator.kt` | 歌词聚合（多源获取） |
| `QrcDecrypt.kt` | QQ QRC解密（3DES+zlib） |

---

## ⚠️ 已知限制

1. **网易云YRC解析**：部分歌曲YRC格式未完全兼容，已fallback到LRC
2. **Spotify播放控制**：`playTrack` API仅支持Premium用户
3. **中文显示**：部分系统字体不支持粗体中文
4. **横竖屏切换**：需系统开启自动旋转

---

最后更新：2024-10-19
