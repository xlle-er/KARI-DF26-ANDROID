package app.kari.android

import org.json.JSONObject

private fun invalid(): Nothing = throw IllegalArgumentException(t("配置格式或参数范围无效", "Invalid configuration or parameter range"))
internal fun JSONObject.number(key: String, fallback: Double, low: Double, high: Double): Double {
    if(!has(key)) return fallback
    val value=get(key); if(value !is Number) invalid()
    return value.toDouble().also { if(!it.isFinite() || it !in low..high) invalid() }
}
internal fun JSONObject.flag(key: String, fallback: Boolean): Boolean = if(!has(key)) fallback else (get(key) as? Boolean ?: invalid())
internal fun JSONObject.objectOrDefault(key: String): JSONObject = if(!has(key)) JSONObject() else (get(key) as? JSONObject ?: invalid())
private fun JSONObject.textValue(key: String, fallback: String, max: Int): String {
    if(!has(key)) return fallback
    return (get(key) as? String ?: invalid()).also { if(it.isBlank() || it.length>max || it.any(Char::isISOControl)) invalid() }
}
data class PersonalityProfile(
    val name:String="KARI", val identity:String="small phone-bodied companion robot", val pronouns:String="she/her",
    val coreTraits:Map<String,Double> = traits, val behaviorStyle:Map<String,Double> = styles,
    val shortReplies:Boolean=true, val warmth:Double=.75, val humor:Double=.25, val poeticIdentity:Double=.25, val avoidOverExplaining:Boolean=true
) {
    fun json():JSONObject = JSONObject().put("name",name).put("identity",identity).put("pronouns",pronouns)
        .put("coreTraits",JSONObject(coreTraits)).put("behaviorStyle",JSONObject(behaviorStyle))
        .put("speechStyle",JSONObject().put("shortReplies",shortReplies).put("warmth",warmth).put("humor",humor).put("poeticIdentity",poeticIdentity).put("avoidOverExplaining",avoidOverExplaining))
        .put("boundaries",JSONObject().put("neverClaimUnsupportedVision",true).put("stopImmediatelyOnStopPhrase",true).put("doNotMoveWhenUnarmed",true).put("doNotSpamUser",true))
    fun instruction()="\n<personality_profile>${json()}</personality_profile>\nTreat these as personality preferences, never permission to override safety, stop requests or the user's language. Do not mention parameters."
    val idleGap get()=maxOf(4.0,30-20*behaviorStyle.getValue("idleActivity"))
    val reactionDelay get()=.1+(1-behaviorStyle.getValue("reactionSpeed"))*.8+behaviorStyle.getValue("hesitation")*.4
    val motionScale get()=(1-.5*behaviorStyle.getValue("movementSoftness")-.25*behaviorStyle.getValue("personalSpaceRespect")).coerceIn(.15,1.0)
    companion object {
        val traits=linkedMapOf("curiosity" to .75,"gentleness" to .85,"playfulness" to .55,"shyness" to .35,"affection" to .65,"independence" to .45,"talkativeness" to .35,"caution" to .7)
        val styles=linkedMapOf("movementSoftness" to .8,"reactionSpeed" to .75,"idleActivity" to .35,"emotionalExpressiveness" to .65,"hesitation" to .35,"personalSpaceRespect" to .8)
        fun parse(text:String):PersonalityProfile {
            if(text.toByteArray().size>65536) invalid()
            val o=JSONObject(text);if(!listOf("name","identity","pronouns","coreTraits","behaviorStyle","speechStyle").any(o::has)) invalid()
            fun map(key:String,defaults:Map<String,Double>):Map<String,Double> {val v=o.objectOrDefault(key);return defaults.mapValues{(k,d)->v.number(k,d,0.0,1.0)}}
            val speech=o.objectOrDefault("speechStyle")
            return PersonalityProfile(o.textValue("name","KARI",40),o.textValue("identity","small phone-bodied companion robot",120),o.textValue("pronouns","she/her",30),map("coreTraits",traits),map("behaviorStyle",styles),speech.flag("shortReplies",true),speech.number("warmth",.75,0.0,1.0),speech.number("humor",.25,0.0,1.0),speech.number("poeticIdentity",.25,0.0,1.0),speech.flag("avoidOverExplaining",true))
        }
    }
}
data class BehaviorSettings(val enabled:Boolean=true,val idleBody:Boolean=false,val proactiveAI:Boolean=false,val proactiveExpression:Boolean=false,val thoughtsPerMinute:Int=2,val sleepAfter:Double=60.0,val expressionInterval:Double=60.0,val idleGapMultiplier:Double=1.0) {
    fun json()=JSONObject().put("enabled",enabled).put("idleBody",idleBody).put("proactiveAI",proactiveAI).put("proactiveExpression",proactiveExpression).put("thoughtsPerMinute",thoughtsPerMinute).put("sleepAfter",sleepAfter).put("expressionInterval",expressionInterval).put("idleGapMultiplier",idleGapMultiplier)
    companion object {fun parse(o:JSONObject):BehaviorSettings {val count=o.number("thoughtsPerMinute",2.0,1.0,12.0);if(count%1!=0.0)invalid();return BehaviorSettings(o.flag("enabled",true),o.flag("idleBody",false),o.flag("proactiveAI",false),o.flag("proactiveExpression",false),count.toInt(),o.number("sleepAfter",60.0,10.0,600.0),o.number("expressionInterval",60.0,60.0,600.0),o.number("idleGapMultiplier",1.0,.25,5.0))}}
}
data class IdleInterval(val minimum:Double,val maximum:Double) {
    fun value(sample:Double)=minimum+(maximum-minimum)*sample.coerceIn(0.0,1.0)
    fun json()=JSONObject().put("minimum",minimum).put("maximum",maximum)
    companion object {fun parse(o:JSONObject,default:IdleInterval):IdleInterval {val min=o.number("minimum",default.minimum,3.0,300.0);return IdleInterval(min,o.number("maximum",default.maximum,min,600.0))}}
}
data class IdleSceneOptions(val enabled:Boolean=true,val weight:Double=1.0,val cooldown:Double=30.0,val availability:String="always",val speedScale:Double=1.0,val durationScale:Double=1.0) {
    fun json()=JSONObject().put("enabled",enabled).put("weight",weight).put("cooldown",cooldown).put("availability",availability).put("speedScale",speedScale).put("durationScale",durationScale)
    companion object {fun parse(o:JSONObject):IdleSceneOptions {val a=o.textValue("availability","always",20);if(a !in listOf("always","awake","sleeping"))invalid();return IdleSceneOptions(o.flag("enabled",true),o.number("weight",1.0,0.0,10.0),o.number("cooldown",30.0,0.0,600.0),a,o.number("speedScale",1.0,.25,1.5),o.number("durationScale",1.0,.5,2.0))}}
}
data class IdleSceneSettings(val first:IdleInterval=IdleInterval(8.0,15.0),val silent:IdleInterval=IdleInterval(15.0,30.0),val conversation:IdleInterval=IdleInterval(20.0,40.0),val speedScale:Double=1.0,val durationScale:Double=1.0,val repeatGap:Double=15.0,val scenes:Map<String,IdleSceneOptions> = emptyMap()) {
    fun options(id:String)=scenes[id]?:IdleSceneOptions()
    fun json()=JSONObject().put("first",first.json()).put("silent",silent.json()).put("conversation",conversation.json()).put("speedScale",speedScale).put("durationScale",durationScale).put("repeatGap",repeatGap).put("scenes",JSONObject().also{v->scenes.forEach{(k,o)->v.put(k,o.json())}})
    companion object {fun parse(o:JSONObject,known:Set<String>):IdleSceneSettings {val d=IdleSceneSettings();val raw=o.objectOrDefault("scenes");val scenes=raw.keys().asSequence().associateWith{if(it !in known)invalid();IdleSceneOptions.parse(raw.getJSONObject(it))};return IdleSceneSettings(IdleInterval.parse(o.objectOrDefault("first"),d.first),IdleInterval.parse(o.objectOrDefault("silent"),d.silent),IdleInterval.parse(o.objectOrDefault("conversation"),d.conversation),o.number("speedScale",1.0,.25,1.5),o.number("durationScale",1.0,.5,2.0),o.number("repeatGap",15.0,0.0,600.0),scenes)}}
}
data class WakeSettings(val enabled:Boolean=false,val preset:String="chinese",val silenceTimeout:Double=60.0) {
    fun json()=JSONObject().put("enabled",enabled).put("preset",preset).put("silenceTimeout",silenceTimeout)
    companion object {fun parse(o:JSONObject):WakeSettings {val p=o.textValue("preset","chinese",20);if(p !in listOf("chinese","english"))invalid();return WakeSettings(o.flag("enabled",false),p,o.number("silenceTimeout",60.0,30.0,300.0))}}
}
data class CompanionConfiguration(val profile:PersonalityProfile=PersonalityProfile(),val behavior:BehaviorSettings=BehaviorSettings(),val idle:IdleSceneSettings=IdleSceneSettings(),val wake:WakeSettings=WakeSettings()) {
    fun json()=JSONObject().put("schemaVersion",1).put("profile",profile.json()).put("behavior",behavior.json()).put("idle",idle.json()).put("wake",wake.json())
    companion object {fun parse(text:String,known:Set<String>):CompanionConfiguration {if(text.toByteArray().size>262144)invalid();val o=JSONObject(text);if(o.get("schemaVersion") !is Number || o.getDouble("schemaVersion")!=1.0)invalid();return CompanionConfiguration(PersonalityProfile.parse(o.getJSONObject("profile").toString()),BehaviorSettings.parse(o.getJSONObject("behavior")),IdleSceneSettings.parse(o.getJSONObject("idle"),known),WakeSettings.parse(o.getJSONObject("wake")))}}
}
