package com.wilfrombdn.telegramnt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors

/** Scans a friend's quipu QR code (or a group invite) and hands the text back to the chat list. */
class QrActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var done = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val preview = PreviewView(this).apply { id = 1 }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(TextView(context).apply {
                text = "Point the camera at a friend's quipu code"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
                setPadding(40, 40, 40, 40); setBackgroundColor(0x88000000.toInt())
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun start() {
        val preview = findViewById<PreviewView>(1)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(worker) { img ->
                try {
                    if (done) return@setAnalyzer
                    // the brightness (Y) plane is all a QR reader needs
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val data = ByteArray(buf.remaining()).also { buf.get(it) }
                    val src = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
                    val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text }.getOrNull()
                    reader.reset()
                    if (text != null && (text.startsWith(ADD) || text.startsWith("msgr01://group"))) {
                        done = true
                        runOnUiThread { setResult(RESULT_OK, Intent().putExtra("qr", text)); finish() }
                    }
                } finally { img.close() }
            }
            val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    companion object {
        const val ADD = "msgr01://add/"

        /** My code: a QR of "msgr01://add/<username>", black on white so any scanner can read it. */
        fun codeFor(user: String, size: Int): Bitmap {
            val m = QRCodeWriter().encode(ADD + user, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
