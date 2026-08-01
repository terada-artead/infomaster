package com.infomaster.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 残高計算の検証。
 *
 * ここを間違えると「まだ余裕があるのに警告が出る」か、もっと悪い
 * 「尽きているのに警告が出ない」が起きる。後者は朝の通知が黙って
 * 止まる形で現れるので、境界をきちんと押さえておく。
 */
class BudgetStateTest {

    private fun state(
        credit: Double = 10.0,
        spent: Double = 0.0,
        average: Double = 0.15,
        warnBelow: Int = 10,
    ) = BudgetState(
        creditUsd = credit,
        spentUsd = spent,
        averageRunUsd = average,
        warnBelowRuns = warnBelow,
    )

    @Test
    fun `残高と残り回数を計算する`() {
        val s = state(credit = 10.0, spent = 0.08, average = 0.15)

        assertEquals(9.92, s.remainingUsd, 1e-9)
        assertEquals(66, s.runsRemaining) // 9.92 / 0.15 = 66.1 -> 切り捨て
        assertFalse(s.low)
        assertNull(s.alertMessage())
    }

    @Test
    fun `しきい値ちょうどでは警告する`() {
        // 残り 10 回ちょうど。「10回分を切ったら」の境界を含める側に倒す
        val s = state(credit = 10.0, spent = 8.5, average = 0.15, warnBelow = 10)
        assertEquals(10, s.runsRemaining)
        assertTrue(s.low)
        assertTrue(s.alertMessage()!!.contains("残り約10回分"))
    }

    @Test
    fun `しきい値より1回多ければ警告しない`() {
        val s = state(credit = 10.0, spent = 8.3, average = 0.15, warnBelow = 10)
        assertEquals(11, s.runsRemaining)
        assertFalse(s.low)
        assertNull(s.alertMessage())
    }

    @Test
    fun `使い切ったら別の文面にする`() {
        val s = state(credit = 10.0, spent = 10.0, average = 0.15)

        assertEquals(0.0, s.remainingUsd, 1e-9)
        assertEquals(0, s.runsRemaining)
        assertTrue(s.low)
        assertTrue(s.alertMessage()!!.contains("尽きました"))
    }

    @Test
    fun `使いすぎても残高は負にしない`() {
        val s = state(credit = 10.0, spent = 12.0, average = 0.15)
        assertEquals(0.0, s.remainingUsd, 1e-9)
        assertTrue(s.alertMessage()!!.contains("尽きました"))
    }

    @Test
    fun `購入額が未設定なら警告しない`() {
        // 使い始めに「残高が尽きています」と出ては困る
        val s = state(credit = 0.0, spent = 5.0, average = 0.15)

        assertFalse(s.configured)
        assertFalse(s.low)
        assertNull(s.alertMessage())
    }

    @Test
    fun `平均が取れていないうちは判断しない`() {
        // 消費記録が始まる前は 1 回あたりの額が分からない
        val s = state(credit = 10.0, spent = 0.0, average = 0.0)

        assertEquals(-1, s.runsRemaining)
        assertFalse(s.low)
        assertNull(s.alertMessage())
    }

    @Test
    fun `警告文に残高が入る`() {
        val s = state(credit = 10.0, spent = 8.8, average = 0.15)
        val message = s.alertMessage()
        assertNotNull(message)
        assertTrue("残高が入っていない: $message", message!!.contains("1.20"))
    }
}
