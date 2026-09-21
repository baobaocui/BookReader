# BookReader 开发手册

> 下次继续开发时，先读本文。密钥只写在 `local.properties`（已 gitignore），**不要**把 Key 写进本文件或提交到 Git。

## 1. 产品目标

Android App：摄像头对准**实体纸质书页**，用语音或按钮触发「读当前页」。

- **没有电子书库**：正文只来自当前画面
- **不用纯 OCR**：用豆包多模态理解版面、阅读顺序、区分正文 vs 生词/页眉页脚
- **朗读**：豆包语音合成 2.0（小何），失败时回退系统 `TextToSpeech`

主流程：

```text
相机预览 →（语音指令 | 读当前页）→ 抓帧
  → 豆包视觉 Chat Completions（图+提示词）→ 正文
  → TTS 朗读
```

语音支线：

```text
点「语音指令」录音 → 再点结束
  → 火山豆包流式 ASR 2.0（nostream WebSocket）→ 文本
  → ReadIntentParser 判断是否「要读」
  → 是则进入上面的抓帧读页
```

---

## 2. 工程位置与运行

- 工程目录：`/Users/cuiguoguan/Documents/BookReader`
- 包名：`com.bookreader.app`
- 语言：Kotlin，minSdk 26，compileSdk/targetSdk 34
- 构建：

```bash
cd /Users/cuiguoguan/Documents/BookReader
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.bookreader.app/.MainActivity
```

或用 Android Studio 打开该目录 Run（真机；华为机实测）。

Logcat：

```bash
adb logcat -s BookReader:E
```

---

## 3. 配置（local.properties）

文件：项目根目录 `local.properties`（勿提交）。

```properties
sdk.dir=...你的 Android SDK...

# —— 读页：火山方舟（Ark）——
DOUBAO_API_KEY=ark-xxxxxxxx
DOUBAO_MODEL_ID=doubao-seed-2-1-turbo-260628

# —— 语音：火山「语音技术」应用（与上面不是同一套 Key）——
VOLC_ASR_APP_ID=数字AppId
VOLC_ASR_ACCESS_TOKEN=AccessToken
```

注入方式：`app/build.gradle.kts` 读 local.properties → `BuildConfig` → `ApiConfig`。

改配置后必须 **重新编译安装**，否则手机上还是旧 Key。

| 能力 | 控制台 | 字段 |
|------|--------|------|
| 看图读页 | [方舟](https://console.volcengine.com/ark) → API Key + 推理接入点 | `DOUBAO_*` |
| 语音转文字 | [语音应用](https://console.volcengine.com/speech/app) → App ID + Access Token | `VOLC_ASR_*` |

**重要：** 方舟 `ark-` Key **不能**当语音 ASR 用（会 401）。语音必须单独开语音应用。

### 语音应用要勾选的能力

创建/编辑应用时：

- **必勾：** `豆包流式语音识别模型 2.0` → **小时版**
- 对应 Resource Id：`volc.seedasr.sauc.duration`
- 高级配置里要选好「项目」
- 当前代码**不依赖**「录音文件识别 / flash HTTP」；双向流式 `bigmodel` 在该账号上不可用，用的是 **nostream**

---

## 4. 核心代码地图

| 文件 | 职责 |
|------|------|
| `MainActivity.kt` | 相机、按钮、「语音指令」二次点击录音、读页编排、翻页后续读 |
| `PageTurnDetector.kt` | 预览流抽样，约 200ms 一帧 |
| `PageTurnTracker.kt` | 翻页判定：运动量 + 中心区域 dHash。停稳且和上一页不同才触发 |
| `DoubaoVisionClient.kt` | 方舟多模态：JPEG base64 → Chat Completions；`thinking: disabled` |
| `PageReader.kt` | 调视觉识别 + 朗读（云端 TTS，失败回退系统语音） |
| `VolcTtsClient.kt` | 豆包语音合成 2.0：HTTP NDJSON → PCM |
| `CloudVoiceCommander.kt` | 录音 → ASR 串联 |
| `PcmWavRecorder.kt` | AudioRecord 16kHz / 16bit / mono → WAV |
| `VolcAsrClient.kt` | 火山 ASR WebSocket 二进制协议 |
| `ReadIntentParser.kt` | 关键词判断是否朗读意图（读/朗读/帮我读…） |
| `ApiConfig.kt` | URL 与 BuildConfig 配置入口 |
| `VoiceDiagnostics.kt` | 本机系统听写能力诊断文案 |
| `VoiceCommandHelper.kt` | **遗留**：系统 SpeechRecognizer，华为上不可用，可忽略或删 |

依赖：`CameraX`、`OkHttp`、`coroutines`、Material。视觉侧已去掉 ML Kit OCR。

---

## 5. 读页（豆包视觉）实现要点

- 接口：`POST https://ark.cn-beijing.volces.com/api/v3/chat/completions`
- Header：`Authorization: Bearer <DOUBAO_API_KEY>`
- Model：`DOUBAO_MODEL_ID`（当前常用 `doubao-seed-2-1-turbo-260628`，支持看图）
- 图片：`content` 里 `image_url` + `data:image/jpeg;base64,...`
- 请求体加：`"thinking": {"type": "disabled"}`（否则深度思考很慢）
- 提示词要求：按阅读顺序抽正文；跳过页眉页脚页码；忽略生词表/旁注；只输出纯文本
- 抓帧：CameraX `ImageCapture`，分辨率约 1280×960，上传前再缩到最长边约 1024、JPEG 压缩

状态文案大致：`正在抓取书页…` → 编码/请求进度 → `正在朗读…`

---

## 6. 语音指令（云端 ASR）实现要点

### 6.1 为什么不用系统听写

实测华为机：

- 无 `RECOGNIZE_SPEECH` Activity
- 只有 `FakeRecognitionService`（空壳）→ 超时 / 「无语音服务」
- 讯飞包是 TTS，不是给第三方的 RecognitionService
- 无 Google 语音服务

因此改为：**App 内录音 + 火山云端 ASR**。

### 6.2 交互

1. 点「语音指令」→ 开始录音（按钮变「结束录音」）
2. 说「帮我读这一页」
3. 再点「结束录音」→ ASR → 意图解析 → 自动读页

未配置 `VOLC_ASR_*` 时，界面会显示调试报告与配置步骤，不会假装能听。

### 6.3 WebSocket（已踩坑，勿改错）

| 项 | 正确值 |
|----|--------|
| URL | `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream` |
| Resource Id | `volc.seedasr.sauc.duration` |
| Header | `X-Api-App-Key` = App ID；`X-Api-Access-Key` = Access Token；另加 `X-Api-Resource-Id`、`X-Api-Connect-Id`、`X-Api-Request-Id` |

**不要用：**

- `.../sauc/bigmodel`（双向流式）：该应用会 `resourceId ... is not allowed`
- `.../auc/bigmodel/recognize/flash` + `volc.bigasr.auc_turbo`：未开通会 `not granted`；且与当前勾选能力不一致

**二进制协议摘要（V1）：**

1. 建连后先发 **full client request**（message type `0x1`，无 sequence）
2. 服务端 ack 的 sequence = **1**
3. 再发音频包（message type `0x2`）：sequence 必须从 **2** 递增；最后一包 flags=`0x3` 且 sequence 为**负数**
4. 音频：PCM s16le，16kHz，单声道；可按约 3200 字节一块发送
5. 响应 JSON 里文本多在 `result.text`

若报 `autoAssignedSequence (2) mismatch sequence in request (1)`：说明音频序号从 1 起了，应改为从 2 起。

官方文档入口（流式 ASR）：  
https://www.volcengine.com/docs/6561/1354869

---

## 7. 安全注意

- Debug 包内嵌 Key 仅适合个人试用
- 上架或分享工程前：改为服务端代理；轮换已在聊天中出现过的 Key
- `local.properties` 保持不入库

---

## 8. 建议的后续功能（未做）

- [x] 翻页后自动继续读：读完一页后看预览流，新手停稳才再抓一张高清图走豆包。点「停止」结束。不把每一帧送给视觉模型
- [ ] 「继续」语音指令
- [ ] 段落选择（「读第二段」）依赖更强版面理解
- [x] 云端 TTS：豆包语音合成 2.0，`seed-tts-2.0`，音色 `zh_female_xiaohe_uranus_bigtts`（小何）。鉴权复用 `VOLC_ASR_*`。系统 TTS 仅作失败回退
- [ ] 删除无用的 `VoiceCommandHelper`
- [ ] 密钥迁到服务端；Release 签名与混淆
- [ ] 若官方文档有 nostream 示例差异，对照 `VolcAsrClient` 再校准

---

## 9. 下次开发检查清单

1. 读本文 + 看 `ApiConfig.kt`、`VolcAsrClient.kt`、`DoubaoVisionClient.kt`、`MainActivity.kt`
2. 确认 `local.properties` 四项配置齐全，且语音应用仍勾选 **2.0 小时版**
3. `./gradlew :app:assembleDebug` 后真机安装
4. 先测「读当前页」，再测「语音指令」
5. 出问题先 `adb logcat -s BookReader:E`

---

## 10. 相关文件

- 本手册：`DEVELOPMENT.md`（继续开发以本文为准）
- 简版说明：`README.md`（可随后与本文对齐）
- 旧语音排查笔记：`VOICE_DEBUG.md`（部分过时，细节以本文第 6 节为准）
