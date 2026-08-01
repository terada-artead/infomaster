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
 * 要点は「入力時点からの増分だけを引く」こと。累計をそのまま引くと
 * 入力より前の消費まで二重に引かれ、残高が実際より少なく出る。
 *
 * ここを間違えると「まだ余裕があるのに警告が出る」か、もっと悪い
 * 「尽きているのに警告が出ない」が起きる。後者は朝の通知が黙って
 * 止まる形で現れるので、境界をきちんと押さえておく。
 */
class BudgetStateTest {

    private fun state(
        entered: Double = 10.0,
        spentAtEntry: Double = 0.0,
        spent: Double = 0.0,
        average: Double = 0.15,
        warnBelow: Int = 10,
    ) = BudgetState(
        enteredBalanceUsd = entered,
        spentAtEntryUsd = spentAtEntry,
        spentUsd = spent,
        averageRunUsd = average,
        warnBelowRuns = warnBelow,
    )

    @Test
    fun `入力直後は入力額がそのまま残高になる`() {
        // 累計 0.08 の時点で「残高 9.92」と入力した直後
        val s = state(entered = 9.92, spentAtEntry = 0.08, spent = 0.08)

        assertEquals(0.0, s.spentSinceEntryUsd, 1e-9)
        assertEquals(9.92, s.remainingUsd, 1e-9)
    }

    @Test
    fun `使うと自動で減る`() {
        // 入力後に 3 回分（0.45）使った
        val s = state(entered = 9.92, spentAtEntry = 0.08, spent = 0.53)

        assertEquals(0.45, s.spentSinceEntryUsd, 1e-9)
        assertEquals(9.47, s.remainingUsd, 1e-9)
        assertEquals(63, s.runsRemaining) // 9.47 / 0.15 = 63.1 -> 切り捨て
        assertFalse(s.low)
    }

    @Test
    fun `入力より前の消費は引かない`() {
        // 累計 5.00 まで使った状態で「残高 5.00」と入力したら、
        // その 5.00 は引かれてはいけない
        val s = state(entered = 5.0, spentAtEntry = 5.0, spent = 5.0)
        assertEquals(5.0, s.remainingUsd, 1e-9)
    }

    @Test
    fun `消費記録が巻き戻っても残高は増えない`() {
        // usage.json が失われて累計が 0 に戻ったケース
        val s = state(entered = 5.0, spentAtEntry = 5.0, spent = 0.0)
        assertEquals(0.0, s.spentSinceEntryUsd, 1e-9)
        assertEquals(5.0, s.remainingUsd, 1e-9)
    }

    @Test
    fun `しきい値ちょうどでは警告する`() {
        // 残り 10 回ちょうど。「10回分を切ったら」の境界を含める側に倒す
        val s = state(entered = 10.0, spent = 8.5, average = 0.15, warnBelow = 10)
        assertEquals(10, s.runsRemaining)
        assertTrue(s.low)
        assertTrue(s.alertMessage()!!.contains("残り約10回分"))
    }

    @Test
    fun `しきい値より1回多ければ警告しない`() {
        val s = state(entered = 10.0, spent = 8.3, average = 0.15, warnBelow = 10)
        assertEquals(11, s.runsRemaining)
        assertFalse(s.low)
        assertNull(s.alertMessage())
    }

    @Test
    fun `使い切ったら別の文面にする`() {
        val s = state(entered = 10.0, spent = 10.0, average = 0.15)

        assertEquals(0.0, s.remainingUsd, 1e-9)
        assertEquals(0, s.runsRemaining)
        assertTrue(s.alertMessage()!!.contains("尽きました"))
    }

    @Test
    fun `使いすぎても残高は負にしない`() {
        val s = state(entered = 10.0, spent = 12.0, average = 0.15)
        assertEquals(0.0, s.remainingUsd, 1e-9)
        assertTrue(s.alertMessage()!!.contains("尽きました"))
    }

    @Test
    fun `未設定なら警告も表示もしない`() {
        // 使い始めに「残高が尽きています」と出ては困る
        val s = state(entered = 0.0, spent = 5.0, average = 0.15)

        assertFalse(s.configured)
        assertFalse(s.low)
        assertNull(s.alertMessage())
        assertNull(s.summary())
    }

    @Test
    fun `平均が取れていないうちは回数を出さない`() {
        // 消費記録が始まる前は 1 回あたりの額が分からない
        val s = state(entered = 10.0, spent = 0.0, average = 0.0)

        assertEquals(-1, s.runsRemaining)
        assertFalse(s.low)
        assertNull(s.alertMessage())
        assertEquals("残高 \$10.00", s.summary())
    }

    @Test
    fun `残高表示に回数が入る`() {
        val s = state(entered = 9.92, spentAtEntry = 0.08, spent = 0.08, average = 0.15)
        assertEquals("残高 \$9.92（残り約66回）", s.summary())
    }

    @Test
    fun `警告文に残高が入る`() {
        val s = state(entered = 10.0, spent = 8.8, average = 0.15)
        val message = s.alertMessage()
        assertNotNull(message)
        assertTrue("残高が入っていない: $message", message!!.contains("1.20"))
    }
}
