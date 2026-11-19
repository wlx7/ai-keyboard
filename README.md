# Keylo — Flagship Offline AI Keyboard for Android

**完全离线的双擎旗舰 Android 输入法：零网络与零麦克风权限 · 内置 Octagram 毫秒级语法词图（兼容万象 `.gram` 导入）· 内置 `Q8_0` 高精词嵌入端侧大模型 · 全句上下文联想 · 实时自造词学习 · 233 万搜狗级大词库 · 4 万字生僻字手写**

Keylo 在 `AndroidManifest.xml` 中不声明 `android.permission.INTERNET` 与 `android.permission.RECORD_AUDIO`，从操作系统权限机制上杜绝任何键盘输入、个人词典、验证码、剪贴板或图片上传及后台录音的可能，同时通过**「第一层 0ms Octagram N-gram 词图底座 + 第二层跨句 Transformer 大模型」双引擎架构**，在短句零延迟体验与长句跨分句语境消歧/续写上双向对齐并超越主流语法模型方案。

---

## 下载安装（Release v3.2.0）

前往 [**Releases 页面**](https://github.com/wlx7/ai-keyboard/releases/latest) 按需选择下载：

1. **🌟 `keylo-3.2.0-arm64.apk`（双擎完整旗舰版，主力推荐）**
   - 直接内嵌 **`Keylo-460K Octagram 语法词图库`（`5.0 MiB`）** + **`Keylo-285M v2` 端侧专属大模型（`Q8_0` 高精词嵌入，约 `185 MiB`）** + **9 万词头多词/诗词上下句联想库** + **233 万条中文大词库** + **4 万字生僻字手写**，安装即享完整双引擎体验，无需额外配置（包名兼容旧版，可直接覆盖升级，彻底移除冗余离线语音体积立减 ~35 MiB）。
2. **⚡ `keylo-3.2.0-lite-arm64.apk`（精简外挂版，内置 Octagram 语法库，仅 `52 MiB`）**
   - 仍完整内置 `Keylo-460K Octagram` 毫秒级语法模型、9 万词头多词联想、233 万词库与全字集手写；可按需在设置页导入外部 `.gguf` 大模型或完整版 `wanxiang-lts-zh-hans.gram`。

---

## v3.2 核心特性一览

- **双擎全句上下文「输入后联想」（`ChinesePredictor v2` + `Llm.nativePredict`）**
  - **第一层（`<0.05 ms` 9 万词头多词 N-gram + 千古名句/成语对句库）**：支持 `2~7` 字多词联合上文（如 `今天天气 → 真不错`、`这个问题 → 怎么解决`）与古诗文名句上下句（如 `清风徐来 / 清风徐来， → 水波不兴`、`远上寒山石径斜， → 白云生处有人家`、`落霞与孤鹜齐飞 → 秋水共长天一色`）；严禁将多字词尾降级拆成单字黏着词素或被偶发单字历史抢占。
  - **第二层（`~10 ms` 端侧大模型 48 字跨分句续写 `✦`）**：直接复用打字阶段 `seq 0` KV Cache，感知整句远距语义输出精准续写词（如 `这份代码有个漏洞，需要马上 → ✦ 修改 / ✦ 修复`、`肚子好饿，我想点一份 → ✦ 早餐 / ✦ 大餐`）。

- **双擎智能核心：Octagram 0ms 词图底座 + `Keylo-285M v2` 跨句大模型**
  - **第一层（0ms 敲击级语法词图）**：原生激活 `librime-octagram` 维特比词图语法引擎，内置 46 万条高置信双词/三词搭配、成语与句尾边界库（`keylo-zh-hans.gram`，如 `qiushuishizhi` 敲完瞬间直出 `秋水时至`，无需等大模型异步推理），并修复 UTF-8 长编码栈溢出隐患；设置页同时支持**一键校验导入外部完整版 `wanxiang-lts-zh-hans.gram`**。
  - **第二层（跨句深层语义重排）**：全新重蒸馏 **`Keylo-285M v2`**（剔除百科词条定义句偏置，词嵌入升级为近无损 8-bit `Q8_0` 量化），融合 `llm_jni.cpp` 句界锚点与 25 亿字频贝叶斯稀有字先验保护，彻底根除「泅水是指」类百科生僻字误推，专注跨逗号、跨分句长上文同音消歧。

- **全链路实时自学习词库与上下文零样本学词（`UserZhLexicon` + `HanziPinyin`）**
  - **分段组词与单字连打记忆**：无论在连拼中逐段选字（如 `jinse` 选 `禁` + `色`、或在整句 `woxihuanjinsede` 中选字自动剥离虚词学得 `禁色`），还是分开逐字选字（`jin` 选 `禁` 后紧接 `se` 选 `色`），均立即写入本地持久化自学习词库，下次输入 `jinse`、简拼 `js`、前缀 `jins` 或整句 `jinsezheshouge` 直接置顶首选（`✦ 禁色这首歌`）。
  - **上下文零样本同音感知**：只要当前输入框或近期输入记忆中出现过新词/歌名/专有名词（如《禁色》《达明一派》），首次输入 `jinse` 即刻零样本提取置顶 **`✦ 禁色`** 并注入端侧大模型重排候选池；手写及无快捷码个人词条自动建立多音字拼音索引；**长按任意候选词即可一键忘词**。
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
bash tools/fetch_rime_sources.sh
bash tools/build_rime_data.sh

# 运行单元测试并构建内置 AI 旗舰版与精简版 APK
./gradlew testDebugUnitTest assembleRelease
./gradlew assembleRelease -PembedAi=false
```

## 开源许可

本应用因内置雾凇拼音词库（`rime-ice`，GPL-3.0），整体遵循 **GPL-3.0** 协议开源（各组件详细来源与许可参见 [ROADMAP.md](ROADMAP.md)）。
