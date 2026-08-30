# 媒体播放器 · Media Player

> 一款 **本地优先、全协议、音视频一体化** 的 Android 媒体播放器。
> 兼顾 **手机触控**（音频播放器 + 全屏视频）与 **电视盒子遥控器**（TV 模式）两种操作方式，
> 主打：多网络协议访问、ASS/SSA 字幕、卡拉 OK 歌词、DLNA 投屏、专业十段均衡器。
点击链接加入群聊【《媒体播放器》 交流】：http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=1wOIcIVqkGHn96qutyAqcQx1UPZ9QGFG&authKey=nz88i%2Fmvusa6ivLLk3pxK0MZtjQ4vP86BgzuVvQNBNhqukA%2B4gSMfYjdIeWRtDdF&noverify=0&group_code=649768753
---

## 目录

- [功能特性](#功能特性)
- [支持的协议与格式](#支持的协议与格式)
- [技术栈](#技术栈)
- [操作指南](#操作指南)
  - [一、触屏手势操作](#一触屏手势操作)
    - [音频播放页（PlayerScreen）](#音频播放页-手机模式)
    - [全屏视频页（FullScreenVideoOverlay）](#全屏视频页-手机模式)
    - [迷你播放器与播放列表](#迷你播放器与播放列表)
  - [二、TV 遥控器快捷键](#二tv-遥控器快捷键)
    - [视频播放页](#tv-视频播放页)
    - [音乐播放页](#tv-音乐播放页)
    - [首页 / 文件列表](#tv-首页文件列表)
    - [歌词与进度条](#tv-歌词与进度条)
    - [完整按键速查表](#tv-完整按键速查表)
- [工程结构](#工程结构)
- [构建与安装](#构建与安装)
- [致谢与许可](#致谢与许可)

---

## 功能特性

- 🎵 **音频播放**：本地 + 网络音频，十段均衡器、预设、卡拉 OK 歌词、歌词搜索与缓存、迷你播放器、锁屏媒体通知。
- 🎬 **视频播放**：全屏手势控制（亮度 / 音量 / 进度 / 倍速 / 缩放）、硬件解码切换、比例与循环、画中画（PiP）、ASS / SSA 内嵌与外挂字幕。
- 📚 **多网络协议**：SMB / HTTP / WebDAV / FTP / SFTP，统一「服务器」模型，云端文件直接流式播放。
- 📺 **TV 模式**：完整遥控器 D-pad / 媒体键按键体系、焦点导航、沉浸式电视首页。
- 📻 **DLNA 投屏**：本地文件转 HTTP 投放到支持 DLNA 的设备。
- 🧩 **音频可视化**：卡拉 OK 逐字高亮、跑马灯标题、专辑封面旋转、相位检测 / 节拍器 / 录音取词等工具。

---

## 支持的协议与格式

| 类别 | 支持项 |
|---|---|
| **网络协议** | SMB(JCIFS-ng)、HTTP、WebDAV、FTP(commons-net)、SFTP(sshj) |
| **视频** | MP4、MKV、WebM 及 HLS / DASH / SmoothStreaming / RTSP 等流媒体（Media3） |
| **音频** | MP3、FLAC、WAV、M4A、AAC 等 |
| **字幕** | MKV 内嵌与外部 `.ass` / `.ssa`（libass via `peerless2012/ass-kt`，OpenGL 渲染）|
| **歌词** | LRC / QRC(加密 QRC 解密) / YRC / 逐字卡拉 OK / 在线歌词库 |
| **其他** | DLNA(UPnP Cast)、本地 HTTP 服务器(NanoHttpD)、静音裁剪、音频波形 |

> **RELEASE 包 ABI**：仅保留 `arm64-v8a` 与 `armeabi-v7a`，已排除 x86 / x86_64，以减小体积（Release ≈ 17MB+）。
> Debug 包保留全 ABI 便于模拟器调试。

---

## 技术栈

- **语言 / UI**：Kotlin + Jetpack Compose（Material 3，BOM）
- **播放内核**：Media3 ExoPlayer（含 FFmpeg 软解、HLS/DASH/RTSP）
- **字幕**：`io.github.peerless2012:ass-kt:0.5.1` + `ass-media:0.5.1`（libass）
- **网络**：`jcifs-ng`、`commons-net`、`sshj`、`bouncycastle`
- **投屏**：`upnpcast`、`nanohttpd`
- **图片 / 音频**：Coil(Coil Video)、AudioWaveformManager
- **构建**：Gradle + Kotlin Compose，`minSdk 24 / targetSdk 35 / compileSdk 35`
- **系统能力**：前台媒体服务、无障碍服务（息屏长按音量切歌）、画中画、悬浮歌词

---

## 操作指南

> 本文档按「手机触屏」与「电视遥控器」两条路径分别说明，数值均来自源码实证。

### 一、触屏手势操作

#### 音频播放页（手机模式）

| 手势 | 位置 / 触发条件 | 行为 | 关键参数 |
|---|---|---|---|
| **水平滑动** | 主内容区（上滑/下滑区之外也响应） | 左滑**下一曲**、右滑**上一曲** | 位移 > **60px** |
| **垂直滑动** | 主内容区 | 上滑进**全屏歌词**、下滑打开**播放列表** | 位移 > **60px** |
| **双击** | 播放器主区域 | 若当前是视频则进入视频全屏 | — |
| **短按播放/暂停键** | 控制栏 | 播放 / 暂停 | — |
| **长按播放/暂停键** | 控制栏，**> 2 秒** | 停止播放 + 显示停止通知 + 轻微震动 | `2000ms` 长按阈值 |
| **快退 / 快进键** | 控制栏 | 单击 ±5 秒；**按住 ≥300ms** 后每 200ms 再跳 ±5 秒 | `5000ms/步`，按住 `300ms` 起步、`200ms` 间隔 |
| **拖拽进度条** | 底部 WaveformSeekBar | 拖动中仅预览，抬手才真正 seek | 拇指透明隐藏式 |
| **双击专辑图** | 专辑封面内容区 | 进入 TV 模式（仅切显示模式，不改变播放） | — |
| **滚动播放列表封面** | 播放列表面板 | 左右滑动翻页，页码变化后触发「从播放列表切换」 | 首次判定阈值 `15px`、纵向 `80px` |
| **迷你播放器滑动** | 底部迷你栏 | 右滑 >120px 上一曲、左滑 >120px 下一曲 | `120px`，松手弹簧回弹 |
| **点击歌词行** | 全屏歌词页 | 跳转到该行对应的时间点 | — |

**关于主内容区滑动**：代码使用 `detectDragGestures` 累积 `totalDragX / totalDragY`，
以「较大一轴 + 超过 60px」判定动作方向，因此**横竖滑不会互相干扰**。

**关于长按播放键**：`pointerInput` 内先等待按下，若在 `2000ms` 内未松开则触发「停止播放」，
否则在释放后触发普通「播放 / 暂停」。

```kotlin
// 主内容区滑动切歌 / 歌词 / 播放列表（PlayerScreen.kt）
.detectDragGestures(
    onDragEnd = {
        val absX = totalDragX.absoluteValue; val absY = totalDragY.absoluteValue
        if (absX > absY && absX > 60f) {        // 水平超过横向阈值
            if (totalDragX < -60f) actions.onNext()      // 左滑 → 下一曲
            else if (totalDragX > 60f) actions.onPrevious() // 右滑 → 上一曲
        } else if (absY > absX && absY > 60f) {  // 垂直超过纵向阈值
            if (totalDragY < -60f) lyricsFullscreen = true
            else if (totalDragY > 60f) showPlaylist = true
        }
    }
) { _, dragAmount -> totalDragX += dragAmount.x; totalDragY += dragAmount.y }
```

#### 全屏视频页（手机模式）

| 手势 | 位置 / 触发条件 | 行为 | 关键参数 |
|---|---|---|---|
| **单击屏幕** | 任意位置 | 切换控制栏显隐（**锁定时空点**显示解锁提示） | — |
| **双击切歌** | 屏幕按**三等分**划分 | 左三分之一**上一曲**、中三分之一**播放/暂停**、右三分之一**下一曲** | 锁定状态不生效 |
| **长按加速** | 屏幕中心 5%–95% 区域，**>500ms** | 以 **5 倍速**播放（记录原速，松手恢复） | `500ms` 长按，`5.0f` 倍速 |
| **左侧单指竖滑** | 初始按下点在屏幕**左半边** | 调节**屏幕亮度** | 满屏 = 灵敏度 `height×0.7`，范围 `0.01–1.0` |
| **右侧单指竖滑** | 初始按下点在屏幕**右半边** | 调节**媒体音量** | 满屏 = 灵敏度 `height×0.7`，映射 `0–maxVolume` |
| **单指横滑** | 任一行，横向 | **快进 / 快退**（锁定为寻道模式） | 满屏 ≈ **60 秒**（灵敏度 `width×0.8`） |
| **双指捏合** | 任意位置 | **缩放画面**（可平移） | 范围 `0.5x–4x` |
| **拖拽/点击进度条** | 顶部 EnergyProgressBar | 实时 seek | — |
| **点击播放列表面板空白** | 侧边半屏面板 | 关闭面板 | 右侧 50% 内容区阻断点击穿透 |

**关键判定逻辑（`awaitEachGesture`）**：
- 按下起点 `< width/2` ⇒ **左半边=亮度**，否则 **右半边=音量**；
- 移动后取较大主轴：横移 > 50%（且横向 > **40px**）锁定为「寻道」，竖移 > 50%（且纵向 > **40px**）锁定为「亮度/音量」；
- 手势类型一旦锁定即可分离调节区，避免误触。

```kotlin
// 全屏视频：按起点分左右半边（FullScreenVideoOverlay.kt）
if (dragStartX < size.width / 2f) { startBrightness = currentBrightness }   // 左 → 亮度
else { startVolume = audioManager.getStreamVolume(STREAM_MUSIC) }            // 右 → 音量
```

```kotlin
// 长按中心区域 500ms 加速到 5x（FullScreenVideoOverlay.kt）
onPress = { pressOffset ->
    if (pressOffset in centerZone && !isLocked) {
        delay(500)
        if (!gestureActive && !longPressActive) {
            originalSpeed = state.playbackSpeed
            onSpeedChange(5.0f)      // 5 倍速
            awaitRelease()
        }
    }
}
```

---

### 二、TV 遥控器快捷键

> TV 模式基于 Compose 焦点体系实现（`onKeyEvent` + `FocusRequester` + `BackHandler`）。
> 解释一个通用规律：**方向键在列表里**多用于**焦点 / 每按 10 项跳页**；**在视频画面上**用于**长按 400ms 快进快退、松开切歌**。

#### TV · 视频播放页

| 按键 | 行为 | 逻辑细节 |
|---|---|---|
| **← / → 方向键（短按）** | 不立即动作，仅登记「待寻道」任务 | 为区分单击/长按，按下先启动 400ms 延迟任务 |
| **← 方向键（长按 ≥ 400ms）** | 快退 **10 秒** | `onSeekMs(currentPosition − 10000)` |
| **→ 方向键（长按 ≥ 400ms）** | 快进 **10 秒** | `onSeekMs(currentPosition + 10000)` |
| **← 长按又松手（未达 400ms）** | 切 **上一曲** | 长按前的单击被解释为切歌 |
| **→ 长按又松手（未达 400ms）** | 切 **下一曲** | 同上 |
| **BACK** | 有关播放列表则关列表，否则退出播放页 | 视频页 `onKeyEvent` 不吞 BACK，交给 `BackHandler` |

> 若控制栏处于显示状态，← / → 不响应（优先操作控制栏上的焦点控件）。

```kotlin
// TV 视频页方向键：400ms 区分 快进快退 与 切歌（TvPlayerScreen.kt）
if (event.type == KeyEventType.KeyDown) {
    pendingSeekJob?.also { it.cancel() }
    pendingSeekJob = scope.launch {
        delay(400)
        onSeekMs(currentPositionMs + dir * 10_000)   // 长按 → 快进/快退 10s
    }
} else {  // KeyUp 未达 400ms
    pendingSeekJob?.cancel()
    if (dir == LEFT) onPrevious() else onNext()      // 短按 → 切歌
}
```

#### TV · 音乐播放页

| 按键 | 行为 | 底层调用 |
|---|---|---|
| **MEDIA_PLAY_PAUSE** | 播放 / 暂停 | `viewModel.togglePlayPause()` |
| **MEDIA_NEXT** | 下一首 | `viewModel.playNext(isAutoAdvance=true, isManual=true)` |
| **MEDIA_PREVIOUS** | 上一首 | `viewModel.playPrevious(isManual=true)` |
| **MEDIA_FASTFORWARD** | 相对**快进 5%**（进度比例） | `viewModel.seek(cur + 0.05)` |
| **MEDIA_REWIND** | 相对**快退 5%**（进度比例） | `viewModel.seek(cur − 0.05)` |
| **← / → 方向键** | 音乐页**不响应**（由进度条/列表接管） | — |

#### TV · 首页 / 文件列表

| 区域 | 按键 | 行为 |
|---|---|---|
| **列表项（网格/横向）** | ← / → | 焦点左右移动（`moveFocus(Left/Right)`） |
| **列表项（纵向列表）** | ← / → | **每按一次跳前/后 10 项**（带 100ms 延迟）；↑↓ 走 Compose 默认焦点 |
| **播放列表** | ← / → | 跳前/后 10 项；↓ 在最后一项被拦截（防止跳出） |
| **首页 BACK** | 返回 | 按优先级：关播放列表 → 关排序对话框 → 退出 SMB 目录 → 退出本地子目录 → 返回首页分区 |
| **首页关闭按钮** | ↑ | 拦截，防止焦点跳出列表顶部 |

#### TV · 歌词与进度条

| 区域 | 按键 | 行为 |
|---|---|---|
| **歌词列表项** | ENTER / DPAD_CENTER | 跳转到该歌词对应时间点 |
| **歌词第一行** | ↑ | 焦点移到右上角「词」图标 |
| **歌词候选框（在线/内嵌/本地选择）** | ENTER | 请求并选中该歌词（在线会缓存）；BACK 关闭候选框 |
| **歌词候选框** | ↑ / ← / → | 拦截，防止焦点逃逸到底层播放界面 |
| **进度条（获焦）** | ← / → | 持续寻道：先单步，**200ms 后每 50ms** 连续步进；松开提交 |

```kotlin
// 进度条长按连续寻道（HoldToSeek.kt）
KeyDown → 先执行 singleStep，delay(initialDelayMs=200) 后每 periodMs=50 循环 holdStep
KeyUp   → 取消 holdJob 并 onSeekFinished()
```

- 视频页进度条：`singleStep = 0.01`（1%），`holdStep = 0.005`（0.5%/次）
- TV 音乐进度条：`singleStep = 5000/时长`，`holdStep = 2000/时长`（即约 5 秒 / 2 秒步进）

#### TV · 完整按键速查表

| 按键 | 视频播放页 | 音乐播放页 | 播放列表 | 歌词 | 首页列表 | 进度条 |
|:--|:--|:--|:--|:--|:--|:--|
| **DPAD_LEFT** | 长按 400ms 快退 10s；短按切上一曲 | — | 跳前 10 项 | — | 跳前 10 项 / 网格左移 | 持续后退 |
| **DPAD_RIGHT** | 长按 400ms 快进 10s；短按切下一曲 | — | 跳后 10 项 | — | 跳后 10 项 / 网格右移 | 持续前进 |
| **DPAD_UP** | 默认焦点 | 默认焦点 | 默认焦点 | 首行回「词」按钮 | 默认焦点 | — |
| **DPAD_DOWN** | 默认焦点 | 默认焦点 | 末项拦截 | 默认焦点 | 默认焦点 | — |
| **ENTER / CENTER** | — | — | 选中项 | 跳到歌词时间 | 选中项 | — |
| **MEDIA_PLAY_PAUSE** | — | 播放/暂停 | — | — | — | — |
| **MEDIA_NEXT** | — | 下一首 | — | — | — | — |
| **MEDIA_PREVIOUS** | — | 上一首 | — | — | — | — |
| **MEDIA_FASTFORWARD** | — | 快进 5% | — | — | — | — |
| **MEDIA_REWIND** | — | 快退 5% | — | — | — | — |
| **BACK** | 关列表/退出 | 关列表/退出 | 关列表 | 关候选框 | 关列表/返回上级 | — |

> 长按时间差异：视频页方向键长按阈值 **400ms**；进度条长按起点 **200ms**、步进周期 **50ms**。

---

## 工程结构

```
app/src/main/java/com/mediaplayer/plus/
├── MainActivity.kt / MediaPlayerApp.kt   # 入口、Activity 生命周期、真实按键分发绑定
├── player/                               # 播放核心层
│   ├── MediaPlayer.kt                    # ExoPlayer 封装、解码切换、ASS 管线、均衡器
│   ├── SubtitleSupport.kt / AssUtils.kt / AssHandlerRegistry.kt / NormalizingAssMatroskaExtractor.kt
│   ├── LyricParser.kt / YrcParser.kt / QrcLyricDecoder.kt / QrcTripledes.kt / LyricRepository.kt / OnlineLyricProvider.kt
│   ├── AudioLoopbackManager.kt / MetronomeManager.kt / PhaseDetectionManager.kt
│   └── MediaPlayer (Music)Service 相关：MusicService.kt / MusicServiceManager.kt
├── data/                                # 数据与协议层
│   ├── MediaRepository.kt / MediaModels.kt / Id3Metadata.kt
│   └── SmbManager / WebDavManager / FtpManager / SftpManager / SmbHttpProxy / ServerProtocol
├── ui/                                  # Compose 界面层
│   ├── PlayerScreen.kt / PlayerViewModel.kt    # 音频播放页 + 手势
│   ├── FullScreenVideoOverlay.kt / HoldToSeek.kt  # 全屏视频手势 + TV 持续寻道
│   ├── TvHomeScreen.kt / TvPlayerScreen.kt      # TV 首页 + TV 播放页（遥控器按键）
│   ├── KaraokeLyrics.kt / KaraokeTextView.kt / FloatingLyricsService.kt
│   ├── AudioEffectsScreens.kt / PhaseDetectionScreen.kt
│   ├── SearchScreen.kt / LibraryScreens.kt / SettingsScreen.kt
│   └── theme/ Theme.kt / utils/ SmartFontUtils.kt
├── protocol 扩展：LocalFileServer.kt / DlnaManager / DlnaCastService / BluetoothLyricsManager
└── 无障碍：VolumeButtonAccessibilityService / MusicBroadcastReceiver
```

**手势 / 按键代码入口速查**

| 功能 | 文件 |
|---|---|
| 音频页触控手势 | `ui/PlayerScreen.kt`（主区 `576-584`、长按播放键 `1208-1257`、迷你播放器 `1278-1302`） |
| 全屏视频手势 | `ui/FullScreenVideoOverlay.kt`（单击/双击 `411-423`、长按变速 `424-446`、单指亮度音量进度 `449-556`、捏合 `462-479`） |
| TV 遥控器按键 | `ui/TvPlayerScreen.kt`（视频 `131-179`、音乐 `584-595`、列表 `449-475`、歌词 `1334-1586`）、`ui/TvHomeScreen.kt` |
| 长按连续寻道 | `ui/HoldToSeek.kt`（27-76） |

---

## 构建与安装

环境：JDK 11+、Android SDK 35、Android Studio（Hedgehog 及其后版本）。

```bash
# 构建 Debug（含全部 ABI，便于模拟器调试）
./gradlew :app:assembleDebug

# 构建 Release（仅 arm64-v8a + armeabi-v7a，启用 R8 混淆）
./gradlew :app:assembleRelease
```

> Release 因启用 R8 混淆，`proguard-rules.pro` 已包含 JNI / libass 反射相关的 keep 规则；
> 若修改字幕或底层解码配置，请先回归构建并验证大 `APK` 与 ABI。

---

## 致谢与许可

- 字幕渲染：基于 [peerless2012/ass-kt](https://github.com/peerless2012/ass-kt) 与 [peerless2012/libass-android](https://github.com/peerless2012/libass-android) 的原生 libass 封装。
- 播放内核：Android-Architecture 之 [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media)。
- 协议库：`jcifs-ng`、`commons-net`（FTP）、`sshj`（SFTP）。

本项目为本地优先的个人 / 学习型媒体播放器，遵守 MIT 开源协议（具体以仓库内 LICENSE 为准）。
