package expo.modules.mp4muxer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import android.view.Window
import expo.modules.core.interfaces.ActivityEventListener
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.events.OnActivityResultPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections

class ViewCaptureHandler (
    private val context: AppContext,
    private val view: Window,
    private val outputFile: File,
    private val options: EncoderOptions
) {
    private val guardBitmaps = Object()
    private val weakBitmaps = Collections.emptySet<Bitmap>()
    private val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private val iFrameInterval = 5
    private val REQUEST_SCREEN_CAPTURE = 1001
    private var pendingCaptureResult: CompletableDeferred<Boolean>? = null

    private var trackIndex: Int = -1
    private var muxerStarted: Boolean = false
    private var frameIndex: Int = 0

    private lateinit var muxer: MediaMuxer
    private lateinit var encoder: MediaCodec
    private lateinit var encoderSurface: Surface

    private val encodingCompletion = CompletableDeferred<Unit>()

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var mediaProjection: MediaProjection

    private lateinit var eglDisplay: EGLDisplay
    private lateinit var eglConfig: EGLConfig
    private lateinit var eglContext: EGLContext
    private lateinit var eglSurface: EGLSurface
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var sourceSurface: Surface
    private var sourceTextureId: Int = 0


    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f, 0f, // bottom-left
        1f, -1f, 1f, 0f, // bottom-right
        -1f,  1f, 0f, 1f, // top-left
        1f,  1f, 1f, 1f  // top-right
    )

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVertices); position(0) }

    // Simple vertex shader
    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    // Simple fragment shader
    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    private var programId = -1
    private var posHandle = -1
    private var texHandle = -1
    private var texUniformHandle = -1

    init {
        initTexturedQuad()
    }

    fun initTexturedQuad() {
        val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, vertexShaderCode)
            GLES20.glCompileShader(it)
        }

        val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, fragmentShaderCode)
            GLES20.glCompileShader(it)
        }

        programId = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        posHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        texHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        texUniformHandle = GLES20.glGetUniformLocation(programId, "uTexture")
    }

    suspend fun start(): Boolean {
        pendingCaptureResult = CompletableDeferred()
        projectionManager = context.reactContext!!.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager;

        withContext(Dispatchers.Main) {
            context.currentActivity!!.startActivityForResult(
                projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()),
                REQUEST_SCREEN_CAPTURE
            )
        }
        Log.i("MyTag", "awaititng...")
        return pendingCaptureResult!!.await()
    }

    private fun chooseEglConfig(display: EGLDisplay): EGLConfig {
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw RuntimeException("Unable to find a suitable EGLConfig")
        }
        return configs[0]!!
    }

    private fun prepareVirtualDisplaySurface() {
        // 1. Initialize EGL
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, null, 0, null, 0)

        // 2. Choose EGL config
        eglConfig = chooseEglConfig(eglDisplay)

        // 3. Create EGL context
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        // 4. Create EGL surface for encoder
        eglSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(
                EGL14.EGL_WIDTH, context.reactContext!!.resources.displayMetrics.widthPixels,
                EGL14.EGL_HEIGHT, context.reactContext!!.resources.displayMetrics.heightPixels,
                EGL14.EGL_NONE
            ),
            0
        )


        // 5. Create texture & SurfaceTexture for MediaProjection
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        sourceTextureId = tex[0]

        surfaceTexture = SurfaceTexture(sourceTextureId)
        sourceSurface = Surface(surfaceTexture)
    }

    private fun copyToEncoder() {
        val attribList = intArrayOf(EGL14.EGL_NONE)
        val eglEncoderSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, attribList, 0)
//        val eglEncoderSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, encoderSurface, attribList, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglEncoderSurface, eglContext)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTextureId);

        GLES20.glUseProgram(programId)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(posHandle)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTextureId)
        GLES20.glUniform1i(texUniformHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)

        EGL14.eglSwapBuffers(eglDisplay, eglEncoderSurface);
        EGL14.eglDestroySurface(eglDisplay, eglEncoderSurface)
    }

    fun createEncoder() {
        // Prepare MediaProjection surface
        prepareVirtualDisplaySurface()
        val virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            context.reactContext!!.resources.displayMetrics.widthPixels,   // desired width
            context.reactContext!!.resources.displayMetrics.heightPixels,  // desired height
            context.reactContext!!.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            sourceSurface, null, null
        )

        // Prepare output file and muxer
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Prepare encoder
        val format = MediaFormat.createVideoFormat(
            mimeType,
            context.reactContext!!.resources.displayMetrics.widthPixels,
            context.reactContext!!.resources.displayMetrics.heightPixels
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, options.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, options.framerate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        }

        encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        encoderSurface = encoder.createInputSurface()
        // Set callbacks for async buffers handling
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // do nothing. Handles by surface
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                Log.i("MyTag", "New buffer")
                // EOS. Cleanup codec, muxer and surface
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    muxer.stop()
                    muxer.release()
                    codec.stop()
                    codec.release()
                    encoderSurface.release()
                    encodingCompletion.complete(Unit)
                    virtualDisplay.release()
                    return
                }

                val encodedData = codec.getOutputBuffer(index) ?: return

                if (info.size > 0 && muxerStarted) {
                    // Override timestamp to ensure smoothness of video
                    info.presentationTimeUs = frameIndex * (1_000_000L / options.framerate)
                    frameIndex++;

                    encodedData.position(info.offset)
                    encodedData.limit(info.offset + info.size)
                    muxer.writeSampleData(trackIndex, encodedData, info)
                }

                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(
                codec: MediaCodec,
                e: MediaCodec.CodecException
            ) {
                e.printStackTrace()
            }

            override fun onOutputFormatChanged(
                p0: MediaCodec,
                format: MediaFormat
            ) {
                trackIndex = muxer.addTrack(format)
                muxer.start()
                muxerStarted = true
            }
        })

        encoder.start()
    }

    fun capture() {
        copyToEncoder()
    }

    suspend fun finish(): String {
        Log.i("MyTag", "ending stream???")
        encoder.signalEndOfInputStream()
        Log.i("MyTag", "ending stream")
        encodingCompletion.await()
        Log.i("MyTag", "stream ended")
        return outputFile.absolutePath
    }

    fun activityScreenCaptureResultHandler(activity: Activity, payload: OnActivityResultPayload) {
        if (payload.resultCode != REQUEST_SCREEN_CAPTURE || payload.data == null) {
            pendingCaptureResult?.complete(false)
            return
        }

        mediaProjection = projectionManager.getMediaProjection(payload.resultCode, payload.data!!)
        createEncoder()
        pendingCaptureResult?.complete(true)
    }
}