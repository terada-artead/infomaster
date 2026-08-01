package com.infomaster.data

import android.content.Context
import kotlin.math.floor
import kotlin.math.max

/**
 * APIクレジットの購入額と、残高の計算。
 *
 * 購入額をアプリ側に置いているのは、クレジットを買い足したときに
 * リポジトリを編集せずに更新できるようにするため。
 * 消費額はパイプラインが digests に載せてくるので、その差が残高になる。
 */
data class BudgetState(
    /** これまでに購入したクレジットの累計（USD）。0 なら未設定。 */
    val creditUsd: Double,
    /** パイプラインが記録した累計消費額（USD）。 */
    val spentUsd: Double,
    /** 1回あたりの平均消費額（USD）。 */
    val averageRunUsd: Double,
    /** 残り何回分を切ったら警告するか。 */
    val warnBelowRuns: Int,
) {
    val configured: Boolean get() = creditUsd > 0.0

    val remainingUsd: Double get() = max(0.0, creditUsd - spentUsd)

    /**
     * あと何回ダイジェストを生成できるか。
     * 平均が取れていないうちは判断材料が無いので -1（不明）を返す。
     */
    val runsRemaining: Int
        get() = if (averageRunUsd <= 0.0) -1
        else floor(remainingUsd / averageRunUsd).toInt()

    val low: Boolean
        get() = configured && runsRemaining >= 0 && runsRemaining <= warnBelowRuns

    /** アプリと通知に出す警告文。余裕があれば null。 */
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

    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var creditUsd: Double
        get() = prefs.getFloat(KEY_CREDIT, 0f).toDouble()
        set(value) {
            prefs.edit().putFloat(KEY_CREDIT, value.toFloat()).apply()
        }

    var warnBelowRuns: Int
        get() = prefs.getInt(KEY_WARN_BELOW, DEFAULT_WARN_BELOW)
        set(value) {
            prefs.edit().putInt(KEY_WARN_BELOW, value).apply()
        }

    fun state(spend: Spend?): BudgetState = BudgetState(
        creditUsd = creditUsd,
        spentUsd = spend?.spentUsd ?: 0.0,
        averageRunUsd = spend?.averageRunUsd ?: 0.0,
        warnBelowRuns = warnBelowRuns,
    )

    private companion object {
        const val PREFS = "budget"
        const val KEY_CREDIT = "credit_usd"
        const val KEY_WARN_BELOW = "warn_below_runs"
        const val DEFAULT_WARN_BELOW = 10
    }
}
