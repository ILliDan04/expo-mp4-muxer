package expo.modules.mp4muxer

import android.util.Log
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.functions.Coroutine

class ExpoMp4MuxerModule : Module() {
    private lateinit var captureView: CaptureView

    override fun definition() = ModuleDefinition {
        Name("ExpoMp4Muxer")

        View(CaptureView::class) {
            Prop<Int>("framerate") { view, data ->
                view.setFramerate(data)
            }
            Prop<Int>("bitrate") { view, data ->
                view.setBitrate(data)
            }

            AsyncFunction("start") Coroutine { view: CaptureView ->
                captureView = view
                return@Coroutine view.start()
            }
            AsyncFunction("capture") Coroutine { view: CaptureView ->
                view.capture()
                return@Coroutine
            }
            AsyncFunction("finish") Coroutine { view: CaptureView ->
                return@Coroutine view.finish()
            }
        }

        OnActivityResult { activity, payload -> {
            Log.i("MyTag", "response received")
            captureView.activityResultHandler(activity, payload)
        } }
    }
}
