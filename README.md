# Cue Factory

Android 本地 CUE 整轨 FLAC 切分工具。

## Disclaimer

- **全程 vibe coding**（AI 对话生成），**不保证**质量、安全、兼容或维护。
- **AS IS，自担风险。** 源码 [MIT](LICENSE)；内置 arm64 slim FFmpeg 遵循 [FFmpeg 许可](https://ffmpeg.org/legal.html)。

## Hybrid 切分：研究结论

FLAC 可独立解码的最小单位是 **frame**，不是任意 PCM 样本。CUE 切点若落在帧内，就无法「只拷贝压缩字节」又保持样本数精确。

CD 扇区 CUE 常见切点是 588 samples（44.1 kHz），而 FLAC 常见 block 为 4096；Hi-Res（如 48 kHz / 24-bit）同样经常不对齐。这是格式几何，不是实现细节。

因此 **不存在** 同时满足下列全部条件的普适方案：

1. 完全不重编码任何音频  
2. 输出样本数与 CUE 区间 **绝对一致**  
3. 结果为合法、可独立播放的 FLAC  

业界常见取舍：

| 路线 | 代表 | 重编码 | 样本精确 | 说明 |
|------|------|--------|----------|------|
| 整轨解码再压 | `shnsplit` + flac、ffmpeg `atrim` | 整轨 | 是 | 稳、慢、普适 |
| 仅整帧拷贝 | stream-copy 脚本 | 否 | 仅边界对齐时 | 常见 CD CUE 会失败 |
| 吸附到帧界 | [flac-tracksplit](https://github.com/boinkor-net/flac-tracksplit) | 否 | **否**（可多吞数毫秒） | 用时间误差换零重编码 |
| 专用 CD 工具 | [CUETools](http://cue.tools/wiki/CUETools) | 视流程 | CD 语境 | 基本限 16-bit / 44.1 kHz stereo |
| **Hybrid（本项目）** | 首尾残帧重编码 + 中间帧拷贝 | **仅边界** | **是** | 速度与精确的折中 |

本应用默认策略：

```text
CUE 两端都在 frame 边界  → FrameCopy（压缩帧拷贝 + 重写帧号/STREAMINFO）
CUE 不对齐（常见）      → HybridBoundary（只重编码首尾残帧，中间帧原样拷贝）
Hybrid 失败             → 整轨 LosslessReencode（ffmpeg atrim，仍无损）
```

补充结论（实现时踩过的坑）：

- 裸 `ffmpeg -c:a copy` **不能**当安全切分器：PCM 可能对，但 STREAMINFO 的 `total_samples` / MD5 仍是整碟，官方校验会挂。  
- 从专辑中部拷出的帧必须 **重编号** 样本/帧序号；否则部分播放器打不开，Poweramp 等更宽松。  
- 边缘重编码应对「覆盖该残帧的几帧」做小窗 atrim，避免每轨都从整碟头解码。

## 参考

- [FLAC format specification](https://xiph.org/flac/format.html)  
- [flac-tracksplit](https://github.com/boinkor-net/flac-tracksplit) — 零重编码、帧界结束（样本可不精确）  
- [shntool](http://www.etree.org/shnutils/shntool/) / cuetools 常见流水线 — 解码后重编码  
- [CUETools](http://cue.tools/wiki/CUETools) — CD 精度工具链（非通用 Hi-Res）  
- [FFmpeg](https://ffmpeg.org/) — 本项目用于边界/整轨无损 FLAC 重编码  

## Build

**仅在 Termux（Android aarch64）上验证过可构建。**  
其他平台（桌面 Linux / macOS / Windows / Android Studio 等）**未测试**，请自行探索环境与依赖。

```bash
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

Android 11+ 扫描本地路径通常需要 **「所有文件访问」**。
