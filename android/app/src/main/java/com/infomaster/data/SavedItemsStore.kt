package com.infomaster.data

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 気に入った項目の保存。
 *
 * ダイジェストは毎日入れ替わり、配信済みの話題は翌日以降ダイジェストに
 * 出てこない。あとで見返したいものは、出典ごとこちらに写しておく。
 * 元のダイジェストが古くなっても内容が残るよう、参照ではなく実体を持つ。
 */
@Serializable
data class SavedItem(
    val item: DigestItem,
    /** どの日のダイジェストから保存したか。 */
    val digestDate: String,
    val savedAt: String,
)

class SavedItemsStore(context: Context) {

    private val file = File(context.filesDir, "saved.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<SavedItem> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<SavedItem>>(file.readText())
        } catch (e: Exception) {
            // 壊れていても、保存済みが読めないだけでアプリは使える
            Log.w(TAG, "保存済みの読み込みに失敗しました", e)
            emptyList()
        }
    }

    /** 保存済みなら外し、未保存なら追加する。戻り値は更新後の一覧。 */
    fun toggle(item: DigestItem, digestDate: String): List<SavedItem> {
        val updated = toggled(load(), item, digestDate, LocalDate.now().toString())
        write(updated)
        return updated
    }

    fun remove(id: String): List<SavedItem> {
        val updated = load().filterNot { it.item.id == id }
        write(updated)
        return updated
    }

    private fun write(items: List<SavedItem>) {
        try {
            file.writeText(json.encodeToString(items))
        } catch (e: Exception) {
            Log.w(TAG, "保存に失敗しました", e)
        }
    }

    private companion object {
        const val TAG = "SavedItemsStore"
    }
}

/**
 * 保存の切り替え。ファイル入出力から切り離してあるのは、
 * 「二重保存されない」「外すと消える」を単体で確かめられるようにするため。
 */
fun toggled(
    current: List<SavedItem>,
    item: DigestItem,
    digestDate: String,
    today: String,
): List<SavedItem> =
    if (current.any { it.item.id == item.id }) {
        current.filterNot { it.item.id == item.id }
    } else {
        // 新しいものが上に来るように先頭へ
        listOf(SavedItem(item = item, digestDate = digestDate, savedAt = today)) + current
    }
