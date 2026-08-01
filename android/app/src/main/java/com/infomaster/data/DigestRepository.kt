package com.infomaster.data

import android.content.Context
import android.util.Log
import com.infomaster.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ダイジェストの取得とキャッシュ。
 *
 * 保存先は端末内のファイルにしている。必要なのは「日付ごとの JSON を
 * そのまま保持する」ことだけで、Room を入れてもクエリの旨みが無く、
 * スキーマ変更のたびにマイグレーションを書く手間だけが増えるため。
 */
class DigestRepository(context: Context) {

    private val cacheDir = File(context.filesDir, "digests").apply { mkdirs() }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * 最新のダイジェストを取得する。
     *
     * 通信できない場合はキャッシュを返すので、地下鉄の中でも読める。
     * ネットワークとキャッシュの両方が駄目なときだけ失敗する。
     */
    suspend fun load(forceRefresh: Boolean = false): Result<Digest> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cached("latest")?.let { return@withContext Result.success(it) }
        }
        val fetched = fetch("latest.json")
        fetched.onSuccess { save("latest", it); save(it.date, it) }
        if (fetched.isFailure) {
            cached("latest")?.let {
                Log.i(TAG, "取得に失敗したためキャッシュを表示します")
                return@withContext Result.success(it)
            }
        }
        fetched
    }

    /** 過去分。キャッシュがあればそれを、無ければ取得して保存する。 */
    suspend fun loadDate(date: String): Result<Digest> = withContext(Dispatchers.IO) {
        cached(date)?.let { return@withContext Result.success(it) }
        fetch("$date.json").onSuccess { save(date, it) }
    }

    /** 端末に保存済みの日付一覧（新しい順）。 */
    fun cachedDates(): List<String> =
        cacheDir.listFiles()
            ?.map { it.nameWithoutExtension }
            ?.filter { it != "latest" }
            ?.sortedDescending()
            ?: emptyList()

    private fun fetch(path: String): Result<Digest> {
        val request = Request.Builder()
            .url(BuildConfig.DIGEST_BASE_URL + path)
            // GitHub の raw は CDN 経由なので、明示しないと古い内容が返ることがある
            .header("Cache-Control", "no-cache")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body?.string()
                    ?: return Result.failure(IOException("空のレスポンス"))
                Result.success(json.decodeFromString<Digest>(body))
            }
        } catch (e: Exception) {
            Log.w(TAG, "$path の取得に失敗しました", e)
            Result.failure(e)
        }
    }

    private fun cached(name: String): Digest? {
        val file = File(cacheDir, "$name.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<Digest>(file.readText())
        } catch (e: Exception) {
            // 壊れたキャッシュは消しておく。次回は取得しに行く。
            Log.w(TAG, "キャッシュ $name が壊れているため削除します", e)
            file.delete()
            null
        }
    }

    private fun save(name: String, digest: Digest) {
        try {
            File(cacheDir, "$name.json").writeText(json.encodeToString(digest))
        } catch (e: Exception) {
            Log.w(TAG, "キャッシュ $name の保存に失敗しました", e)
        }
    }

    private companion object {
        const val TAG = "DigestRepository"
    }
}
