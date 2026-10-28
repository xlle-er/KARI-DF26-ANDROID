package app.kari.android

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

data class IdleScene(val id:String,val title:String,val titleZH:String,val body:Boolean,val pose:String?,val steps:List<JSONObject>) {
    fun label()=if(L10n.chinese)titleZH else title
    companion object {fun parse(text:String):List<IdleScene> {val a=JSONArray(text);return (0 until a.length()).map {val o=a.getJSONObject(it);val s=o.getJSONArray("steps");IdleScene(o.getString("id"),o.getString("title"),o.getString("titleZH"),o.getBoolean("body"),o.optString("pose").takeIf(String::isNotBlank),(0 until s.length()).map(s::getJSONObject))}}}
}
data class LifeState(val energy:Double=.75,val boredom:Double=.2,val curiosity:Double=.6,val affection:Double=.5,val loneliness:Double=.1,val comfort:Double=.6,val mood:String="neutral",val attention:String="none",val sleeping:Boolean=false,val speaking:Boolean=false,val moving:Boolean=false,val reason:String="session_started")
class LifeEngine {
    var state=LifeState(); private set
    private var previous:Double?=null;private var suspendedAt:Double?=null
    var lastInteraction=0.0;private set
    private var nextScene=0.0;private var lastScene:String?=null;private var lastSceneAt=Double.NEGATIVE_INFINITY
    private val executions=mutableMapOf<String,Double>()
    fun begin(now:Double,settings:IdleSceneSettings,sample:Double=.5) {state=LifeState();previous=now;suspendedAt=null;lastInteraction=now;nextScene=now+settings.first.value(sample);lastScene=null;lastSceneAt=Double.NEGATIVE_INFINITY;executions.clear()}
    fun suspendClock(){suspendedAt=previous;previous=null}
    fun event(kind:String,now:Double) {
        val s=state
        state=when(kind) {
            "user_activity","wake"->{lastInteraction=now;s.copy(sleeping=false,attention="interaction",boredom=0.0,loneliness=(s.loneliness-.08).coerceIn(0.0,1.0),comfort=(s.comfort+.04).coerceIn(0.0,1.0),affection=(s.affection+.02).coerceIn(0.0,1.0),curiosity=(s.curiosity+.05).coerceIn(0.0,1.0))}
            "visual_change"->s.copy(curiosity=(s.curiosity+.02).coerceIn(0.0,1.0))
            "playback_started"->s.copy(speaking=true)
            "playback_stopped"->s.copy(speaking=false)
            "motion_started"->s.copy(moving=true)
            "motion_stopped","disconnected"->s.copy(moving=false)
            else->s
        }.copy(reason=kind)
    }
    fun tick(now:Double,p:PersonalityProfile,sleepAfter:Double) {
        val old=previous
        if(old==null) {suspendedAt?.let {val pause=max(0.0,now-it);lastInteraction+=pause;nextScene+=pause;lastSceneAt+=pause;executions.replaceAll{_,v->v+pause}};suspendedAt=null;previous=now;return}
        val dt=(now-old).coerceIn(0.0,2.0);previous=now;val idle=now-lastInteraction;val s=state
        val energy=(s.energy+(if(s.sleeping).004 else if(s.moving)-.01 else -.001)*dt).coerceIn(0.0,1.0)
        val boredom=(s.boredom+(if(idle>15)(.006+p.behaviorStyle.getValue("idleActivity")*.012)*2 else -.06)*dt).coerceIn(0.0,1.0)
        val loneliness=(s.loneliness+(if(idle>30)(.003+(1-p.coreTraits.getValue("independence"))*.006)*2 else -.008)*dt).coerceIn(0.0,1.0)
        val sleeping=idle>=sleepAfter
        state=s.copy(energy=energy,boredom=boredom,loneliness=loneliness,curiosity=(s.curiosity+(if(idle>8)(.002+p.coreTraits.getValue("curiosity")*.004)*2*dt else 0.0)).coerceIn(0.0,1.0),comfort=(s.comfort-if(idle>30).004*dt else 0.0).coerceIn(0.0,1.0),sleeping=sleeping,mood=if(sleeping||energy<.2)"sleepy" else if(idle<2.5)"attentive" else if(boredom>.7||loneliness>.72)"curious" else "neutral",attention=if(sleeping)"resting" else if(idle<8)"interaction" else "surroundings")
    }
    fun eligible(scene:IdleScene,now:Double,settings:IdleSceneSettings,bodyAllowed:Boolean,speaking:Boolean):Boolean {
        val o=settings.options(scene.id)
        return o.enabled&&o.weight>0&&(!scene.body||bodyAllowed&&!speaking&&!state.sleeping)&&(o.availability=="always"||o.availability=="sleeping"&&state.sleeping||o.availability=="awake"&&!state.sleeping)&&now-(executions[scene.id]?:Double.NEGATIVE_INFINITY)>=o.cooldown&&(scene.id!=lastScene||now-lastSceneAt>=settings.repeatGap)
    }
    fun admitSuggestion(scene:IdleScene,now:Double,settings:IdleSceneSettings,bodyAllowed:Boolean,speaking:Boolean):Boolean {
        if(!eligible(scene,now,settings,bodyAllowed,speaking))return false
        executions[scene.id]=now;lastScene=scene.id;lastSceneAt=now;state=state.copy(reason=scene.id);return true
    }
    fun select(now:Double,catalog:List<IdleScene>,settings:IdleSceneSettings,p:PersonalityProfile,bodyAllowed:Boolean,speaking:Boolean,conversation:Boolean,manual:Boolean=false,gapMultiplier:Double=1.0,sample:Double=.5):IdleScene? {
        if((!manual&&now<nextScene)||now-lastInteraction<3)return null
        nextScene=now+(if(conversation)settings.conversation else settings.silent).value(sample)*max(.25,p.idleGap/20)*gapMultiplier
        val candidates=catalog.filter{eligible(it,now,settings,bodyAllowed,speaking)}
        fun weight(s:IdleScene):Double {val expression=p.behaviorStyle.getValue("emotionalExpressiveness");return settings.options(s.id).weight*(if(s.pose=="pose_"+state.mood)2.0 else 1.0)*(if(s.pose=="pose_happy").5+p.coreTraits.getValue("playfulness") else if(s.pose=="pose_curious").5+state.curiosity else 1.0)*(if(s.pose=="pose_neutral")1.5-expression else if(s.body)1.0 else max(.05,expression))}
        val total=candidates.sumOf(::weight);if(total<=0){state=state.copy(reason="no_eligible_scene");return null}
        var pick=sample.coerceIn(0.0,.999999)*total
        val chosen=candidates.firstOrNull{pick-=weight(it);pick<0}?:candidates.last()
        executions[chosen.id]=now;lastScene=chosen.id;lastSceneAt=now;state=state.copy(reason=chosen.id);return chosen
    }
}
data class CompanionEvent(val time:Double,val kind:String,val reason:String)
class BehaviorCoordinator {
    val life=LifeEngine()
    private val eventLog=ArrayDeque<CompanionEvent>();private val decisionLog=ArrayDeque<CompanionEvent>()
    val events get()=eventLog.toList();val decisions get()=decisionLog.toList()
    private val thoughts=ArrayDeque<Double>();private var lastExpression=Double.NEGATIVE_INFINITY
    var epoch=0;private set
    fun begin(now:Double,settings:IdleSceneSettings) {epoch++;life.begin(now,settings);thoughts.clear();lastExpression=Double.NEGATIVE_INFINITY;event(now,"session_started")}
    fun cancel(now:Double) {epoch++;life.suspendClock();event(now,"suspended")}
    fun event(now:Double,kind:String,reason:String="") {life.event(kind,now);eventLog.addLast(CompanionEvent(now,kind,reason.take(160)));while(eventLog.size>500)eventLog.removeFirst()}
    fun decision(now:Double,kind:String,reason:String) {decisionLog.addLast(CompanionEvent(now,kind,reason.take(160)));while(decisionLog.size>100)decisionLog.removeFirst()}
    fun clear(){eventLog.clear();decisionLog.clear()}
    fun admitThought(now:Double,limit:Int):Boolean {while(thoughts.isNotEmpty()&&now-thoughts.first()>=60)thoughts.removeFirst();if(thoughts.size>=limit||thoughts.lastOrNull()?.let{now-it<5}==true)return false;thoughts.addLast(now);return true}
    fun admitExpression(now:Double,interval:Double):Boolean {if(now-lastExpression<max(60.0,interval))return false;lastExpression=now;return true}
}
enum class WakePhase { STOPPED,WAITING,CONNECTING,CONVERSATION,SUSPENDED,FAILED }
class WakePolicy {
    var phase=WakePhase.STOPPED;private set
    var generation=0;private set
    var overflow=false;private set
    var lastActivity=0.0;private set
    private var lastWake=Double.NEGATIVE_INFINITY
    private var buffer=ByteArray(0)
    val bufferedBytes get()=buffer.size
    fun wait(now:Double){generation++;phase=WakePhase.WAITING;buffer=ByteArray(0);overflow=false;lastActivity=now}
    fun wake(now:Double):Int? {if(phase!=WakePhase.WAITING||now-lastWake<2)return null;phase=WakePhase.CONNECTING;lastWake=now;lastActivity=now;return generation}
    fun append(pcm:ByteArray,now:Double,voice:Boolean):ByteArray? {
        if(voice)activity(now)
        when(phase){
            WakePhase.WAITING->{buffer=(buffer+pcm).takeLast(32000).toByteArray()}
            WakePhase.CONNECTING->{if(!overflow){if(buffer.size+pcm.size>256000){buffer=ByteArray(0);overflow=true}else buffer+=pcm}}
            WakePhase.CONVERSATION->return pcm
            else->Unit
        };return null
    }
    fun connected(token:Int,now:Double):ByteArray? {if(token!=generation||phase!=WakePhase.CONNECTING)return null;phase=WakePhase.CONVERSATION;activity(now);return buffer.also{buffer=ByteArray(0)}}
    fun activity(now:Double){lastActivity=now}
    fun timedOut(now:Double,timeout:Double,busy:Boolean)=phase==WakePhase.CONVERSATION&&!busy&&now-lastActivity>=timeout
    fun stop(suspended:Boolean=false,failed:Boolean=false){generation++;phase=if(suspended)WakePhase.SUSPENDED else if(failed)WakePhase.FAILED else WakePhase.STOPPED;buffer=ByteArray(0);overflow=false}
}
