@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.kari.android

import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

private object CompanionSpacing { val page=16.dp;val gap=12.dp;val touch=48.dp }
@Composable fun CompanionHome(r:KariRuntime,s:AppState,page:Int,select:(Int)->Unit,route:String,setRoute:(String)->Unit,robots:()->Unit,debug:()->Unit,configure:()->Unit,start:()->Unit,test:()->Unit,requestCamera:()->Unit) {
    val ctx=LocalContext.current
    val c by r.features.config.collectAsState();val status by r.features.status.collectAsState()
    val management by r.robot.management.state.collectAsState();val ready by r.robot.ready.collectAsState()
    val editable=s.phase==Phase.IDLE&&!r.remote.busy&&!management.busy
    var importProfile by remember{mutableStateOf(false)}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){val profileOnly=importProfile;r.scope.launch{try{val text=withContext(Dispatchers.IO){ctx.contentResolver.openInputStream(uri)!!.use{val b=it.readBytesBounded(if(profileOnly)65536 else 262144);b.toString(Charsets.UTF_8)}};r.features.import(text,profileOnly)}catch(_:Exception){r.report(t("文件无法读取或过大，原配置已保留","Could not read file or file is too large; settings preserved"))}}}}
    fun share(text:String,name:String){try{val dir=File(ctx.cacheDir,"exports").apply{mkdirs()};val f=File(dir,name);f.writeText(text);val uri=FileProvider.getUriForFile(ctx,ctx.packageName+".exports",f);ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),t("导出 JSON","Export JSON")))}catch(_:Exception){r.report(t("无法打开分享界面","Could not open sharing"))}}
    BackHandler(route.isNotEmpty()){r.features.cancelPreview();setRoute("")}
    LaunchedEffect(page,route,s.phase){if(page!=2||route.isNotEmpty())r.stopPreviewCamera()}
    Scaffold(modifier=Modifier.fillMaxSize(),contentWindowInsets=WindowInsets.safeDrawing,
        topBar={if(route.isNotEmpty())TopAppBar(title={Text(when{route=="behavior"->t("行为与待机","Behavior & idle");route=="scenes"||route.startsWith("scene:")->t("待机场景","Idle scenes");route=="life"->t("生命状态","Life state");route=="wake"->t("语音与唤醒","Voice & wake");else->t("诊断","Diagnostics")})},navigationIcon={TextButton(onClick={r.features.cancelPreview();setRoute("")}){Text(t("返回","Back"))}})},
        bottomBar={NavigationBar {
            val labels=listOf(t("机器人","Robot"),t("人格","Personality"),t("视觉","Vision"),t("设置","Settings"))
            val icons=listOf(Icons.Filled.Face,Icons.Filled.Person,Icons.Filled.Search,Icons.Filled.Settings)
            for(i in 0..4) {
                if(i==2)IconButton(onClick=start,enabled=editable&&!management.info.optBoolean("ota_mode"),modifier=Modifier.weight(1f).sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("startCompanion")){Icon(Icons.Filled.PlayArrow,t("开始陪伴","Start companionship"))}
                else {val tab=if(i<2)i else i-1;NavigationBarItem(selected=page==tab,onClick={r.features.cancelPreview();setRoute("");select(tab)},enabled=editable,icon={Icon(icons[tab],null)},label={Text(labels[tab],maxLines=1)},modifier=Modifier.testTag("homeTab_$tab"))}
            }
        }}
    ) {padding->Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)){
        when {
            route=="behavior"->BehaviorPage(c,{r.features.save(it)})
            route=="scenes"->SceneListPage(r,c,{r.features.save(it)},{setRoute("scene:"+it)})
            route.startsWith("scene:")->r.features.catalog.firstOrNull{it.id==route.removePrefix("scene:")}?.let{SceneDetailPage(r,it,c,{r.features.save(it)})}
            route=="life"->LifePage(status)
            route=="wake"->WakeSettingsPage(c,status,{r.features.save(it)})
            route=="diagnostics"->DiagnosticsPage(r,status){share(r.features.diagnostics(),"kari-diagnostics.json")}
            page==0->Column(Modifier.fillMaxSize()){
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text(t("机器人","Robot"),Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);TextButton(onClick=robots,enabled=editable){Text(t("连接","Connect"))};TextButton(onClick=r::openRemote,enabled=ready&&editable){Text(t("遥控器","Remote control"))}}
                if(!ready)PageColumn{Text(t("连接机器人后可进行电机测试、校准、配网及 OTA。无需连接也可开始 AI 陪伴。","Connect a robot for motor tests, calibration, Wi-Fi and OTA. AI companionship also works without a robot."));Button(onClick=robots){Text(t("选择机器人","Choose robot"))};TextButton(onClick=debug){Text(t("管理设置与手动 IP","Management & manual IP"))}}
                else DeviceManagementDialog(r,embedded=true){}
            }
            page==1->PersonalityPage(c,{r.features.save(it)},{setRoute(it)},{importProfile=true;importer.launch(arrayOf("application/json","text/plain","application/octet-stream"))},{share(c.profile.json().toString(2),"kari-personality.json")},{importProfile=false;importer.launch(arrayOf("application/json","text/plain","application/octet-stream"))},{share(c.json().toString(2),"kari-companion.json")})
            page==2->VisionPage(r,s,requestCamera)
            else->SettingsScreen(r,s,robots,debug,configure,start,test,standalone=false,navigate={setRoute(it)})
        }
        if(s.phase in setOf(Phase.STARTING,Phase.TESTING,Phase.STOPPING))Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(),tonalElevation=4.dp){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(24.dp));Text(t("正在准备或测试…","Preparing or testing…"),Modifier.weight(1f).padding(horizontal=12.dp));TextButton(onClick={r.features.cancelPreview();r.cancel()}){Text(t("取消","Cancel"))}}}
    }}
}
private fun java.io.InputStream.readBytesBounded(limit:Int):ByteArray {val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=read(b);if(n<0)break;require(out.size()+n<=limit);out.write(b,0,n)};return out.toByteArray()}
@Composable private fun PageColumn(content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CompanionSpacing.page),verticalArrangement=Arrangement.spacedBy(CompanionSpacing.gap),content=content)}
@Composable private fun Heading(text:String){Text(text,style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics{heading()})}
@Composable private fun Check(label:String,value:Boolean,change:(Boolean)->Unit){Row(Modifier.fillMaxWidth().heightIn(min=CompanionSpacing.touch),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,change,modifier=Modifier.semantics{contentDescription=label})}}
@Composable private fun Scale(label:String,value:Double,range:ClosedFloatingPointRange<Float> = 0f..1f,change:(Double)->Unit){Column{Text(label+" · "+if(range.endInclusive==1f)"${(value*100).toInt()}%" else String.format(java.util.Locale.ROOT,"%.2f",value));Slider(value.toFloat().coerceIn(range),{change(it.toDouble())},valueRange=range,modifier=Modifier.semantics{contentDescription=label})}}
private val traitLabels=listOf("好奇" to "Curiosity","温柔" to "Gentleness","爱玩" to "Playfulness","害羞" to "Shyness","亲近" to "Affection","独立" to "Independence","健谈" to "Talkativeness","谨慎" to "Caution")
private val styleLabels=listOf("动作柔和" to "Movement softness","反应速度" to "Reaction speed","待机活跃" to "Idle activity","情绪表达" to "Emotional expression","犹豫程度" to "Hesitation","空间尊重" to "Personal space")
@Composable private fun PersonalityPage(config:CompanionConfiguration,save:(CompanionConfiguration)->Unit,navigate:(String)->Unit,importProfile:()->Unit,exportProfile:()->Unit,importAll:()->Unit,exportAll:()->Unit){
    var draft by remember(config.profile){mutableStateOf(config.profile)}
    PageColumn{
        Heading(t("人格","Personality"))
        OutlinedTextField(draft.name,{draft=draft.copy(name=it)},label={Text(t("名字","Name"))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(draft.identity,{draft=draft.copy(identity=it)},label={Text(t("身份","Identity"))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(draft.pronouns,{draft=draft.copy(pronouns=it)},label={Text(t("代词","Pronouns"))},modifier=Modifier.fillMaxWidth())
        Heading(t("核心人格","Core traits"))
        PersonalityProfile.traits.keys.forEachIndexed{i,k->Scale(t(traitLabels[i].first,traitLabels[i].second),draft.coreTraits.getValue(k)){draft=draft.copy(coreTraits=draft.coreTraits+(k to it))}}
        Heading(t("行为风格","Behavior style"))
        PersonalityProfile.styles.keys.forEachIndexed{i,k->Scale(t(styleLabels[i].first,styleLabels[i].second),draft.behaviorStyle.getValue(k)){draft=draft.copy(behaviorStyle=draft.behaviorStyle+(k to it))}}
        Heading(t("语言风格","Speech style"))
        Check(t("简短回复","Short replies"),draft.shortReplies){draft=draft.copy(shortReplies=it)}
        Scale(t("温暖程度","Warmth"),draft.warmth){draft=draft.copy(warmth=it)}
        Scale(t("幽默程度","Humor"),draft.humor){draft=draft.copy(humor=it)}
        Scale(t("诗意表达","Poetic expression"),draft.poeticIdentity){draft=draft.copy(poeticIdentity=it)}
        Check(t("避免过度解释","Avoid over-explaining"),draft.avoidOverExplaining){draft=draft.copy(avoidOverExplaining=it)}
        Button(onClick={save(config.copy(profile=draft))},modifier=Modifier.fillMaxWidth()){Text(t("保存人格","Save personality"))}
        TextButton(onClick={draft=PersonalityProfile()}){Text(t("重置草稿","Reset draft"))}
        listOf("behavior" to t("行为与待机","Behavior & idle"),"scenes" to t("待机场景","Idle scenes"),"life" to t("生命状态","Life state")).forEach{(id,title)->OutlinedButton(onClick={navigate(id)},modifier=Modifier.fillMaxWidth()){Text(title)}}
        OutlinedButton(onClick=importProfile,modifier=Modifier.fillMaxWidth()){Text(t("导入人格 JSON","Import personality JSON"))}
        OutlinedButton(onClick=exportProfile,modifier=Modifier.fillMaxWidth()){Text(t("导出人格 JSON","Export personality JSON"))}
        OutlinedButton(onClick=importAll,modifier=Modifier.fillMaxWidth()){Text(t("导入陪伴配置","Import companion configuration"))}
        OutlinedButton(onClick=exportAll,modifier=Modifier.fillMaxWidth().testTag("personalityFooter")){Text(t("导出陪伴配置","Export companion configuration"))}
    }
}
@Composable private fun BehaviorPage(c:CompanionConfiguration,save:(CompanionConfiguration)->Unit){var b by remember(c.behavior){mutableStateOf(c.behavior)};PageColumn{
    Check(t("规则待机","Rule-based idle"),b.enabled){b=b.copy(enabled=it)};Check(t("待机身体动作","Idle body motion"),b.idleBody){b=b.copy(idleBody=it)}
    Check(t("主动 AI 思考","Proactive AI thinking"),b.proactiveAI){b=b.copy(proactiveAI=it)};Check(t("主动表达","Proactive expression"),b.proactiveExpression){b=b.copy(proactiveExpression=it)}
    Scale(t("每分钟思考上限","Thoughts per minute"),b.thoughtsPerMinute.toDouble(),1f..12f){b=b.copy(thoughtsPerMinute=it.toInt())}
    Scale(t("休眠等待秒数","Sleep after seconds"),b.sleepAfter,10f..600f){b=b.copy(sleepAfter=it)}
    Scale(t("表达最短间隔秒数","Minimum expression interval seconds"),b.expressionInterval,60f..600f){b=b.copy(expressionInterval=it)}
    Scale(t("待机间隔倍率","Idle interval multiplier"),b.idleGapMultiplier,.25f..5f){b=b.copy(idleGapMultiplier=it)}
    Text(t("身体待机仍需要本次运动许可；遥控、跟随、语音播放及升级会限制身体动作。","Idle body motion still requires session permission; remote, follow, speech playback and updates restrict body actions."))
    Button(onClick={save(c.copy(behavior=b))}){Text(t("保存","Save"))}
}}
@Composable private fun SceneListPage(r:KariRuntime,c:CompanionConfiguration,save:(CompanionConfiguration)->Unit,select:(String)->Unit){var d by remember(c.idle){mutableStateOf(c.idle)};PageColumn{
    listOf(t("首次待机","First idle") to d.first,t("静默期间","During silence") to d.silent,t("对话间隙","Conversation gap") to d.conversation).forEachIndexed{i,(name,v)->
        Heading(name)
        fun update(value:IdleInterval){d=when(i){0->d.copy(first=value);1->d.copy(silent=value);else->d.copy(conversation=value)}}
        Scale(t("最短秒数","Minimum seconds"),v.minimum,3f..300f){update(v.copy(minimum=it,maximum=maxOf(it,v.maximum)))}
        Scale(t("最长秒数","Maximum seconds"),v.maximum,v.minimum.toFloat()..600f){update(v.copy(maximum=it))}
    }
    Scale(t("全局速度倍率","Global speed multiplier"),d.speedScale,.25f..1.5f){d=d.copy(speedScale=it)}
    Scale(t("全局时长倍率","Global duration multiplier"),d.durationScale,.5f..2f){d=d.copy(durationScale=it)}
    Scale(t("同场景最短重复秒数","Minimum repeat interval seconds"),d.repeatGap,0f..600f){d=d.copy(repeatGap=it)}
    Button(onClick={save(c.copy(idle=d))}){Text(t("保存全局参数","Save global settings"))}
    r.features.catalog.forEach{scene->OutlinedButton(onClick={select(scene.id)},modifier=Modifier.fillMaxWidth()){Text(scene.label()+if(scene.body)t(" · 身体"," · Body")else t(" · 表情"," · Expression"))}}
}}
@Composable private fun SceneDetailPage(r:KariRuntime,scene:IdleScene,c:CompanionConfiguration,save:(CompanionConfiguration)->Unit){var d by remember(scene.id,c.idle){mutableStateOf(c.idle.options(scene.id))};val status by r.features.status.collectAsState();PageColumn{
    Heading(scene.label());Check(t("启用","Enabled"),d.enabled){d=d.copy(enabled=it)}
    Scale(t("相对权重","Relative weight"),d.weight,0f..10f){d=d.copy(weight=it)};Scale(t("冷却秒数","Cooldown seconds"),d.cooldown,0f..600f){d=d.copy(cooldown=it)}
    listOf("always" to t("总是","Always"),"awake" to t("清醒","Awake"),"sleeping" to t("休眠","Sleeping")).forEach{(key,title)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(d.availability==key,{d=d.copy(availability=key)},modifier=Modifier.semantics{contentDescription=title});Text(title)}}
    if(scene.body){Scale(t("速度倍率","Speed multiplier"),d.speedScale,.25f..1.5f){d=d.copy(speedScale=it)};Scale(t("时长倍率","Duration multiplier"),d.durationScale,.5f..2f){d=d.copy(durationScale=it)}}
    Button(onClick={save(c.copy(idle=c.idle.copy(scenes=c.idle.scenes+(scene.id to d))))}){Text(t("保存场景","Save scene"))}
    Text(t("预览使用已保存参数；身体场景会实际移动车轮。","Preview uses saved settings; body scenes physically move the wheels."))
    OutlinedButton(onClick={r.features.preview(scene)},enabled=!status.previewing){Text(t("预览一次","Preview once"))};if(status.previewing)Button(onClick={r.features.cancelPreview()}){Text(t("取消预览","Cancel preview"))}
}}
@Composable private fun LifePage(s:CompanionStatus){PageColumn{Heading(t("模拟生命状态","Simulated life state"));listOf(t("精力","Energy") to s.life.energy,t("无聊","Boredom") to s.life.boredom,t("好奇","Curiosity") to s.life.curiosity,t("亲近","Affection") to s.life.affection,t("孤独","Loneliness") to s.life.loneliness,t("舒适度","Comfort") to s.life.comfort).forEach{(name,v)->Text("$name ${(v*100).toInt()}%");LinearProgressIndicator(progress={v.toFloat()},modifier=Modifier.fillMaxWidth())};Text(t("情绪","Mood")+": "+s.life.mood);Text(t("注意力","Attention")+": "+s.life.attention);Text(t("休眠","Sleeping")+": "+s.life.sleeping);Text(t("变化原因","Change reason")+": "+s.life.reason);Text(t("身份、距离、障碍：未知","Identity, distance, obstacles: unknown"));Heading(t("最近决策","Recent decisions"));s.decisions.takeLast(20).reversed().forEach{Text(it.kind+": "+it.reason)}}}
@Composable private fun WakeSettingsPage(c:CompanionConfiguration,s:CompanionStatus,save:(CompanionConfiguration)->Unit){var w by remember(c.wake){mutableStateOf(c.wake)};PageColumn{
    Check(t("前台本地唤醒","Foreground offline wake"),w.enabled){w=w.copy(enabled=it)}
    listOf("chinese" to "你好卡里","english" to "Hey KARI").forEach{(key,title)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(w.preset==key,{w=w.copy(preset=key)},modifier=Modifier.semantics{contentDescription=title});Text(title)}}
    Scale(t("静默超时秒数","Silence timeout seconds"),w.silenceTimeout,30f..300f){w=w.copy(silenceTimeout=it)}
    Text(t("模型随应用提供。仅 Gemini／标准 Vertex Live 使用；等待时不连接 Live，不上传音视频。需要麦克风权限，不支持锁屏后台唤醒。","Model included. Gemini / standard Vertex Live only. Waiting has no Live connection or media upload. Microphone permission required; no background or lock-screen wake."))
    PermissionStatusCard()
    Text(s.localStatus.ifBlank{t("尚未启动本地监听","Local listening has not started")});Button(onClick={save(c.copy(wake=w))}){Text(t("保存","Save"))}
}}
@Composable fun WakeCompanionControls(r:KariRuntime){val status by r.features.status.collectAsState();if(r.features.usesWake()){Text(when(status.wake){WakePhase.WAITING->t("等待唤醒","Waiting for wake");WakePhase.CONNECTING->t("连接中","Connecting");WakePhase.CONVERSATION->t("对话中","Conversation");else->t("唤醒暂停或不可用","Wake paused or unavailable")});Text(status.localStatus);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){if(status.wake!=WakePhase.CONVERSATION)TextButton(onClick={r.features.manualWake()}){Text(t("手动唤醒","Wake manually"))};if(status.wake==WakePhase.CONVERSATION)TextButton(onClick={r.features.returnToWaiting()}){Text(t("回到等待","Return to waiting"))};if(status.wake in setOf(WakePhase.FAILED,WakePhase.SUSPENDED))TextButton(onClick={r.features.retryWake()}){Text(t("重试本地监听","Retry local listening"))}}}}
@Composable private fun DiagnosticsPage(r:KariRuntime,s:CompanionStatus,export:()->Unit){PageColumn{
    Heading(t("运行诊断","Runtime diagnostics"));val state by r.state.collectAsState();var audio by remember{mutableStateOf(r.audio.status.value)};var cam by remember{mutableStateOf(false)};var live by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){while(true){audio=r.audio.status.value;cam=r.camera.running.value;live=r.live.connected.value;delay(1000)}}
    Text("${state.phase} · Live $live · Camera $cam");Text(t("录音／播放","Recording / playback")+": ${audio.recording} / ${audio.playing}");Text(t("输出路由","Output route")+": "+audio.route);Text(t("缓冲毫秒","Buffered ms")+": "+audio.pendingMs);Text(t("采集启动次数","Capture starts")+": "+r.audio.captureStarts.get());Text(t("唤醒／缓存字节","Wake / buffered bytes")+": ${s.wake} / ${s.bufferedBytes}");Text(t("运动来源","Motion owner")+": "+(r.motion.owner?.name?:"none"));Text(t("跟随","Follow")+": "+r.follow.enabled)
    Button(onClick=export){Text(t("导出脱敏诊断","Export private-safe diagnostics"))};OutlinedButton(onClick={r.features.clear()}){Text(t("清理记录","Clear records"))}
    Heading(t("最近事件","Recent events"));s.events.takeLast(30).reversed().forEach{Text(it.kind+": "+it.reason)};Heading(t("最近决策","Recent decisions"));s.decisions.takeLast(30).reversed().forEach{Text(it.kind+": "+it.reason)}
}}
@Composable private fun VisionPage(r:KariRuntime,s:AppState,requestCamera:()->Unit){
    val running by r.camera.running.collectAsState();val aspect by r.camera.frameAspect.collectAsState();val detections by r.camera.detections.collectAsState();var follow by remember{mutableStateOf(r.followPreset)}
    DisposableEffect(Unit){onDispose{r.stopPreviewCamera()}}
    PageColumn{
        Heading(t("视觉","Vision"));Button(onClick={r.cameraWanted(true);requestCamera()}){Text(t("开启预览","Start preview"))}
        if(running)Box(Modifier.fillMaxWidth().aspectRatio(4f/3f)){
            AndroidView(factory={PreviewView(it).also(r.camera::attach)},modifier=Modifier.fillMaxSize(),onRelease={r.camera.detach(it)})
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()){
                val w=if(size.width/size.height>aspect)size.height*aspect else size.width;val h=w/aspect;val dx=(size.width-w)/2;val dy=(size.height-h)/2
                detections.forEach{d->val x=if(r.camera.front)1-d.box.x-d.box.w else d.box.x;drawRect(if(s.selected==d.id)androidx.compose.ui.graphics.Color.Yellow else androidx.compose.ui.graphics.Color.Cyan,androidx.compose.ui.geometry.Offset(dx+x*w,dy+d.box.y*h),androidx.compose.ui.geometry.Size(d.box.w*w,d.box.h*h),style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))}
            }
        }
        OutlinedButton(onClick=r::flipCamera){Text(t("切换镜头","Switch lens"))};Check(t("本地目标检测","Local detection"),s.detecting){r.detect(it)}
        detections.forEach{d->OutlinedButton(onClick={r.selectTarget(d)},modifier=Modifier.fillMaxWidth()){Text(d.label+" · "+(d.score*100).toInt()+"%"+if(s.selected==d.id)" ✓" else "")}}
        Button(onClick=r::calibrateFollow,enabled=s.selected!=null){Text(t("设置目标参考大小","Set target reference size"))}
        Check(t("陪伴时跟随此目标","Follow this target during companionship"),follow){follow=it;r.updateFollowPreset(it)}
        Text(t("实验功能：开始陪伴后，仅在同一目标持续有效且运动获准时跟随；丢失、切镜头、断线或手动接管均取消，不自动恢复。无测距、避障或防跌落。","Experimental: follows only a valid selected target with motion permission after starting. Loss, lens switch, disconnect or manual takeover cancels it without automatic recovery. No distance sensing, obstacle or fall avoidance."),modifier=Modifier.testTag("visionFooter"))
    }
}

@Composable fun CompanionStopIcon() {
    val image=remember{androidx.compose.ui.graphics.vector.ImageVector.Builder("stop",24.dp,24.dp,24f,24f).addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString("M6,6 L18,6 L18,18 L6,18 Z").toNodes(),fill=androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White)).build()}
    Icon(imageVector=image,contentDescription=t("停止陪伴","Stop companionship"))
}

@Composable fun PermissionStatusCard(){
    val context=LocalContext.current
    val owner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    var version by remember{mutableStateOf(0)}
    DisposableEffect(owner){val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)version++};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
    val states=remember(version){listOf(t("麦克风","Microphone") to android.Manifest.permission.RECORD_AUDIO,t("相机","Camera") to android.Manifest.permission.CAMERA,t("机器人蓝牙","Robot Bluetooth") to if(android.os.Build.VERSION.SDK_INT>=31)android.Manifest.permission.BLUETOOTH_CONNECT else android.Manifest.permission.ACCESS_FINE_LOCATION).map{(title,key)->title to (androidx.core.content.ContextCompat.checkSelfPermission(context,key)==android.content.pm.PackageManager.PERMISSION_GRANTED)}}
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Heading(t("权限","Permissions"));states.forEach{(title,granted)->Text(title+" · "+if(granted)t("已允许","Allowed")else t("未允许","Not allowed"))};OutlinedButton(onClick={context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName)))}){Text(t("打开系统权限设置","Open system permission settings"))}}}
}
