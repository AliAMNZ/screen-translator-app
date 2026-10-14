package com.example.screentranslator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class CaptureActivity : AppCompatActivity() {

    private var startX = 0f
    private var startY = 0f
    private lateinit var cropBox: View
    private lateinit var overlayRoot: View
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        cropBox = findViewById(R.id.cropBox)
        overlayRoot = findViewById(R.id.overlayRoot)

        cropBox.visibility = View.GONE

        overlayRoot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    cropBox.layoutParams.width = 0
                    cropBox.layoutParams.height = 0
                    cropBox.x = startX
                    cropBox.y = startY
                    cropBox.visibility = View.VISIBLE
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val currentX = event.x
                    val currentY = event.y
                    val left = Math.min(startX, currentX)
                    val top = Math.min(startY, currentY)
                    val width = Math.abs(currentX - startX).toInt()
                    val height = Math.abs(currentY - startY).toInt()

                    cropBox.x = left
                    cropBox.y = top
                    val params = cropBox.layoutParams
                    params.width = width
                    params.height = height
                    cropBox.layoutParams = params
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val finalX = cropBox.x.toInt()
                    val finalY = cropBox.y.toInt()
                    val finalW = cropBox.width
                    val finalH = cropBox.height
                    overlayRoot.visibility = View.GONE

                    if (finalW > 25 && finalH > 25) {
                        captureAndProcess(finalX, finalY, finalW, finalH)
                    } else {
                        finish()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun captureAndProcess(x: Int, y: Int, width: Int, height: Int) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val resultCode = FloatingService.mResultCode
        val data = FloatingService.mResultData

        if (resultCode == 0 || data == null) {
            Toast.makeText(this, "مجوز تصویربرداری یافت نشد", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                virtualDisplay?.release()
                mediaProjection?.stop()
                imageReader?.setOnImageAvailableListener(null, null)

                val safeX = Math.max(0, Math.min(x, bitmap.width - 1))
                val safeY = Math.max(0, Math.min(y, bitmap.height - 1))
                val safeW = Math.min(width, bitmap.width - safeX)
                val safeH = Math.min(height, bitmap.height - safeY)

                val croppedBitmap = Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)

                val outputStream = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                runOnUiThread {
                    showResultDialog(base64)
                }
            }
        }, null)
    }

    private fun showResultDialog(base64Image: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_result_dialog, null)
        val txtResult = dialogView.findViewById<TextView>(R.id.txtResult)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val btnCopy = dialogView.findViewById<Button>(R.id.btnCopy)
        val btnClose = dialogView.findViewById<Button>(R.id.btnClose)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        val apiKey = getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString("api_key", "") ?: ""

        CoroutineScope(Dispatchers.Main).launch {
            val result = GeminiHelper.translateImage(base64Image, apiKey)
            progressBar.visibility = View.GONE

            result.onSuccess { text ->
                txtResult.text = text
                btnCopy.visibility = View.VISIBLE
                btnCopy.setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ترجمه", text))
                    Toast.makeText(this@CaptureActivity, "کپی شد!", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                txtResult.text = "خطا: ${err.message}"
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
            finish()
        }
    }
}
