package app.kari.android

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.math.sqrt

data class CompanionStatus(val life:LifeState=LifeState(),val wake:WakePhase=WakePhase.STOPPED,val bufferedBytes:Int=0,val events:List<CompanionEvent> = emptyList(),val decisions:List<CompanionEvent> = emptyList(),val localStatus:String="",val previewing:Boolean=false)
class CompanionFeatures(private val context:Context,private val runtime:KariRuntime) {
    val config=MutableStateFlow(CompanionConfiguration())
    val status=MutableStateFlow(CompanionStatus())
    val catalog=IdleScene.parse(context.assets.open("companion-idle-scenes.json").bufferedReader().use{it.readText()})
    val known=catalog.map{it.id}.toSet()
    val coordinator=BehaviorCoordinator()
    val wake=WakePolicy()
    private val detector=OfflineWake(context,runtime.scope,{manualWake()},{failWake()})
    private var loop:Job?=null;private var sceneJob:Job?=null;private var thinkJob:Job?=null;private var connectJob:Job?=null
    @Volatile private var epoch=0;private var interactionEpoch=0
    private var visualLabels="";private var lastVisual=Double.NEGATIVE_INFINITY
    private var lastVoice=Double.NEGATIVE_INFINITY
    private var lastSpeaking=false
    private var resumed=false
    fun now()=SystemClock.elapsedRealtime()/1000.0
    val waiting get()=wake.phase in setOf(WakePhase.WAITING,WakePhase.CONNECTING,WakePhase.FAILED,WakePhase.SUSPENDED)
    val automaticBody get()=!waiting&&!coordinator.life.state.sleeping&&config.value.behavior.idleBody
    suspend fun load() {try{config.value=runtime.settings.companion(known)}catch(_:Exception){runtime.report(t("旧陪伴配置无法读取，使用安全默认值", "Saved companion settings are invalid; safe defaults are active"))}}
    suspend fun apply(value:CompanionConfiguration) {
        check(runtime.gate.phase==Phase.IDLE)
        val valid=CompanionConfiguration.parse(value.json().toString(),known)
        runtime.settings.saveCompanion(valid);config.value=valid;runtime.live.clearResumeHandle()
    }
    fun save(value:CompanionConfiguration){runtime.scope.launch{runCatching{apply(value)}.onSuccess{runtime.report(t("配置已保存", "Settings saved"))}.onFailure{runtime.report(t("配置无效，原配置已保留", "Invalid settings; original settings preserved"))}}}
    fun import(text:String,personalityOnly:Boolean) {runtime.scope.launch{runCatching{val value=if(personalityOnly)config.value.copy(profile=PersonalityProfile.parse(text))else CompanionConfiguration.parse(text,known);apply(value)}.onSuccess{runtime.report(t("导入成功", "Import succeeded"))}.onFailure{runtime.report(t("导入失败，原配置已保留", "Import failed; original settings preserved"))}}}
    fun event(kind:String){if(runtime.gate.phase!=Phase.ACTIVE)return;coordinator.event(now(),kind);if(kind=="user_activity"||kind=="wake"){interactionEpoch++;wake.activity(now());thinkJob?.cancel();sceneJob?.cancel()}}
    suspend fun begin(){epoch++;resumed=true;coordinator.begin(now(),config.value.idle);lastSpeaking=false;startLoop();if(usesWake())startWaiting()}
    fun usesWake()=config.value.wake.enabled&&runtime.state.value.liveMode&&runtime.state.value.config.provider in listOf("gemini","vertex")&&!runtime.state.value.config.express
    private fun snapshot(){status.value=status.value.copy(life=coordinator.life.state,wake=wake.phase,bufferedBytes=wake.bufferedBytes,events=coordinator.events,decisions=coordinator.decisions)}
    private fun startLoop(){loop?.cancel();loop=runtime.scope.launch{while(isActive&&runtime.gate.phase==Phase.ACTIVE){
        val time=now();val playing=runtime.audio.status.value.playing
        if(playing!=lastSpeaking){coordinator.event(time,if(playing)"playback_started" else "playback_stopped");lastSpeaking=playing}
        coordinator.life.tick(time,config.value.profile,config.value.behavior.sleepAfter)
        if(wake.phase==WakePhase.CONVERSATION){if(playing||!runtime.live.turnComplete||runtime.state.value.busy)wake.activity(time);if(wake.timedOut(time,config.value.wake.silenceTimeout,playing||!runtime.live.turnComplete||runtime.state.value.busy))startWaiting()}
        if(config.value.behavior.enabled&&!blocked()){runRule(false);if(config.value.behavior.proactiveAI)think(false)}
        snapshot();delay(1000)
    }}}
    private fun blocked()=waiting||!resumed||runtime.remote.busy||runtime.follow.enabled||runtime.state.value.busy||sceneJob?.isActive==true||runtime.robot.management.state.value.busy||runtime.robot.management.state.value.info.optBoolean("ota_mode")||runtime.actionInProgress()
    fun visual(labels:Set<String>){val key=labels.sorted().joinToString(",");if(key!=visualLabels&&now()-lastVisual>=2){visualLabels=key;lastVisual=now();event("visual_change")}}
    fun runRule(manual:Boolean=true) {
        if(runtime.gate.phase!=Phase.ACTIVE||blocked())return
        val c=config.value
        val scene=coordinator.life.select(now(),catalog,c.idle,c.profile,automaticBody&&runtime.motion.allowed&&runtime.robot.ready.value,runtime.audio.status.value.playing,now()-coordinator.life.lastInteraction<15,manual,c.behavior.idleGapMultiplier,Random.nextDouble())
        if(scene==null){if(coordinator.life.state.reason=="no_eligible_scene") {if(coordinator.decisions.lastOrNull()?.reason!="no_eligible_scene")coordinator.decision(now(),"rule","no_eligible_scene");runtime.face.publish(runtime.expressionState("neutral"))};return}
        coordinator.decision(now(),"rule",scene.id);execute(scene,false)
    }
    private fun execute(scene:IdleScene,preview:Boolean){if(sceneJob?.isActive==true)return;sceneJob=runtime.scope.launch{status.update{it.copy(previewing=preview)};try{runtime.runIdleScene(scene,preview);coordinator.decision(now(),if(preview)"preview" else "scene",scene.id)}catch(e:CancellationException){throw e}catch(_:Exception){coordinator.decision(now(),"cancelled","scene_unavailable");if(preview)runtime.report(t("场景取消或运动未获确认", "Scene cancelled or motion unconfirmed"))}finally{status.update{it.copy(previewing=false)}}}}
    fun preview(scene:IdleScene){if(runtime.gate.phase!=Phase.IDLE||runtime.remote.busy)return;execute(scene,true)}
    fun cancelPreview(){sceneJob?.cancel();sceneJob=null;if(runtime.gate.phase==Phase.TESTING)runtime.cancel()}
    fun pauseActions(){interactionEpoch++;sceneJob?.cancel();sceneJob=null;thinkJob?.cancel();thinkJob=null;coordinator.event(now(),"motion_stopped")}
    fun think(manual:Boolean=true){
        if(runtime.gate.phase!=Phase.ACTIVE||blocked()||thinkJob?.isActive==true||runtime.audio.status.value.playing||runtime.state.value.config.provider=="offline")return
        val c=config.value;if(!manual&&!c.behavior.proactiveAI||!coordinator.admitThought(now(),c.behavior.thoughtsPerMinute))return
        val token=epoch;val interaction=interactionEpoch
        thinkJob=runtime.scope.launch{try{
            val raw=runtime.thoughtRequest("Suggest one allowed companion scene, optionally a short friendly message. Return JSON {\"scene\":null,\"text\":\"\"}. Allowed scenes: "+catalog.filter{!it.body||automaticBody}.joinToString(","){it.id}+". Simulated mood: "+coordinator.life.state.mood)
            if(token!=epoch||interaction!=interactionEpoch||blocked())return@launch
            val parsed=JSONObject(raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            val scene=catalog.firstOrNull{it.id==parsed.optString("scene")}
            if(scene!=null&&coordinator.life.admitSuggestion(scene,now(),c.idle,automaticBody&&runtime.motion.allowed&&runtime.robot.ready.value,runtime.audio.status.value.playing)){coordinator.decision(now(),"thought",scene.id);execute(scene,false)}
            val text=parsed.optString("text").take(300)
            if(text.isNotBlank()&&c.behavior.proactiveExpression&&coordinator.admitExpression(now(),c.behavior.expressionInterval)) {
                if(runtime.state.value.liveMode&&runtime.live.connected.value)runtime.live.text("Say briefly in the user's language: "+text)else runtime.state.update{it.copy(reply=text)}
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){coordinator.decision(now(),"thought_failed","local_rule_fallback");runRule(false)}}
    }
    private fun failWake(reason:String=t("本地唤醒不可用，可手动唤醒或重试", "Local wake unavailable; wake manually or retry")){if(runtime.gate.phase!=Phase.ACTIVE)return;wake.stop(failed=true);detector.stop();status.update{it.copy(localStatus=reason)};coordinator.decision(now(),"wake_failed","offline_recognizer_unavailable");snapshot()}
    suspend fun startWaiting(){
        if(!usesWake()||!resumed)return
        connectJob?.cancel();runtime.live.close();runtime.audio.clearPlayback();pauseActions();runtime.stopMotion()
        wake.wait(now());snapshot()
        if(!runtime.microphoneGranted()){failWake(t("麦克风权限未授予，请在系统设置授权后恢复", "Microphone permission denied; grant it in system settings and restore"));return}
        try{runtime.audio.startOutput();runtime.audio.startInput(::sharedPcm);detector.start(config.value.wake.preset);status.update{it.copy(localStatus=t("本地等待唤醒", "Waiting locally for wake phrase"))}}catch(e:CancellationException){throw e}catch(_:Throwable){failWake()}
    }
    fun retryWake(){runtime.scope.launch{resumed=true;startWaiting();startLoop()}}
    fun manualWake(){
        if(!usesWake()||runtime.gate.phase!=Phase.ACTIVE)return
        if(!resumed){resumed=true;startLoop()}
        if(wake.phase in setOf(WakePhase.FAILED,WakePhase.SUSPENDED,WakePhase.STOPPED))wake.wait(now())
        val token=wake.wake(now())?:return;val session=epoch;detector.stop();event("wake");snapshot()
        connectJob=runtime.scope.launch{try{
            runtime.audio.startOutput();runtime.audio.startInput(::sharedPcm);runtime.connectLive(false)
            if(session!=epoch||token!=wake.generation||!resumed){runtime.live.close();return@launch}
            val buffered=wake.connected(token,now());if(wake.overflow)runtime.report(t("连接耗时过长，请在就绪后重新提问", "Connection took too long; please repeat your question now"))
            else if(buffered!=null){var offset=0;while(offset<buffered.size){val end=minOf(offset+6400,buffered.size);runtime.live.pcm(buffered.copyOfRange(offset,end));offset=end}}
            // Start the silence interval without waiting for a model turn that may never begin.
            runtime.live.markReadyForQuestion();status.update{it.copy(localStatus=t("可以对话", "Ready to talk"))};snapshot()
        }catch(e:CancellationException){throw e}catch(_:Exception){runtime.live.close();failWake(t("连接失败，请检查麦克风权限、模型、凭据和网络后重试", "Connection failed; check microphone permission, model, credential and network, then retry"))}}
    }
    fun sharedPcm(bytes:ByteArray){val token=epoch;runtime.scope.launch{
        if(token!=epoch||!resumed||runtime.gate.phase!=Phase.ACTIVE)return@launch
        var sum=0.0;for(i in 0 until bytes.size/2){val x=((bytes[2*i].toInt()and 255)or(bytes[2*i+1].toInt()shl 8)).toShort()/32768.0;sum+=x*x}
        val voice=sqrt(sum/maxOf(1,bytes.size/2))>.025&&!runtime.audio.status.value.playing
        if(voice&&now()-lastVoice>=1){lastVoice=now();event("user_activity")}
        if(!usesWake()){runtime.live.pcm(bytes);return@launch}
        val forward=wake.append(bytes,now(),voice);if(wake.phase==WakePhase.WAITING&&!runtime.audio.status.value.playing)detector.offer(bytes);forward?.let(runtime.live::pcm)
    }}
    fun liveFailed(){if(usesWake()){connectJob?.cancel();detector.stop();wake.stop(failed=true);snapshot()}}
    fun returnToWaiting(){runtime.scope.launch{startWaiting()}}
    fun suspend(){resumed=false;epoch++;pauseActions();loop?.cancel();connectJob?.cancel();detector.stop();wake.stop(suspended=true);coordinator.cancel(now());snapshot()}
    suspend fun resume(){resumed=true;coordinator.life.tick(now(),config.value.profile,config.value.behavior.sleepAfter);startLoop();if(usesWake())startWaiting()}
    fun stop(){suspend();wake.stop();snapshot()}
    fun diagnostics():String {snapshot();val o=JSONObject().put("schemaVersion",1).put("phase",runtime.gate.phase.name).put("wake",wake.phase.name).put("bufferedBytes",wake.bufferedBytes).put("live",runtime.live.connected.value).put("uploadedVideoFrames",runtime.live.videoCount).put("audioPendingMs",runtime.audio.pendingMs()).put("captureStarts",runtime.audio.captureStarts.get()).put("playbackFailures",runtime.audio.failures.get()).put("audioRouteType",Regex("\\(([0-9]+)\\)$").find(runtime.audio.status.value.route)?.groupValues?.get(1)?:"unknown").put("detectionMs",runtime.camera.inferenceMs.value).put("detectorReady",runtime.camera.detectorReady.value).put("motionSource",runtime.motion.owner?.name?:"none").put("life",JSONObject().put("energy",coordinator.life.state.energy).put("mood",coordinator.life.state.mood).put("reason",coordinator.life.state.reason))
        fun log(v:List<CompanionEvent>)=JSONArray().also{a->v.forEach{a.put(JSONObject().put("time",it.time).put("kind",it.kind).put("reason",it.reason))}}
        return o.put("events",log(coordinator.events)).put("decisions",log(coordinator.decisions)).toString(2)}
    fun clear(){coordinator.clear();snapshot()}
}
