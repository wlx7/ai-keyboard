# 灵犀输入法 · 专属小语言模型剪枝与蒸馏管线

本目录提供一套在单张消费级 6 GB 显卡（如 RTX 3060 Laptop）上即可跑通的离线输入法专属语言模型压缩与知识蒸馏流水线，生成的 `.gguf` 模型无需修改任何 C++ 推理代码即可直接导入灵犀输入法运行。

## 核心技术设计

1. **100% 中文无损 BPE 祖先闭包词表裁剪（`151,936 → 32,768`）**
   - `Qwen3-0.6B-Base` 词表中共有 `28,020` 个包含汉字、全角字符与中文标点的词元，加上 256 个字节回退基元及完整 BPE 合并树祖先共 `28,919` 个词元。
   - 裁剪至 `32,768` 词元（剩余槽位填入最高频英文/数字/ASCII 词元）后，**任意中文多字词与成语的分词结果与原版千问 100% 一致**（彻底消除部分多字词被拆碎导致的对数似然长度偏置），同时砍掉 **78.4%** 的 `lm_head` 投影与 `logSumExp` 词表归一化开销。

2. **贪心反向消元 KL 选层（Greedy KL-Optimal Layer Pruning）**
   - 相比孤立统计各层余弦变化的传统 Block Influence，本管线直接在 32k 词表分布上逐步贪心剔除使教师-学生 KL 散度增幅最小的冗余中间层（保留完整 `16Q/8KV` 注意力头与 `3072` SwiGLU 宽度以守住 36T Token 预训练搭配记忆），零样本初始 KL 比 BI 选层低约 **4 倍**（`28 → 16 层` 初始 KL 仅 `1.83`）。

3. **冻结词嵌入 + FP32 主权重双温 KL 蒸馏 + 教师验证同音边际对齐**
   - **冻结 `embed_tokens` / `lm_head`**：确保生僻词、专有名词与成语词向量在微调中零遗忘。
   - **`AdamWBF16Moments`**：前反向采用原生 `BF16`（并将教师模型原地切片至 32k 词表，峰值显存仅 ~3.8 GB），参数更新在精确 `FP32` 主权重上累加，彻底规避小学习率（`4e-5`）下的 `BF16` 尾数下溢。
   - **双温度 KL（`T=2.0` + `T=1.0`）+ 同音词对数似然差对齐**：同步传递暗知识与精确的 `nat` 量纲尺度，使模型直接适配 `AiRanker.kt` 的默认 `α = 3.0, margin = 1.0` 门限。

4. **`LLAMAFILE | REPACK` 友好的纯 `Q4_0` 量化导出**
   - `export_gguf.py` 的 `q4_0_pure` 模式激活 `llama.cpp` 的 SIMD 权重重排（`REPACK = 1`）快路径，推理速度显著快于 `Q4_K_M` / `Q4_1`。

## 实测结果（Android x86_64 模拟器，`EvalReceiver` 167 题整句基准）

| 模型版本 | 体积 | 参数结构 | 首选正确率 (`α=3, m=1`) | 改错统计 | 平均打分耗时 | 停顿总耗时中位数 |
|---|---|---|---|---|---|---|
| 仅 `librime`（雾凇全拼） | — | — | 76.6% | — | — | — |
| 原版 `Qwen3-0.6B-Base`（旧词格） | 396.7 MB | `V=152k, L=28` | 83.2%–83.8% | `0 BROKE` | 349 ms | 375 ms |
| 原版 `Qwen3-0.6B-Base`（新全句词格） | 396.7 MB | `V=152k, L=28` | **85.6%** | `15 fixed / 0 BROKE` | 380 ms | 428 ms |
| **`zh-lm-lingxi-285m-q4_0.gguf`（推荐主力）** | **154.5 MiB** | `V=32k, L=16, KV=8, FFN=3072` | **85.6%** | **`15 fixed / 0 BROKE`** | **181 ms（快 2.1×）** | **227 ms** |
| `zh-lm-lingxi-80m-q4_0.gguf`（超轻量版） | **46.7 MiB** | `V=16k, L=10, KV=4, FFN=1024` | 79.0% | — | **58 ms（快 6.5×）** | **102 ms** |

## 复现命令（本机 RTX 3060 6GB）

```powershell
# 1. 产出主力高准度模型 Lingxi-285M（154.5 MiB，85.6% 正确率）
tools\distill\.venv\Scripts\python.exe tools\distill\prune_and_distill.py `
    --vocab-size 32768 --layers 16 --kv-heads 8 --ffn-dim 3072 --steps 600 `
    --output dist\lingxi-pro-hf
tools\distill\.venv\Scripts\python.exe tools\distill\export_gguf.py `
    dist\lingxi-pro-hf dist\zh-lm-lingxi-285m-q4_0.gguf q4_0_pure

# 2. 产出超轻量极速模型 Lingxi-80M（46.7 MiB，58 ms 打分）
tools\distill\.venv\Scripts\python.exe tools\distill\prune_and_distill.py `
    --vocab-size 16384 --layers 10 --kv-heads 4 --ffn-dim 1024 --steps 1800 --lr 1.8e-4 `
    --output dist\lingxi-lite-hf
tools\distill\.venv\Scripts\python.exe tools\distill\export_gguf.py `
    dist\lingxi-lite-hf dist\zh-lm-lingxi-80m-q4_0.gguf q4_0_pure
```
