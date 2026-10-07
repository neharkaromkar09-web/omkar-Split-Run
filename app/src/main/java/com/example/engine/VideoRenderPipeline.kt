package com.example.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.example.data.model.EditProject
import com.example.data.model.FrameTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Production-grade Hardware-accelerated OpenGL ES 2.0 + MediaCodec video rendering pipeline.
 * Bakes real transform keyframes (scale, translation, rotation) directly into output video frames.
 * Muxes video and audio in strict monotonic chronological interleaved order for reliable playback.
 */
object VideoRenderPipeline {

    private const val TAG = "VideoRenderPipeline"

    private data class BufferedAudioSample(
        val data: ByteBuffer,
        val presentationTimeUs: Long,
        val flags: Int
    )

    suspend fun renderClip(
        context: Context,
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        editProject: EditProject,
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: CodecInputSurface? = null
        var outputSurface: CodecOutputSurface? = null

        try {
            videoExtractor.setDataSource(context, sourceUri, null)
            val trackCount = videoExtractor.trackCount

            var videoTrackIndex = -1
            var audioTrackIndex = -1

            for (i in 0 until trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "No video track found in source")
                return@withContext false
            }

            // Extract video metadata and rotation
            val rawVideoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val rawWidth = rawVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val rawHeight = rawVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val encWidth = ((rawWidth / 2) * 2).coerceAtLeast(320)
            val encHeight = ((rawHeight / 2) * 2).coerceAtLeast(240)
            val bitRate = rawVideoFormat.optInteger(MediaFormat.KEY_BIT_RATE, 5_000_000).coerceIn(1_000_000, 15_000_000)
            val frameRate = rawVideoFormat.optInteger(MediaFormat.KEY_FRAME_RATE, 30).coerceIn(15, 60)
            val videoMime = rawVideoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC

            var rotation = 0
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, sourceUri)
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                retriever.release()
            } catch (ignored: Exception) {}

            val startUs = (startMs * 1000L).coerceAtLeast(0L)
            val endUs = (endMs * 1000L).coerceAtLeast(startUs + 100000L)
            val durationUs = (endUs - startUs).coerceAtLeast(1000L)

            // Step 1: Pre-buffer audio samples for the clip range (in synchronized relative timestamps)
            val bufferedAudio = mutableListOf<BufferedAudioSample>()
            var audioFormat: MediaFormat? = null

            if (audioTrackIndex != -1) {
                try {
                    audioExtractor.setDataSource(context, sourceUri, null)
                    audioExtractor.selectTrack(audioTrackIndex)
                    audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
                    audioExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                    val audioBuffer = ByteBuffer.allocateDirect(128 * 1024)
                    var audioBaseTimeUs = -1L

                    while (true) {
                        val sampleTimeUs = audioExtractor.sampleTime
                        if (sampleTimeUs < 0 || sampleTimeUs > endUs) break

                        if (sampleTimeUs >= startUs) {
                            if (audioBaseTimeUs == -1L) {
                                audioBaseTimeUs = sampleTimeUs
                            }
                            audioBuffer.clear()
                            val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                            if (sampleSize > 0) {
                                val sampleCopy = ByteBuffer.allocateDirect(sampleSize)
                                audioBuffer.position(0)
                                audioBuffer.limit(sampleSize)
                                sampleCopy.put(audioBuffer)
                                sampleCopy.flip()

                                val relPts = (sampleTimeUs - audioBaseTimeUs).coerceAtLeast(0L)
                                bufferedAudio.add(
                                    BufferedAudioSample(
                                        data = sampleCopy,
                                        presentationTimeUs = relPts,
                                        flags = audioExtractor.sampleFlags
                                    )
                                )
                            }
                        }
                        if (!audioExtractor.advance()) break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio buffering failed, continuing without audio: ${e.message}")
                }
            }

            // Step 2: Configure H.264 Encoder
            val outputVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = CodecInputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()

            // Step 3: Configure Video Decoder targeting CodecOutputSurface
            outputSurface = CodecOutputSurface(encWidth, encHeight)
            decoder = MediaCodec.createDecoderByType(videoMime)
            decoder.configure(rawVideoFormat, outputSurface.surface, null, 0)
            decoder.start()

            // Step 4: Configure Output Muxer
            if (outputFile.exists()) outputFile.delete()
            outputFile.parentFile?.mkdirs()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) {
                try { muxer.setOrientationHint(rotation) } catch (ignored: Exception) {}
            }

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false
            var audioWriteIndex = 0

            // Select video track and seek to startUs keyframe
            videoExtractor.selectTrack(videoTrackIndex)
            videoExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decoderInputBufferInfo = MediaCodec.BufferInfo()
            val encoderBufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 5000L

            var decoderDone = false
            var encoderDone = false
            var videoPtsOffset = -1L
            var lastReportMs = System.currentTimeMillis()

            while (!encoderDone) {
                // 1. Feed Extractor samples to Decoder
                if (!decoderDone) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val sampleSize = videoExtractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decoderDone = true
                            } else {
                                val sampleTimeUs = videoExtractor.sampleTime
                                if (sampleTimeUs > endUs) {
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    decoderDone = true
                                } else {
                                    decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, 0)
                                    videoExtractor.advance()
                                }
                            }
                        }
                    }
                }

                // 2. Decode frames -> Draw into OpenGL ES Surface with authoritative transform -> Swap to Encoder
                var outIndex = decoder.dequeueOutputBuffer(decoderInputBufferInfo, timeoutUs)
                while (outIndex >= 0) {
                    val ptsUs = decoderInputBufferInfo.presentationTimeUs
                    val isEos = (decoderInputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    if (isEos) {
                        encoder.signalEndOfInputStream()
                    }

                    val shouldRender = !isEos && (ptsUs in startUs..endUs)
                    decoder.releaseOutputBuffer(outIndex, shouldRender)

                    if (shouldRender) {
                        outputSurface.awaitNewImage()

                        if (videoPtsOffset == -1L) {
                            videoPtsOffset = ptsUs
                        }

                        // Authoritative transform: exactly queries the EditProject for this frame's timestamp
                        val ptsMs = ptsUs / 1000L
                        val transform = editProject.getTransformAt(ptsMs)

                        // Draw real transformed frame into the encoder's input surface
                        outputSurface.drawImage(transform)

                        val encodedPtsNs = (ptsUs - videoPtsOffset).coerceAtLeast(0L) * 1000L
                        inputSurface.setPresentationTime(encodedPtsNs)
                        inputSurface.swapBuffers()

                        val now = System.currentTimeMillis()
                        if (now - lastReportMs > 200) {
                            val prog = ((ptsUs - startUs).toFloat() / durationUs).coerceIn(0f, 1f)
                            onProgress(prog * 0.85f)
                            lastReportMs = now
                        }
                    }

                    outIndex = decoder.dequeueOutputBuffer(decoderInputBufferInfo, 0)
                }

                // 3. Drain Encoder Output -> Interleave with Audio Samples -> Write to Muxer
                var encIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, timeoutUs)
                while (encIndex >= 0) {
                    if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoder.releaseOutputBuffer(encIndex, false)
                        encIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, 0)
                        continue
                    }

                    if (!muxerStarted) {
                        muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null && bufferedAudio.isNotEmpty()) {
                            muxerAudioTrack = muxer.addTrack(audioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    }

                    val encodedBuffer = encoder.getOutputBuffer(encIndex)
                    if (encodedBuffer != null && encoderBufferInfo.size > 0 && muxerStarted) {
                        val curVideoPtsUs = encoderBufferInfo.presentationTimeUs

                        encodedBuffer.position(encoderBufferInfo.offset)
                        encodedBuffer.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                        muxer.writeSampleData(muxerVideoTrack, encodedBuffer, encoderBufferInfo)

                        // Interleave audio samples up to current video PTS
                        if (muxerAudioTrack != -1) {
                            while (audioWriteIndex < bufferedAudio.size &&
                                bufferedAudio[audioWriteIndex].presentationTimeUs <= curVideoPtsUs
                            ) {
                                val audioSample = bufferedAudio[audioWriteIndex]
                                val info = MediaCodec.BufferInfo().apply {
                                    offset = 0
                                    size = audioSample.data.limit()
                                    presentationTimeUs = audioSample.presentationTimeUs
                                    flags = audioSample.flags
                                }
                                audioSample.data.position(0)
                                muxer.writeSampleData(muxerAudioTrack, audioSample.data, info)
                                audioWriteIndex++
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(encIndex, false)

                    if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true
                        break
                    }

                    encIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, 0)
                }
            }

            // Step 5: Flush any remaining buffered audio samples
            if (muxerStarted && muxerAudioTrack != -1) {
                while (audioWriteIndex < bufferedAudio.size) {
                    val audioSample = bufferedAudio[audioWriteIndex]
                    val info = MediaCodec.BufferInfo().apply {
                        offset = 0
                        size = audioSample.data.limit()
                        presentationTimeUs = audioSample.presentationTimeUs
                        flags = audioSample.flags
                    }
                    audioSample.data.position(0)
                    muxer.writeSampleData(muxerAudioTrack, audioSample.data, info)
                    audioWriteIndex++
                }
            }

            onProgress(1.0f)
            val success = outputFile.exists() && outputFile.length() > 0
            if (success) {
                Log.i(TAG, "Render completed successfully: ${outputFile.name} (${outputFile.length()} bytes)")
            } else {
                Log.e(TAG, "Render failed: output file is missing or 0 bytes")
            }
            return@withContext success

        } catch (e: Exception) {
            Log.e(TAG, "VideoRenderPipeline exception", e)
            return@withContext false
        } finally {
            runCatching { decoder?.stop(); decoder?.release() }
            runCatching { encoder?.stop(); encoder?.release() }
            runCatching { outputSurface?.release() }
            runCatching { inputSurface?.release() }
            runCatching { muxer?.stop(); muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun MediaFormat.optInteger(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultValue
    }
}

/**
 * Manages EGLSurface connected to encoder input Surface
 */
private class CodecInputSurface(private val surface: Surface) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0] ?: throw RuntimeException("Unable to find suitable EGL config")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
    }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun swapBuffers(): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        surface.release()
    }
}

/**
 * Manages SurfaceTexture fed by decoder and renders via GLES20 shader.
 * Uses Main Looper Handler for OnFrameAvailableListener to guarantee callback delivery on coroutine threads.
 */
private class CodecOutputSurface(private val width: Int, private val height: Int) : SurfaceTexture.OnFrameAvailableListener {

    private val surfaceTexture: SurfaceTexture
    val surface: Surface
    private val frameSyncObject = Object()
    private var frameAvailable = false

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()

    private val triangleVerticesData = floatArrayOf(
        -1.0f, -1.0f, 0f, 0f, 0f,
         1.0f, -1.0f, 0f, 1f, 0f,
        -1.0f,  1.0f, 0f, 0f, 1f,
         1.0f,  1.0f, 0f, 1f, 1f
    )

    private val triangleVertices: FloatBuffer
    private var program = 0
    private var textureId = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    init {
        triangleVertices = ByteBuffer.allocateDirect(triangleVerticesData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(triangleVerticesData)
        triangleVertices.position(0)

        program = createProgram(vertexShaderCode, fragmentShaderCode)
        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        // Explicitly register listener on MainLooper so it never drops frames on background threads
        surfaceTexture.setOnFrameAvailableListener(this, Handler(Looper.getMainLooper()))
        surface = Surface(surfaceTexture)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        synchronized(frameSyncObject) {
            frameAvailable = true
            frameSyncObject.notifyAll()
        }
    }

    fun awaitNewImage() {
        val timeoutMs = 2000L
        var hadNewFrame = false
        synchronized(frameSyncObject) {
            val startWait = System.currentTimeMillis()
            while (!frameAvailable) {
                frameSyncObject.wait(timeoutMs)
                if (frameAvailable || (System.currentTimeMillis() - startWait) >= timeoutMs) {
                    break
                }
            }
            hadNewFrame = frameAvailable
            frameAvailable = false
        }
        if (hadNewFrame) {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
        }
    }

    fun drawImage(transform: FrameTransform) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Calculate MVP Matrix with exact scale, position, and rotation
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.translateM(mvpMatrix, 0, transform.positionX, transform.positionY, 0f)
        Matrix.scaleM(mvpMatrix, 0, transform.scale, transform.scale, 1.0f)
        if (transform.rotation != 0f) {
            Matrix.rotateM(mvpMatrix, 0, transform.rotation, 0f, 0f, 1f)
        }

        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, stMatrix, 0)

        triangleVertices.position(0)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, triangleVertices)
        GLES20.glEnableVertexAttribArray(maPositionHandle)

        triangleVertices.position(3)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, triangleVertices)
        GLES20.glEnableVertexAttribArray(maTextureHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}
