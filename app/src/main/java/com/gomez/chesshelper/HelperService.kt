package com.gomez.chesshelper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class HelperService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile var running = false

        const val SCAN_MS = 300L        // how often the screen is read
        const val STABLE_FRAMES = 3     // same reading this many times before a move counts
        const val THINK_MS = 600        // engine thinking time per move
        const val AUTO_DELAY_MS = 700L  // wait before auto play taps
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var pending: Image? = null
    private var frame: Frame? = null
    private var rowBytes: ByteArray? = null

    private lateinit var wm: WindowManager
    private var overlay: View? = null
    private lateinit var moveTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var autoCb: CheckBox

    private var engine: Engine? = null
    private var reader: BoardReader? = null
    private var game: Game? = null
    private val history = ArrayList<IntArray>()
    private var lastBad: IntArray? = null
    private var badCount = 0
    private var suggestedFen: String? = null
    private var playedFen: String? = null
    private var looping = false
    @Volatile private var auto = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running || intent == null) return START_NOT_STICKY
        startForegroundNow()
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        workerThread = HandlerThread("vision").apply { start() }
        worker = Handler(workerThread.looper)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        startCapture()
        showOverlay()
        worker.post {
            try {
                engine = Engine(File(applicationInfo.nativeLibraryDir, "libstockfish.so").absolutePath)
                ui("جاهز", "افتح لعبة ضد بوت والرقعة على البداية، واضغط ابدأ")
            } catch (e: Exception) {
                ui("خطأ", "ما اشتغل المحرك: ${e.message}")
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("helper", "مساعد الشطرنج", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "helper")
            .setContentTitle("مساعد الشطرنج شغال")
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    // ---------- screen capture ----------

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    @SuppressLint("WrongConstant")
    private fun startCapture() {
        val (w, h) = screenSize()
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        // Keep only the newest image; it is turned into pixels when a reading is needed.
        ir.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                pending?.close()
                pending = img
            } catch (_: Exception) {}
        }, worker)
        imageReader = ir
        display = projection?.createVirtualDisplay(
            "chess", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, worker
        )
    }

    /** Newest screenshot (runs on the worker thread). */
    private fun latestFrame(): Frame? {
        val img = pending ?: return frame
        pending = null
        try {
            val w = img.width; val h = img.height
            val plane = img.planes[0]
            val ps = plane.pixelStride; val rs = plane.rowStride
            val buf = plane.buffer
            val px = if (frame?.w == w && frame?.h == h) frame!!.px else IntArray(w * h)
            var row = rowBytes
            if (row == null || row.size < rs) { row = ByteArray(rs); rowBytes = row }
            for (y in 0 until h) {
                buf.position(y * rs)
                buf.get(row, 0, minOf(rs, buf.remaining()))
                var o = y * w
                var x = 0
                while (x < w) {
                    val i = x * ps
                    px[o++] = (0xFF shl 24) or ((row[i].toInt() and 0xFF) shl 16) or
                            ((row[i + 1].toInt() and 0xFF) shl 8) or (row[i + 2].toInt() and 0xFF)
                    x++
                }
            }
            frame = Frame(w, h, px)
        } finally {
            img.close()
        }
        return frame
    }

    // ---------- floating window ----------

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showOverlay() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply { setColor(0xEE1E1E1E.toInt()); cornerRadius = 24f }
        }
        moveTv = TextView(this).apply {
            text = "..."; textSize = 24f; setTextColor(0xFF7CFC00.toInt())
            gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        infoTv = TextView(this).apply {
            text = "يشغل المحرك..."; textSize = 12f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            maxWidth = 700
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val startBtn = Button(this).apply { text = "ابدأ"; setOnClickListener { worker.post { begin() } } }
        autoCb = CheckBox(this).apply {
            text = "تلقائي"; setTextColor(Color.WHITE); isChecked = true
            setOnCheckedChangeListener { _, c -> auto = c; if (c) worker.post { playedFen = null } }
        }
        val stopBtn = Button(this).apply { text = "✕"; setOnClickListener { stopSelf() } }
        row.addView(startBtn); row.addView(autoCb); row.addView(stopBtn)
        box.addView(moveTv); box.addView(infoTv); box.addView(row)

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 80 }

        // Drag the window by its text
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        val drag = View.OnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(box, lp)
                }
            }
            true
        }
        moveTv.setOnTouchListener(drag)
        infoTv.setOnTouchListener(drag)
        wm.addView(box, lp)
        overlay = box
    }

    private fun ui(move: String, info: String, color: Int = 0xFF7CFC00.toInt()) {
        main.post {
            if (overlay == null) return@post
            moveTv.text = move; moveTv.setTextColor(color); infoTv.text = info
        }
    }

    // ---------- game loop (worker thread) ----------

    private fun begin() {
        if (engine == null) { ui("انتظر", "المحرك ما جهز للحين"); return }
        val f = latestFrame()
        if (f == null) { ui("انتظر", "ما وصلت صورة الشاشة للحين، جرّب مرة ثانية"); return }
        val rect = BoardVision.detect(f)
        if (rect == null) { ui("؟", "ما لقيت الرقعة. افتح اللعبة وخل النافذة هذي برا الرقعة", Color.YELLOW); return }
        val r = BoardReader(rect)
        val err = r.calibrate(f)
        if (err != null) { ui("؟", err, Color.YELLOW); return }
        reader = r
        game = Game(r.whiteBottom)
        history.clear(); lastBad = null; badCount = 0; suggestedFen = null; playedFen = null
        ui("✓", "أنت تلعب بـ" + if (r.whiteBottom) "الأبيض" else "الأسود")
        if (!looping) { looping = true; worker.postDelayed(::tick, SCAN_MS) }
    }

    private fun tick() {
        if (!running) return
        try { step() } catch (e: Exception) { ui("خطأ", e.message ?: e.toString(), Color.RED) }
        worker.postDelayed(::tick, SCAN_MS)
    }

    private fun step() {
        val g = game ?: return
        val r = reader ?: return
        val f = latestFrame() ?: return
        val occ = r.read(f)
        history.add(occ)
        if (history.size > STABLE_FRAMES) history.removeAt(0)
        val stable = history.size == STABLE_FRAMES && history.all { it.contentEquals(occ) }
        if (stable) {
            val expected = g.occupancy()
            if (occ.contentEquals(expected)) {
                badCount = 0
            } else if (lastBad == null || !occ.contentEquals(lastBad)) {
                if (g.follow(occ)) {
                    badCount = 0; lastBad = null
                } else {
                    lastBad = occ
                    if (++badCount >= 3) ui("؟", "ما فهمت الرقعة. لا تغطيها بشي، أو اضغط ابدأ بلعبة جديدة", Color.YELLOW)
                }
            }
        }

        if (g.over) { ui("انتهت", "اضغط ابدأ للعبة جديدة", Color.LTGRAY); return }
        if (!g.myTurn) { if (badCount < 3) ui("⏳", "دور الخصم...", Color.LTGRAY); return }

        val fen = g.fen
        if (suggestedFen == fen) return
        val res = engine!!.best(fen, THINK_MS)
        if (g.fen != fen) return
        suggestedFen = fen
        if (res.move.length < 4) return
        val from = Game.index(res.move.substring(0, 2))
        val to = Game.index(res.move.substring(2, 4))
        val ev = when {
            res.mate != null && res.mate > 0 -> "كش مات بعد ${res.mate}"
            res.mate != null -> "خاسر"
            res.cp != null -> "التقييم: %+.1f".format(res.cp / 100.0)
            else -> ""
        }
        ui("${Game.name(from)} → ${Game.name(to)}", "${g.pieceName(from)}  |  $ev")

        val tapper = TapService.instance
        if (auto && playedFen != fen) {
            if (tapper == null) {
                ui("${Game.name(from)} → ${Game.name(to)}", "فعّل اللعب التلقائي من إمكانية الوصول", Color.YELLOW)
                return
            }
            playedFen = fen
            val promo = res.move.length > 4
            worker.postDelayed({
                if (auto && game?.fen == fen) playMove(tapper, r, from, to, promo)
            }, AUTO_DELAY_MS)
        }
    }

    private fun playMove(t: TapService, r: BoardReader, from: Int, to: Int, promo: Boolean) {
        val a = r.center(from); val b = r.center(to)
        main.post { t.tap(a.first, a.second) }
        main.postDelayed({ t.tap(b.first, b.second) }, 200)
        if (promo) main.postDelayed({ t.tap(b.first, b.second) }, 700) // queen is the first choice
    }

    override fun onDestroy() {
        running = false
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        overlay = null
        display?.release()
        imageReader?.close()
        projection?.stop()
        if (::worker.isInitialized) {
            worker.post { engine?.quit(); engine = null }
            workerThread.quitSafely()
        }
        super.onDestroy()
    }
}
