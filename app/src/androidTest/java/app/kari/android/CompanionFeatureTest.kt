package app.kari.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import com.k2fsa.sherpa.onnx.*

@RunWith(AndroidJUnit4::class)
class CompanionFeatureTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun runtime():KariRuntime=(compose.activity.application as KariApplication).runtime
    private fun ready():KariRuntime {val r=runtime();compose.waitUntil(10000){r.state.value.loaded};compose.runOnIdle{r.setLanguage("en");r.clearMessage()};compose.waitUntil(5000){r.state.value.language=="en"};return r}
    @Test fun fourPagesFooterNavigationAndSingleStartStop(){val r=ready()
        compose.onNodeWithTag("homeTab_0").assertIsDisplayed()
        compose.onNodeWithTag("homeTab_1").performClick()
        compose.onNodeWithTag("personalityFooter").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Life state").performScrollTo().performClick()
        compose.onNodeWithText("Simulated life state").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Voice & wake").assertDoesNotExist()
        compose.onNodeWithTag("homeTab_2").performClick()
        compose.onNodeWithTag("visionFooter").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("homeTab_3").performClick()
        compose.onNodeWithText("Voice & wake").performScrollTo().performClick()
        compose.onNodeWithText("Foreground offline wake").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle{r.cameraWanted(false);r.selectProvider("offline")}
        compose.waitUntil(5000){r.state.value.config.provider=="offline"}
        compose.onNodeWithTag("startCompanion").performClick()
        compose.waitUntil(10000){r.state.value.phase==Phase.ACTIVE}
        compose.onNodeWithTag("homeTab_3").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop companionship").performClick()
        compose.waitUntil(10000){r.state.value.phase==Phase.IDLE}
        compose.onNodeWithTag("homeTab_3").assertIsSelected()
    }
    @Test fun persistedImportIsAtomicAndDiagnosticExportPrivate(){val r=ready();val original=r.features.config.value
        try{runBlocking(Dispatchers.Main){r.features.apply(original.copy(profile=original.profile.copy(name="Test robot")))}
            val saved=runBlocking{r.settings.companion(r.features.known)};assertEquals("Test robot",saved.profile.name)
            compose.runOnIdle{r.features.import("""{"schemaVersion":99}""",false)}
            compose.waitUntil(5000){r.state.value.message.isNotEmpty()};assertEquals(saved,r.features.config.value)
            val out=runBlocking(Dispatchers.Main){r.features.diagnostics()};assertFalse(out.contains("Test robot"));assertFalse(out.contains("apiKey"));assertFalse(out.contains("transcript"));assertFalse(out.contains("audioData"))
        }finally{runBlocking(Dispatchers.Main){r.features.apply(original)};compose.runOnIdle{r.clearMessage()}}
    }
    @Test fun scenePreviewReturnsToDetailsAndCanCancel(){val r=ready()
        compose.onNodeWithTag("homeTab_1").performClick();compose.onNodeWithText("Idle scenes").performScrollTo().performClick()
        compose.onNodeWithText("neutral · Expression").performScrollTo().performClick()
        compose.waitUntil(15000){r.face.isReady}
        compose.onNodeWithText("Preview once").performScrollTo().performClick()
        compose.waitUntil(5000){r.features.status.value.previewing}
        compose.onNodeWithText("Scene preview").assertIsDisplayed()
        compose.onNodeWithText("Cancel preview").performClick()
        compose.waitUntil(5000){!r.features.status.value.previewing&&r.gate.phase==Phase.IDLE}
        compose.onNodeWithText("Preview once").performScrollTo().assertIsDisplayed()
    }
    @Test fun startupPermissionFailureKeepsCompanion(){val r=ready();val original=r.features.config.value
        try{
            runBlocking(Dispatchers.Main){r.features.apply(original.copy(wake=WakeSettings(enabled=true)))}
            compose.runOnIdle{r.cameraWanted(false);r.selectProvider("gemini")}
            compose.waitUntil(5000){r.state.value.config.provider=="gemini"}
            compose.runOnIdle{r.startGranted(r.prepareStart()!!)}
            compose.waitUntil(10000){r.state.value.phase==Phase.ACTIVE}
            assertFalse(r.live.connected.value);assertEquals(0,r.live.videoCount)
            compose.runOnIdle{r.features.wake.append(ByteArray(640),r.features.now(),true)}
            assertFalse(r.live.connected.value)
            runBlocking(Dispatchers.Main){r.stop()}
        }finally{runBlocking(Dispatchers.Main){if(r.gate.phase!=Phase.IDLE)r.stop();r.features.apply(original)};compose.runOnIdle{r.selectProvider("offline");r.clearMessage()}}
    }
    @Test fun actualOfflineKeywordModelOnBilingualFixtures(){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val samples=InstrumentationRegistry.getInstrumentation().context.assets
        fun detect(name:String,preset:String):List<String>{
            val base="wake/"
            val spotter=KeywordSpotter(ctx.assets,KeywordSpotterConfig(modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder=base+"encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",decoder=base+"decoder-epoch-13-avg-2-chunk-16-left-64.onnx",joiner=base+"joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx"),tokens=base+"tokens.txt",modelType="zipformer2",numThreads=1),keywordsScore=1.0f,numTrailingBlanks=1,keywordsFile=base+preset+".txt"))
            val stream=spotter.createStream()
            try{
                val bytes=samples.open("wake/$name.wav").use{it.readBytes()};var offset=12;var data=ByteArray(0)
                fun int32(i:Int)=(bytes[i].toInt()and 255)or((bytes[i+1].toInt()and 255)shl 8)or((bytes[i+2].toInt()and 255)shl 16)or(bytes[i+3].toInt()shl 24)
                while(offset+8<=bytes.size){val id=bytes.copyOfRange(offset,offset+4).toString(Charsets.US_ASCII);val size=int32(offset+4);if(id=="data"){data=bytes.copyOfRange(offset+8,offset+8+size);break};offset+=8+size+(size%2)}
                assertTrue(data.size>32000);val all=FloatArray(data.size/2+16000){i->if(i*2+1<data.size)((data[i*2].toInt()and 255)or(data[i*2+1].toInt()shl 8)).toShort()/32768f else 0f};val hits=mutableListOf<String>()
                for(i in all.indices step 320){stream.acceptWaveform(all.copyOfRange(i,minOf(i+320,all.size)),16000);while(spotter.isReady(stream)){spotter.decode(stream);val hit=spotter.getResult(stream).keyword;if(hit.isNotEmpty()){hits+=hit;spotter.reset(stream)}}}
                return hits
            }finally{stream.release();spotter.release()}
        }
        for((name,preset) in listOf("chinese" to "chinese","english" to "english","negative-zh" to "chinese","negative-en" to "english")) {
            val hits=detect(name,preset);android.util.Log.i("KariWakeFixture",name+": "+hits)
            if(name.startsWith("negative"))assertTrue("Unexpected wake in $name: $hits",hits.isEmpty())else assertTrue("Missed wake in $name",hits.isNotEmpty())
        }
    }
}
