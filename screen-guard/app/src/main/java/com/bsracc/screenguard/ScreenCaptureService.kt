package com.bsracc.screenguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.content.pm.ServiceInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: CensorOverlayView? = null
    private var faceDetector: FaceDetector? = null
    private var genderInterpreter: Interpreter? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    @Volatile private var lastFrameAt = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        startAsForeground()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startCapture(resultCode, resultData)
        } catch (error: Exception) {
            stopCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen Guard", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            91,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Screen Guard aktif")
            .setContentText("Sensor layar berjalan di perangkat")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Hentikan",
                    stopPendingIntent
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val display = windowManagerDefaultDisplay()
        val realSize = Point()
        display.getRealSize(realSize)
        screenWidth = realSize.x
        screenHeight = realSize.y
        if (screenWidth <= 0 || screenHeight <= 0) error("Invalid display size")

        workerThread = HandlerThread("ScreenGuardCapture").apply { start() }
        workerHandler = Handler(workerThread!!.looper)

        val faceOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.045f)
            .build()
        faceDetector = FaceDetection.getClient(faceOptions)
        genderInterpreter = loadGenderModel()

        overlayView = CensorOverlayView(this)
        val overlayParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .addView(overlayView, overlayParams)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
            ?: error("MediaProjection unavailable")

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, workerHandler)

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()
            if (processing.get() || now - lastFrameAt < FRAME_INTERVAL_MS) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameAt = now
            if (!processing.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }

            val frame = try {
                imageToBitmap(image)
            } catch (_: Exception) {
                null
            } finally {
                image.close()
            }

            if (frame == null) {
                processing.set(false)
            } else {
                analysisExecutor.execute { analyzeFrame(frame) }
            }
        }, workerHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenGuardDisplay",
            screenWidth,
            screenHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            workerHandler
        )
    }

    private fun windowManagerDefaultDisplay(): android.view.Display {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return wm.defaultDisplay
    }

    private fun loadGenderModel(): Interpreter {
        val asset = assets.openFd("model_lite_gender_q.tflite")
        val buffer = FileInputStream(asset.fileDescriptor).channel.use { channel ->
            channel.map(
                FileChannel.MapMode.READ_ONLY,
                asset.startOffset,
                asset.declaredLength
            )
        }
        asset.close()
        return Interpreter(
            buffer,
            Interpreter.Options().setNumThreads(2)
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (paddedWidth != image.width) {
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        } else {
            padded
        }
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun analyzeFrame(frame: Bitmap) {
        try {
            val maxDimension = 720
            val factor = min(
                1f,
                maxDimension.toFloat() / max(frame.width, frame.height).toFloat()
            )
            val scaled = if (factor < 1f) {
                Bitmap.createScaledBitmap(
                    frame,
                    max(1, (frame.width * factor).toInt()),
                    max(1, (frame.height * factor).toInt()),
                    true
                )
            } else {
                frame
            }
            val scaleX = screenWidth.toFloat() / scaled.width.toFloat()
            val scaleY = screenHeight.toFloat() / scaled.height.toFloat()

            val input = InputImage.fromBitmap(scaled, 0)
            val detector = faceDetector ?: run {
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
                processing.set(false)
                return
            }

            detector.process(input)
                .addOnSuccessListener(analysisExecutor) { faces ->
                    try {
                        val censored = ArrayList<RectF>()
                        faces.forEach { face ->
                            val bounds = face.boundingBox
                            if (bounds.width() < 14 || bounds.height() < 14) return@forEach
                            if (looksFemale(scaled, bounds)) {
                                censored.add(estimateBodyArea(bounds, scaleX, scaleY))
                            }
                        }
                        overlayView?.update(censored)
                    } catch (_: Exception) {
                        overlayView?.update(emptyList())
                    } finally {
                        if (scaled !== frame) scaled.recycle()
                        frame.recycle()
                        processing.set(false)
                    }
                }
                .addOnFailureListener(analysisExecutor) {
                    if (scaled !== frame) scaled.recycle()
                    frame.recycle()
                    overlayView?.update(emptyList())
                    processing.set(false)
                }
        } catch (_: Exception) {
            if (!frame.isRecycled) frame.recycle()
            processing.set(false)
        }
    }

    private fun looksFemale(source: Bitmap, face: Rect): Boolean {
        val left = face.left.coerceIn(0, source.width - 1)
        val top = face.top.coerceIn(0, source.height - 1)
        val right = face.right.coerceIn(left + 1, source.width)
        val bottom = face.bottom.coerceIn(top + 1, source.height)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val resized = Bitmap.createScaledBitmap(crop, MODEL_SIZE, MODEL_SIZE, true)
        if (resized !== crop) crop.recycle()

        val input = ByteBuffer.allocateDirect(
            MODEL_SIZE * MODEL_SIZE * 3 * 4
        ).order(ByteOrder.nativeOrder())
        for (y in 0 until MODEL_SIZE) {
            for (x in 0 until MODEL_SIZE) {
                val pixel = resized.getPixel(x, y)
                input.putFloat(android.graphics.Color.red(pixel) / 255f)
                input.putFloat(android.graphics.Color.green(pixel) / 255f)
                input.putFloat(android.graphics.Color.blue(pixel) / 255f)
            }
        }
        resized.recycle()
        input.rewind()

        val output = Array(1) { FloatArray(2) }
        genderInterpreter?.run(input, output) ?: return false
        // Model author maps index 0 to male and index 1 to female.
        return output[0][1] > output[0][0]
    }

    private fun estimateBodyArea(face: Rect, scaleX: Float, scaleY: Float): RectF {
        val faceWidth = face.width().toFloat()
        val faceHeight = face.height().toFloat()
        val centerX = face.exactCenterX()

        // Conservative rectangle around the person, estimated from face size.
        // This is deliberately not described as a true body segmentation mask.
        val left = ((centerX - faceWidth * 2.8f) * scaleX).coerceIn(0f, screenWidth.toFloat())
        val right = ((centerX + faceWidth * 2.8f) * scaleX).coerceIn(0f, screenWidth.toFloat())
        val top = ((face.top - faceHeight * 0.8f) * scaleY).coerceIn(0f, screenHeight.toFloat())
        val bottom = ((face.top + faceHeight * 16.0f) * scaleY).coerceIn(0f, screenHeight.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun stopCapture() {
        if (stopped) return
        stopped = true
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            projection?.stop()
            projection = null
            faceDetector?.close()
            faceDetector = null
            genderInterpreter?.close()
            genderInterpreter = null
            overlayView?.let {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            }
            overlayView = null
            workerThread?.quitSafely()
            workerThread = null
            analysisExecutor.shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // Best-effort resource cleanup after Android or the user ends capture.
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.bsracc.screenguard.START"
        const val ACTION_STOP = "com.bsracc.screenguard.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_guard_capture"
        private const val NOTIFICATION_ID = 7431
        private const val FRAME_INTERVAL_MS = 350L
        private const val MODEL_SIZE = 128
    }
}
