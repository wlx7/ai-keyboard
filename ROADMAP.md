# 灵犀输入法 · 路线图

完全离线的 Android 输入法：大词库 + 本地 AI，追求云输入法的聪明，界面对标 iOS 键盘。

## 硬性原则

- **不申请 INTERNET 权限**。系统层面无法联网，隐私靠机制保证，而不是靠承诺。词库更新用离线包导入。
- 安装后总占用 **≤ 1 GB**；AI 模型按需加载（mmap），不常驻内存。
- **耗电不明显**：神经网络只在需要时运行（长句、停顿时），短词只查词库。
- 候选栏回归测试：**任何数量的候选词都必须可点**（CyIME 在只有两个英文候选时第二个点不动，见 `CandidateLayoutTest`）。

## 架构

```
┌──────────── ImeService ────────────┐
│ Scene（输入框类型：邮箱/网址/密码/数字/电话） │
│   ↓ 决定布局、纠错、学习、回车键文字        │
│ KeyboardView ── CandidateBar ── EmojiPanel │
│   ↓                                        │
│ 引擎层                                      │
│  ├─ EnglishEngine   （M1，自研，Kotlin）      │
│  ├─ RimeEngine      （M2，librime JNI）       │
│  │    全拼 / 双拼 / 五笔 / 仓颉 / 注音 / 笔画   │
│  ├─ Handwriting     （M3，ONNX）             │
│  └─ AI Rescorer     （M4，小型 LM，int8/int4） │
└────────────────────────────────────┘
```

## 里程碑

### M1 键盘外壳 + 英文（已完成，可安装）
- iOS 风格按键、按键气泡、长按备选字符（第一排长按出数字，元音长按出重音字母）
- 空格长按或滑动 = 触控板移动光标；删除键长按加速连删；多指滚动输入
- 英文：23.3 万词（OpenSubtitles + Google Web 词频），前缀补全，按键盘相邻关系纠错，
  缩写补撇号（dont→don't、im→I'm），常见拼写错误表，学习用户的词，退格撤销自动纠错
- 场景优化：邮箱键盘带 `@` `.` 并联想常用邮箱域名；网址键盘带 `/` `.com`（长按出 .cn .net 等）；
  密码框不联想、不学习；数字框和电话框用专用数字键盘；回车键按场景显示“发送/搜索/前往/下一项/完成”
- 表情面板 1900+ 个，分类浏览加“常用”；只显示系统字体能渲染的表情
- 自动大写、连按两下空格输入句号、跟随系统深浅色

### M2 中文：librime（已完成，v0.2.0）
- librime 1.16.1（含 lua、octagram、predict 插件），使用 fcitx5-android/prebuilt 的 NDK 28 静态库，
  自写 JNI 桥（`app/src/main/cpp`）。依赖库用 `tools/fetch_prebuilt.sh` 获取，版本固定
- 9 个方案：雾凇全拼；小鹤、自然码、微软、搜狗、智能 ABC、紫光双拼；五笔 86（极点）；仓颉五代。
  在设置页切换
- 整句输入、部分选词、内联拼音、中文标点（键盘上有 ，。，长按出 、；：？！）、
  展开式候选面板（最多 300 个）、用户词频学习（存在本地 userdb）
- 首次启动自动部署词库（模拟器约 16 秒），部署期间字母原样上屏并提示“正在准备中文词库…”
- 数据由 `tools/build_rime_data.sh` 组装，补丁：五笔补上 hamster 词库；仓颉的反查改用雾凇，
  标点改用 default 预设，关闭 essay 预设词汇

### M2.x 完善（v0.3.0 已完成）
- 九宫格拼音：专用 3×3 键盘。左栏在输入时列出可能的拼音（如 64 → ni、mi），点选即可锁定音节；
  不输入时左栏是常用标点。重输、分词、选定键齐全，输入框内显示拼音而不是数字
- 五笔、仓颉按键角标显示字根
- 简繁：设置里的“输出繁体字”同时作用于全拼、双拼、九宫格、五笔和仓颉
  （打包了 OpenCC 文本词典，Apache-2.0）
- 首次部署时候选栏提示“正在准备中文词库（仅首次）…”

### M2.x 仍待完善
- 五笔 98、注音
- 万象语法模型（octagram）单独测试。它在你手机上闪退，需要 logcat 定位，先不默认开启
- 预先在电脑上编译词库，免去首次部署等待

### M3 手写
- 中英文手写识别（ONNX Runtime，可选开源的 ochwpro 模型，MIT 协议），支持连写

### M4 AI 层（第一版已接入，v0.4.0）
- 引擎：llama.cpp（MIT），以独立的 `libaikb_llm.so` 存在；没有模型时输入法照常工作
- 模型：Qwen3-0.6B-Base Q4_K_M（约 400 MB），在设置页从手机存储导入；应用本身不联网
- 原理：
  1. librime 只给出一个整句候选，所以先在隐藏的第二个会话里展开：依次试选前几个词首，
     读取 librime 对剩余部分的最佳转换，再往下展开一层，得到约 8 个整句备选。
     会补全整句的候选只读取、不选中，避免把没打过的词写进用户词库
  2. 语言模型结合光标前的文字，给每个备选打分 log P(句子 | 上文)。
     所有备选共有的开头并入上文、只计算一次（带 KV 缓存），只给不同的部分打分
  3. 总分 = 模型分 − α·ln(1 + librime 排名) + 近期短语加分；
     如果和 librime 的首选不同，就作为第一个候选显示（空格直接上屏）
- 省电：拼音至少 3 个音节、且停顿 150 ms 后才运行；继续打字会立刻中止后台运算，不卡键盘
- 自适应：选了 AI 的句子 α 降低（更信任 AI），选回 librime 的首选 α 升高；
  α 存在本地。本次会话打过的短语有加分（只在内存中）
- 评测：`tools/eval/sentences.txt`（167 题，含同音歧义和对话上文），
  调试版里用 `adb shell am broadcast -n app.aikb.ime/.EvalReceiver` 在真机或模拟器上运行
  （运行时让应用保持在前台，否则 Android 会冻结后台进程）

| 2026-11-18，模拟器 x86_64 | 整句首选正确率 |
|---|---|
| 只用 librime（雾凇） | 76.6% |
| AI，不读上文 | 约 80% |
| AI，读上文（α=3，阈值 1.0） | **83.2–83.8%**（错误率下降约 30%） |
| 上限（正确句子在备选里） | 85.6% |

  - 只要拼音不少于 3 个音节，AI 在评测中没有改错过任何一个 librime 原本答对的句子
  - 每次停顿的耗时（模拟器，v0.4.1 优化后）：展开 59 ms，模型打分 349 ms；
    中位数 375 ms，90% 在 574 ms 以内（v0.4.0 时约 2.6 秒）。真机待测

**v0.4.1 提速记录（评测正确率不变）**

| 优化 | 效果 |
|---|---|
| llama.cpp 始终用 -O3 编译（调试版之前是 -O0，基准数据严重失真） | 打分 1773 → 241 ms |
| librime 展开改用 `set_input` 一次设好输入，不再逐键回放；展开会话关闭 emoji 和简繁滤镜 | 展开 564 → 53 ms |
| 备选的共同开头并入上文缓存，共同结尾只保留 2 个 token | 打分的 token 数约减半 |
| log-sum-exp 改用单精度、8 路累加，便于编译器向量化 | 每次约 20–30 ms |
| 新输入会立即中止旧的计算（llama.cpp 中止回调和代数检查），过时任务直接跳过 | 不再积压任务，也不浪费电量 |

### M4.x 待做
- 速度：在真机上测量；考虑更小的模型（约 1 亿参数）或 ARM 权重重排
- 英文：下一个词预测、结合上下文的纠错
- 可选：充电时用 LoRA 按个人习惯微调（默认关闭）

### M5 扩展
- 九宫格、滑行输入、离线词库包导入
- 其他语言工作量评估：
  - 德、法、西、意、葡、俄：**小**。复用英文引擎，换词库（FrequencyWords 有现成词频表）和布局（QWERTZ、AZERTY、ЙЦУКЕН）
  - 韩文：**中小**。需要谚文组字自动机
  - 日文：**中**。用 librime 的 jaroomaji 方案

## 空间预算（目标）

| 组件 | 大小 |
|---|---|
| 英文词库 | 5 MB（M1） |
| 中文词库（雾凇、五笔、仓颉） | 安装包内约 20 MB，部署后约 75 MB（v0.2） |
| 中文语法模型 | ~300 MB（可选） |
| AI 重排序模型 | 397 MB（Qwen3-0.6B Q4_K_M，可选，单独导入） |
| 手写模型 | ~20 MB |
| 程序本体 | ~20 MB |

## 构建

需要 JDK 17、Android SDK 35、NDK r27c（本机已装在 `D:\dev`）。

```
set JAVA_HOME=D:\dev\jdk17
gradlew assembleDebug        # 生成 app/build/outputs/apk/debug/app-debug.apk
gradlew testDebugUnitTest    # 引擎与候选栏测试
```

首次构建前需要准备（这些都不提交到仓库，脚本里固定了版本）：

```
bash tools/fetch_prebuilt.sh        # librime 及依赖的 Android 静态库 → third_party/prebuilt
bash tools/fetch_llama.sh           # llama.cpp 源码 → third_party/llama.cpp
bash tools/fetch_rime_sources.sh    # 雾凇、五笔、仓颉、OpenCC 原始数据 → tools/data
bash tools/build_rime_data.sh       # 组装 app/src/main/assets/rime
```

重新生成英文词库和表情数据（原始数据放在 `tools/data`）：

```
java tools/BuildEnglishDict.java tools/data app/src/main/assets/dict/en.dict
java tools/BuildEmoji.java tools/data/emoji-test.txt app/src/main/assets/emoji.txt
```

## 数据来源和许可

- OpenSubtitles 2018 词频（hermitdave/FrequencyWords）：CC BY-SA 4.0
- Google Web Trillion Word 词频（norvig.com/ngrams）
- Unicode emoji-test.txt：Unicode 许可
- 雾凇拼音 rime-ice：GPL-3.0；五笔 86 极点 rime-wubi86-jidian：MIT；仓颉 rime-cangjie：LGPL-3.0
- librime：BSD-3；Android 静态库来自 fcitx5-android/prebuilt
- 因为使用了雾凇拼音（GPL-3.0），本应用整体按 GPL-3.0 发布
