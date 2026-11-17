package com.example.timertap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 画面上にマーカーと操作パネルを重ねて表示し、設定時刻にマーカー位置を番号順にタップする。
 * タップは AccessibilityService.dispatchGesture で行う。
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null
            private set

        /** 待機中、これより古くなったら時刻を取り直す。 */
        private const val RESYNC_AGE_MS = 10 * 60_000L
    }

    private class Marker(val view: MarkerView, val lp: WindowManager.LayoutParams)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private val markers = mutableListOf<Marker>()
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var clockText: TextView? = null
    private var stateText: TextView? = null
    private var startButton: TextView? = null

    /** 待機セッションの世代。変わったら実行中のワーカーは中断する。 */
    private val generation = AtomicInteger(0)
    @Volatile private var armed = false
    @Volatile private var useNtp = true
    private var targetAt = 0L
    private var finalSyncDone = false

    private val clockFormat = SimpleDateFormat("HH:mm:ss.S", Locale.US)
    private val ticker = object : Runnable {
        override fun run() {
            val now = now()
            clockText?.text = clockFormat.format(Date(now))
            if (armed) {
                val remaining = targetAt - now
                stateText?.text = "待機中 あと ${formatRemaining(remaining)}"
                resyncIfNeeded(remaining)
            }
            ui.postDelayed(this, 50)
        }
    }

    override fun onServiceConnected() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** 補正を使う設定なら標準時刻、そうでなければ端末の時計。 */
    private fun now(): Long = if (useNtp) TimeSync.now() else System.currentTimeMillis()

    // ---- オーバーレイ ----

    fun showOverlay() {
        val prefs = Prefs.load(this)
        useNtp = prefs.useNtp
        if (useNtp && !TimeSync.synced) TimeSync.syncAsync { refreshIdleState() }
        if (panel != null) {
            refreshIdleState()
            return
        }
        createMarkers(prefs.points)

        val p = buildPanel()
        val pLp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = dp(16)
            y = dp(96)
        }
        makeDraggable(p, p, pLp) {}
        wm.addView(p, pLp)
        panel = p
        panelLp = pLp

        refreshIdleState()
        ui.post(ticker)
    }

    fun hideOverlay() {
        disarm()
        ui.removeCallbacks(ticker)
        removeMarkers()
        panel?.let { wm.removeView(it) }
        panel = null
        panelLp = null
        clockText = null
        stateText = null
        startButton = null
    }

    /** 設定(プリセットの読み込みなど)が変わったとき、表示中のマーカーを作り直す。 */
    fun reloadFromPrefs() {
        if (panel == null) return
        disarm()
        val prefs = Prefs.load(this)
        useNtp = prefs.useNtp
        removeMarkers()
        createMarkers(prefs.points)
        refreshIdleState()
    }

    private fun createMarkers(points: List<Point>) {
        if (points.isEmpty()) addMarkerAt(defaultPoint(0)) else points.forEach { addMarkerAt(it) }
    }

    private fun removeMarkers() {
        markers.forEach { wm.removeView(it.view) }
        markers.clear()
    }

    private fun defaultPoint(index: Int): Point {
        val size = dp(56)
        val metrics = resources.displayMetrics
        return Point(
            metrics.widthPixels / 2 - size / 2 + index * dp(28),
            metrics.heightPixels / 2 - size / 2 + index * dp(28),
        )
    }

    private fun addMarkerAt(point: Point) {
        val size = dp(56)
        val view = MarkerView(this, markers.size + 1)
        val lp = overlayParams(size, size).apply {
            x = point.x
            y = point.y
        }
        makeDraggable(view, view, lp) { savePoints() }
        wm.addView(view, lp)
        markers.add(Marker(view, lp))
    }

    private fun addMarker() {
        if (armed) return
        if (markers.size >= Prefs.MAX_MARKERS) {
            Toast.makeText(this, "マーカーは${Prefs.MAX_MARKERS}個までです", Toast.LENGTH_SHORT).show()
            return
        }
        addMarkerAt(defaultPoint(markers.size))
        savePoints()
    }

    private fun removeLastMarker() {
        if (armed || markers.size <= 1) return
        val last = markers.removeAt(markers.size - 1)
        wm.removeView(last.view)
        savePoints()
    }

    private fun savePoints() {
        Prefs.load(this).copy(points = markers.map { Point(it.lp.x, it.lp.y) }).save(this)
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun buildPanel(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE61E293B.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        clockText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.MONOSPACE
        }
        stateText = TextView(this).apply {
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 12f
        }
        root.addView(clockText)
        root.addView(stateText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = panelButton("▶") { if (armed) disarm() else arm() }
        row.addView(startButton)
        row.addView(panelButton("＋") { addMarker() })
        row.addView(panelButton("－") { removeLastMarker() })
        row.addView(panelButton("⚙") {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
        row.addView(panelButton("✕") { hideOverlay() })
        root.addView(row)
        return root
    }

    private fun panelButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(0xFF334155.toInt())
            cornerRadius = dp(10).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            topMargin = dp(6)
            rightMargin = dp(6)
        }
        setOnClickListener { onClick() }
    }

    /** handle をドラッグすると window を移動する。動かさずに離した場合はクリック扱い。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View, window: View, lp: WindowManager.LayoutParams, onDrop: () -> Unit) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    if (window.isAttachedToWindow) wm.updateViewLayout(window, lp)
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) < dp(4) && abs(e.rawY - downY) < dp(4)) handle.performClick()
                    else onDrop()
                }
            }
            true
        }
    }

    // ---- 待機とタップ ----

    private fun arm() {
        if (markers.isEmpty()) return
        val prefs = Prefs.load(this)
        useNtp = prefs.useNtp

        // 各マーカーの中心(画面座標)を番号順に求める
        val taps = markers.map { m ->
            val loc = IntArray(2)
            m.view.getLocationOnScreen(loc)
            floatArrayOf(loc[0] + m.view.width / 2f, loc[1] + m.view.height / 2f)
        }

        val first = prefs.nextTargetMillis(now()) + prefs.offsetMs
        targetAt = first
        armed = true
        finalSyncDone = false
        val myGen = generation.incrementAndGet()

        // マーカー自身がタップを受け取らないよう、待機中はタッチを素通しにする
        setMarkersArmed(true)
        setKeepScreenOn(true)
        startButton?.text = "■"

        val total = prefs.count * taps.size
        Thread({
            var done = 0
            while (done < total) {
                if (!waitUntil(first + done * prefs.intervalMs, myGen)) return@Thread
                val p = taps[done % taps.size]
                tap(p[0], p[1])
                done++
            }
            ui.post {
                if (generation.get() == myGen) {
                    disarm()
                    Toast.makeText(this, "タップ完了(${total}回)", Toast.LENGTH_SHORT).show()
                }
            }
        }, "tap-timer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun disarm() {
        generation.incrementAndGet()
        armed = false
        setMarkersArmed(false)
        setKeepScreenOn(false)
        refreshIdleState()
    }

    /**
     * 待機が長いと端末内部の時計が少しずつずれるので、標準時刻を取り直す。
     * 古くなったときと、タップの少し前(残り15〜45秒)に1回行う。
     */
    private fun resyncIfNeeded(remaining: Long) {
        if (!useNtp || remaining < 15_000) return
        val nearTarget = remaining < 45_000 && !finalSyncDone && TimeSync.ageMs() > 30_000
        if (nearTarget || TimeSync.ageMs() > RESYNC_AGE_MS) {
            if (nearTarget) finalSyncDone = true
            TimeSync.syncAsync()
        }
    }

    private fun refreshIdleState() {
        if (armed) return
        startButton?.text = "▶"
        val clock = when {
            !useNtp -> "端末時計"
            TimeSync.synced -> "標準時刻"
            else -> "未同期"
        }
        stateText?.text = "設定 ${Prefs.load(this).targetLabel()}・$clock"
    }

    /**
     * 時刻が time になるまで待つ。直前までは sleep、最後の数ミリ秒はスピンして精度を出す。
     * 途中で中断されたら false。
     */
    private fun waitUntil(time: Long, myGen: Int): Boolean {
        while (true) {
            if (generation.get() != myGen) return false
            val remaining = time - now()
            if (remaining <= 0) return true
            if (remaining > 3) Thread.sleep(minOf(remaining - 3, 20))
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 10))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun setMarkersArmed(armedNow: Boolean) {
        for (m in markers) {
            m.lp.flags = if (armedNow) m.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else m.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (m.view.isAttachedToWindow) wm.updateViewLayout(m.view, m.lp)
            m.view.armed = armedNow
            m.view.invalidate()
        }
    }

    /** 待機中は画面が消えないようにする(画面が消えるとタップできないため)。 */
    private fun setKeepScreenOn(on: Boolean) {
        val p = panel ?: return
        val lp = panelLp ?: return
        lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (p.isAttachedToWindow) wm.updateViewLayout(p, lp)
    }

    private fun formatRemaining(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val min = t / 60_000 % 60
        val s = t / 1000 % 60
        val tenth = t / 100 % 10
        return if (h > 0) "%d:%02d:%02d".format(h, min, s) else "%02d:%02d.%d".format(min, s, tenth)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** タップ位置を示す照準マーカー。中心がタップ位置で、右上の数字がタップする順番。 */
    private class MarkerView(context: Context, private val number: Int) : View(context) {
        var armed = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - 4f
            val color = if (armed) 0xFF22C55E.toInt() else 0xFFF43F5E.toInt()

            paint.style = Paint.Style.FILL
            paint.color = (color and 0x00FFFFFF) or 0x33000000
            canvas.drawCircle(cx, cy, r, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = color
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawLine(cx - r, cy, cx - r / 3, cy, paint)
            canvas.drawLine(cx + r / 3, cy, cx + r, cy, paint)
            canvas.drawLine(cx, cy - r, cx, cy - r / 3, paint)
            canvas.drawLine(cx, cy + r / 3, cx, cy + r, paint)

            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, 4f, paint)

            // 順番の数字(白フチ付きで背景を問わず読めるようにする)
            paint.textSize = r * 0.62f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            val tx = cx + r * 0.5f
            val ty = cy - r * 0.28f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = Color.WHITE
            canvas.drawText(number.toString(), tx, ty, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF0F172A.toInt()
            canvas.drawText(number.toString(), tx, ty, paint)
        }
    }
}
