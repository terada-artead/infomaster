package com.infomaster.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存の切り替えと永続化の検証。
 *
 * 保存はダイジェストが流れた後の唯一の参照先なので、
 * 「二重に増えない」「外したら消える」「出典が失われない」を押さえる。
 */
class SavedItemsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun item(id: String) = DigestItem(
        id = id,
        importance = "high",
        category = "new_model",
        titleJa = "見出し $id",
        summaryJa = "要約 $id",
        sources = listOf(
            Source("Hugging Face", "https://huggingface.co/$id"),
            Source("Hacker News", "https://news.ycombinator.com/$id"),
        ),
    )

    @Test
    fun `保存すると先頭に入る`() {
        val first = toggled(emptyList(), item("a"), "2026-08-03", "2026-08-03")
        val second = toggled(first, item("b"), "2026-08-04", "2026-08-04")

        assertEquals(2, second.size)
        assertEquals("b", second.first().item.id)
        assertEquals("2026-08-04", second.first().digestDate)
    }

    @Test
    fun `同じ項目を二度押しても増えない`() {
        val once = toggled(emptyList(), item("a"), "2026-08-03", "2026-08-03")
        val twice = toggled(once, item("a"), "2026-08-03", "2026-08-03")

        // 2回目は解除なので空になる
        assertTrue(twice.isEmpty())

        val thrice = toggled(twice, item("a"), "2026-08-03", "2026-08-03")
        assertEquals(1, thrice.size)
    }

    @Test
    fun `外しても他は残る`() {
        var list = toggled(emptyList(), item("a"), "2026-08-03", "2026-08-03")
        list = toggled(list, item("b"), "2026-08-03", "2026-08-03")
        list = toggled(list, item("a"), "2026-08-03", "2026-08-03")

        assertEquals(1, list.size)
        assertEquals("b", list.first().item.id)
    }

    @Test
    fun `保存した内容は出典ごと往復できる`() {
        // ダイジェストが入れ替わっても内容が残る必要があるので、
        // 参照ではなく実体を保持できていることを確かめる
        val list = toggled(emptyList(), item("a"), "2026-08-03", "2026-08-03")
        val restored = json.decodeFromString<List<SavedItem>>(json.encodeToString(list))

        assertEquals(1, restored.size)
        val saved = restored.first()
        assertEquals("見出し a", saved.item.titleJa)
        assertEquals("要約 a", saved.item.summaryJa)
        assertEquals(2, saved.item.sources.size)
        assertEquals("https://huggingface.co/a", saved.item.sources.first().url)
        assertEquals("2026-08-03", saved.digestDate)
    }
}
