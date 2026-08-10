# Contributing

感谢你考虑为 Media Player Plus 贡献代码！

## 开发环境

- Android Studio Hedgehog（或更新版本）
- JDK 11+
- Android SDK（API 24 ~ 35）
- Kotlin 2.4.10

## 快速开始

```bash
# 克隆项目
git clone https://github.com/<your-org>/media-player-plus.git
cd media-player-plus

# 创建 local.properties
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 编译
./gradlew assembleDebug
```

## 代码规范

### Kotlin 代码风格

- 使用 Kotlin 官方代码风格（`kotlin.code.style=official`）
- 通过 `./gradlew formatKotlin` 自动格式化

### 命名约定

| 类型 | 规范 | 示例 |
|------|------|------|
| Composable 函数 | PascalCase | `PlayerScreen`, `MediaListRow` |
| 状态变量 | camelCase | `isPlaying`, `currentLyricIndex` |
| StateFlow | 后缀 `Flow` 或语义名 | `playerStateFlow`, `songListFlow` |
| 资源文件 | snake_case | `ic_music_note.xml` |

### 架构约定

- **UI 层** (`ui/`): 仅包含 Compose UI 逻辑，不直接访问数据
- **数据层** (`data/`): 负责媒体扫描、SMB 协议、网络请求
- **播放层** (`player/`): ExoPlayer 封装、字幕、歌词解析
- **状态管理**: 通过 `PlayerViewModel` + `StateFlow` 统一管理，UI 层只订阅

### Compose 开发规范

- 优先使用 `remember` / `rememberUpdatedState` 管理可变状态
- 使用 `LaunchedEffect` 替代 `DisposableEffect` 进行一次性副作用
- 列表使用 `LazyColumn` / `LazyVerticalGrid`，避免深层嵌套
- 主题通过 `PowerampTheme` Composable 提供，不在局部覆盖颜色

## PR 流程

1. Fork 仓库并创建分支：`git checkout -b feature/your-feature`
2. 提交修改并推送：`git push origin feature/your-feature`
3. 创建 Pull Request，说明变更内容和测试方式

## Issue 报告

报告 Bug 时请提供：
- Android 版本和设备型号
- 复现步骤
- 预期行为与实际行为
- 相关日志（如有）

## 安全须知

- 不要提交 `keystore.properties`、`*.jks` 等密钥文件
- 不要提交 `local.properties` 等本地配置
- 不要在代码中硬编码密钥、Token 或密码
