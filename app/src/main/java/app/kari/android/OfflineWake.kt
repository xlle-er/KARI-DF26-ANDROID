package app.kari.android

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Only consumes shared PCM. This class never acquires a microphone or opens a socket. */
class OfflineWake(private val context:Context,private val scope:CoroutineScope,private val hit:()->Unit,private val failure:()->Unit) {
    private var worker:Job?=null
    private var frames:Channel<ByteArray>?=null
    private var generation=0
    suspend fun start(preset:String) {
        stop();worker?.join()
        val token=++generation
        val queue=Channel<ByteArray>(64);frames=queue
        val ready=CompletableDeferred<Unit>()
        worker=scope.launch(Dispatchers.Default) {
            var spotter:KeywordSpotter?=null;var stream:OnlineStream?=null
            try {
                val base="wake/"
                spotter=KeywordSpotter(context.assets,KeywordSpotterConfig(modelConfig=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder=base+"encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",decoder=base+"decoder-epoch-13-avg-2-chunk-16-left-64.onnx",joiner=base+"joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx"),tokens=base+"tokens.txt",modelType="zipformer2",numThreads=1,provider="cpu"),keywordsScore=1.0f,numTrailingBlanks=1,keywordsFile=base+preset+".txt"))
                stream=spotter.createStream();ready.complete(Unit)
                for(bytes in queue) {
                    ensureActive()
                    val samples=FloatArray(bytes.size/2){i->((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort()/32768f}
                    stream.acceptWaveform(samples,16000)
                    while(spotter.isReady(stream))spotter.decode(stream)
                    if(spotter.getResult(stream).keyword.isNotEmpty()) {spotter.reset(stream);withContext(Dispatchers.Main.immediate){if(token==generation)hit()}}
                }
            } catch(e:CancellationException){throw e}
            catch(_:Throwable){ready.completeExceptionally(IllegalStateException("offline_wake_unavailable"));withContext(Dispatchers.Main.immediate){if(token==generation)failure()}}
            finally{stream?.release();spotter?.release();queue.close()}
        }
        ready.await()
    }
    fun offer(pcm:ByteArray) {val q=frames?:return;if(q.trySend(pcm).isFailure){stop();failure()}}
    fun stop(){generation++;frames?.close();frames=null;worker?.cancel()}
}
