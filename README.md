# Cue Factory（Cue 工厂）

Android 本地 **CUE 整轨切分** 工具：扫描外置/内嵌 CUE，按样本数精确切 FLAC，支持 Hybrid（首尾残帧重编码 + 中间帧拷贝）。

> **⚠️ Vibe coding disclaimer**  
> 本仓库 **全程 vibe coding**（对话式 AI 结对生成）。  
> **不保证** 代码质量、安全性、兼容性、长期维护或任何特定用途适用性。  
> 可能存在隐蔽 bug、错误假设、过度工程或直接不能用的路径。  
> **自担风险使用。** 发现问题请开 issue；不保证会修。

**License:** [MIT](LICENSE)（项目源码）  
**Bundled FFmpeg:** 第三方动态库，遵循 [FFmpeg 自身许可](https://ffmpeg.org/legal.html)（本仓库预编译 arm64 轻量构建，仅作本地切分用途）。

---

## Features

- 精准匹配外置 / 内嵌 CUE（歧义不自动切）
- 默认 **Hybrid** 切分策略  
  - 对齐 → FrameCopy（帧号重编号，播放器友好）  
  - 不对齐 → HybridBoundary  
  - 失败 → 整轨 LosslessReencode  
- 样本数与 CUE 区间严格一致（不做有损）
- 多任务队列、可调并发
- 中文 UI、命名模板预设、多编码 CUE 探测
- 私有 slim FFmpeg（APK `jniLibs`，不依赖公共 `native/` 目录）
- 需要 Android 11+ **「所有文件访问」** 才能扫 Music 等路径

**不做播放器。**

---

## Build (Termux aarch64 / Linux)

```bash
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-17-openjdk}"
export PATH="$JAVA_HOME/bin:$HOME/bin:$PATH"

printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties

./gradlew :app:assembleDebug :core:testDebugUnitTest
# optional release (unsigned — sign yourself)
./gradlew :app:assembleRelease
```

| Variant | applicationId |
|---------|-----------------|
| debug | `com.cuefactory.app.debug` |
| release | `com.cuefactory.app` |

Install debug example:

```bash
install-apk app/build/outputs/apk/debug/app-debug.apk \
  com.cuefactory.app.debug/com.cuefactory.app.MainActivity
```

---

## On-device layout

App **cannot** read Termux `$HOME`. Use shared storage:

```text
/storage/emulated/0/CueFactory/
  samples/          # your FLAC + .cue
  split-out/        # default output
```

Grant **All files access** for the package if scan returns empty on Android 11+.

---

## Docs

Planning docs, unit tests, and local samples are **not** published.
This repository only contains sources needed to build the APK.


## Project status

Personal / experimental. Built for local use on Android (Termux + real device).  
Expect rough edges. Contributions welcome if you enjoy chaos.

```
MIT · AS IS · vibe-coded · no warranty
```
