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

        private const val CHANNEL_ID = "quickmt_overlay_channel"
        private const val NOTIF_ID = 4041

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var prefs: AppPrefs
    private lateinit var windowManager: WindowManager
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

    private var screenW = 1080
    private var screenH = 1920
    private var screenDpi = 320

    private var prevThumb: IntArray? = null
    private var lastEnglishText = ""
    private var emptyStreak = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (stopped.get()) return
            val delayMs = if (prefs.overlayMode == OverlayMode.COVER) 850L else 320L
            if (!paused.get() && pickerView == null && QuickMTNative.isInitialized && busy.compareAndSet(false, true)) {
                if (prefs.overlayMode == OverlayMode.COVER && subtitleView?.visibility == View.VISIBLE) {
                    // Briefly hide overlay for 1 composite frame so MediaProjection sees the game text underneath
                    subtitleView?.visibility = View.INVISIBLE
                    mainHandler.postDelayed({
                        captureAndProcess(restoreCoverVisibility = true)
                    }, 45L)
                } else {
                    captureAndProcess(restoreCoverVisibility = false)
                }
            }
            mainHandler.postDelayed(this, delayMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

        // Ensure native QuickMT model is loaded on background thread
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
        updateScreenMetrics()
        recreateVirtualDisplay()
        updateSubtitlePosition()
        prevThumb = null
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
            screenW = bounds.width()
            screenH = bounds.height()
            screenDpi = resources.displayMetrics.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
            screenDpi = dm.densityDpi
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: run {
            stopSelf()
            return
        }
        mp.registerCallback(projectionCallback, mainHandler)
        mediaProjection = mp
        recreateVirtualDisplay()
    }

    @SuppressLint("WrongConstant")
    private fun recreateVirtualDisplay() {
        virtualDisplay?.release()
        imageReader?.close()

        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mediaProjection?.createVirtualDisplay(
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

    private fun createSubtitleOverlay() {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = prefs.fontSizeSp.toFloat()
            gravity = Gravity.CENTER
            setPadding(28, 16, 28, 16)
            setShadowLayer(4f, 1f, 2f, Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.argb(prefs.overlayOpacity, 10, 12, 18))
                cornerRadius = 20f
            }
            visibility = View.GONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        subtitleView = tv
        subtitleParams = lp
        windowManager.addView(tv, lp)
        updateSubtitlePosition()
    }

    private fun updateSubtitlePosition() {
        val tv = subtitleView ?: return
        val lp = subtitleParams ?: return

        val regLeft = (prefs.regionLeft * screenW).toInt()
        val regTop = (prefs.regionTop * screenH).toInt()
        val regRight = (prefs.regionRight * screenW).toInt()
        val regBottom = (prefs.regionBottom * screenH).toInt()
        val regW = max(240, regRight - regLeft)
        val regH = max(70, regBottom - regTop)

        tv.textSize = prefs.fontSizeSp.toFloat()
        (tv.background as? GradientDrawable)?.setColor(
            Color.argb(prefs.overlayOpacity, 10, 12, 18)
        )

        if (prefs.overlayMode == OverlayMode.COVER) {
            lp.x = regLeft
            lp.y = regTop
            lp.width = regW
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            tv.minHeight = regH
        } else {
            // ABOVE mode: sits cleanly right above the English subtitle region with ZERO overlap
            lp.width = regW
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            tv.minHeight = 0
            lp.x = regLeft
            val approxH = max(90, (prefs.fontSizeSp * resources.displayMetrics.scaledDensity * 2.8f).toInt())
            lp.y = max(20, regTop - approxH - 10)
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

        // Allow dragging the floating control pill anywhere on screen
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
                        lp.x = initX + dx
                        lp.y = initY + dy
                        windowManager.updateViewLayout(pill, lp)
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
                updateSubtitlePosition()
                prevThumb = null
                lastEnglishText = ""
            },
            onCancel = { closeRegionPicker() }
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
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

    private fun captureAndProcess(restoreCoverVisibility: Boolean) {
        val reader = imageReader
        if (reader == null) {
            if (restoreCoverVisibility && lastEnglishText.isNotEmpty()) {
                subtitleView?.visibility = View.VISIBLE
            }
            busy.set(false)
            return
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        }

        if (restoreCoverVisibility && lastEnglishText.isNotEmpty()) {
            subtitleView?.visibility = View.VISIBLE
        }

        if (image == null) {
            busy.set(false)
            return
        }

        val cropBitmap: Bitmap? = try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val full = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(buffer)

            val x = (prefs.regionLeft * image.width).toInt().coerceIn(0, image.width - 20)
            val y = (prefs.regionTop * image.height).toInt().coerceIn(0, image.height - 20)
            val w = ((prefs.regionRight - prefs.regionLeft) * image.width).toInt()
                .coerceIn(20, image.width - x)
            val h = ((prefs.regionBottom - prefs.regionTop) * image.height).toInt()
                .coerceIn(20, image.height - y)

            val cropped = Bitmap.createBitmap(full, x, y, w, h)
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

        // Fast downsampled pixel-diff filter to save CPU & battery when subtitles are static
        val thumb = computeThumb(cropBitmap)
        val prev = prevThumb
        if (prev != null && prev.size == thumb.size) {
            var diffSum = 0L
            for (i in thumb.indices) diffSum += abs(thumb[i] - prev[i])
            val meanDiff = diffSum.toFloat() / thumb.size
            if (meanDiff < 3.5f) {
                cropBitmap.recycle()
                busy.set(false)
                return
            }
        }
        prevThumb = thumb

        val inputImage = InputImage.fromBitmap(cropBitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.textBlocks
                    .sortedBy { it.boundingBox?.top ?: 0 }
                    .joinToString(" ") { it.text.replace('\n', ' ') }
                    .replace(Regex("""\s+"""), " ")
                    .trim()

                cropBitmap.recycle()

                if (!isPlausibleEnglishSubtitle(rawText)) {
                    emptyStreak++
                    if (emptyStreak >= 2 && lastEnglishText.isNotEmpty()) {
                        lastEnglishText = ""
                        subtitleView?.visibility = View.GONE
                    }
                    busy.set(false)
                    return@addOnSuccessListener
                }

                emptyStreak = 0
                val normText = QuickMTNative.normalizeOcrEnglish(rawText)
                if (normText.equals(lastEnglishText, ignoreCase = true) ||
                    similarity(normText, lastEnglishText) >= 0.90f
                ) {
                    busy.set(false)
                    return@addOnSuccessListener
                }

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
                            renderSubtitle(speaker, translated, normText)
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

    private fun renderSubtitle(speaker: String, translated: String, original: String) {
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
        updateSubtitlePosition()
        tv.visibility = View.VISIBLE
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
