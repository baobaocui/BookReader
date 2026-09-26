# BookReader

纸质书「看一眼就读」Android 项目。

**继续开发请先读：[DEVELOPMENT.md](./DEVELOPMENT.md)**（架构、配置、语音/视觉接口与踩坑全在那里）。

## 快速运行

1. 在 `local.properties` 配置方舟 Key / Model；可选火山 ASR、Azure TTS（见开发手册）
2. `./gradlew :app:assembleDebug`
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## 使用

- **读当前页**：对准书页直接读（不依赖语音）
- **朗读切换**：点「朗读：xxx」在 Azure / 豆包小何 / 系统之间切换
- **语音指令**：点一下开始录音 → 说「帮我读这一页」→ 再点结束
