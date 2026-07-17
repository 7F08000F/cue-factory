# Cue Factory

Android 本地 CUE 整轨 FLAC 切分工具（Hybrid：首尾残帧重编码 + 中间帧拷贝）。

## Disclaimer

- **全程 vibe coding**（AI 对话生成），**不保证**质量、安全、兼容或维护。
- **AS IS，自担风险。** MIT 许可，见 [LICENSE](LICENSE)。
- 内置 arm64 slim FFmpeg 为第三方组件，遵循 [FFmpeg 许可](https://ffmpeg.org/legal.html)。

## Build

**仅在 Termux（Android aarch64）上验证过可构建。**  
其他平台（桌面 Linux / macOS / Windows / Android Studio）**未测试**，请自行探索环境与依赖。

```bash
# Termux 示例
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-17-openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"

printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # unsigned，自行签名
```

| 变体 | applicationId |
|------|----------------|
| debug | `com.cuefactory.app.debug` |
| release | `com.cuefactory.app` |

Android 11+ 扫描本地路径需要系统设置中的 **「所有文件访问」**。

## Scope

仓库只放构建 APK 所需源码。无单元测试、无 agent/规划文档、无样片。
