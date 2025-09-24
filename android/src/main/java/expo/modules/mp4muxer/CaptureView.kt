package expo.modules.mp4muxer

import android.app.Activity
import android.content.Context
import android.util.Log
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.events.OnActivityResultPayload
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.views.ExpoView
import java.io.File

class CaptureView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
    private var bitrate: Int = 1_000_000
    private var framerate: Int = 30

    private var captureHandler: ViewCaptureHandler? = null
    private var outputFile: File?= null

    init {
        outputFile = File(context.getExternalFilesDir(null), "capture_${System.currentTimeMillis()}.mp4")
        captureHandler = ViewCaptureHandler(appContext,appContext.currentActivity?.window!!, outputFile!!, EncoderOptions(framerate, bitrate))
    }

    fun setBitrate(data: Int) {
        this.bitrate = data
    }

    fun setFramerate(data: Int) {
        this.framerate = data
    }

    fun activityResultHandler(activity: Activity, payload: OnActivityResultPayload) {
        if (captureHandler == null) {
            throw CodedException("INVALID_CALL", "Frame handler is not initialized", null)
        }
        captureHandler!!.activityScreenCaptureResultHandler(activity, payload)
    }

    suspend fun start(): Boolean {
        if (captureHandler == null) {
            throw CodedException("INVALID_CALL", "Frame handler is not initialized", null)
        }
        return captureHandler!!.start()
    }

    fun capture() {
        if (captureHandler == null) {
            throw CodedException("INVALID_CALL", "Frame handler is not initialized", null)
        }
        captureHandler!!.capture()
    }

    suspend fun finish(): String {
        if (captureHandler == null) {
            Log.i("MyTag", "INSIDE FINISH ERROR")
            throw CodedException("INVALID_CALL", "Cannot finish encoding when handler is not initialized", null)
        }
        val res = captureHandler!!.finish()
        captureHandler = null
        return res
    }
}