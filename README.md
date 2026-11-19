# Keylo — Flagship Offline AI Keyboard for Android

**完全离线的旗舰级 Android 智能输入法：零网络权限 · 实时自造词与上下文学词 · 兼容 Gboard 词库双向导入导出 · 233 万搜狗级大词库 · 内置端侧专属 AI 大模型 · 4 万字大画布手写 · 1Password 内联填充**

Keylo 在 `AndroidManifest.xml` 中不声明 `android.permission.INTERNET`，从操作系统权限机制上杜绝任何键盘输入、语音、个人词典、验证码、剪贴板或图片上传的可能，同时在本地词库容量、实时自学习记忆、端侧 AI 上下文选句、全字集手写、克制精准的智能纠错与个性化体验上全面对齐主流旗舰输入法。

---

## 下载安装（Release v2.5.0）

前往 [**Releases 页面**](https://github.com/wlx7/ai-keyboard/releases/latest) 按需选择下载：

1. **🌟 `keylo-2.5.0-arm64.apk`（内置 AI 完整旗舰版，主力推荐）**
   - 直接将专属蒸馏模型 **`Keylo-285M Q4_0`（`154.5 MiB`）**、**233 万条中文大词库**、**23.3 万英文词库**、**4 万字全生僻字手写引擎** 与 **`sherpa-onnx` 流式离线语音模型** 全部内嵌于安装包中，**安装即享完整 AI 选句，无需手动下载或导入模型文件**（包名兼容旧版，可直接覆盖升级）。
2. **⚡ `keylo-2.5.0-lite-arm64.apk`（精简外挂版，约 `86.8 MiB`）**
   - 包含除内置 `.gguf` 外的全部词库、离线语音与全生僻字手写功能；可按需下载 `.gguf` 模型在设置中导入。

---

## v2.5 核心特性一览

- **全链路实时自学习词库与上下文零样本学词（`UserZhLexicon` + `HanziPinyin`）**
  - **分段组词与单字连打记忆**：无论在连拼中逐段选字（如 `jinse` 选 `禁` + `色`、或在整句 `woxihuanjinsede` 中选字自动剥离虚词学得 `禁色`），还是分开逐字选字（`jin` 选 `禁` 后紧接 `se` 选 `色`），均立即写入本地持久化自学习词库，下次输入 `jinse`、简拼 `js`、前缀 `jins` 或整句 `jinsezheshouge` 直接置顶首选（`✦ 禁色这首歌`）。
  - **上下文零样本同音感知**：只要当前输入框或近期输入记忆中出现过新词/歌名/专有名词（如《禁色》《达明一派》），首次输入 `jinse` 即刻零样本提取置顶 **`✦ 禁色`** 并注入端侧大模型重排候选池；手写、语音及无快捷码个人词条自动建立多音字拼音索引；**长按任意候选词即可一键忘词**。
- **Gboard 兼容个人词典与词库导入导出**
  - 原生兼容 **`# Gboard Dictionary version:1` 标准 `.zip`（内含 `dictionary.txt`）双向导入导出**（含自学习中文词组同步备份），同时支持导入 Rime `.dict.yaml` / `.userdb.txt` 与纯文本词表。
- **克制精准的双语纠错与端侧 AI 选句**
  - 当拼音本身已构成完整合法词组（如 `bu dong → 不懂 / 不动`）或正在输入声母半途（如 `jin ri ch`）时，**绝不强插邻键噪声或提前篡改候选**；仅在真正出现断音节误触（`nihso → 你好`、`zhognguo → 中国`）或高置信通顺整句（`远上寒山石径斜，+ baiyunshengchuyourenjia → 白云深处有人家`）时智能介入。
- **WebView / 富文本下划线防卡死退格引擎**
  - 针对 Chrome WebView 跨行重排、系统截图弹窗切换焦点导致的预编辑下划线残留专门加固，`⌫` 退格自动清理孤立下划线组合态并精准逐码点删除，绝不卡住。
- **中英文统一顺手布局 + 剪贴板独立总开关 + 即发即消图片气泡**
  - 英文键盘底栏同步配备独立 `,` 与 `.` 键（长按支持 `; : ' " ? !`）；剪贴板支持一键总开关彻底停用监听与入口；顶栏最新截图/照片发送后立即永久消隐（自动过滤微信等应用重存的回声副本）。
- **所见即所得实时键盘调参预览 + 微信键盘辅助输入全对齐 + 1Password 内联填充 + 4 万字生僻字手写**。

---

## 从源码构建

环境要求：JDK 17、Android SDK 35、NDK `28.0.13004108`、CMake `3.31.6`。

```bash
# 首次拉取第三方预编译库与词库源码
bash tools/fetch_prebuilt.sh
bash tools/fetch_llama.sh
bash tools/fetch_sherpa.sh
bash tools/fetch_rime_sources.sh
bash tools/build_rime_data.sh

# 运行单元测试并构建内置 AI 旗舰版与精简版 APK
./gradlew testDebugUnitTest assembleRelease
./gradlew assembleRelease -PembedAi=false
```

## 开源许可

本应用因内置雾凇拼音词库（`rime-ice`，GPL-3.0），整体遵循 **GPL-3.0** 协议开源（各组件详细来源与许可参见 [ROADMAP.md](ROADMAP.md)）。
