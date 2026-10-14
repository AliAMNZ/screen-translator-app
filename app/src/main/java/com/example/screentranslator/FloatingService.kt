package com.example.screentranslator

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingBubble: View? = null
    private var selectionOverlay: View? = null
    private var resultDialogView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode != 0 && data != null && mediaProjection == null) {
            setupMediaProjection(resultCode, data)
        }

        if (floatingBubble == null) {
            createFloatingBubble()
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "floating_translator_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "مترجم شناور جمینای",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("مترجم شناور فعال است")
            .setContentText("روی حباب آبی بزنید و دور متن کادر بکشید")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .build()

        startForeground(1, notification)
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        // ثبت Callback الزامی برای اندروید 14 به بالا
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                virtualDisplay?.release()
                imageReader?.close()
            }
        }, Handler(Looper.getMainLooper()))

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenTranslatorDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun getOverlayFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun createFloatingBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingBubble = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 300
        }

        windowManager.addView(floatingBubble, params)

        val bubbleIcon = floatingBubble?.findViewById<ImageView>(R.id.imgBubble)

        bubbleIcon?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) {
                            isClick = false
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(floatingBubble, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            // وقتی کاربر روی دایره کلیک کرد، صفحه انتخاب کادر باز شود
                            showCropOverlay()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun showCropOverlay() {
        if (selectionOverlay != null) return
        floatingBubble?.visibility = View.GONE

        selectionOverlay = LayoutInflater.from(this).inflate(R.layout.activity_capture, null)
        val cropBox = selectionOverlay!!.findViewById<View>(R.id.cropBox)
        val overlayRoot = selectionOverlay!!.findViewById<FrameLayout>(R.id.overlayRoot)

        // افزودن راهنمای متنی بالای صفحه
        val hintText = TextView(this).apply {
            text = "دور متن مورد نظر کادر بکشید (یا یک ضربه بزنید تا کل صفحه ترجمه شود)"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC0F172A"))
            setPadding(32, 16, 32, 16)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val hintParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = 80 }
        overlayRoot.addView(hintText, hintParams)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayFlag(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(selectionOverlay, params)

        var startX = 0f
        var startY = 0f

        overlayRoot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    cropBox.x = startX
                    cropBox.y = startY
                    cropBox.layoutParams.width = 0
                    cropBox.layoutParams.height = 0
                    cropBox.visibility = View.VISIBLE
                    cropBox.requestLayout()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val currentX = event.rawX
                    val currentY = event.rawY
                    cropBox.x = Math.min(startX, currentX)
                    cropBox.y = Math.min(startY, currentY)
                    cropBox.layoutParams.width = Math.abs(currentX - startX).toInt()
                    cropBox.layoutParams.height = Math.abs(currentY - startY).toInt()
                    cropBox.requestLayout()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val finalX = cropBox.x.toInt()
                    val finalY = cropBox.y.toInt()
                    val finalW = cropBox.layoutParams.width
                    val finalH = cropBox.layoutParams.height

                    removeCropOverlay()

                    // کمی مکث تا لایه آبی محو شود و عکس تمیز از صفحه گرفته شود
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (finalW > 40 && finalH > 40) {
                            captureScreenArea(finalX, finalY, finalW, finalH)
                        } else {
                            // اگر کاربر فقط یک ضربه زد، کل صفحه را ترجمه کن
                            captureScreenArea(0, 0, screenWidth, screenHeight)
                        }
                    }, 120)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeCropOverlay() {
        selectionOverlay?.let {
            windowManager.removeView(it)
            selectionOverlay = null
        }
    }

    private fun captureScreenArea(x: Int, y: Int, width: Int, height: Int) {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                floatingBubble?.visibility = View.VISIBLE
                Toast.makeText(this, "تصویر آماده نشد، دوباره روی حباب بزنید", Toast.LENGTH_SHORT).show()
                return
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val fullBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val safeX = Math.max(0, Math.min(x, fullBitmap.width - 1))
            val safeY = Math.max(0, Math.min(y, fullBitmap.height - 1))
            val safeW = Math.min(width, fullBitmap.width - safeX)
            val safeH = Math.min(height, fullBitmap.height - safeY)

            val cropped = Bitmap.createBitmap(fullBitmap, safeX, safeY, safeW, safeH)
            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            floatingBubble?.visibility = View.VISIBLE
            showFloatingResultCard(base64)

        } catch (e: Exception) {
            floatingBubble?.visibility = View.VISIBLE
            Toast.makeText(this, "خطا در ثبت تصویر: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFloatingResultCard(base64Image: String) {
        removeResultCard()

        resultDialogView = LayoutInflater.from(this).inflate(R.layout.layout_result_dialog, null)
        val txtResult = resultDialogView!!.findViewById<TextView>(R.id.txtResult)
        val progressBar = resultDialogView!!.findViewById<ProgressBar>(R.id.progressBar)
        val btnCopy = resultDialogView!!.findViewById<Button>(R.id.btnCopy)
        val btnClose = resultDialogView!!.findViewById<Button>(R.id.btnClose)

        val params = WindowManager.LayoutParams(
            (screenWidth * 0.9).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayFlag(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(resultDialogView, params)

        val apiKey = getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString("api_key", "") ?: ""

        CoroutineScope(Dispatchers.Main).launch {
            val res = GeminiHelper.translateImage(base64Image, apiKey)
            progressBar.visibility = View.GONE

            res.onSuccess { translated ->
                txtResult.text = translated
                btnCopy.visibility = View.VISIBLE
                btnCopy.setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ترجمه", translated))
                    Toast.makeText(this@FloatingService, "کپی شد ✓", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                txtResult.text = "خطا در ارتباط با جمینای:\n${err.message}"
            }
        }

        btnClose.setOnClickListener {
            removeResultCard()
        }
    }

    private fun removeResultCard() {
        resultDialogView?.let {
            windowManager.removeView(it)
            resultDialogView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingBubble?.let { windowManager.removeView(it) }
        removeCropOverlay()
        removeResultCard()
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}
