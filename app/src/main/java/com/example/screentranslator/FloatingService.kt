package com.example.screentranslator

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.util.TypedValue
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
    private var inPlaceOverlayView: FrameLayout? = null

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
            initMediaProjectionToken(resultCode, data)
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
            .setContentTitle("مترجم شناور آماده است")
            .setContentText("ضربه: انتخاب کادر یا ترجمه درجای صفحه")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .build()

        startForeground(1, notification)
    }

    // فقط توکن را نگه می‌دارد و VirtualDisplay را در لحظه نیاز می‌سازد تا کروم قفل نشود
    private fun initMediaProjectionToken(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                releaseVirtualDisplay()
            }
        }, Handler(Looper.getMainLooper()))

        updateMetrics()
        // ساخت اولیه سطح تصویر
        ensureVirtualDisplayReady()
    }

    private fun updateMetrics() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun ensureVirtualDisplayReady() {
        if (virtualDisplay != null && imageReader != null) return
        updateMetrics()
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

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
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

        // فلگ‌های غیرمزاحم تا کروم و کیبورد به هیچ وجه مسدود نشوند
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayFlag(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 400
            // جلوگیری از حساسیت امنیتی کروم روی اندروید 12+
            alpha = 0.95f
        }

        windowManager.addView(floatingBubble, params)

        val bubbleIcon = floatingBubble?.findViewById<ImageView>(R.id.imgBubble)
        val longPressHandler = Handler(Looper.getMainLooper())
        var isLongPressTriggered = false

        val longPressRunnable = Runnable {
            isLongPressTriggered = true
            startInPlaceFullPageTranslation()
        }

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
                        isLongPressTriggered = false
                        longPressHandler.postDelayed(longPressRunnable, 500)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) {
                            isClick = false
                            longPressHandler.removeCallbacks(longPressRunnable)
                        }
                        if (!isLongPressTriggered) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(floatingBubble, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        if (isClick && !isLongPressTriggered) {
                            // اگر لایه ترجمه درجا روی صفحه باز است، با زدن روی حباب پاکش کن
                            if (inPlaceOverlayView != null) {
                                removeInPlaceOverlay()
                            } else {
                                showCropOverlay()
                            }
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
        removeInPlaceOverlay()
        floatingBubble?.visibility = View.GONE

        selectionOverlay = LayoutInflater.from(this).inflate(R.layout.activity_capture, null)
        val cropBox = selectionOverlay!!.findViewById<View>(R.id.cropBox)
        val overlayRoot = selectionOverlay!!.findViewById<FrameLayout>(R.id.overlayRoot)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
        }

        val btnFullPageInPlace = Button(this).apply {
            text = "✨ ترجمه درجای کل صفحه (In-Place)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(40, 20, 40, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = 50f
                setStroke(3, Color.WHITE)
            }
            setOnClickListener {
                removeCropOverlay()
                startInPlaceFullPageTranslation()
            }
        }

        val btnCancel = Button(this).apply {
            text = "✖ بستن"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(35, 20, 35, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1E293B"))
                cornerRadius = 50f
                setStroke(2, Color.parseColor("#64748B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 20 }

            setOnClickListener {
                removeCropOverlay()
                floatingBubble?.visibility = View.VISIBLE
            }
        }

        topBar.addView(btnCancel)
        topBar.addView(btnFullPageInPlace)

        val topBarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = 90 }
        overlayRoot.addView(topBar, topBarParams)

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

                    if (finalW > 40 && finalH > 40) {
                        removeCropOverlay()
                        Handler(Looper.getMainLooper()).postDelayed({
                            captureScreenArea(finalX, finalY, finalW, finalH)
                        }, 150)
                    }
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

    // اجرای ترجمه درجای کل صفحه (مانند Google Lens)
    private fun startInPlaceFullPageTranslation() {
        removeInPlaceOverlay()
        floatingBubble?.visibility = View.GONE
        Toast.makeText(this, "⏳ در حال اسکن و ترجمه درجای صفحه...", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            val base64 = captureFullScreenBase64()
            floatingBubble?.visibility = View.VISIBLE

            if (base64 == null) {
                Toast.makeText(this, "خطا در ثبت تصویر صفحه، دوباره تلاش کنید", Toast.LENGTH_SHORT).show()
                return@postDelayed
            }

            val apiKey = getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString("api_key", "") ?: ""

            CoroutineScope(Dispatchers.Main).launch {
                val res = GeminiHelper.translateFullPageInPlace(base64, apiKey)
                res.onSuccess { blocks ->
                    if (blocks.isEmpty()) {
                        Toast.makeText(this@FloatingService, "متنی در صفحه پیدا نشد", Toast.LENGTH_SHORT).show()
                    } else {
                        renderInPlaceBlocksOnScreen(blocks)
                    }
                }.onFailure { err ->
                    Toast.makeText(this@FloatingService, "خطا: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, 180)
    }

    // رسم باکس‌های ترجمه دقیقاً روی مختصات متون اصلی صفحه
    private fun renderInPlaceBlocksOnScreen(blocks: List<InPlaceTextBlock>) {
        removeInPlaceOverlay()

        inPlaceOverlayView = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#1A000000"))
        }

        for (block in blocks) {
            val left = (block.xmin / 1000f * screenWidth).toInt()
            val top = (block.ymin / 1000f * screenHeight).toInt()
            val right = (block.xmax / 1000f * screenWidth).toInt()
            val bottom = (block.ymax / 1000f * screenHeight).toInt()

            val width = Math.max(80, right - left)
            val height = Math.max(40, bottom - top)

            val textCard = TextView(this).apply {
                text = block.text
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(16, 8, 16, 8)

                // تنظیم خودکار سایز فونت متناسب با کادر
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)

                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F21E293B")) // پوشش کامل روی متن انگلیسی زیرین
                    cornerRadius = 12f
                    setStroke(2, Color.parseColor("#38BDF8"))
                }
            }

            val lp = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = left
                topMargin = top
                minHeight = height
            }

            inPlaceOverlayView!!.addView(textCard, lp)
        }

        // دکمه شناور بالا برای بستن لایه ترجمه درجا و ادامه اسکرول صفحه
        val closeBanner = Button(this).apply {
            text = "✖ پاک کردن ترجمه‌های روی صفحه (یا ضربه روی حباب)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(40, 18, 40, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E11D48"))
                cornerRadius = 50f
                setStroke(3, Color.WHITE)
            }
            setOnClickListener {
                removeInPlaceOverlay()
            }
        }

        val bannerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = 85 }

        inPlaceOverlayView!!.addView(closeBanner, bannerParams)

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayFlag(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(inPlaceOverlayView, overlayParams)
    }

    private fun removeInPlaceOverlay() {
        inPlaceOverlayView?.let {
            windowManager.removeView(it)
            inPlaceOverlayView = null
        }
    }

    private fun captureFullScreenBase64(): String? {
        return try {
            ensureVirtualDisplayReady()
            val image = imageReader?.acquireLatestImage() ?: return null

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

            val exactScreen = Bitmap.createBitmap(fullBitmap, 0, 0, screenWidth, screenHeight)
            val stream = ByteArrayOutputStream()
            exactScreen.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun captureScreenArea(x: Int, y: Int, width: Int, height: Int) {
        try {
            ensureVirtualDisplayReady()
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                floatingBubble?.visibility = View.VISIBLE
                Toast.makeText(this, "تصویر آماده نشد، دوباره تلاش کنید", Toast.LENGTH_SHORT).show()
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
            (screenWidth * 0.92).toInt(),
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
        removeInPlaceOverlay()
        releaseVirtualDisplay()
        mediaProjection?.stop()
    }
}
