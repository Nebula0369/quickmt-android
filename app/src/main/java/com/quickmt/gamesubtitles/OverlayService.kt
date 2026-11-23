package com.quickmt.gamesubtitles

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.quickmt.gamesubtitles.START"
        const val ACTION_STOP = "com.quickmt.gamesubtitles.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val TAG = "QuickMTOverlay"
        private const val CHANNEL_ID = "quickmt_overlay_channel"
        private const val NOTIF_ID = 4041

        private val TURKISH_ONLY_CHARS = Regex("""[ğĞşŞıİçÇöÖüÜ]""")

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var prefs: AppPrefs
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var subtitleView: TextView? = null
    private var subtitleParams: WindowManager.LayoutParams? = null
    private var pillView: LinearLayout? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var pickerView: RegionPickerView? = null

    private val paused = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val rotating = AtomicBoolean(false)

    private var screenW = 1080
    private var screenH = 1920
    private var screenDpi = 320

    private var prevThumb: IntArray? = null
    private var lastEnglishText = ""
    private var recentTurkish = ArrayDeque<String>()
    private var emptyStreak = 0
    private var stableCoverChecks = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            scheduleDisplayResize()
        }
    }

    private val resizeRunnable = Runnable {
        if (!stopped.get()) {
            applyScreenResizeIfChanged()
        }
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (stopped.get()) return
            val isCover = prefs.overlayMode == OverlayMode.COVER
            val overlayShowing = subtitleView?.visibility == View.VISIBLE && lastEnglishText.isNotEmpty()

            // Fast 250ms scan when no subtitle is on screen (0 flicker!);
            // Calm adaptive interval (1.8s -> 2.3s) in COVER mode while reading a displayed subtitle!
            val nextDelayMs = when {
                !isCover -> 300L
                !overlayShowing -> 250L
                stableCoverChecks >= 1 -> (prefs.coverCheckIntervalMs + 500L).coerceAtMost(3500L)
                else -> prefs.coverCheckIntervalMs.toLong()
            }

            if (!paused.get() && !rotating.get() && pickerView == null &&
                QuickMTNative.isInitialized && busy.compareAndSet(false, true)
            ) {
                if (isCover && overlayShowing) {
                    // Ultra-brief GPU alpha=0 synced to Choreographer VSync (~16-32ms instead of 80ms+)
                    val tv = subtitleView
                    if (tv != null) {
                        tv.alpha = 0f
                        Choreographer.getInstance().postFrameCallback {
                            if (stopped.get()) {
                                busy.set(false)
                                return@postFrameCallback
                            }
                            try {
                                imageReader?.acquireLatestImage()?.close()
                            } catch (_: Exception) {
                            }
                            Choreographer.getInstance().postFrameCallback {
                                if (stopped.get()) {
                                    busy.set(false)
                                    return@postFrameCallback
                                }
                                captureAndProcess(restoreCoverAlpha = true)
                            }
                        }
                    } else {
                        captureAndProcess(restoreCoverAlpha = false)
                    }
                } else {
                    captureAndProcess(restoreCoverAlpha = false)
                }
            }
            mainHandler.postDelayed(this, nextDelayMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        stopped.set(false)
        isRunning = true

        updateScreenMetrics()
        setupMediaProjection(resultCode, resultData)
        createSubtitleOverlay()
        createControlPill()
        displayManager.registerDisplayListener(displayListener, mainHandler)

        if (!QuickMTNative.isInitialized && ModelDownloader.isModelReady(this)) {
            workerExecutor.execute {
                QuickMTNative.init(ModelDownloader.getModelDir(this), threads = 4)
            }
        }

        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.postDelayed(scanRunnable, 500L)
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleDisplayResize()
    }

    private fun scheduleDisplayResize() {
        mainHandler.removeCallbacks(resizeRunnable)
        mainHandler.postDelayed(resizeRunnable, 140L)
    }

    /**
     * Android 14+ safe orientation resize!
     * NEVER calls mediaProjection.createVirtualDisplay() a second time (which crashes on Android 14+).
     * Instead uses virtualDisplay.resize() + virtualDisplay.setSurface(newReader.surface).
     */
    @SuppressLint("WrongConstant")
    private fun applyScreenResizeIfChanged() {
        val oldW = screenW
        val oldH = screenH
        updateScreenMetrics()
        if (screenW == oldW && screenH == oldH) return

        rotating.set(true)
        try {
            val vd = virtualDisplay
            if (vd != null) {
                val oldReader = imageReader
                // Detach surface BEFORE closing old ImageReader to avoid SurfaceFlinger error
                vd.setSurface(null)
                val newReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
                vd.resize(screenW, screenH, screenDpi)
                vd.setSurface(newReader.surface)
                imageReader = newReader
                try {
                    oldReader?.close()
                } catch (_: Exception) {
                }
            }

            // Clamp floating control pill inside new landscape/portrait screen bounds
            pillParams?.let { lp ->
                lp.x = lp.x.coerceIn(12, max(12, screenW - 180))
                lp.y = lp.y.coerceIn(24, max(24, screenH - 120))
                pillView?.let { v ->
                    try {
                        windowManager.updateViewLayout(v, lp)
                    } catch (_: Exception) {
                    }
                }
            }

            // Reset OCR cache and hide stale subtitle until next scan in new orientation
            prevThumb = null
            lastEnglishText = ""
            subtitleView?.visibility = View.GONE
            positionOverlayToScanZone()
        } catch (ex: Exception) {
            Log.e(TAG, "Error resizing VirtualDisplay on rotation", ex)
        } finally {
            rotating.set(false)
        }
    }

    private fun startAsForeground() {
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QuickMT Oyun Altyazı Aktif")
            .setContentText("Oyun ekranındaki İngilizce altyazılar çevrimdışı çevriliyor")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Durdur", stopPi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "QuickMT Altyazı Servisi",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(ch)
    }

    private fun updateScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenW = max(1, bounds.width())
            screenH = max(1, bounds.height())
            screenDpi = resources.displayMetrics.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenW = max(1, dm.widthPixels)
            screenH = max(1, dm.heightPixels)
            screenDpi = dm.densityDpi
        }
    }

    @SuppressLint("WrongConstant")
    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: run {
            stopSelf()
            return
        }
        mp.registerCallback(projectionCallback, mainHandler)
        mediaProjection = mp

        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "QuickMTGameCapture",
            screenW,
            screenH,
            screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
    }

    private fun applyFullScreenCutoutMode(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun createSubtitleOverlay() {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = prefs.fontSizeSp.toFloat()
            gravity = Gravity.CENTER
            setPadding(28, 14, 28, 14)
            setShadowLayer(4f, 1f, 2f, Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.argb(prefs.overlayOpacity, 10, 12, 18))
                cornerRadius = 18f
            }
            visibility = View.GONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            applyFullScreenCutoutMode(this)
        }

        subtitleView = tv
        subtitleParams = lp
        windowManager.addView(tv, lp)
        positionOverlayToScanZone()
    }

    private fun positionOverlayToScanZone() {
        val regLeft = (prefs.regionLeft * screenW).toInt()
        val regTop = (prefs.regionTop * screenH).toInt()
        val regRight = (prefs.regionRight * screenW).toInt()
        val regBottom = (prefs.regionBottom * screenH).toInt()
        positionOverlayForRect(Rect(regLeft, regTop, max(regLeft + 120, regRight), max(regTop + 40, regBottom)))
    }

    /**
     * Positions the dark Turkish overlay box pixel-accurately relative to the EXACT
     * detected English subtitle bounding box in full raw display coordinates (0,0 = true top-left).
     */
    private fun positionOverlayForRect(detectedRect: Rect) {
        val tv = subtitleView ?: return
        val lp = subtitleParams ?: return

        tv.textSize = prefs.fontSizeSp.toFloat()
        (tv.background as? GradientDrawable)?.setColor(
            Color.argb(prefs.overlayOpacity, 10, 12, 18)
        )

        val regW = max(240, ((prefs.regionRight - prefs.regionLeft) * screenW).toInt())
        val padX = 28
        val padY = 14

        // Compute ideal width: covers the English text + padding, wider if Turkish needs more room
        val minCoverW = detectedRect.width() + padX * 2
        val maxAllowedW = min(screenW - 24, max(regW, minCoverW))

        tv.measure(
            View.MeasureSpec.makeMeasureSpec(maxAllowedW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenH / 2, View.MeasureSpec.AT_MOST)
        )
        val targetW = max(minCoverW, tv.measuredWidth).coerceIn(180, maxAllowedW)

        // Re-measure exact height at final targetW
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH / 2, View.MeasureSpec.AT_MOST)
        )
        val measuredH = max(48, tv.measuredHeight)

        // Center horizontally directly on the detected English subtitle center
        val centerX = detectedRect.centerX()
        val x = (centerX - targetW / 2).coerceIn(12, max(12, screenW - targetW - 12))

        if (prefs.overlayMode == OverlayMode.COVER) {
            val coverH = max(detectedRect.height() + padY * 2, measuredH)
            val centerY = detectedRect.centerY()
            val y = (centerY - coverH / 2).coerceIn(12, max(12, screenH - coverH - 12))
            lp.x = x
            lp.y = y
            lp.width = targetW
            lp.height = coverH
            tv.minHeight = coverH
        } else {
            // ABOVE mode: bottom edge sits cleanly 10px directly above the detected English text!
            val y = (detectedRect.top - measuredH - 10).coerceIn(12, max(12, screenH - measuredH - 12))
            lp.x = x
            lp.y = y
            lp.width = targetW
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            tv.minHeight = 0
        }

        try {
            windowManager.updateViewLayout(tv, lp)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createControlPill() {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            background = GradientDrawable().apply {
                setColor(Color.argb(215, 15, 23, 42))
                setStroke(2, Color.parseColor("#38BDF8"))
                cornerRadius = 36f
            }
        }

        fun makeBtn(label: String, colorHex: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(colorHex))
                setPadding(16, 6, 16, 6)
                setOnClickListener { onClick() }
            }
        }

        val btnRegion = makeBtn("⌖", "#38BDF8") { openRegionPicker() }
        lateinit var btnPause: TextView
        btnPause = makeBtn("⏸", "#F8FAFC") {
            val nowPaused = !paused.get()
            paused.set(nowPaused)
            btnPause.text = if (nowPaused) "▶" else "⏸"
            if (nowPaused) {
                subtitleView?.visibility = View.GONE
            } else {
                prevThumb = null
            }
        }
        val btnClose = makeBtn("✕", "#F87171") { stopSelf() }

        pill.addView(btnRegion)
        pill.addView(btnPause)
        pill.addView(btnClose)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 140
        }

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        pill.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = lp.x
                    initY = lp.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        lp.x = (initX + dx).coerceIn(0, max(0, screenW - 120))
                        lp.y = (initY + dy).coerceIn(0, max(0, screenH - 80))
                        try {
                            windowManager.updateViewLayout(pill, lp)
                        } catch (_: Exception) {
                        }
                        true
                    } else false
                }
                else -> false
            }
        }

        pillView = pill
        pillParams = lp
        windowManager.addView(pill, lp)
    }

    private fun openRegionPicker() {
        if (pickerView != null) return
        subtitleView?.visibility = View.GONE

        val picker = RegionPickerView(
            context = this,
            initialLeft = prefs.regionLeft,
            initialTop = prefs.regionTop,
            initialRight = prefs.regionRight,
            initialBottom = prefs.regionBottom,
            onConfirm = { l, t, r, b ->
                prefs.regionLeft = l
                prefs.regionTop = t
                prefs.regionRight = r
                prefs.regionBottom = b
                closeRegionPicker()
                positionOverlayToScanZone()
                prevThumb = null
                lastEnglishText = ""
            },
            onCancel = { closeRegionPicker() }
        )

        // Exact full raw display coordinates (matching ImageReader 1:1 across notches & status bar!)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            applyFullScreenCutoutMode(this)
        }
        pickerView = picker
        windowManager.addView(picker, lp)
    }

    private fun closeRegionPicker() {
        pickerView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        pickerView = null
    }

    private fun captureAndProcess(restoreCoverAlpha: Boolean) {
        if (rotating.get() || stopped.get()) {
            if (restoreCoverAlpha) subtitleView?.alpha = 1f
            busy.set(false)
            return
        }
        val reader = imageReader
        if (reader == null) {
            if (restoreCoverAlpha) subtitleView?.alpha = 1f
            busy.set(false)
            return
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        }

        // Restore overlay alpha immediately after grabbing the frame (~16-32ms total hide time)
        if (restoreCoverAlpha) {
            subtitleView?.alpha = 1f
        }

        if (image == null) {
            busy.set(false)
            return
        }

        var cropX = 0
        var cropY = 0
        var imgW = 1
        var imgH = 1

        val cropBitmap: Bitmap? = try {
            imgW = image.width
            imgH = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * imgW

            val full = Bitmap.createBitmap(
                imgW + rowPadding / pixelStride,
                imgH,
                Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(buffer)

            cropX = (prefs.regionLeft * imgW).toInt().coerceIn(0, max(0, imgW - 20))
            cropY = (prefs.regionTop * imgH).toInt().coerceIn(0, max(0, imgH - 20))
            val cropW = ((prefs.regionRight - prefs.regionLeft) * imgW).toInt()
                .coerceIn(20, max(20, imgW - cropX))
            val cropH = ((prefs.regionBottom - prefs.regionTop) * imgH).toInt()
                .coerceIn(20, max(20, imgH - cropY))

            val cropped = Bitmap.createBitmap(full, cropX, cropY, cropW, cropH)
            full.recycle()
            cropped
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }

        if (cropBitmap == null) {
            busy.set(false)
            return
        }

        val thumb = computeThumb(cropBitmap)
        val prev = prevThumb
        if (prev != null && prev.size == thumb.size) {
            var diffSum = 0L
            for (i in thumb.indices) diffSum += abs(thumb[i] - prev[i])
            if (diffSum.toFloat() / thumb.size < 3.5f) {
                cropBitmap.recycle()
                busy.set(false)
                return
            }
        }
        prevThumb = thumb

        val scaleX = screenW.toFloat() / max(1, imgW)
        val scaleY = screenH.toFloat() / max(1, imgH)

        val inputImage = InputImage.fromBitmap(cropBitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val blocks = visionText.textBlocks.sortedBy { it.boundingBox?.top ?: 0 }
                val rawText = blocks
                    .joinToString(" ") { it.text.replace('\n', ' ') }
                    .replace(Regex("""\s+"""), " ")
                    .trim()

                // Compute exact union bounding box of the detected English text in screen pixels
                var minX = Int.MAX_VALUE
                var minY = Int.MAX_VALUE
                var maxX = Int.MIN_VALUE
                var maxY = Int.MIN_VALUE
                for (b in blocks) {
                    val box = b.boundingBox ?: continue
                    minX = min(minX, box.left)
                    minY = min(minY, box.top)
                    maxX = max(maxX, box.right)
                    maxY = max(maxY, box.bottom)
                }

                val detectedScreenRect = if (minX < maxX && minY < maxY) {
                    Rect(
                        ((cropX + minX) * scaleX).toInt(),
                        ((cropY + minY) * scaleY).toInt(),
                        ((cropX + maxX) * scaleX).toInt(),
                        ((cropY + maxY) * scaleY).toInt()
                    )
                } else {
                    Rect(
                        (prefs.regionLeft * screenW).toInt(),
                        (prefs.regionTop * screenH).toInt(),
                        (prefs.regionRight * screenW).toInt(),
                        (prefs.regionBottom * screenH).toInt()
                    )
                }

                cropBitmap.recycle()

                if (!isPlausibleEnglishSubtitle(rawText)) {
                    emptyStreak++
                    stableCoverChecks = 0
                    val clearAfter = if (prefs.overlayMode == OverlayMode.COVER) 1 else 2
                    if (emptyStreak >= clearAfter && lastEnglishText.isNotEmpty()) {
                        lastEnglishText = ""
                        subtitleView?.visibility = View.GONE
                    }
                    busy.set(false)
                    return@addOnSuccessListener
                }

                // Prevent translating our own Turkish subtitle if a stale frame ever slips through
                if (isSelfEcho(rawText)) {
                    busy.set(false)
                    return@addOnSuccessListener
                }

                emptyStreak = 0
                val normText = QuickMTNative.normalizeOcrEnglish(rawText)
                if (normText.equals(lastEnglishText, ignoreCase = true) ||
                    similarity(normText, lastEnglishText) >= 0.90f
                ) {
                    stableCoverChecks++
                    busy.set(false)
                    return@addOnSuccessListener
                }

                stableCoverChecks = 0

                workerExecutor.execute {
                    val (speaker, body) = if (prefs.speakerHighlight) {
                        QuickMTNative.extractSpeakerPrefix(normText)
                    } else {
                        "" to normText
                    }
                    val translated = QuickMTNative.translate(
                        body,
                        beamSize = prefs.beamSize,
                        rewriteIdioms = prefs.gameIdioms
                    )
                    mainHandler.post {
                        if (!stopped.get() && translated.isNotEmpty()) {
                            lastEnglishText = normText
                            recentTurkish.addLast(translated)
                            if (speaker.isNotEmpty()) recentTurkish.addLast("$speaker: $translated")
                            while (recentTurkish.size > 6) recentTurkish.removeFirst()
                            renderSubtitle(speaker, translated, normText, detectedScreenRect)
                        }
                        busy.set(false)
                    }
                }
            }
            .addOnFailureListener {
                cropBitmap.recycle()
                busy.set(false)
            }
    }

    private fun renderSubtitle(
        speaker: String,
        translated: String,
        original: String,
        detectedScreenRect: Rect
    ) {
        val tv = subtitleView ?: return
        val sb = SpannableStringBuilder()
        if (prefs.showOriginal && original.isNotEmpty()) {
            val start = sb.length
            sb.append(original).append("\n")
            sb.setSpan(
                ForegroundColorSpan(Color.parseColor("#94A3B8")),
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (speaker.isNotEmpty()) {
            val start = sb.length
            sb.append(speaker).append(": ")
            sb.setSpan(
                ForegroundColorSpan(Color.parseColor("#FBBF24")),
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        sb.append(translated)
        tv.text = sb
        positionOverlayForRect(detectedScreenRect)
        tv.visibility = View.VISIBLE
    }

    private fun isSelfEcho(detected: String): Boolean {
        val normDet = detected.lowercase().replace(Regex("""[^\w\s]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        if (normDet.isEmpty()) return false
        for (prev in recentTurkish) {
            val normPrev = prev.lowercase().replace(Regex("""[^\w\s]"""), " ")
                .replace(Regex("""\s+"""), " ").trim()
            if (normPrev.length >= 4 &&
                (normDet.contains(normPrev) || similarity(normDet, normPrev) >= 0.72f)
            ) {
                return true
            }
        }
        return TURKISH_ONLY_CHARS.findAll(detected).count() >= 2
    }

    private fun computeThumb(bmp: Bitmap): IntArray {
        val tw = 48
        val th = 16
        val small = Bitmap.createScaledBitmap(bmp, tw, th, false)
        val pixels = IntArray(tw * th)
        small.getPixels(pixels, 0, tw, 0, 0, tw, th)
        small.recycle()
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
        }
    }

    private fun isPlausibleEnglishSubtitle(text: String): Boolean {
        val noSpace = text.replace(Regex("""\s+"""), "")
        if (noSpace.length < 3) return false
        val asciiLetters = noSpace.count { it in 'A'..'Z' || it in 'a'..'z' }
        if (asciiLetters < 3) return false
        if (asciiLetters.toFloat() / noSpace.length < 0.55f) return false
        return Regex("""[A-Za-z]{2,}""").containsMatchIn(text)
    }

    private fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val s1 = a.lowercase()
        val s2 = b.lowercase()
        val maxLen = max(s1.length, s2.length)
        val common = min(s1.length, s2.length).let { n ->
            var matches = 0
            for (i in 0 until n) if (s1[i] == s2[i]) matches++
            matches
        }
        return common.toFloat() / maxLen
    }

    override fun onDestroy() {
        stopped.set(true)
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        closeRegionPicker()
        subtitleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        pillView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        workerExecutor.shutdownNow()
        super.onDestroy()
    }
}
