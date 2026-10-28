# Android 1.3.0（versionCode 10）

**语言 / Language:** [简体中文](#简体中文) · [English](#english)

## 简体中文

功能基线为 iOS 1.3.0；当前 KARI Android 构建号为 10，包名 `app.kari.companion`，最低 API 24。新应用需要重新配置权限和服务；OAuth 需匹配新包名和签名。当前发布与验证见[统一验证记录](https://github.com/xlle-er/KARI-DF26/blob/main/docs/validation.md)。

## 页面

底部为机器人、人格、播放操作、视觉、设置。Material 3 NavigationBar 与 Scaffold 分配安全区；正文可滚动到底。陪伴期间隐藏配置导航，右上角停止按钮随控制层显隐；停止后返回原页面。平板／多窗口不依赖强制横屏。原始眼睛继续由本地 WebView 展示，设置与文件操作均为原生。

- 机器人：连接、电机测试、校准、接线、配网、OTA；右上角遥控。
- 人格：身份、八项人格、六项行为风格、语言风格，保存／重置、人格与完整陪伴 JSON。二级页面为行为与待机、待机场景、生命状态。
- 视觉：本地相机预览、检测、选目标、设置框面积参考及陪伴跟随预设。继续使用原 EfficientDet 模型。
- 设置：机器人／AI 服务、语音与唤醒、权限、诊断、组件测试、应用更新。

## 人格、配置及生命状态

文字与 Live 使用同一份已保存人格；保存后废弃旧恢复句柄。人格只影响风格，不能解除停止和运动许可约束。JSON 参数执行类型、范围和大小检查；完整陪伴配置 schemaVersion=1 与 iOS 字段对齐，校验后原子应用，导入错误保留原值。导入走系统文件选择器，导出走系统分享；导出不含凭据和设备记录。

唯一 BehaviorCoordinator 替代原固定待机循环。Life Engine 每秒按前台单调时间更新模拟精力、无聊、好奇、亲近、孤独、舒适度、情绪、注意力和休眠；不补算后台衰减，每次开始重置。状态不是物理传感器值，用户身份、距离与障碍保持未知。

## 待机场景与主动 AI

6 个表情与 11 个底盘场景；首次／静默／对话间隙时间范围、全局速度与时长、最短重复、单场景启用／权重／冷却／适用状态／倍率。预览一次使用已保存参数，取消／返回／后台请求停止。没有候选场景时不强制运动。场景身体指令限制在 ±0.1，单次 50–1000 ms；不代表物理速度。

规则待机默认开启，身体待机、主动思考、主动表达默认关闭。播放语音只允许表情；遥控、跟随、显式动作、升级等限制自动行为。取得新运动控制权前先确认停止；取消代次防止慢 ACK 重新授予旧控制权。显式场景内的短停止不等于撤销整段授权，急停则使旧授权失效。

主动思考默认最多每分钟两次，串行使用当前提供商文字接口，失败退回规则；建议仍服从场景白名单、启用／冷却／许可。主动表达最短间隔一分钟，Live 使用当前语音通道，文字模式显示消息，用户交互取消旧决策。陪伴控制提供单次规则／思考入口。

## 前台离线唤醒

默认关闭，仅 Gemini／标准 Vertex Live。预设“你好卡里”与“Hey KARI”，内含卡里发音词表。sherpa-onnx 2.0.0、中英 zipformer 3M chunk-16 量化组合随 APK 提供；哈希和原始许可说明保存在 wake-model-manifest.json。无需 Google Play、系统 Speech 服务或网络模型下载。

复用单一 AudioRecord 和 16 kHz PCM 分发。等待时无 Live 连接及媒体上传；对话／播放暂停关键词推理。保留命中前约 1 秒，连接期间最多缓冲 8 秒，按顺序交接一次；超限清空并提示重说，不发送部分问题。

默认静默超时 60 秒，可调 30–300 秒，连接／讲话／处理／播放不计入静默。返回等待关闭 Live、清空恢复句柄，取消跟随和身体待机，不自动恢复身体运动。加载或连接失败仍保留陪伴并显示原因，可手动唤醒或重试；不回退网络识别。后台／电话中断清空监听和缓冲，回前台需显式恢复。不是锁屏后台助手。

## 诊断与验证边界

最多 500 个事件、100 个决策；诊断页面一秒采样一次，支持清理与脱敏 JSON 分享。无密钥、管理密码、原始音视频、识别文本、聊天原文及完整设备标识。

自动检查覆盖配置、场景、时钟、停止屏障、缓冲、隐私与页面。实际 JNI 模型通过固定中英合成音频及普通话语负样本；不能替代真人噪声／距离验收。Android ARM 模拟器原 EfficientDet 测试存在已记录的指令集限制，不计作通过。真实语音、插话、温升耗电、BLE、跟随及 OTA 仍待用户手机与机器人验收。

## English

The feature baseline is iOS 1.3.0. The current KARI Android build is 10, application ID `app.kari.companion`, minimum API 24. Configure permissions and services again for the new app; OAuth must match its package and signing certificate. See [current validation](https://github.com/xlle-er/KARI-DF26/blob/main/docs/validation.md).

### Pages

The bottom bar contains Robot, Personality, the play action, Vision and Settings. Material 3 NavigationBar / Scaffold allocate safe areas and allow content to scroll to the bottom. Companionship hides configuration navigation; the upper-right stop button follows the controls. Stopping returns to the previous page. Tablets / multi-window do not require forced landscape. Eyes use a local WebView; settings and files are native.

- Robot: connection, motor tests, calibration, wiring, provisioning, OTA and remote control.
- Personality: identity, eight traits, six behavior styles, language style, save/reset and personality / full-companion JSON. Subpages provide behavior, idle scenes and life state.
- Vision: local preview, detection, target selection, reference box area and a following preset; the existing EfficientDet model is retained.
- Settings: robot / AI services, voice and wake, permissions, diagnostics, component tests and app updates.

### Personality, configuration and life state

Text and Live share the saved personality; saving invalidates old resume handles. Personality affects style but cannot override stop or motion permission. JSON types, ranges and size are validated; schemaVersion=1 aligns with iOS and is applied atomically. Failed imports preserve current values. System pickers handle import/share. Exports omit credentials and device records.

One BehaviorCoordinator replaces the fixed idle loop. The Life Engine uses foreground monotonic time once per second for simulated energy, boredom, curiosity, closeness, loneliness, comfort, mood, attention and sleep. It does not catch up background decay and resets on start. These are simulated states; user identity, physical distance and obstacles remain unknown.

### Idle scenes and proactive AI

Six expressions and eleven chassis scenes support first/quiet/conversation-gap intervals, global speed/duration, minimum repetition and per-scene enablement, weight, cooldown, state and multipliers. One-shot previews use saved settings and stop on cancel, exit or backgrounding. No candidate means no forced motion. Idle body commands are bounded to ±0.1 and 50–1000 ms, not physical speed units.

Rule idle is on by default; body idle, proactive thought and proactive expression are off. Speech permits expressions only. Remote control, following, explicit actions and upgrades constrain automation. A stop barrier precedes ownership; generations prevent late ACKs from restoring old control. A short stop within a permitted scenario does not revoke the whole scenario; emergency stop does.

Proactive thought defaults to at most twice per minute, serialized through the current text provider, with rule fallback. Suggested scenes still obey allowlists, enablement, cooldown and permission. Proactive expression has at least a one-minute gap; Live uses the current voice channel and text mode displays messages. User activity invalidates stale decisions. Companion controls expose one-shot rule / thought actions.

### Foreground offline wake

Off by default, for Gemini / standard Vertex Live only. Presets are “你好卡里” and “Hey KARI”, using the KARI phoneme lists. Bundled sherpa-onnx 2.0.0 and the bilingual quantized zipformer 3M chunk-16 model require no Google Play, system Speech service or model download. Hashes and terms are in `wake-model-manifest.json`.

One AudioRecord distributes 16 kHz PCM. Waiting has no Live connection or media upload; conversation / playback pauses keyword inference. About one second of pre-roll and at most eight seconds of connection buffering are handed over once in order. Overflow clears the whole segment and asks the user to repeat, rather than sending a partial question.

Silence timeout defaults to 60 seconds, configurable 30–300; connection, speech, processing and playback do not count. Returning to waiting closes Live, clears resumption, cancels following/body idle and does not restore motion. Failures preserve companionship and allow manual wake/retry, without network recognition fallback. Backgrounding / calls clear listening and buffers; resumption is explicit. This is not a lock-screen assistant.

### Diagnostics and validation limits

At most 500 events and 100 decisions are sampled once per second, cleared or shared as redacted JSON. Exports exclude keys, administration passwords, raw media, transcripts, conversation text and full device IDs.

Automated checks cover configuration, scenes, clocks, stops, buffers, privacy and pages. JNI keyword tests use fixed bilingual synthetic positives and ordinary-speech negatives, not real-speaker/noise/distance acceptance. The recorded ARM emulator instruction-set limitation for EfficientDet is not counted as a pass. See central validation for maintainer-confirmed physical tests; speech, interruptions, thermals, BLE, following and OTA require suitable phone/robot conditions.
