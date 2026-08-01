package com.infomaster.data

import android.content.Context
import kotlin.math.floor
import kotlin.math.max

/**
 * APIクレジット残高の管理。
 *
 * Anthropic には残高照会の API が無いので、Console で確認した残高を
 * 人が入力する。以降はパイプラインが記録した消費額の増分を引いていくので、
 * 補充するまでは自動で減っていく。
 *
 * 入力時点の累計消費額（[spentAtEntryUsd]）を基準として覚えておき、
 * 「入力後にいくら使ったか」を差分で出すのが要点。累計をそのまま引くと、
 * 入力より前の消費まで二重に引いてしまう。
 */
data class BudgetState(
    /** 最後に入力された残高（USD）。0 なら未設定。 */
    val enteredBalanceUsd: Double,
    /** その入力時点でのパイプライン累計消費額（USD）。 */
    val spentAtEntryUsd: Double,
    /** 現在のパイプライン累計消費額（USD）。 */
    val spentUsd: Double,
    /** 1回あたりの平均消費額（USD）。 */
    val averageRunUsd: Double,
    /** 残り何回分を切ったら警告するか。 */
    val warnBelowRuns: Int,
) {
    val configured: Boolean get() = enteredBalanceUsd > 0.0

    /**
     * 入力してからの消費額。
     *
     * 消費記録が失われて累計が巻き戻った場合に負にならないよう下限を 0 にする。
     * （負を許すと残高が入力額より増えてしまう）
     */
    val spentSinceEntryUsd: Double get() = max(0.0, spentUsd - spentAtEntryUsd)

    /** 現在の残高。 */
    val remainingUsd: Double get() = max(0.0, enteredBalanceUsd - spentSinceEntryUsd)

    /**
     * あと何回ダイジェストを生成できるか。
     * 平均が取れていないうちは判断材料が無いので -1（不明）を返す。
     */
    val runsRemaining: Int
        get() = if (averageRunUsd <= 0.0) -1
        else floor(remainingUsd / averageRunUsd).toInt()

    val low: Boolean
        get() = configured && runsRemaining >= 0 && runsRemaining <= warnBelowRuns

    /** 画面上部に常時出す残高の表示。未設定なら null。 */
    fun summary(): String? {
        if (!configured) return null
        val balance = "残高 $${format(remainingUsd)}"
        return if (runsRemaining >= 0) "$balance（残り約${runsRemaining}回）" else balance
    }

    /** 残高が細ってきたときの警告文。余裕があれば null。 */
    fun alertMessage(): String? {
        if (!low) return null
        if (runsRemaining <= 0) {
            return "APIクレジットが尽きました（残高 $${format(remainingUsd)}）。" +
                "補充しないと明日以降のダイジェストは生成されません。"
        }
        return "APIクレジット残高が少なくなっています。" +
            "残り約${runsRemaining}回分（$${format(remainingUsd)}）です。"
    }

    private fun format(value: Double): String = String.format("%.2f", value)
}

class BudgetSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val enteredBalanceUsd: Double
        get() = prefs.getFloat(KEY_BALANCE, 0f).toDouble()

    private val spentAtEntryUsd: Double
        get() = prefs.getFloat(KEY_SPENT_AT_ENTRY, 0f).toDouble()

    var warnBelowRuns: Int
        get() = prefs.getInt(KEY_WARN_BELOW, DEFAULT_WARN_BELOW)
        set(value) {
            prefs.edit().putInt(KEY_WARN_BELOW, value).apply()
        }

    /**
     * Console で確認した残高を記録する。
     *
     * @param balanceUsd 確認した残高
     * @param spentNowUsd その時点のパイプライン累計消費額。以降はここからの
     *   増分だけを引く。
     */
    fun setBalance(balanceUsd: Double, spentNowUsd: Double) {
        prefs.edit()
            .putFloat(KEY_BALANCE, balanceUsd.toFloat())
            .putFloat(KEY_SPENT_AT_ENTRY, spentNowUsd.toFloat())
            .apply()
    }

    fun state(spend: Spend?): BudgetState = BudgetState(
        enteredBalanceUsd = enteredBalanceUsd,
        spentAtEntryUsd = spentAtEntryUsd,
        spentUsd = spend?.spentUsd ?: 0.0,
        averageRunUsd = spend?.averageRunUsd ?: 0.0,
        warnBelowRuns = warnBelowRuns,
    )

    private companion object {
        const val PREFS = "budget"
        const val KEY_BALANCE = "balance_usd"
        const val KEY_SPENT_AT_ENTRY = "spent_at_entry_usd"
        const val KEY_WARN_BELOW = "warn_below_runs"
        const val DEFAULT_WARN_BELOW = 10
    }
}
