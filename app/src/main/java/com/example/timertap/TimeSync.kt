package com.example.timertap

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * NTP サーバーから標準時刻を取得し、端末の時計のずれを補正した現在時刻を返す。
 *
 * 同期結果は「同期した瞬間の標準時刻」と「そのときの起動後経過時間」の組で持つ。
 * 経過時間は端末の時計を変更しても影響を受けないため、同期後に時計が動いても狂わない。
 */
object TimeSync {

    private class Anchor(val trueMs: Long, val elapsedMs: Long)
    private class Sample(val trueAtReceive: Long, val elapsedAtReceive: Long, val delay: Long, val offset: Long)

    private val servers = listOf("ntp.nict.jp", "time.google.com", "pool.ntp.org")
    private const val SAMPLES = 3
    private const val TIMEOUT_MS = 2000
    private const val NTP_EPOCH_OFFSET_S = 2_208_988_800L

    @Volatile private var anchor: Anchor? = null
    @Volatile private var syncing = false

    /** 直近の同期で測った「標準時刻 − 端末の時計」(ミリ秒)。 */
    @Volatile var lastOffsetMs = 0L
        private set

    val synced: Boolean get() = anchor != null

    /** 補正済みの現在時刻。未同期なら端末の時計。 */
    fun now(): Long {
        val a = anchor ?: return System.currentTimeMillis()
        return a.trueMs + (SystemClock.elapsedRealtime() - a.elapsedMs)
    }

    /** 最後の同期からの経過ミリ秒。未同期なら Long.MAX_VALUE。 */
    fun ageMs(): Long {
        val a = anchor ?: return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - a.elapsedMs
    }

    /** バックグラウンドで同期する。結果(成功したか)はメインスレッドで通知する。 */
    fun syncAsync(onDone: ((Boolean) -> Unit)? = null) {
        if (syncing) return
        syncing = true
        Thread({
            val ok = try {
                syncBlocking()
            } finally {
                syncing = false
            }
            if (onDone != null) Handler(Looper.getMainLooper()).post { onDone(ok) }
        }, "ntp-sync").start()
    }

    private fun syncBlocking(): Boolean {
        for (host in servers) {
            // 往復時間がいちばん短かった応答が、いちばん誤差が小さい
            val best = (1..SAMPLES).mapNotNull { query(host) }.minByOrNull { it.delay } ?: continue
            anchor = Anchor(best.trueAtReceive, best.elapsedAtReceive)
            lastOffsetMs = best.offset
            return true
        }
        return false
    }

    private fun query(host: String): Sample? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            val address = InetAddress.getByName(host)
            val buf = ByteArray(48)
            buf[0] = 0x1B // LI=0, VN=3, Mode=3(client)

            val sendWall = System.currentTimeMillis()
            val sendElapsed = SystemClock.elapsedRealtime()
            socket.send(DatagramPacket(buf, buf.size, address, 123))

            val response = DatagramPacket(ByteArray(48), 48)
            socket.receive(response)
            val recvElapsed = SystemClock.elapsedRealtime()
            val data = response.data

            val mode = data[0].toInt() and 0x7
            val stratum = data[1].toInt() and 0xFF
            val serverReceive = readTimestamp(data, 32)
            val serverTransmit = readTimestamp(data, 40)
            if (response.length < 48 || (mode != 4 && mode != 5) || stratum == 0 || serverTransmit == 0L) {
                return@use null
            }

            val roundTrip = recvElapsed - sendElapsed
            val recvWall = sendWall + roundTrip
            val offset = ((serverReceive - sendWall) + (serverTransmit - recvWall)) / 2
            val delay = roundTrip - (serverTransmit - serverReceive)
            Sample(recvWall + offset, recvElapsed, delay, offset)
        }
    } catch (e: Exception) {
        null
    }

    /** NTP タイムスタンプ(1900年起点の秒 + 小数部)を Unix エポックのミリ秒にする。 */
    private fun readTimestamp(data: ByteArray, at: Int): Long {
        var seconds = 0L
        var fraction = 0L
        for (i in 0..3) seconds = (seconds shl 8) or (data[at + i].toLong() and 0xFF)
        for (i in 4..7) fraction = (fraction shl 8) or (data[at + i].toLong() and 0xFF)
        if (seconds == 0L && fraction == 0L) return 0L
        return (seconds - NTP_EPOCH_OFFSET_S) * 1000 + fraction * 1000 / 0x1_0000_0000L
    }
}
