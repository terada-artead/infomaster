package com.infomaster.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * パイプラインが出力する digests/&lt;日付&gt;.json の形。
 *
 * 注: Kotlin のブロックコメントは入れ子になるため、コメント内に
 * スラッシュ＋アスタリスクの並びを書くとそこから内側のコメントが開いてしまう。
 *
 * サーバ側で項目が増えても落ちないよう、Json のデコーダは
 * ignoreUnknownKeys = true で構成している（[DigestRepository] を参照）。
 */
@Serializable
data class Digest(
    val date: String,
    @SerialName("generated_at") val generatedAt: String = "",
    val stats: Stats = Stats(),
    val highlights: List<String> = emptyList(),
    val items: List<DigestItem> = emptyList(),
    /**
     * これまでの API 消費額。購入額はアプリが保持しているので、
     * 残高と残り回数の計算はアプリ側で行う。
     */
    val budget: Spend? = null,
)

@Serializable
data class Spend(
    @SerialName("spent_usd") val spentUsd: Double = 0.0,
    @SerialName("run_cost_usd") val runCostUsd: Double = 0.0,
    @SerialName("average_run_usd") val averageRunUsd: Double = 0.0,
    @SerialName("runs_recorded") val runsRecorded: Int = 0,
)

@Serializable
data class Stats(
    val collected: Int = 0,
    val selected: Int = 0,
    val published: Int = 0,
)

@Serializable
data class DigestItem(
    val id: String,
    val importance: String,
    val category: String,
    @SerialName("title_ja") val titleJa: String,
    @SerialName("summary_ja") val summaryJa: String,
    val sources: List<Source> = emptyList(),
) {
    val isHigh: Boolean get() = importance == "high"
}

@Serializable
data class Source(
    val name: String,
    val url: String,
)

/** カテゴリの日本語表示。未知の値が来ても落とさずそのまま出す。 */
fun categoryLabel(category: String): String = when (category) {
    "new_model" -> "新モデル"
    "product" -> "プロダクト"
    "tool" -> "ツール"
    "funding" -> "資金調達"
    "business" -> "ビジネス"
    "other" -> "その他"
    else -> category
}
