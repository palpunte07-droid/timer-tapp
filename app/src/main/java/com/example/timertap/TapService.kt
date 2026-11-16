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
 * 画面上にマーカーと操作パネルを重ねて表示し、設定時刻にマーカー位置をタップする。
 * タップは AccessibilityService.dispatchGesture で行う。
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null
            private set
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var marker: MarkerView? = null
    private var markerLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var clockText: TextView? = null
    private var stateText: TextView? = null
    private var startButton: TextView? = null

    /** 待機セッションの世代。変わったら実行中のワーカーは中断する。 */
    private val generation = AtomicInteger(0)
    @Volatile private var armed = false
    private var targetAt = 0L

    private val clockFormat = SimpleDateFormat("HH:mm:ss.S", Locale.US)
    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            clockText?.text = clockFormat.format(Date(now))
            if (armed) stateText?.text = "待機中 あと ${formatRemaining(targetAt - now)}"
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

    // ---- オーバーレイ ----

    fun showOverlay() {
        if (panel != null) {
            refreshIdleState()
            return
        }
        val size = dp(56)
        val metrics = resources.displayMetrics

        val m = MarkerView(this)
        val mLp = overlayParams(size, size).apply {
            x = metrics.widthPixels / 2 - size / 2
            y = metrics.heightPixels / 2 - size / 2
        }
        makeDraggable(m, m, mLp)
        wm.addView(m, mLp)
        marker = m
        markerLp = mLp

        val p = buildPanel()
        val pLp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = dp(16)
            y = dp(96)
        }
        makeDraggable(p, p, pLp)
        wm.addView(p, pLp)
        panel = p

        refreshIdleState()
        ui.post(ticker)
    }

    fun hideOverlay() {
        disarm()
        ui.removeCallbacks(ticker)
        marker?.let { wm.removeView(it) }
        panel?.let { wm.removeView(it) }
        marker = null
        markerLp = null
        panel = null
        clockText = null
        stateText = null
        startButton = null
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
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(0xFF334155.toInt())
            cornerRadius = dp(10).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(44)).apply {
            topMargin = dp(6)
            rightMargin = dp(6)
        }
        setOnClickListener { onClick() }
    }

    /** handle をドラッグすると window を移動する。動かさずに離した場合はクリック扱い。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View, window: View, lp: WindowManager.LayoutParams) {
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
                }
            }
            true
        }
    }

    // ---- 待機とタップ ----

    private fun arm() {
        val m = marker ?: return
        val prefs = Prefs.load(this)
        val loc = IntArray(2)
        m.getLocationOnScreen(loc)
        val x = loc[0] + m.width / 2f
        val y = loc[1] + m.height / 2f

        val first = prefs.nextTargetMillis() + prefs.offsetMs
        targetAt = first
        armed = true
        val myGen = generation.incrementAndGet()

        // マーカー自身がタップを受け取らないよう、待機中はタッチを素通しにする
        setMarkerTouchable(false)
        m.armed = true
        m.invalidate()
        startButton?.text = "■"

        Thread({
            var done = 0
            while (done < prefs.count) {
                if (!waitUntil(first + done * prefs.intervalMs, myGen)) return@Thread
                tap(x, y)
                done++
            }
            ui.post {
                if (generation.get() == myGen) {
                    disarm()
                    Toast.makeText(this, "タップ完了(${prefs.count}回)", Toast.LENGTH_SHORT).show()
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
        setMarkerTouchable(true)
        marker?.let { it.armed = false; it.invalidate() }
        refreshIdleState()
    }

    private fun refreshIdleState() {
        if (armed) return
        startButton?.text = "▶"
        stateText?.text = "設定 ${Prefs.load(this).targetLabel()}"
    }

    /**
     * 端末時計が time になるまで待つ。直前までは sleep、最後の数ミリ秒はスピンして精度を出す。
     * 途中で中断されたら false。
     */
    private fun waitUntil(time: Long, myGen: Int): Boolean {
        while (true) {
            if (generation.get() != myGen) return false
            val remaining = time - System.currentTimeMillis()
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

    private fun setMarkerTouchable(touchable: Boolean) {
        val m = marker ?: return
        val lp = markerLp ?: return
        lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (m.isAttachedToWindow) wm.updateViewLayout(m, lp)
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

    /** タップ位置を示す照準マーカー。中心がタップ位置。 */
    private class MarkerView(context: Context) : View(context) {
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
        }
    }
}
