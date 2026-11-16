package com.example.timertap

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
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

        show(Prefs.load(this))

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnNow).setOnClickListener {
            val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 1) }
            hour.setText(cal.get(Calendar.HOUR_OF_DAY).toString())
            minute.setText(cal.get(Calendar.MINUTE).toString())
            second.setText("0")
            millis.setText("0")
        }
        findViewById<Button>(R.id.btnMidnight).setOnClickListener {
            hour.setText("0")
            minute.setText("0")
            second.setText("0")
            millis.setText("0")
        }
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
    }

    private fun show(p: Prefs) {
        hour.setText(p.hour.toString())
        minute.setText(p.minute.toString())
        second.setText(p.second.toString())
        millis.setText(p.millis.toString())
        count.setText(p.count.toString())
        interval.setText(p.intervalMs.toString())
        offset.setText(p.offsetMs.toString())
    }

    /** 入力欄を読み取り、範囲外の値は丸める。 */
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
        )
    }
}
