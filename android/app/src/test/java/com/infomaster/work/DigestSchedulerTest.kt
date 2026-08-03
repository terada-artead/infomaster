package com.infomaster.work

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 次の朝までの待ち時間の検証。
 *
 * ここを間違えると保険の WorkManager が朝以外に走る。特に
 * 「ちょうど 8:00」を過ぎている扱いにしないと、8:00 に起動した直後に
 * また 0 分後を指して無限に走り続ける。
 */
class DigestSchedulerTest {

    private fun minutesFrom(text: String): Long =
        DigestScheduler.minutesUntilNextMorning(LocalDateTime.parse(text))

    @Test
    fun `朝より前なら当日の8時まで`() {
        assertEquals(3 * 60, minutesFrom("2026-08-03T05:00:00"))
        assertEquals(1, minutesFrom("2026-08-03T07:59:00"))
    }

    @Test
    fun `ちょうど8時なら翌朝に回す`() {
        // 8:00 に起動した直後に 0 分後を指すと繰り返し走ってしまう
        assertEquals(24 * 60, minutesFrom("2026-08-03T08:00:00"))
    }

    @Test
    fun `朝を過ぎていれば翌朝まで`() {
        assertEquals(23 * 60, minutesFrom("2026-08-03T09:00:00"))
        assertEquals(11 * 60, minutesFrom("2026-08-03T21:00:00"))
        assertEquals(7 * 60 + 59, minutesFrom("2026-08-04T00:01:00"))
    }

    @Test
    fun `再試行の上限は朝から数時間をカバーする`() {
        // 8:00 開始で 20 分間隔。パイプラインが 1 時間遅れても間に合う幅が要る
        val coveredMinutes = DigestScheduler.MAX_RETRIES * DigestScheduler.RETRY_MINUTES
        org.junit.Assert.assertTrue(
            "再試行が $coveredMinutes 分では短すぎる",
            coveredMinutes >= 180,
        )
    }
}
