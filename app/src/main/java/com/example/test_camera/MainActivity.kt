package com.example.test_camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

data class InferenceResult(
    val classification: Map<String, Float>?,
    val objectDetections: List<BoundingBox>?,
    val visualAnomalyGridCells: List<BoundingBox>?,
    val anomalyResult: Map<String, Float>?,
    val timing: Timing
)
data class BoundingBox(val label: String, val confidence: Float, val x: Int, val y: Int, val width: Int, val height: Int)
data class Timing(val sampling: Int, val dsp: Int, val classification: Int, val anomaly: Int, val dsp_us: Long, val classification_us: Long, val anomaly_us: Long)

private const val TAG = "MainActivity"
private const val REQ = 1001

class BoundingBoxOverlay(ctx: android.content.Context, attrs: android.util.AttributeSet? = null) : View(ctx, attrs) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val anom = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL; alpha = 60 }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 36f }
    var boundingBoxes: List<BoundingBox> = emptyList()
        set(v) { field = v; invalidate() }
    override fun onDraw(c: Canvas) {
        val W = width; val H = height
        for (b in boundingBoxes) {
            val r = Rect(b.x, b.y, (b.x + b.width).coerceAtMost(W), (b.y + b.height).coerceAtMost(H))
            if (b.label.equals("anomaly", true)) {
                c.drawRect(r, anom)
                txt.textAlign = Paint.Align.CENTER
                c.drawText(String.format("%.2f", b.confidence), r.exactCenterX(), r.exactCenterY(), txt)
            } else {
                c.drawRect(r, stroke)
                c.drawText("${b.label} ${(b.confidence * 100).toInt()}%", r.left.toFloat(), (r.top - 10).toFloat(), txt)
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var textureView: TextureView
    private lateinit var overlay: BoundingBoxOverlay
    private lateinit var resultText: TextView

    // Camera2
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val openCloseLock = Semaphore(1)

    // Stream config (conservative)
    private val streamSize = Size(640, 480)

    init { try { System.loadLibrary("test_camera") } catch (t: Throwable) { Log.w(TAG, "JNI not loaded: ${t.message}") } }
    private external fun passToCpp(imageData: ByteArray): InferenceResult?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        overlay = findViewById(R.id.boundingBoxOverlay)
        resultText = findViewById(R.id.resultTextView)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        if (!hasCamPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ)
        } else {
            startWhenReady()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (rc == REQ && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWhenReady()
        else resultText.text = "Camera permission required"
    }

    private fun hasCamPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startWhenReady() {
        if (!textureView.isAvailable) {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        } else openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val id = cameraManager.cameraIdList.firstOrNull() ?: run {
            resultText.text = "No Camera2 IDs from HAL"; return
        }
        try {
            if (!openCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                resultText.text = "Camera open timeout"; return
            }
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    openCloseLock.release()
                    cameraDevice = cd
                    createSession()
                }
                override fun onDisconnected(cd: CameraDevice) {
                    openCloseLock.release(); cd.close(); cameraDevice = null
                }
                override fun onError(cd: CameraDevice, err: Int) {
                    openCloseLock.release(); cd.close(); cameraDevice = null
                    resultText.text = "Camera error: $err"
                }
            }, null)
        } catch (e: Exception) {
            openCloseLock.release()
            resultText.text = "Open failed: ${e.message}"
            Log.e(TAG, "openCamera", e)
        }
    }

    private fun createSession() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: run { resultText.text = "No SurfaceTexture"; return }
        st.setDefaultBufferSize(streamSize.width, streamSize.height)
        val previewSurface = Surface(st)

        imageReader = ImageReader.newInstance(streamSize.width, streamSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                processImage(image)
                image.close()
            }, null)
        }

        val targets = listOf(previewSurface, imageReader!!.surface)

        try {
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        addTarget(imageReader!!.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        // Range import fixed:
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
                    }.build()
                    session.setRepeatingRequest(req, null, null)
                    resultText.text = "Preview started (${streamSize.width}x${streamSize.height})"
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    resultText.text = "CaptureSession configure failed"
                }
            }, null)
        } catch (e: Exception) {
            resultText.text = "Session failed: ${e.message}"
            Log.e(TAG, "createSession", e)
        }
    }

    private fun processImage(image: Image) {
        try {
            val rgb = yuv420ToRgb888(image)
            GlobalScope.launch(Dispatchers.IO) {
                val res = try { passToCpp(rgb) } catch (t: Throwable) { Log.w(TAG, "JNI fail: ${t.message}"); null }
                withContext(Dispatchers.Main) { display(res) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "processImage", t)
        }
    }

    private fun display(res: InferenceResult?) {
        if (res == null) {
            resultText.text = "No result"
            overlay.boundingBoxes = emptyList()
            return
        }
        val sb = StringBuilder()
        res.classification?.maxByOrNull { it.value }?.let { sb.append("Top: ${it.key} ${(it.value * 100).toInt()}%  ") }
        res.anomalyResult?.get("anomaly")?.let { sb.append("Anomaly: ${(it * 100).toInt()}%  ") }
        res.objectDetections?.let { if (it.isNotEmpty()) sb.append("Detections: ${it.size}") }
        resultText.text = if (sb.isNotEmpty()) sb.toString() else "Running…"
        overlay.boundingBoxes = res.objectDetections ?: emptyList()
    }

    private fun yuv420ToRgb888(image: Image): ByteArray {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val ySize = y.remaining(); val uSize = u.remaining(); val vSize = v.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        y.get(nv21, 0, ySize)
        var off = ySize
        repeat(minOf(uSize, vSize)) {
            nv21[off++] = v.get()
            nv21[off++] = u.get()
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        return bitmapToRgb888(bmp)
    }

    private fun bitmapToRgb888(bmp: Bitmap): ByteArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3)
        var i = 0
        for (p in px) {
            out[i++] = ((p shr 16) and 0xFF).toByte()
            out[i++] = ((p shr 8) and 0xFF).toByte()
            out[i++] = (p and 0xFF).toByte()
        }
        return out
    }

    override fun onStop() {
        super.onStop()
        try {
            openCloseLock.acquire()
            captureSession?.close(); captureSession = null
            imageReader?.close(); imageReader = null
            cameraDevice?.close(); cameraDevice = null
        } catch (_: InterruptedException) {
        } finally {
            openCloseLock.release()
        }
    }
}
