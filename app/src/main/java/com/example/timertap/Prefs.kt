package com.example.timertap

import android.content.Context
import java.util.Calendar

/** ユーザー設定。SharedPreferences に保存する。 */
data class Prefs(
    val hour: Int = 12,
    val minute: Int = 0,
    val second: Int = 0,
    val millis: Int = 0,
    val count: Int = 1,
    val intervalMs: Long = 100,
    val offsetMs: Long = 0,
) {
    /** 次に設定時刻になる瞬間(端末時計のエポックミリ秒)。過ぎていれば翌日。補正は含まない。 */
    fun nextTargetMillis(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, millis)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    fun targetLabel(): String = "%02d:%02d:%02d.%03d".format(hour, minute, second, millis)

    fun save(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt("hour", hour).putInt("minute", minute)
            .putInt("second", second).putInt("millis", millis)
            .putInt("count", count).putLong("interval", intervalMs)
            .putLong("offset", offsetMs)
            .apply()
    }

    companion object {
        private const val NAME = "settings"
        const val MIN_INTERVAL_MS = 30L

        fun load(context: Context): Prefs {
            val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            val d = Prefs()
            return Prefs(
                sp.getInt("hour", d.hour), sp.getInt("minute", d.minute),
                sp.getInt("second", d.second), sp.getInt("millis", d.millis),
                sp.getInt("count", d.count), sp.getLong("interval", d.intervalMs),
                sp.getLong("offset", d.offsetMs),
            )
        }
    }
}
