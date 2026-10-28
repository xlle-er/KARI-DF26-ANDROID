package app.kari.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

 data class AppState(
    val phase: Phase=Phase.IDLE, val config: ProviderConfig=ProviderConfig(), val language: String="system",
    val message: String="", val liveMode: Boolean=true, val cameraWanted: Boolean=true, val detecting: Boolean=false,
    val frontCamera: Boolean=true, val motion: Boolean=false, val following: Boolean=false, val followReference: Boolean=false, val selected: Long?=null,
    val reply: String="", val busy: Boolean=false, val loaded: Boolean=false, val myRobot: String="", val lastRobot: String=""
)
class KariApplication: Application(), DefaultLifecycleObserver {
    lateinit var runtime: KariRuntime; private set
    override fun onCreate() {
        super<Application>.onCreate(); runtime=KariRuntime(this); runtime.updates.check()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
    override fun onStop(owner: LifecycleOwner) { runtime.background() }
}
class KariRuntime(private val context: Context) {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val state=MutableStateFlow(AppState())
    val gate=SessionGate()
    val settings=SettingsStore(context)
    private val credentials=CredentialStore(context)
    val audio=AudioEngine(context,scope,::report)
    val robot=RobotClient(context,scope,::report)
    val updates=AppUpdateManager(context,scope)
    val motion=MotionArbiter(robot)
    val camera=CameraEngine(context,scope,::report)
    val face=FaceHost(context,scope,::report)
    val live=LiveClient(context,scope,audio,::report)
    val follow=FollowPolicy()
    val features=CompanionFeatures(context,this)
    val remote=RemoteControl(scope,{robot.ready.value},send={ command ->
        check(remoteReady())
        motion.arm(true)
        val lease=motion.acquire(MotionSource.REMOTE)
        motion.send(command,MotionSource.REMOTE,lease)
    },stop={
        // Drain the old follow/scenario cleanup before admitting manual input.
        followJob?.cancelAndJoin(); stopFollowLocal()
        scenarioLock.withLock {
            motion.revoke(); state.update { it.copy(motion=false) }; face.publish(faceState())
            if(!robot.ready.value) false else runCatching { motion.stop(true); true }.getOrDefault(false)
        }
    },takeover={ features.pauseActions(); cancelAction(); followJob?.cancel(); motion.revoke(); state.update { it.copy(motion=false) } },
      fault={motion.revoke(); state.update {it.copy(motion=false)}; report(t("遥控停止或指令未获确认，请检查机器人","Remote stop or command unconfirmed; check the robot"))})
    private fun remoteReady(): Boolean = remote.ready
    fun openRemote() { if(gate.phase in setOf(Phase.IDLE,Phase.ACTIVE)) remote.open() }
    private val http=OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).build()
    private val scenarios: Set<String> = JSONArray(context.assets.open("scenarios.json").bufferedReader().use { it.readText() }).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    private var work: Job? = null
    private var requestJob: Job? = null
    private var actionJob: Job? = null
    private var followJob: Job? = null
    private var activeLease: Long? = null
    private var activeSource=MotionSource.SCENARIO
    private var scenarioBusy=false
    private var actionEpoch=0L
    private val scenarioLock=Mutex()
    private var lastActivity=SystemClock.elapsedRealtime()
    private var permissionsToken: Long? = null
    private var testOnly=false
    init {
        scope.launch {
            settings.migrateStoredScenes()
            features.load()
            L10n.language=settings.language()
            val config=settings.load(settings.selected())
            val front=settings.option("frontCamera",true); if(camera.front != front) camera.flip()
            val cameraWanted=settings.option("cameraWanted",true)
            state.update { it.copy(config=config,language=L10n.language,loaded=true,frontCamera=front,cameraWanted=cameraWanted,myRobot=settings.own(),lastRobot=settings.last(),liveMode=config.provider in listOf("gemini","vertex")) }
        }
        audio.onInterrupted={features.suspend();live.close();scope.launch{stopMotion()}}
        live.personality={features.config.value.profile.instruction()}
        robot.onDisconnect={ features.event("disconnected"); features.pauseActions(); remote.connectionLost(); motion.revoke(); stopFollowLocal(); state.update { it.copy(motion=false) }; face.publish(faceState()) }
        camera.onDetectorUnavailable={ state.update {it.copy(detecting=false)} }
        camera.onInvalidated={ invalidateFollow() }
        camera.onFrame={ if(state.value.phase == Phase.ACTIVE && state.value.liveMode && !features.waiting && live.connected.value) live.frame(it) }
        camera.onDetections={ values ->
            features.visual(values.map{it.label}.toSet())
            if(follow.target != null) {
                if(!follow.update(values,SystemClock.elapsedRealtime())) invalidateFollow()
                state.update { it.copy(selected=follow.target?.id,followReference=follow.reference != null) }
            }
        }
        face.photo={ camera.snapshot() }
        face.command={ command ->
            check(!remote.busy) { t("手动遥控中，AI 身体动作已暂停","Manual remote active; AI body actions are paused") }
            if(command.optString("type") == "stop") { check(robot.ready.value) { t("停止未获确认：机器人未连接", "Stop unconfirmed: robot disconnected") }; val lease=activeLease;if(lease!=null&&motion.valid(activeSource,lease))motion.send(command,activeSource,lease)else motion.stop(); JSONObject().put("type","ack") }
            else {
                check(gate.phase == Phase.ACTIVE || activeSource == MotionSource.TEST)
                val lease=activeLease ?: throw KariException("动作许可已失效", "Motion lease expired")
                val tuned=JSONObject(command.toString())
                if(activeSource==MotionSource.SCENARIO&&tuned.optString("type")=="motion"){
                    val p=features.config.value.profile
                    tuned.put("linear",tuned.optDouble("linear")*p.motionScale).put("angular",tuned.optDouble("angular")*p.motionScale)
                    val duration=tuned.optInt("duration_ms",300)
                    tuned.put("ramp_ms",maxOf(tuned.optInt("ramp_ms",0),(150*p.behaviorStyle.getValue("movementSoftness")).toInt()).coerceAtMost(duration/2))
                }
                motion.send(tuned,activeSource,lease)
            }
        }
        live.onScenario={ runScenario(it,MotionSource.SCENARIO) }
        live.onCancel={ if(!remote.busy) { cancelAction(); scope.launch { stopMotion() } } }
        live.onStop={ scope.launch { stopMotion() } }
        live.onFailure={
            features.liveFailed()
            if(gate.phase == Phase.ACTIVE || gate.phase == Phase.STARTING) {
                scope.launch { audio.stop(); if(!remote.busy) { invalidateFollow(); runCatching { motion.stop(true) }; state.update { it.copy(motion=false) } } }
            }
        }
        scope.launch {
            while(isActive) {
                delay(50)
                if(follow.enabled && follow.target?.let { SystemClock.elapsedRealtime()-it.timeMs > 300 } == true) invalidateFollow()
            }
        }
        scope.launch {
            audio.status.distinctUntilChangedBy { it.playing }.collect { face.publish(faceState()) }
        }
    }
    fun report(message: String) { state.update { it.copy(message=message) } }
    fun clearMessage() { state.update { it.copy(message="") } }
    private fun faceState() = JSONObject().put("connected",robot.ready.value && motion.allowed).put("cameraRunning",camera.running.value)
        .put("speaking",audio.status.value.playing).put("thinking",state.value.busy).put("expression",if(state.value.busy) "curious" else "neutral")
    fun setLanguage(value: String) { scope.launch { settings.setLanguage(value); state.update { it.copy(language=value,message="") } } }
    fun selectProvider(value: String) { scope.launch { val config=settings.load(value); settings.save(config); state.update { it.copy(config=config,liveMode=value in listOf("gemini","vertex")) } } }
    fun saveConfig(config: ProviderConfig, key: String) { scope.launch {
        try {
            val defaults=ProviderConfig.defaults(config.provider)
            val effective=config.copy(model=config.model.ifBlank { defaults.model },liveModel=config.liveModel.ifBlank { defaults.liveModel })
            withContext(Dispatchers.IO) { credentials.set(config.provider,key) }; settings.save(effective)
            state.update { it.copy(config=effective) }; report(t("配置已保存", "Settings saved"))
        } catch(_: Exception) { report(t("无法保存凭据或配置", "Could not save settings or credential")) }
    } }
    private suspend fun credential(config: ProviderConfig): String {
        if(config.provider == "vertex" && config.googleOAuth) {
            val account=withContext(Dispatchers.IO) { credentials.get("google.account") }
            check(account.isNotBlank()) { t("请先完成 Google 授权", "Authorize with Google first") }
            return GoogleAuth.token(context,account)
        }
        return withContext(Dispatchers.IO) { credentials.get(config.provider) }
    }
    fun authorized(account: String) {
        scope.launch {
            withContext(Dispatchers.IO) { credentials.set("google.account",account) }
            report(t("Google Cloud 授权成功", "Google Cloud authorized"))
        }
    }
    fun deleteCredential() { scope.launch { withContext(Dispatchers.IO) { credentials.remove(state.value.config.provider); if(state.value.config.provider == "vertex") credentials.remove("google.account") }; report(t("凭据已删除", "Credential deleted")) } }
    fun setLive(value: Boolean) { if(gate.phase == Phase.IDLE) state.update { it.copy(liveMode=value) } }
    fun prepareStart(test: Boolean=false): Long? {
        if(remote.busy) return null
        val token=gate.begin(test) ?: return null
        testOnly=test; permissionsToken=token
        state.update { it.copy(phase=gate.phase,message="",reply="") }; return token
    }
    fun requiredPermissions(test: Boolean): Array<String> {
        val list=mutableListOf<String>()
        if(!test && state.value.liveMode && state.value.config.provider != "offline") list += Manifest.permission.RECORD_AUDIO
        if(!test && state.value.cameraWanted) list += Manifest.permission.CAMERA
        return list.filter { ContextCompat.checkSelfPermission(context,it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }
    fun startGranted(token: Long) {
        if(!gate.current(token) || permissionsToken != token) return
        work=scope.launch {
            try {
                val s=state.value
                if(!testOnly && s.liveMode && s.config.provider != "offline") check(has(Manifest.permission.RECORD_AUDIO)) { t("语音陪伴需要麦克风权限", "Voice companionship requires microphone permission") }
                val wakeMode=!testOnly && features.usesWake()
                if(s.config.provider != "offline" && (s.liveMode || testOnly) && !wakeMode) {
                    audio.startOutput()
                    val key=credential(s.config)
                    live.start(s.config,key,testOnly)
                    if(!gate.current(token)) return@launch
                    if(testOnly) {
                        val failureBaseline=audio.failures.get(); val playedBaseline=audio.renderedFrames.get()
                        live.text(if(L10n.chinese) "请开始语音测试" else "Please start the voice test")
                        withTimeout(25000) {
                            while(!(live.receivedAudio > 0 && live.turnComplete && audio.pendingMs() == 0L && audio.renderedFrames.get() > playedBaseline)) {
                                check(audio.failures.get() == failureBaseline && live.connected.value) { t("AI 测试播放或连接失败", "AI test playback or connection failed") }; delay(50)
                            }
                            check(audio.failures.get() == failureBaseline)
                        }
                        report(t("AI 语音测试成功：已收到并播放完成", "AI voice test passed: audio received and fully played"))
                        stop(preserveMessage=true); return@launch
                    }
                    audio.startInput(features::sharedPcm)
                } else if(testOnly) {
                    report(t("离线模式请使用声音测试", "Use Sound test in offline mode")); stop(true); return@launch
                }
                if(!gate.current(token)) return@launch
                // Each new companion session starts with body motion enabled.
                // Stops, disconnects and manual takeover can revoke it for this session.
                motion.arm(true)
                gate.active(token); state.update { it.copy(phase=Phase.ACTIVE,motion=true) }
                face.publish(faceState())
                if(s.cameraWanted && has(Manifest.permission.CAMERA)) camera.start()
                else if(s.cameraWanted) report(t("相机未授权，已降级为语音或文字陪伴", "Camera denied; voice or text companionship is available"))
                features.begin()
                if(state.value.followReference && followPreset && !features.waiting) startFollow()
            } catch(e: CancellationException) { if(e is TimeoutCancellationException && gate.current(token)) { report(t("连接或测试超时", "Connection or test timed out")); startupFailure(token) } }
            catch(e: Exception) { if(gate.current(token)) { report(e.message ?: t("启动失败", "Could not start")); startupFailure(token) } }
        }
    }
    fun microphoneGranted()=has(Manifest.permission.RECORD_AUDIO)
    private fun has(permission: String) = ContextCompat.checkSelfPermission(context,permission) == PackageManager.PERMISSION_GRANTED
    fun cancel() { scope.launch { stop() } }
    suspend fun stop(preserveMessage: Boolean=false) {
        if(gate.phase == Phase.STOPPING) return
        remote.close()
        gate.stop(); permissionsToken=null
        val self=currentCoroutineContext()[Job]
        if(work !== self) work?.cancel(); work=null
        features.stop(); requestJob?.cancel(); requestJob=null
        cancelAction(); stopFollowLocal(); camera.stop(); live.close(); audio.stop()
        val stopped=runCatching { motion.stop(true) }; motion.revoke()
        gate.idle(); state.update { it.copy(phase=Phase.IDLE,motion=false,following=false,busy=false,selected=null,followReference=false,message=if(stopped.isFailure) t("停止未获确认，请检查机器人", "Stop was not confirmed; check the robot") else if(preserveMessage) it.message else "") }
    }
    fun background() {
        remote.close()
        scope.launch { features.suspend(); if(gate.phase==Phase.ACTIVE){requestJob?.cancel();cancelAction();stopFollowLocal();live.close();audio.stop();camera.stop();runCatching{motion.stop(true)};state.update{it.copy(motion=false,busy=false)};report(t("采集已暂停，请显式恢复", "Capture paused; restore explicitly"))}else if(gate.phase!=Phase.IDLE)stop()else{runCatching{motion.stop(true)};camera.stop();audio.stop()};robot.stopScan();robot.disconnect() }
    }
    fun cameraWanted(value: Boolean) {
        state.update { it.copy(cameraWanted=value) }; scope.launch {settings.saveOption("cameraWanted",value)}
        if(gate.phase in setOf(Phase.ACTIVE,Phase.IDLE)) { if(value && has(Manifest.permission.CAMERA)) camera.start() else camera.stop() }
        face.publish(faceState())
    }
    fun cameraGranted() { if(gate.phase in setOf(Phase.ACTIVE,Phase.IDLE) && state.value.cameraWanted && has(Manifest.permission.CAMERA)) camera.start() }
    fun flipCamera() { invalidateFollow(); camera.flip(); state.update {it.copy(frontCamera=camera.front)}; scope.launch {settings.saveOption("frontCamera",camera.front)} }
    fun detect(value: Boolean) {
        camera.detectEnabled=value; state.update { it.copy(detecting=value) }
        if(!value) invalidateFollow()
    }
    fun selectTarget(value: Detection) { invalidateFollow(); follow.select(value); state.update { it.copy(selected=value.id,followReference=false) } }
    fun calibrateFollow() {
        val ok=follow.calibrate(SystemClock.elapsedRealtime()); state.update { it.copy(followReference=ok) }
        if(!ok) report(t("目标不稳定，请重新选择", "Target is not stable; select it again"))
    }
    fun arm(value: Boolean) {
        if(remote.busy) return
        if(value && !robot.ready.value) { report(t("请先连接机器人", "Connect a robot first")); return }
        motion.arm(value); state.update { it.copy(motion=value) }; face.publish(faceState())
        if(!value) scope.launch { stopMotion() }
    }
    fun startFollow() {
        if(remote.busy) return
        features.pauseActions()
        if(!motion.allowed || !robot.ready.value || !camera.running.value || !camera.detectEnabled || !follow.start(SystemClock.elapsedRealtime())) {
            report(t("需要相机、目标参考和本次运动许可", "Camera, target reference and motion permission are required")); return
        }
        followJob?.cancel()
        followJob=scope.launch {
            try {
                var lease=motion.acquire(MotionSource.FOLLOW); state.update { it.copy(following=true) }
                var moving=false
                while(isActive && follow.enabled) {
                    val now=SystemClock.elapsedRealtime(); val intent=follow.intent(now)
                    if(!follow.enabled) { follow.invalidate(); break }
                    if(intent.linear == 0.0 && intent.angular == 0.0) {
                        if(moving) { motion.stop(); moving=false }
                    } else {
                        if(motion.owner != MotionSource.FOLLOW) lease=motion.acquire(MotionSource.FOLLOW)
                        motion.send(JSONObject().put("type","motion").put("linear",intent.linear).put("angular",intent.angular).put("duration_ms",150).put("ramp_ms",0),MotionSource.FOLLOW,lease)
                        moving=true
                    }
                    delay((200-(SystemClock.elapsedRealtime()-now)).coerceAtLeast(1))
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { motion.revoke(); report(t("跟随动作失败，运动许可已撤销", "Follow command failed; motion permission revoked")); state.update { it.copy(motion=false) } }
            finally { follow.stop(); state.update { it.copy(following=false) }; withContext(NonCancellable) { runCatching { motion.stop() }.onFailure { report(t("停止未获确认", "Stop was not confirmed")) } } }
        }
    }
    private fun stopFollowLocal() { followJob?.cancel(); followJob=null; follow.invalidate(); state.update { it.copy(following=false,selected=null,followReference=false) } }
    private fun invalidateFollow() {
        followPreset=false
        val moving=follow.enabled; stopFollowLocal()
        if(moving) scope.launch { runCatching { motion.stop() }.onFailure { report(t("停止未获确认", "Stop was not confirmed")) } }
    }
    suspend fun stopMotion() {
        if(remote.state.value.presented) { remote.emergencyStop(); return }
        features.pauseActions(); cancelAction(); stopFollowLocal(); motion.arm(false); state.update { it.copy(motion=false) }; face.publish(faceState())
        runCatching { motion.stop(true) }.onFailure { report(t("停止未获确认", "Stop was not confirmed")) }
    }
    private fun cancelAction() { actionEpoch++; actionJob?.cancel(); actionJob=null; activeLease=null; face.cancel() }
    private suspend fun runScenario(name: String, source: MotionSource, internal: Boolean=false) {
        val requestedEpoch=actionEpoch
        scenarioLock.withLock {
        check(!remote.busy) { t("手动遥控中，AI 身体动作已暂停","Manual remote active; AI body actions are paused") }
        check(requestedEpoch == actionEpoch) { t("动作已取消", "Action cancelled") }
        check(name in scenarios || internal && name in setOf("pose_neutral","pose_attentive","pose_curious","pose_happy","pose_sleepy","pose_scared","pose_shy","wake_activation")) { t("未知动作", "Unknown action") }
        check(!testOnly || source == MotionSource.TEST)
        val token=actionEpoch
        check(!follow.enabled) { t("跟随运行时不接受其他身体动作", "Body actions unavailable while following") }
        delay((features.config.value.profile.reactionDelay*1000).toLong())
        currentCoroutineContext().ensureActive()
        val lease=if(motion.allowed) motion.acquire(source) else null
        activeSource=source; activeLease=lease; lastActivity=SystemClock.elapsedRealtime(); face.publish(faceState())
        scenarioBusy=true;if(lease!=null)features.event("motion_started")
        try { face.run(name,lease != null) }
        catch(e: Exception) { if(lease != null) { motion.revoke(); state.update { it.copy(motion=false) }; runCatching { motion.stop(true) } }; throw e }
        finally { scenarioBusy=false;features.event("motion_stopped"); if(token == actionEpoch) { activeLease=null; if(lease != null) motion.release(source,lease) } }
    }
    }
    fun actionTest(name: String="ack_yes") {
        if(remote.busy) return
        if(!robot.ready.value) { report(t("请先连接机器人", "Connect a robot first")); return }
        val testToken=gate.begin(true) ?: return
        state.update { it.copy(phase=Phase.TESTING) }; cancelAction()
        actionJob=scope.launch {
            try { motion.arm(true); runScenario(name,MotionSource.TEST); report(t("动作序列执行完成", "Action sequence completed")) }
            catch(_: Exception) { report(t("动作测试失败或已取消", "Action test failed or cancelled")) }
            finally { motion.revoke(); state.update { it.copy(motion=false) }; runCatching { motion.stop(true) }; face.publish(faceState()); if(gate.current(testToken)) { gate.stop(); gate.idle(); state.update { it.copy(phase=Phase.IDLE) } } }
        }
    }
    fun connect(row: RobotRow) { work=scope.launch {
        try {
            robot.connect(row); settings.last(row.address); state.update { it.copy(lastRobot=row.address) }
            motion.revoke()
        } catch(e: CancellationException) { robot.disconnect() }
        catch(_: Exception) { robot.disconnect(); report(t("机器人连接失败", "Robot connection failed")) }
    } }
    fun cancelConnect() { work?.cancel(); robot.disconnect() }
    fun alias(row: RobotRow, value: String) { scope.launch { settings.alias(row.address,value); robot.robots.update { rows -> rows.map { if(it.address == row.address) it.copy(alias=value) else it } } } }
    fun own(row: RobotRow) { scope.launch { settings.own(row.address); state.update { it.copy(myRobot=row.address) } } }
    fun soundTest() {
        scope.launch {
            try { audio.startOutput(); audio.tone(); report(t("提示音已提交，实际输出请听辨", "Test tone queued; listen to verify output")) }
            catch(_: Exception) { report(t("声音测试失败", "Sound test failed")) }
            if(gate.phase == Phase.IDLE) { delay(1500); if(gate.phase == Phase.IDLE) audio.stop() }
        }
    }
    fun recover() {
        val token=gate.generation
        scope.launch {
            try {
                check(gate.phase == Phase.ACTIVE)
                if(features.usesWake()){features.resume();return@launch}
                features.resume()
                if(state.value.liveMode && state.value.config.provider != "offline") {
                    if(!live.connected.value) {
                        val key=credential(state.value.config)
                        live.start(state.value.config,key,resume=true)
                        if(!gate.current(token)) { live.close(); return@launch }
                        audio.startOutput(); audio.startInput(features::sharedPcm)
                    } else audio.recover()
                }
                if(state.value.cameraWanted && has(Manifest.permission.CAMERA)) { if(!camera.running.value) camera.start() }
                report(t("恢复请求已执行，请检查音视频状态", "Recovery requested; verify audio and video status"))
            } catch(_: Exception) { if(gate.current(token)) report(t("恢复失败，请检查权限、凭据和网络", "Recovery failed; check permissions, credential and network")) }
        }
    }
    fun text(value: String, image: ByteArray?) {
        if(value.isBlank() || gate.phase != Phase.ACTIVE || requestJob?.isActive == true) return
        if(isStopRequest(value)) { scope.launch { stopMotion() }; return }
        lastActivity=SystemClock.elapsedRealtime();features.event("user_activity")
        if(features.waiting){report(t("请先唤醒再提问", "Wake before asking a question"));return}
        if(state.value.liveMode && live.connected.value && image == null) { live.text(value); return }
        val token=gate.generation; val c=state.value.config
        requestJob=scope.launch {
            state.update { it.copy(busy=true) }; face.publish(faceState())
            try {
                if(c.provider == "offline") {
                    val response=offlineReply(value)
                    state.update { it.copy(reply=response.text) }; runCatching { runScenario(response.scenario,MotionSource.SCENARIO,internal=true) }
                } else {
                    val key=credential(c)
                    val base=JSONObject(context.assets.open("live-setup.json").bufferedReader().use { it.readText() }).getJSONObject("setup").getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
                    val system=base+features.config.value.profile.instruction()+"\n"+languageInstruction()+"\nReturn JSON {\"text\":\"reply\",\"scenario\":null} or one exact allowed scenario: "+scenarios.joinToString(",")
                    val raw=http.await(ProviderRequests.text(c,key,value,image,system))
                    if(!gate.current(token)) return@launch
                    val response=ProviderRequests.parse(raw,c.provider)
                    val parsed=runCatching { JSONObject(response.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
                    state.update { it.copy(reply=parsed?.optString("text") ?: response) }
                    parsed?.optString("scenario")?.takeIf { it in scenarios }?.let { runScenario(it,MotionSource.SCENARIO) }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(gate.current(token)) report(e.message ?: t("文字请求失败", "Text request failed")) }
            finally { if(gate.current(token)) { state.update { it.copy(busy=false) }; face.publish(faceState()) } }
        }
    }
    private suspend fun startupFailure(token:Long) {
        live.close();audio.stop()
        if(testOnly){stop(true);return}
        if(!gate.current(token))return
        gate.active(token);state.update{it.copy(phase=Phase.ACTIVE,motion=false)}
        motion.revoke();if(state.value.cameraWanted&&has(Manifest.permission.CAMERA))camera.start()
        features.begin()
    }
    fun actionInProgress()=scenarioBusy || actionJob?.isActive==true || activeLease!=null
    fun expressionState(expression:String)=faceState().put("expression",expression)
    suspend fun connectLive(resume:Boolean){val c=state.value.config;live.start(c,credential(c),resume=resume)}
    suspend fun thoughtRequest(prompt:String):String {
        val c=state.value.config
        val system=features.config.value.profile.instruction()+"\n"+languageInstruction()+"\nOnly suggest scenes from the supplied whitelist. No motor commands."
        return ProviderRequests.parse(http.await(ProviderRequests.text(c,credential(c),prompt,null,system)),c.provider)
    }
    var followPreset=false
    fun updateFollowPreset(enabled:Boolean){followPreset=enabled}
    fun previewCamera(){if(gate.phase==Phase.IDLE&&has(Manifest.permission.CAMERA))camera.start()}
    fun stopPreviewCamera(){if(gate.phase==Phase.IDLE)camera.stop()}
    suspend fun runIdleScene(scene:IdleScene,preview:Boolean=false) {
        check(!remote.busy&&!follow.enabled&&!robot.management.state.value.busy&&!robot.management.state.value.info.optBoolean("ota_mode"))
        val previewToken=if(preview)gate.begin(true)?:error("busy") else null
        if(preview)state.update{it.copy(phase=Phase.TESTING)}
        val source=if(preview)MotionSource.TEST else MotionSource.IDLE
        var lease:Long?=null
        try { scenarioLock.withLock {
            check(preview || gate.phase==Phase.ACTIVE)
            if(scene.pose!=null){face.publish(expressionState(scene.pose.removePrefix("pose_")));delay((1800*features.config.value.idle.durationScale*features.config.value.idle.options(scene.id).durationScale).toLong());return@withLock}
            check(scene.body&&robot.ready.value&&!audio.status.value.playing&&(preview||features.automaticBody&&motion.allowed))
            if(preview)motion.arm(true)
            lease=motion.acquire(source)
            val c=features.config.value;val option=c.idle.options(scene.id)
            val speed=c.idle.speedScale*option.speedScale*c.profile.motionScale;val timing=c.idle.durationScale*option.durationScale
            features.event("motion_started")
            for(step in scene.steps){
                currentCoroutineContext().ensureActive()
                check(!remote.busy&&!audio.status.value.playing&&(preview&&gate.phase==Phase.TESTING||!preview&&gate.phase==Phase.ACTIVE&&features.automaticBody))
                val duration=(step.optDouble("durationMs",100.0)*timing).toInt().coerceIn(50,1000)
                when(step.getString("kind")) {
                    "move"->{val left=step.optDouble("left");val right=step.optDouble("right");motion.send(JSONObject().put("type","motion").put("linear",((left+right)/2*speed).coerceIn(-.1,.1)).put("angular",((right-left)/2*speed).coerceIn(-.1,.1)).put("duration_ms",duration).put("ramp_ms",minOf(duration/2,150)),source,lease!!);delay(duration.toLong())}
                    "stop"->motion.send(JSONObject().put("type","stop"),source,lease!!)
                    "pause"->Unit
                    else->error("invalid_scene_step")
                }
                delay((step.optDouble("pauseMs",0.0)*timing).toLong().coerceIn(0,2000))
            }
            motion.send(JSONObject().put("type","stop"),source,lease!!)
        }} finally {
            withContext(NonCancellable){if(lease!=null&&motion.valid(source,lease!!)){runCatching{motion.stop()}.onFailure{motion.revoke();report(t("停止未获确认", "Stop was not confirmed"))};motion.release(source,lease!!)};features.event("motion_stopped")
                if(preview){motion.revoke();if(gate.current(previewToken!!)){gate.stop();gate.idle();state.update{it.copy(phase=Phase.IDLE,motion=false)}}}
            }
        }
    }
}
