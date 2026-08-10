# Media Player Plus

一个功能全面的 Android 本地音视频播放器，基于 Jetpack Compose 构建，支持局域网 SMB 浏览、DLNA 投屏、蓝牙歌词推送等高级功能。

## 截图

> 待补充

## 功能特性

### 播放引擎
- 基于 **ExoPlayer (Media3)**，支持音频/视频流式播放
- 集成 **FFmpeg** 扩展解码器，覆盖 AC3 / E-AC3 / DTS / FLAC / Opus / Vorbis 等编码
- 支持 HLS、DASH、SmoothStreaming、RTSP 等流媒体协议
- 播放倍速调节、音量控制、循环/随机播放
- 自实现 **ID3v2** 元数据解析（含中文乱码修正）
- **Libass** 原生渲染 ASS/SSA 特效字幕
- 支持手动导入外挂字幕（.srt / .ass / .ssa / .vtt）

### 本地媒体库
- 自动扫描本地音频/视频文件
- 按歌手、专辑、文件夹分页浏览
- Poweramp 风格快速滚动条
- 支持自定义扫描文件夹（SAF 选择器）

### 局域网 SMB
- 支持 SMB1 ~ SMB3.1.1 协议
- 匿名（Guest）与用户名密码双认证模式
- LAN 局域网自动扫描服务器（端口 445/139）
- 收藏夹管理、NetBIOS 设备名解析

### DLNA / UPnP 投屏
- 自动发现局域网 DLNA 设备
- 通过本地 HTTP 代理（NanoHTTPD）推送文件
- 支持投屏控制（播放/暂停/停止/跳转/音量）
- 专辑封面推送

### 蓝牙歌词
- 通过 AVRCP PassThrough 向蓝牙耳机推送歌词（API 28+）
- MediaSession 回退方案（兼容性保障）
- 双语歌词自动拆分（原文/翻译）

### 音频效果
- 系统原生 **5 段均衡器**（21 种预设）
- 内置歌词过滤（版权/翻译标记）

### UI / 交互
- 100% **Jetpack Compose** + **Material 3**
- Poweramp 风格深色主题
- 3D 翻页式专辑封面
- 波形进度条可视化
- 悬浮歌词窗口（SYSTEM_ALERT_WINDOW）
- 画中画（PiP）模式
- 电视（TV）模式：横屏播放 + 专用首页
- 视频手势：双击全屏、拖拽切歌、上拉播放列表

## 技术架构

```
┌──────────────────────────────────────────────────────┐
│                  MainActivity                        │
│  (权限 / Intent / TV 模式 / PiP / 音频路由监听)        │
│        │              │              │               │
│  PlayerScreen    TvHomeScreen     TvPlayerScreen     │
│  (手机模式)      (TV 首页)        (TV 播放器)         │
└───────┬───────────┬───────────────┬─────────────────┘
        │           │               │
   ┌────▼────────────▼──────────────▼────┐
   │         PlayerViewModel              │
   │     (StateFlow 状态管理)              │
   └────┬───────────────────────────────┘
        │
   ┌────▼─────────────────────────────────┐
   │        MediaPlayer (ExoPlayer)        │
   │  + Equalizer + ID3 Parser + Lyrics   │
   │  + Libass Subtitle + FFmpeg Decoder   │
   └────┬─────────────────────────────────┘
        │
   ┌────▼──┐  ┌───────────┐  ┌─────────┐  ┌───────────┐
   │Music  │  │ SmbManager│  │ Dlna    │  │Bluetooth  │
   │Service│  │ (SMB/CIFS)│  │ Manager │  │Lyrics     │
   │       │  │           │  │(UPnP/DL)│  │Manager    │
   └───────┘  └───────────┘  └─────────┘  └───────────┘
```

### 核心模块

| 模块 | 文件 | 职责 |
|------|------|------|
| 播放引擎 | `player/MediaPlayer.kt` | ExoPlayer 封装、ID3 解析、字幕、均衡器、歌词 |
| 音乐服务 | `MusicService.kt` | 前台服务、MediaSession、通知栏控制 |
| 媒体库 | `data/MediaRepository.kt` | MediaStore 扫描、音频/视频索引 |
| SMB 管理器 | `data/SmbManager.kt` | SMB 连接、局域网扫描、文件浏览 |
| DLNA 管理器 | `DlnaManager.kt` | UPnP 发现、投屏控制、HTTP 代理 |
| 蓝牙歌词 | `BluetoothLyricsManager.kt` | AVRCP/MediaSession 双通道歌词推送 |
| 浮窗歌词 | `ui/FloatingLyricsService.kt` | 桌面悬浮歌词前台服务 |
| 主界面 | `ui/PlayerScreen.kt` | 手机模式 Poweramp 风格 UI |
| TV 界面 | `ui/TvHomeScreen.kt` / `TvPlayerScreen.kt` | 电视模式界面 |
| 搜索 | `ui/SearchScreen.kt` | 本地搜索 + SMB 浏览 |
| 设置 | `ui/SettingsScreen.kt` | 主题、歌词、解码器设置 |
| 主题 | `ui/theme/Theme.kt` | Poweramp 风格深色主题 |

## 依赖库

| 库 | 用途 |
|----|------|
| [ExoPlayer (Media3)](https://github.com/androidx/media) 1.5.1 | 音视频播放引擎 |
| [Jellyfin FFmpeg Decoder](https://github.com/jellyfin/jellyfin-media3-extensions) | 扩展音频解码器 |
| [Libass](https://github.com/libass/libass) | ASS/SSA 字幕渲染 |
| [jcifs-ng](https://github.com/agonzalezro/jcifs-ng) | SMB/CIFS 协议 |
| [upnpcast](https://github.com/ajalt/upnpcast) | DLNA/UPnP 投屏 |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 本地 HTTP 文件服务器 |
| [Coil](https://github.com/coil-kt/coil) | 异步图片加载 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | 声明式 UI |
| [Material 3](https://m3.material.io/) | 设计系统 |
| Bouncy Castle | SMB NTLM 认证 |

## 系统要求

- **最低 SDK**: Android 7.0 (API 24)
- **目标 SDK**: Android 15 (API 35)
- **编译 SDK**: 35
- **JDK**: 11+
- **Kotlin**: 2.4.10
- **AGP**: 9.3.1

## 构建

### 环境准备

1. 安装 [Android Studio](https://developer.android.com/studio)（推荐 Hedgehog 或更新版本）
2. 安装 Android SDK，最低 API 24，目标 API 35
3. 创建 `local.properties`，设置 SDK 路径：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 编译运行

```bash
# 使用 Gradle Wrapper 编译
./gradlew assembleDebug

# 或直接在 Android Studio 中打开项目并运行
```

### 发布签名（可选）

如需构建已签名的 release APK，创建 `keystore.properties`：

```properties
keyAlias=your_alias
keyPassword=your_key_password
storeFile=release.jks
storePassword=your_store_password
```

将 `release.jks` 放在项目根目录下，然后运行：

```bash
./gradlew assembleRelease
```

> **注意**: `keystore.properties` 和 `*.jks` 已被加入 `.gitignore`，不会被提交到版本控制。

## 权限说明

| 权限 | 用途 |
|------|------|
| `READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO` | 读取本地媒体文件 |
| `INTERNET` | 网络访问（SMB / DLNA / 在线字幕） |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 前台音乐播放 |
| `SYSTEM_ALERT_WINDOW` | 悬浮歌词窗口 |
| `BLUETOOTH_CONNECT` | 蓝牙歌词推送 |
| `WAKE_LOCK` | 播放时保持 CPU 唤醒 |

## 目录结构

```
Media Player/
├── app/
│   ├── build.gradle                 # 模块构建配置
│   ├── src/main/
│   │   ├── java/com/mediaplayer/plus/
│   │   │   ├── data/                # 数据层（SMB / 媒体库 / 模型）
│   │   │   ├── player/              # 播放引擎（ExoPlayer / 字幕 / 歌词）
│   │   │   ├── ui/                  # UI 层（Compose 界面）
│   │   │   │   └── theme/           # 主题
│   │   │   ├── utils/               # 工具类
│   │   │   ├── MainActivity.kt      # 应用入口
│   │   │   ├── MediaPlayerApp.kt    # Application 初始化
│   │   │   ├── MusicService.kt      # 前台音乐服务
│   │   │   ├── DlnaManager.kt       # DLNA 投屏
│   │   │   ├── BluetoothLyricsManager.kt
│   │   │   └── ...
│   │   └── res/                     # 资源文件
│   └── proguard-rules.pro           # 混淆规则
├── gradle/
│   ├── libs.versions.toml           # 版本目录（依赖版本集中管理）
│   ├── toolchains.toml              # 工具链配置
│   └── wrapper/                     # Gradle Wrapper
├── build.gradle                     # 根构建配置
├── settings.gradle                  # 项目设置
└── gradle.properties                # Gradle 全局属性
```

## 开发指南

详见 [CONTRIBUTING.md](CONTRIBUTING.md)

## License

待添加
