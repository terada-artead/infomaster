package com.infomaster.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * パイプラインが実際に生成した JSON をアプリのモデルで読めることを確認する。
 *
 * ここが壊れる典型は @SerialName の綴り違い（title_ja など）で、
 * その場合アプリは起動するが中身が空になり、実機で見るまで気づけない。
 * 素材は本物の digests/latest.json をそのまま置いている。
 */
class DigestParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun fixture(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("latest.json")) {
            "テスト用の latest.json が見つかりません"
        }.bufferedReader().readText()

    @Test
    fun `実データをパースできる`() {
        val digest = json.decodeFromString<Digest>(fixture())

        assertEquals("2026-08-01", digest.date)
        assertEquals(20, digest.items.size)
        assertEquals(3, digest.highlights.size)
        assertTrue("収集件数が読めていない", digest.stats.collected > 0)
    }

    @Test
    fun `日本語フィールドが空にならない`() {
        val digest = json.decodeFromString<Digest>(fixture())

        digest.items.forEach { item ->
            assertFalse("title_ja が空: ${item.id}", item.titleJa.isBlank())
            assertFalse("summary_ja が空: ${item.id}", item.summaryJa.isBlank())
            assertFalse("id が空", item.id.isBlank())
        }
    }

    @Test
    fun `出典の URL が読める`() {
        val digest = json.decodeFromString<Digest>(fixture())

        val withSources = digest.items.filter { it.sources.isNotEmpty() }
        assertTrue("出典を持つ項目が1件も無い", withSources.isNotEmpty())
        withSources.forEach { item ->
            item.sources.forEach { source ->
                assertFalse("出典名が空: ${item.id}", source.name.isBlank())
                assertTrue(
                    "URL が http で始まらない: ${source.url}",
                    source.url.startsWith("http"),
                )
            }
        }
    }

    @Test
    fun `重要度で振り分けられる`() {
        val digest = json.decodeFromString<Digest>(fixture())

        val high = digest.items.filter { it.isHigh }
        val medium = digest.items.filterNot { it.isHigh }
        assertTrue("high が1件も無い", high.isNotEmpty())
        assertEquals(digest.items.size, high.size + medium.size)
    }

    @Test
    fun `未知のフィールドが増えても壊れない`() {
        // パイプライン側に項目を足したときにアプリが落ちないことの確認
        val extended = fixture().replaceFirst("{", """{"future_field": {"a": 1},""")
        val digest = json.decodeFromString<Digest>(extended)
        assertEquals(20, digest.items.size)
    }

    @Test
    fun `平常時は警告が無い`() {
        // パイプラインは残高に余裕があるとき alert キー自体を出さない
        val digest = json.decodeFromString<Digest>(fixture())
        assertEquals(null, digest.alert)
    }

    @Test
    fun `クレジット残高の警告を読める`() {
        val withAlert = fixture().replaceFirst(
            "{",
            """{"alert": "APIクレジット残高が少なくなっています。残り約8回分（＄1.20）です。",
               "budget": {"remaining_usd": 1.2, "runs_remaining": 8, "low": true},""",
        )
        val digest = json.decodeFromString<Digest>(withAlert)

        assertTrue("警告文が読めていない", digest.alert?.contains("残り約8回分") == true)
        assertEquals(8, digest.budget?.runsRemaining)
        assertTrue(digest.budget?.low == true)
        // 警告が出ていても本体は通常どおり読める
        assertEquals(20, digest.items.size)
    }

    @Test
    fun `カテゴリは未知の値でもそのまま表示する`() {
        assertEquals("新モデル", categoryLabel("new_model"))
        assertEquals("資金調達", categoryLabel("funding"))
        assertEquals("mystery", categoryLabel("mystery"))
    }
}
