package com.wilfrombdn.telegramnt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Telegram-style in-app camera: tap the button for a photo, hold it to record video.
 * Pinch to zoom, or (while recording) slide your finger up/down to zoom. Everything is written
 * only to the app's own cache and handed straight back to the chat — nothing touches the gallery.
 */
class CameraActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var shutter: View
    private lateinit var timer: TextView
    private lateinit var hint: TextView

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: androidx.camera.core.Camera? = null
    private var front = false

    private var recording: Recording? = null
    private var recordStart = 0L
    private var pressStart = 0L
    private var pressY = 0f
    private var zoomAtPress = 1f
    private var holding = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val startVideo = Runnable { beginVideo() }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera()
        else { toast("Camera permission is needed to take photos"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)   // no screenshots / recents preview
        cacheDir.resolve("camera").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        setContentView(buildUi())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askPerms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        // pinch-to-zoom anywhere on the viewfinder
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cur = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: return true
                setZoom(cur * d.scaleFactor)
                return true
            }
        })
        // double-tap the viewfinder to switch between front and back cameras (not mid-video)
        val taps = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                if (recording == null) { front = !front; startCamera() }
                return true
            }
        })
        preview.setOnTouchListener { _, e -> pinch.onTouchEvent(e); if (!pinch.isInProgress) taps.onTouchEvent(e); true }

        timer = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; text = "● 0:00"
            setPadding(d(14), d(6), d(14), d(6))
            background = GradientDrawable().apply { setColor(0xCCCC2E2E.toInt()); cornerRadius = d(20).toFloat() }
            visibility = View.GONE
        }
        root.addView(timer, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = d(28) })

        // close (X)
        root.addView(iconButton("✕") { finish() }, FrameLayout.LayoutParams(d(48), d(48), Gravity.TOP or Gravity.START).apply { leftMargin = d(12); topMargin = d(20) })
        // flip camera
        root.addView(iconButton("⟲") { front = !front; startCamera() }, FrameLayout.LayoutParams(d(48), d(48), Gravity.TOP or Gravity.END).apply { rightMargin = d(12); topMargin = d(20) })

        hint = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt()); textSize = 13f; gravity = Gravity.CENTER
            text = "Tap for photo  ·  hold for video  ·  slide up to zoom\nDouble-tap the screen to flip the camera"
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = d(130) })

        // shutter button
        shutter = View(this).apply {
            background = ring(Color.WHITE, Color.TRANSPARENT)
            setOnTouchListener { _, e -> onShutterTouch(e); true }
        }
        root.addView(shutter, FrameLayout.LayoutParams(d(74), d(74), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = d(40) })
        return root
    }

    private fun iconButton(glyph: String, onClick: () -> Unit) = TextView(this).apply {
        text = glyph; setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
        setOnClickListener { onClick() }
    }

    private fun ring(ringColor: Int, fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(fill); setStroke(d(5), ringColor)
    }

    // ---- camera setup ----

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p
            val prev = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(this, selector, prev, imageCapture, videoCapture)
            } catch (e: Exception) {
                // some devices can't run photo + video together; fall back to photo only
                try {
                    p.unbindAll()
                    videoCapture = null
                    camera = p.bindToLifecycle(this, selector, prev, imageCapture)
                    hint.text = "Tap for photo"
                } catch (e2: Exception) { toast("Couldn't open the camera"); finish() }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setZoom(ratio: Float) {
        val zs = camera?.cameraInfo?.zoomState?.value ?: return
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
    }

    // ---- shutter: tap = photo, hold = video, slide up/down = zoom ----

    private fun onShutterTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressStart = SystemClock.uptimeMillis()
                pressY = e.rawY
                zoomAtPress = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                holding = true
                if (videoCapture != null) ui.postDelayed(startVideo, 350)   // becomes a video if held
            }
            MotionEvent.ACTION_MOVE -> {
                if (recording != null) {
                    val zs = camera?.cameraInfo?.zoomState?.value ?: return
                    val up = (pressY - e.rawY)                              // slide up = positive
                    val frac = (up / (resources.displayMetrics.heightPixels * 0.4f)).coerceIn(0f, 1f)
                    setZoom(zoomAtPress + frac * (zs.maxZoomRatio - zoomAtPress))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                holding = false
                ui.removeCallbacks(startVideo)
                if (recording != null) stopVideo()
                else if (SystemClock.uptimeMillis() - pressStart < 350) takePhoto()
            }
        }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val file = File(cacheDir, "camera/photo_${System.currentTimeMillis()}.jpg")
        shutter.isEnabled = false
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) = handBack(file, true)
                override fun onError(e: ImageCaptureException) { shutter.isEnabled = true; toast("Couldn't take the photo") }
            })
    }

    private fun beginVideo() {
        val vc = videoCapture ?: return
        val file = File(cacheDir, "camera/video_${System.currentTimeMillis()}.mp4")
        val opts = FileOutputOptions.Builder(file).setFileSizeLimit(20L * 1024 * 1024).build()   // under the 25 MB send cap
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val pending = vc.output.prepareRecording(this, opts).apply { if (micOk) withAudioEnabled() }
        recordStart = SystemClock.uptimeMillis()
        shutter.background = ring(0xFFE53935.toInt(), 0x55E53935)
        timer.visibility = View.VISIBLE; hint.visibility = View.GONE
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                val hardError = ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                if (hardError || !file.exists() || file.length() == 0L) {
                    file.delete(); resetShutter(); toast("Couldn't record that video")
                } else handBack(file, false)
            }
        }
        tick()   // start the timer only once `recording` is set, or its null-guard bails out immediately
    }

    private fun tick() {
        if (recording == null) return
        val s = ((SystemClock.uptimeMillis() - recordStart) / 1000).toInt()
        timer.text = "● %d:%02d".format(s / 60, s % 60)
        ui.postDelayed({ tick() }, 500)
    }

    private fun stopVideo() {
        recording?.stop()
        recording = null
    }

    private fun resetShutter() {
        recording = null
        shutter.background = ring(Color.WHITE, Color.TRANSPARENT)
        shutter.isEnabled = true
        timer.visibility = View.GONE; hint.visibility = View.VISIBLE
    }

    private fun handBack(file: File, isPhoto: Boolean) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        setResult(RESULT_OK, Intent().setData(uri).putExtra("photo", isPhoto)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (recording != null) stopVideo()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
