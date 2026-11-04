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
@androidx.annotation.OptIn(androidx.camera.view.ExperimentalPreviewViewScreenFlash::class)
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
    private var flashOn = false                    // per visit: a new camera always starts with the flash off
    private var timerSecs = 0                      // per visit: a new camera always starts instant (0)
    private var counting = false
    private lateinit var flashBtn: TextView
    private lateinit var timerBtn: TextView
    private lateinit var countdown: TextView
    private var snap = false                       // snap mode: after the shot, add a caption and send
    private lateinit var rootView: FrameLayout
    private var editLayer: FrameLayout? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val startVideo = Runnable { beginVideo() }

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera()
        else { toast("Camera permission is needed to take photos"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)   // no screenshots / recents preview
        snap = intent.getBooleanExtra("snap", false)
        cacheDir.resolve("camera").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        setContentView(buildUi())
        refreshControls()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askPerms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        rootView = root
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
        root.addView(iconButton("✕") { cancelCountdown(); finish() }, FrameLayout.LayoutParams(d(48), d(48), Gravity.TOP or Gravity.START).apply { leftMargin = d(12); topMargin = d(20) })
        // flip camera
        root.addView(iconButton("⟲") { front = !front; startCamera() }, FrameLayout.LayoutParams(d(48), d(48), Gravity.TOP or Gravity.END).apply { rightMargin = d(12); topMargin = d(20) })
        // flash and timer: both are off every time the camera opens (nothing is remembered)
        flashBtn = iconButton("⚡") { flashOn = !flashOn; applyFlash(); refreshControls() }
        root.addView(flashBtn, FrameLayout.LayoutParams(d(48), d(48), Gravity.TOP or Gravity.END).apply { rightMargin = d(12); topMargin = d(76) })
        timerBtn = iconButton("⏱") { timerSecs = TIMER_STEPS[(TIMER_STEPS.indexOf(timerSecs) + 1) % TIMER_STEPS.size]; refreshControls() }
        root.addView(timerBtn, FrameLayout.LayoutParams(d(48), d(48), Gravity.TOP or Gravity.END).apply { rightMargin = d(12); topMargin = d(132) })
        countdown = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 110f; gravity = Gravity.CENTER; visibility = View.GONE
            setShadowLayer(d(8).toFloat(), 0f, 0f, 0xAA000000.toInt())
        }
        root.addView(countdown, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        preview.setScreenFlashWindow(window)   // front camera: the screen lights up as the flash

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
            // fast shutter, but still the sensor's biggest picture (the quality mode made the shutter feel slow)
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .setAspectRatioStrategy(androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                .build()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(this, selector, screenShaped(prev, imageCapture!!, videoCapture))
            } catch (e: Exception) {
                // some devices can't run photo + video together; fall back to photo only
                try {
                    p.unbindAll()
                    videoCapture = null
                    camera = p.bindToLifecycle(this, selector, screenShaped(prev, imageCapture!!, null))
                    hint.text = "Tap for photo"
                } catch (e2: Exception) { toast("Couldn't open the camera"); finish() }
            }
            imageCapture?.screenFlash = preview.screenFlash   // front camera flash = the screen
            applyFlash()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Photos and videos are cropped to exactly what the viewfinder shows (the phone's screen shape),
     * so a shot fills the screen edge to edge instead of coming out as the sensor's 4:3.
     */
    private fun screenShaped(prev: Preview, photo: ImageCapture, video: VideoCapture<Recorder>?): androidx.camera.core.UseCaseGroup {
        val dm = resources.displayMetrics
        val w = preview.width.takeIf { it > 0 } ?: dm.widthPixels
        val h = preview.height.takeIf { it > 0 } ?: dm.heightPixels
        val port = androidx.camera.core.ViewPort.Builder(android.util.Rational(w, h), android.view.Surface.ROTATION_0)
            .setScaleType(androidx.camera.core.ViewPort.FILL_CENTER).build()
        return androidx.camera.core.UseCaseGroup.Builder().setViewPort(port).addUseCase(prev).addUseCase(photo)
            .apply { video?.let { addUseCase(it) } }.build()
    }

    private fun setZoom(ratio: Float) {
        val zs = camera?.cameraInfo?.zoomState?.value ?: return
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
    }

    // ---- shutter: tap = photo, hold = video, slide up/down = zoom ----

    private fun onShutterTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                shutter.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).start()   // the button gives under your finger at once
                pressStart = SystemClock.uptimeMillis()
                pressY = e.rawY
                zoomAtPress = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                holding = true
                if (videoCapture != null && timerSecs == 0 && !counting) ui.postDelayed(startVideo, 350)   // becomes a video if held (the timer is for photos)
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
                shutter.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                holding = false
                ui.removeCallbacks(startVideo)
                if (recording != null) stopVideo()
                else if (counting) cancelCountdown()
                else if (SystemClock.uptimeMillis() - pressStart < 350 || timerSecs > 0) { if (timerSecs > 0) startCountdown(timerSecs) else takePhoto() }
            }
        }
    }

    // ---- flash and timer ----

    /** Back camera: the LED. Front camera: the screen lights up white (CameraX's screen flash). */
    private fun applyFlash() {
        val ic = imageCapture ?: return
        val hasLed = camera?.cameraInfo?.hasFlashUnit() == true
        ic.flashMode = when { !flashOn -> ImageCapture.FLASH_MODE_OFF; hasLed -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_SCREEN }
        if (!flashOn || hasLed) camera?.cameraControl?.enableTorch(false)
    }

    private fun refreshControls() {
        fun style(b: TextView, on: Boolean) {
            b.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) 0xFFFFD60A.toInt() else 0x66000000) }
            b.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
        style(flashBtn, flashOn)
        style(timerBtn, timerSecs > 0)
        timerBtn.text = if (timerSecs > 0) "${timerSecs}s" else "⏱"
        timerBtn.textSize = if (timerSecs > 0) 15f else 22f
        if (videoCapture != null) hint.text = if (timerSecs > 0) "Tap to start the ${timerSecs}s timer"
            else "Tap for photo  ·  hold for video  ·  slide up to zoom\nDouble-tap the screen to flip the camera"
    }

    private fun startCountdown(from: Int) {
        counting = true
        var left = from
        val step = object : Runnable {
            override fun run() {
                if (!counting) return
                if (left == 0) { counting = false; countdown.visibility = View.GONE; takePhoto(); return }
                countdown.text = "$left"; countdown.visibility = View.VISIBLE
                countdown.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                left--
                ui.postDelayed(this, 1000)
            }
        }
        step.run()
    }

    private fun cancelCountdown() {
        counting = false
        countdown.visibility = View.GONE
    }

    /** Instant "got it": a quick white blink and a tick, before the camera has even finished saving. */
    private fun shutterFeedback() {
        shutter.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        val blink = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0.55f; isClickable = false }
        rootView.addView(blink, FrameLayout.LayoutParams(-1, -1))
        blink.animate().alpha(0f).setDuration(140).withEndAction { rootView.removeView(blink) }.start()
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        shutterFeedback()
        val tapped = SystemClock.uptimeMillis()
        val file = File(cacheDir, "camera/photo_${System.currentTimeMillis()}.jpg")
        shutter.isEnabled = false
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    android.util.Log.d("TellemPerf", "photo saved " + (SystemClock.uptimeMillis() - tapped) + " ms after the tap")
                    if (snap) showEdit(file, true) else handBack(file, true)
                }
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
        if (flashOn && camera?.cameraInfo?.hasFlashUnit() == true) camera?.cameraControl?.enableTorch(true)   // torch while filming
        shutter.background = ring(0xFFE53935.toInt(), 0x55E53935)
        timer.visibility = View.VISIBLE; hint.visibility = View.GONE
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                camera?.cameraControl?.enableTorch(false)
                val hardError = ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                if (hardError || !file.exists() || file.length() == 0L) {
                    file.delete(); resetShutter(); toast("Couldn't record that video")
                } else if (snap) showEdit(file, false) else handBack(file, false)
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
        camera?.cameraControl?.enableTorch(false)
        recording = null
    }

    private fun resetShutter() {
        recording = null
        shutter.background = ring(Color.WHITE, Color.TRANSPARENT)
        shutter.isEnabled = true
        timer.visibility = View.GONE; hint.visibility = View.VISIBLE
    }

    // ---- snap mode: look at the shot, tap to type a caption (drag it up/down), send ----

    private fun showEdit(file: File, isPhoto: Boolean) {
        shutter.isEnabled = false
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isClickable = true }
        val stage = FrameLayout(this)
        layer.addView(stage, FrameLayout.LayoutParams(-1, -1))
        if (isPhoto) {
            val view = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            stage.addView(view, FrameLayout.LayoutParams(-1, -1))
            Thread {   // decoding a 12-megapixel photo on the main thread froze the screen right after the shot
                val bmp = decodeUpright(file, 1600)
                runOnUiThread { if (editLayer === layer) view.setImageBitmap(bmp) }
            }.start()
        } else {
            val vv = android.widget.VideoView(this)
            vv.setVideoURI(android.net.Uri.fromFile(file))
            vv.setOnPreparedListener { it.isLooping = true; vv.start() }
            stage.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val cap = android.widget.EditText(this).apply {
            setTextColor(Color.WHITE); textSize = 19f; gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt()); setPadding(d(14), d(10), d(14), d(10))
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(140))
            maxLines = 3; visibility = View.GONE
        }
        stage.addView(cap, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        fun typing() { cap.visibility = View.VISIBLE; cap.requestFocus(); imm.showSoftInput(cap, 0) }
        fun doneTyping() { cap.clearFocus(); imm.hideSoftInputFromWindow(cap.windowToken, 0); if (cap.text.isNullOrBlank()) cap.visibility = View.GONE }
        cap.setOnEditorActionListener { _, _, _ -> doneTyping(); true }
        // not typing: drag the caption up or down, tap it to edit
        var downY = 0f; var startTy = 0f; var moved = false
        cap.setOnTouchListener { v, e ->
            if (v.isFocused) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startTy = v.translationY; moved = false }
                MotionEvent.ACTION_MOVE -> { if (Math.abs(e.rawY - downY) > d(8)) moved = true; if (moved) v.translationY = startTy + e.rawY - downY }
                MotionEvent.ACTION_UP -> if (!moved) typing()
            }
            true
        }
        stage.setOnClickListener { if (cap.isFocused) doneTyping() else typing() }

        layer.addView(iconButton("✕") {
            doneTyping(); rootView.removeView(layer); editLayer = null
            file.delete(); shutter.isEnabled = true
        }, FrameLayout.LayoutParams(d(48), d(48), Gravity.TOP or Gravity.START).apply { leftMargin = d(12); topMargin = d(20) })
        layer.addView(TextView(this).apply {
            text = "Send  ➤"; setTextColor(Color.parseColor("#111111")); textSize = 17f; gravity = Gravity.CENTER
            setPadding(d(26), d(13), d(26), d(13))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = d(28).toFloat() }
            setOnClickListener {
                doneTyping(); isEnabled = false
                val text = cap.text.toString().trim()
                if (!isPhoto || text.isEmpty()) return@setOnClickListener handBack(file, isPhoto, text)
                // photos: the caption is burned into the picture, like Snapchat
                val stageW = stage.width; val stageH = stage.height
                val centerY = cap.top + cap.translationY + cap.height / 2f
                val textPx = cap.textSize; val padPx = cap.paddingTop.toFloat()
                Thread {
                    runCatching { decodeUpright(file, 2560)?.let { bake(file, it, text, stageW, stageH, centerY, textPx, padPx) } }
                    runOnUiThread { handBack(file, true, text) }
                }.start()
            }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = d(18); bottomMargin = d(28) })
        rootView.addView(layer, FrameLayout.LayoutParams(-1, -1))
        editLayer = layer
    }

    /** Photo decoded the right way up, no bigger than [maxSide]. */
    private fun decodeUpright(file: File, maxSide: Int): android.graphics.Bitmap? {
        val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, b)
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(b.outWidth, b.outHeight) / maxSide) }
        val raw = android.graphics.BitmapFactory.decodeFile(file.path, o) ?: return null
        val deg = when (runCatching { android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
        if (deg == 0f) return raw
        return android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
    }

    /** Draws the caption band onto the photo and saves it back over [file] as a JPEG. */
    private fun bake(file: File, shot: android.graphics.Bitmap, text: String, stageW: Int, stageH: Int, centerY: Float, textPx: Float, padPx: Float) {
        val out = shot.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val scale = minOf(stageW.toFloat() / out.width, stageH.toFloat() / out.height)   // how the picture was fitted on screen
        val dispW = out.width * scale; val dispH = out.height * scale
        val frac = ((centerY - (stageH - dispH) / 2f) / dispH).coerceIn(0f, 1f)
        val k = out.width / dispW
        val c = android.graphics.Canvas(out)
        val tp = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = textPx * k }
        val pad = padPx * k
        val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, tp, (out.width - 2 * pad).toInt().coerceAtLeast(1))
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build()
        val bandH = layout.height + 2 * pad
        val top = (frac * out.height - bandH / 2).coerceIn(0f, maxOf(0f, out.height - bandH))
        c.drawRect(0f, top, out.width.toFloat(), top + bandH, android.graphics.Paint().apply { color = 0x99000000.toInt() })
        c.save(); c.translate(pad, top + pad); layout.draw(c); c.restore()
        file.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val l = editLayer
        if (l != null) { rootView.removeView(l); editLayer = null; shutter.isEnabled = true } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun handBack(file: File, isPhoto: Boolean, caption: String = "") {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        setResult(RESULT_OK, Intent().setData(uri).putExtra("photo", isPhoto).putExtra("caption", caption)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (recording != null) stopVideo()
        cancelCountdown()
    }

    /** A short notice at the bottom in the app's style (not Android's grey toast). */
    private fun toast(s: String) {
        val root = window.decorView as? FrameLayout ?: return
        val v = TextView(this).apply {
            text = s; setTextColor(Color.WHITE); textSize = 14.5f
            setPadding(d(16), d(11), d(16), d(11))
            background = GradientDrawable().apply { setColor(0xEE2A2A2A.toInt()); cornerRadius = d(4).toFloat() }
        }
        root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = d(84) })
        v.postDelayed({ v.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(v) }.start() }, 2200)
    }

    private companion object {
        val TIMER_STEPS = listOf(0, 3, 6, 10)   // seconds; 0 = instant
    }
}
