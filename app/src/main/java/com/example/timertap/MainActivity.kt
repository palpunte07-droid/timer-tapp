package com.example.timertap

import android.app.Activity
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var hour: EditText
    private lateinit var minute: EditText
    private lateinit var second: EditText
    private lateinit var millis: EditText
    private lateinit var count: EditText
    private lateinit var interval: EditText
    private lateinit var offset: EditText
    private lateinit var useNtp: CheckBox
    private lateinit var ntpStatus: TextView
    private lateinit var presetSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        hour = findViewById(R.id.hour)
        minute = findViewById(R.id.minute)
        second = findViewById(R.id.second)
        millis = findViewById(R.id.millis)
        count = findViewById(R.id.count)
        interval = findViewById(R.id.interval)
        offset = findViewById(R.id.offset)
        useNtp = findViewById(R.id.useNtp)
        ntpStatus = findViewById(R.id.ntpStatus)
        presetSpinner = findViewById(R.id.presetSpinner)

        show(Prefs.load(this))
        refreshPresets(null)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnNow).setOnClickListener {
            val cal = Calendar.getInstance().apply {
                timeInMillis = if (useNtp.isChecked) TimeSync.now() else System.currentTimeMillis()
                add(Calendar.MINUTE, 1)
            }
            hour.setText(cal.get(Calendar.HOUR_OF_DAY).toString())
            minute.setText(cal.get(Calendar.MINUTE).toString())
            second.setText("0")
            millis.setText("0")
        }
        bindStep(R.id.hourUp, 3_600_000L, 1)
        bindStep(R.id.hourDown, 3_600_000L, -1)
        bindStep(R.id.minuteUp, 60_000L, 1)
        bindStep(R.id.minuteDown, 60_000L, -1)
        bindStep(R.id.secondUp, 1_000L, 1)
        bindStep(R.id.secondDown, 1_000L, -1)
        bindStep(R.id.millisUp, 1L, 1)
        bindStep(R.id.millisDown, 1L, -1)

        findViewById<Button>(R.id.btnMidnight).setOnClickListener {
            hour.setText("0")
            minute.setText("0")
            second.setText("0")
            millis.setText("0")
        }
        findViewById<Button>(R.id.btnSync).setOnClickListener { sync() }
        useNtp.setOnCheckedChangeListener { _, checked ->
            if (checked && !TimeSync.synced) sync() else showNtpStatus()
        }

        findViewById<Button>(R.id.btnPresetLoad).setOnClickListener { loadPreset() }
        findViewById<Button>(R.id.btnPresetSave).setOnClickListener { askPresetName() }
        findViewById<Button>(R.id.btnPresetDelete).setOnClickListener { deletePreset() }

        findViewById<Button>(R.id.btnShow).setOnClickListener {
            val prefs = read()
            prefs.save(this)
            show(prefs)
            val service = TapService.instance
            if (service == null) {
                Toast.makeText(this, "先にユーザー補助で「タイマータップ」を有効にしてください", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                service.showOverlay()
                Toast.makeText(this, "パネルを表示しました。対象のアプリを開いてください", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = if (TapService.instance != null) "ユーザー補助サービス: 有効 ✓"
        else "ユーザー補助サービス: 無効(タップするには有効化が必要です)"
        if (useNtp.isChecked) sync() else showNtpStatus()
    }

    // ---- 時刻の＋／－ ----

    private val repeatHandler = Handler(Looper.getMainLooper())

    /**
     * ＋／－ボタン。押すと unit×sign だけ時刻を動かし、長押し中は連続で動かす。
     * ミリ秒は長押しが続くと10ずつ動かして速くする。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindStep(id: Int, unit: Long, sign: Int) {
        val button = findViewById<Button>(id)
        var repeats = 0
        val repeater = object : Runnable {
            override fun run() {
                repeats++
                val step = if (unit == 1L && repeats > 15) 10L else 1L
                shiftTime(unit * step * sign)
                repeatHandler.postDelayed(this, if (repeats > 15) 50L else 90L)
            }
        }
        button.setOnTouchListener { v: View, e: MotionEvent ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    repeats = 0
                    v.isPressed = true
                    shiftTime(unit * sign)
                    repeatHandler.postDelayed(repeater, 400L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeatHandler.removeCallbacks(repeater)
                }
            }
            true
        }
        // ユーザー補助(TalkBack など)からの操作用
        button.setOnClickListener { shiftTime(unit * sign) }
    }

    /** 入力中の時刻を deltaMs だけ動かす。24時間で一周し、繰り上がり・繰り下がりする。 */
    private fun shiftTime(deltaMs: Long) {
        fun EditText.num(max: Long) = (text.toString().trim().toLongOrNull() ?: 0L).coerceIn(0, max)
        val day = 86_400_000L
        val current = hour.num(23) * 3_600_000 + minute.num(59) * 60_000 + second.num(59) * 1_000 + millis.num(999)
        val t = ((current + deltaMs) % day + day) % day
        hour.setText((t / 3_600_000).toString())
        minute.setText((t / 60_000 % 60).toString())
        second.setText((t / 1_000 % 60).toString())
        millis.setText((t % 1_000).toString())
    }

    // ---- 時刻の同期 ----

    private fun sync() {
        ntpStatus.text = "標準時刻と同期中…"
        TimeSync.syncAsync { ok ->
            showNtpStatus(failed = !ok)
        }
    }

    private fun showNtpStatus(failed: Boolean = false) {
        ntpStatus.text = when {
            !useNtp.isChecked -> "補正なし: 端末の時計をそのまま使います"
            TimeSync.synced -> {
                val diff = -TimeSync.lastOffsetMs
                val text = when {
                    diff > 0 -> "端末の時計は ${diff} ミリ秒 進んでいます"
                    diff < 0 -> "端末の時計は ${-diff} ミリ秒 遅れています"
                    else -> "端末の時計にずれはありません"
                }
                "同期済み ✓ $text(自動で補正します)" +
                    if (failed) "\n今回の再同期は失敗したため、前回の結果を使っています" else ""
            }
            else -> "同期できませんでした。通信状況を確認してください(同期できるまでは端末の時計を使います)"
        }
    }

    // ---- プリセット ----

    private fun refreshPresets(select: String?) {
        val names = Prefs.presetNames(this)
        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            names.ifEmpty { listOf("(保存した設定はありません)") },
        )
        val index = names.indexOf(select)
        if (index >= 0) presetSpinner.setSelection(index)
    }

    private fun selectedPreset(): String? {
        val names = Prefs.presetNames(this)
        return names.getOrNull(presetSpinner.selectedItemPosition)
    }

    private fun loadPreset() {
        val name = selectedPreset() ?: return
        val prefs = Prefs.loadPreset(this, name) ?: return
        prefs.save(this)
        show(prefs)
        TapService.instance?.reloadFromPrefs()
        Toast.makeText(this, "「$name」を読み込みました", Toast.LENGTH_SHORT).show()
    }

    private fun askPresetName() {
        val input = EditText(this).apply {
            hint = "例: 0時の予約"
            setText(selectedPreset() ?: "")
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("設定に名前を付けて保存")
            .setMessage("時刻・回数・間隔・補正と、いまのマーカーの位置を保存します。同じ名前は上書きします。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名前を入力してください", Toast.LENGTH_SHORT).show()
                } else {
                    val prefs = read()
                    prefs.save(this)
                    Prefs.savePreset(this, name, prefs)
                    refreshPresets(name)
                    Toast.makeText(this, "「$name」を保存しました", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deletePreset() {
        val name = selectedPreset() ?: return
        AlertDialog.Builder(this)
            .setMessage("「$name」を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                Prefs.deletePreset(this, name)
                refreshPresets(null)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---- 入力欄 ----

    private fun show(p: Prefs) {
        hour.setText(p.hour.toString())
        minute.setText(p.minute.toString())
        second.setText(p.second.toString())
        millis.setText(p.millis.toString())
        count.setText(p.count.toString())
        interval.setText(p.intervalMs.toString())
        offset.setText(p.offsetMs.toString())
        useNtp.isChecked = p.useNtp
    }

    /** 入力欄を読み取り、範囲外の値は丸める。マーカーの位置はパネル側で保存したものを引き継ぐ。 */
    private fun read(): Prefs {
        fun EditText.num(default: Long) = text.toString().trim().toLongOrNull() ?: default
        return Prefs(
            hour = hour.num(0).coerceIn(0, 23).toInt(),
            minute = minute.num(0).coerceIn(0, 59).toInt(),
            second = second.num(0).coerceIn(0, 59).toInt(),
            millis = millis.num(0).coerceIn(0, 999).toInt(),
            count = count.num(1).coerceIn(1, 9999).toInt(),
            intervalMs = interval.num(100).coerceAtLeast(Prefs.MIN_INTERVAL_MS),
            offsetMs = offset.num(0).coerceIn(-60_000, 60_000),
            useNtp = useNtp.isChecked,
            points = Prefs.load(this).points,
        )
    }
}
