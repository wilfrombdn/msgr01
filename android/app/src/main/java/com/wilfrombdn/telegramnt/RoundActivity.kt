package com.wilfrombdn.telegramnt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
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
import java.io.File

/**
 * Round video message ("video note"): the front camera in a circle. Recording starts right away;
 * tap the red button to send it, ✕ to throw it away. Up to a minute. The file stays in the app's
 * cache and is deleted as soon as the chat has read it.
 */
class RoundActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var timer: TextView
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var file: File? = null
    private var keep = false
    private var started = 0L
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        if (g[Manifest.permission.CAMERA] == true) startCamera() else finish()
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cacheDir.resolve("round").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val size = minOf(resources.displayMetrics.widthPixels - d(48), d(320))
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // a TextureView, so the circle clip works
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
        }
        timer = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; text = "● 0:00" }
        val send = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE53935.toInt()); setStroke(d(5), Color.WHITE) }
            setOnClickListener { keep = true; recording?.stop() }
        }
        val cancel = TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x44FFFFFF) }
            setOnClickListener { keep = false; if (recording != null) recording?.stop() else finish() }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xF0101012.toInt())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(preview, LinearLayout.LayoutParams(size, size))
                addView(timer, LinearLayout.LayoutParams(-2, -2).apply { topMargin = d(18) })
                addView(TextView(context).apply { text = "Tap the red button to send"; setTextColor(0x99FFFFFF.toInt()); textSize = 14f; setPadding(0, d(6), 0, 0) })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(send, FrameLayout.LayoutParams(d(74), d(74), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = d(40) })
            addView(cancel, FrameLayout.LayoutParams(d(52), d(52), Gravity.BOTTOM or Gravity.START).apply { leftMargin = d(36); bottomMargin = d(51) })
        })
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (camOk) startCamera() else askPerms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            val prev = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            // small on purpose: a round message is a little 480p clip, so it sends quickly over Tor
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST))).build()
            video = VideoCapture.withOutput(recorder)
            try {
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, prev, video)
                ui.postDelayed({ begin() }, 400)
            } catch (e: Exception) { finish() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun begin() {
        val vc = video ?: return
        val f = File(cacheDir, "round/round_${System.currentTimeMillis()}.mp4").also { file = it }
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(f).setFileSizeLimit(12L * 1024 * 1024).build())
            .apply { if (micOk) withAudioEnabled() }
        started = SystemClock.uptimeMillis()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev !is VideoRecordEvent.Finalize) return@start
            recording = null
            val bad = ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
            if (!keep || bad || !f.exists() || f.length() == 0L || SystemClock.uptimeMillis() - started < 800) { f.delete(); finish(); return@start }
            setResult(RESULT_OK, Intent().putExtra("path", f.absolutePath))
            finish()
        }
        tick()
    }

    private fun tick() {
        if (recording == null) return
        val s = ((SystemClock.uptimeMillis() - started) / 1000).toInt()
        timer.text = "● %d:%02d".format(s / 60, s % 60)
        if (s >= 60) { keep = true; recording?.stop(); return }   // a minute max, then it sends
        ui.postDelayed({ tick() }, 500)
    }

    override fun onStop() {
        // left the screen (home button, call): throw the clip away
        if (recording != null && !keep) recording?.stop()
        super.onStop()
    }
}
