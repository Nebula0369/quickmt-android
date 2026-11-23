package com.quickmt.gamesubtitles

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val cancelDownload = AtomicBoolean(false)

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvDownloadInfo: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnCancelDownload: Button
    private lateinit var btnTest: Button
    private lateinit var btnStartStop: Button

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svc = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, svc)
            Toast.makeText(
                this,
                "QuickMT aktif! Oyuna geçip sol üstteki ⌖ tuşuyla altyazı alanını seçin.",
                Toast.LENGTH_LONG
            ).show()
            updateStartButtonState(running = true)
        } else {
            Toast.makeText(this, "Ekran yakalama izni verilmedi.", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        setContentView(buildUi())
        refreshModelStatusAndLoad()
    }

    override fun onResume() {
        super.onResume()
        updateStartButtonState(OverlayService.isRunning)
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
        }
        scroll.addView(root)

        // Header
        root.addView(TextView(this).apply {
            text = "QuickMT Oyun Altyazı (Android)"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        })

        tvStatus = TextView(this).apply {
            text = "Kontrol ediliyor..."
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(24, 18, 24, 18)
            background = cardDrawable("#1E293B", "#334155")
        }
        root.addView(tvStatus, marginParams(top = 18))

        // 1. Model Card (value-productions/quickmt-en-tr-int8)
        val modelCard = makeCard("1. QuickMT Çevrimdışı Model (EN → TR, ~199 MB)")
        tvDownloadInfo = TextView(this).apply {
            text = "Model: value-productions/quickmt-en-tr-int8 (CTranslate2 INT8)"
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
        }
        modelCard.addView(tvDownloadInfo)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        modelCard.addView(progressBar, marginParams(top = 12))

        val dlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnDownload = Button(this).apply {
            text = "Modeli İndir (~199 MB)"
            setOnClickListener { startModelDownload() }
        }
        btnCancelDownload = Button(this).apply {
            text = "İptal Et"
            visibility = View.GONE
            setOnClickListener {
                cancelDownload.set(true)
                isEnabled = false
                text = "İptal ediliyor..."
            }
        }
        btnTest = Button(this).apply {
            text = "Test Et"
            isEnabled = false
            setOnClickListener { runQuickTest() }
        }
        dlRow.addView(btnDownload, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dlRow.addView(btnCancelDownload)
        dlRow.addView(btnTest)
        modelCard.addView(dlRow, marginParams(top = 12))
        root.addView(modelCard, marginParams(top = 18))

        // 2. Overlay Position Mode Card
        val modeCard = makeCard("2. Altyazı Kutu Konumu")
        val radioGroup = RadioGroup(this)
        val rbCoverDirect = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Üstünü kapat — Sıfır titreme & Tam hız (Android 14+ 'Tek bir uygulama' seç — Önerilen)"
            setTextColor(Color.WHITE)
            isChecked = prefs.overlayMode == OverlayMode.COVER_DIRECT
        }
        val rbAbove = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Altyazının hemen üstünde (Tüm ekran — Sıfır titreme & Tam hız)"
            setTextColor(Color.WHITE)
            isChecked = prefs.overlayMode == OverlayMode.ABOVE
        }
        val rbCoverHide = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Üstünü kapat — Eski Android / Tüm ekran (kısa gizlemeli)"
            setTextColor(Color.parseColor("#CBD5E1"))
            isChecked = prefs.overlayMode == OverlayMode.COVER_HIDE
        }
        radioGroup.addView(rbCoverDirect)
        radioGroup.addView(rbAbove)
        radioGroup.addView(rbCoverHide)
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.overlayMode = when (checkedId) {
                rbAbove.id -> OverlayMode.ABOVE
                rbCoverHide.id -> OverlayMode.COVER_HIDE
                else -> OverlayMode.COVER_DIRECT
            }
        }
        modeCard.addView(radioGroup)
        modeCard.addView(TextView(this).apply {
            text = "İpucu: Sıfır titremeyle PC gibi tam üstünü kapatmak için 'Oyun Üstünde Başlat' dediğinde " +
                "çıkan Android penceresinde 'Tek bir uygulama' seçip oyununu (veya YouTube'u) seç!"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }, marginParams(top = 8))
        root.addView(modeCard, marginParams(top = 16))

        // 3. Appearance & Quality Card
        val optCard = makeCard("3. Görünüm & Çeviri Kalitesi")

        val tvOp = TextView(this).apply {
            text = "Kutu Koyuluğu: %${prefs.overlayOpacity * 100 / 255}"
            setTextColor(Color.parseColor("#E2E8F0"))
        }
        val sbOp = SeekBar(this).apply {
            max = 175
            progress = prefs.overlayOpacity - 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    prefs.overlayOpacity = p + 80
                    tvOp.text = "Kutu Koyuluğu: %${prefs.overlayOpacity * 100 / 255}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        optCard.addView(tvOp)
        optCard.addView(sbOp)

        val tvFont = TextView(this).apply {
            text = "Yazı Boyutu: ${prefs.fontSizeSp} sp"
            setTextColor(Color.parseColor("#E2E8F0"))
        }
        val sbFont = SeekBar(this).apply {
            max = 22
            progress = prefs.fontSizeSp - 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    prefs.fontSizeSp = p + 12
                    tvFont.text = "Yazı Boyutu: ${prefs.fontSizeSp} sp"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        optCard.addView(tvFont, marginParams(top = 10))
        optCard.addView(sbFont)

        val tvCoverInterval = TextView(this).apply {
            text = "Kapatma Modu Kontrol Aralığı: ${"%.1f".format(prefs.coverCheckIntervalMs / 1000f)} sn"
            setTextColor(Color.parseColor("#E2E8F0"))
        }
        val sbCoverInterval = SeekBar(this).apply {
            max = 25 // 1.0s .. 3.5s in 0.1s steps
            progress = ((prefs.coverCheckIntervalMs - 1000) / 100).coerceIn(0, 25)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    val ms = 1000 + p * 100
                    prefs.coverCheckIntervalMs = ms
                    tvCoverInterval.text = "Kapatma Modu Kontrol Aralığı: ${"%.1f".format(ms / 1000f)} sn"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        optCard.addView(tvCoverInterval, marginParams(top = 10))
        optCard.addView(sbCoverInterval)

        optCard.addView(CheckBox(this).apply {
            text = "Konuşmacı adını koru ve altın renkle vurgula (Arthur: ...)"
            setTextColor(Color.WHITE)
            isChecked = prefs.speakerHighlight
            setOnCheckedChangeListener { _, checked -> prefs.speakerHighlight = checked }
        }, marginParams(top = 8))

        optCard.addView(CheckBox(this).apply {
            text = "Oyun deyimleri düzeltici (out cold → baygın vb.)"
            setTextColor(Color.WHITE)
            isChecked = prefs.gameIdioms
            setOnCheckedChangeListener { _, checked -> prefs.gameIdioms = checked }
        })

        optCard.addView(CheckBox(this).apply {
            text = "Orijinal İngilizce metni de üstte küçük göster"
            setTextColor(Color.WHITE)
            isChecked = prefs.showOriginal
            setOnCheckedChangeListener { _, checked -> prefs.showOriginal = checked }
        })

        root.addView(optCard, marginParams(top = 16))

        // 4. Primary Start / Stop Button
        btnStartStop = Button(this).apply {
            text = "Oyun Üstünde Başlat"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = cardDrawable("#0284C7", "#38BDF8")
            setPadding(24, 28, 24, 28)
            setOnClickListener { onStartStopClicked() }
        }
        root.addView(btnStartStop, marginParams(top = 24))

        return scroll
    }

    private fun makeCard(title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = cardDrawable("#131C31", "#334155")
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        })
        return card
    }

    private fun cardDrawable(fillHex: String, strokeHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            setStroke(2, Color.parseColor(strokeHex))
            cornerRadius = 18f
        }
    }

    private fun marginParams(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
    }

    private fun refreshModelStatusAndLoad() {
        if (ModelDownloader.isModelReady(this)) {
            btnDownload.text = "Model Hazır ✓"
            btnDownload.isEnabled = false
            tvStatus.text = "QuickMT modeli belleğe yükleniyor..."
            ioExecutor.execute {
                val ok = QuickMTNative.init(ModelDownloader.getModelDir(this), threads = 4)
                runOnUiThread {
                    if (ok) {
                        tvStatus.text = "✓ QuickMT hazır (Çevrimdışı • ARM64 INT8)"
                        btnTest.isEnabled = true
                    } else {
                        tvStatus.text = "✗ QuickMT başlatılamadı (model dosyalarını tekrar indirin)"
                        btnDownload.isEnabled = true
                        btnDownload.text = "Modeli Tekrar İndir"
                    }
                }
            }
        } else {
            tvStatus.text = "Başlamak için önce QuickMT EN→TR modelini (~199 MB) indirin."
            btnDownload.isEnabled = true
            btnTest.isEnabled = false
        }
    }

    private fun startModelDownload() {
        cancelDownload.set(false)
        btnDownload.isEnabled = false
        btnTest.isEnabled = false
        btnCancelDownload.visibility = View.VISIBLE
        btnCancelDownload.isEnabled = true
        btnCancelDownload.text = "İptal Et"
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0

        ioExecutor.execute {
            try {
                ModelDownloader.download(this, cancelDownload) { p ->
                    runOnUiThread {
                        progressBar.progress = p.percent
                        tvDownloadInfo.text =
                            "İndiriliyor: ${p.currentFile}  •  %${p.percent} (${"%.1f".format(p.downloadedMb)} / ${"%.1f".format(p.totalMb)} MB • ${"%.1f".format(p.speedMbs)} MB/s)"
                    }
                }
                runOnUiThread {
                    btnCancelDownload.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    tvDownloadInfo.text = "Model indirildi ✓ (value-productions/quickmt-en-tr-int8)"
                    refreshModelStatusAndLoad()
                }
            } catch (_: ModelDownloader.CancelledException) {
                runOnUiThread {
                    btnCancelDownload.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    btnDownload.isEnabled = true
                    btnDownload.text = "İndirmeye Devam Et"
                    tvStatus.text = "⏹ İndirme iptal edildi (kaldığı kısım saklandı)."
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    btnCancelDownload.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    btnDownload.isEnabled = true
                    tvStatus.text = "✗ İndirme hatası: ${ex.message}"
                }
            }
        }
    }

    private fun runQuickTest() {
        if (!QuickMTNative.isInitialized) return
        tvStatus.text = "Test çevirisi yapılıyor..."
        ioExecutor.execute {
            val sample = "He's been out cold for hours."
            val t0 = System.nanoTime()
            val out = QuickMTNative.translate(sample, prefs.beamSize, prefs.gameIdioms)
            val ms = (System.nanoTime() - t0) / 1_000_000L
            runOnUiThread {
                tvStatus.text = "✓ Test (${ms} ms): $out"
            }
        }
    }

    private fun onStartStopClicked() {
        if (OverlayService.isRunning) {
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP
            })
            updateStartButtonState(running = false)
            return
        }

        if (!ModelDownloader.isModelReady(this)) {
            Toast.makeText(this, "Önce QuickMT modelini indirin.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Lütfen 'Diğer uygulamaların üzerinde göster' iznini açın.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun updateStartButtonState(running: Boolean) {
        if (running) {
            btnStartStop.text = "Durdur"
            btnStartStop.background = cardDrawable("#991B1B", "#EF4444")
        } else {
            btnStartStop.text = "Oyun Üstünde Başlat"
            btnStartStop.background = cardDrawable("#0284C7", "#38BDF8")
        }
    }
}
