package com.example.timertap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** マーカーの位置(オーバーレイウィンドウの左上座標)。 */
data class Point(val x: Int, val y: Int)

/** ユーザー設定。SharedPreferences に JSON で保存する。 */
data class Prefs(
    val hour: Int = 12,
    val minute: Int = 0,
    val second: Int = 0,
    val millis: Int = 0,
    /** マーカーを 1→2→… と順にタップする一巡を何回繰り返すか。 */
    val count: Int = 1,
    val intervalMs: Long = 100,
    val offsetMs: Long = 0,
    val useNtp: Boolean = true,
    /** マーカーの位置。空なら画面中央に1個。 */
    val points: List<Point> = emptyList(),
) {
    /** now 以降で最初に設定時刻になる瞬間(エポックミリ秒)。過ぎていれば翌日。補正は含まない。 */
    fun nextTargetMillis(now: Long): Long {
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

    fun toJson(): JSONObject = JSONObject()
        .put("hour", hour).put("minute", minute).put("second", second).put("millis", millis)
        .put("count", count).put("interval", intervalMs).put("offset", offsetMs)
        .put("useNtp", useNtp)
        .put("points", JSONArray().apply { points.forEach { put(JSONArray().put(it.x).put(it.y)) } })

    fun save(context: Context) {
        store(context).edit().putString(KEY_CURRENT, toJson().toString()).apply()
    }

    companion object {
        private const val NAME = "settings"
        private const val KEY_CURRENT = "current"
        private const val KEY_PRESETS = "presets"
        const val MIN_INTERVAL_MS = 30L
        const val MAX_MARKERS = 10

        private fun store(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

        fun fromJson(o: JSONObject): Prefs {
            val d = Prefs()
            val pts = o.optJSONArray("points") ?: JSONArray()
            return Prefs(
                hour = o.optInt("hour", d.hour),
                minute = o.optInt("minute", d.minute),
                second = o.optInt("second", d.second),
                millis = o.optInt("millis", d.millis),
                count = o.optInt("count", d.count),
                intervalMs = o.optLong("interval", d.intervalMs),
                offsetMs = o.optLong("offset", d.offsetMs),
                useNtp = o.optBoolean("useNtp", d.useNtp),
                points = (0 until pts.length()).map { i ->
                    val p = pts.getJSONArray(i)
                    Point(p.getInt(0), p.getInt(1))
                },
            )
        }

        fun load(context: Context): Prefs {
            val sp = store(context)
            val json = sp.getString(KEY_CURRENT, null)
            if (json != null) return fromJson(JSONObject(json))
            // 旧バージョンは項目ごとに保存していたので、そこから引き継ぐ
            val d = Prefs()
            return Prefs(
                sp.getInt("hour", d.hour), sp.getInt("minute", d.minute),
                sp.getInt("second", d.second), sp.getInt("millis", d.millis),
                sp.getInt("count", d.count), sp.getLong("interval", d.intervalMs),
                sp.getLong("offset", d.offsetMs),
            )
        }

        // ---- プリセット(名前を付けて保存した設定) ----

        private fun presets(context: Context) =
            JSONObject(store(context).getString(KEY_PRESETS, null) ?: "{}")

        fun presetNames(context: Context): List<String> = presets(context).keys().asSequence().sorted().toList()

        fun loadPreset(context: Context, name: String): Prefs? =
            presets(context).optJSONObject(name)?.let { fromJson(it) }

        fun savePreset(context: Context, name: String, prefs: Prefs) {
            val all = presets(context).put(name, prefs.toJson())
            store(context).edit().putString(KEY_PRESETS, all.toString()).apply()
        }

        fun deletePreset(context: Context, name: String) {
            val all = presets(context).apply { remove(name) }
            store(context).edit().putString(KEY_PRESETS, all.toString()).apply()
        }
    }
}
