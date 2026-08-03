package com.infomaster.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infomaster.InfomasterApp
import com.infomaster.MainActivity
import com.infomaster.R
import com.infomaster.data.BudgetSettings
import com.infomaster.data.Digest
import com.infomaster.data.DigestRepository

/**
 * ダイジェストを取得してローカル通知を出す。
 *
 * サーバから push するのではなく端末側から取りに行く形にしているのは、
 * FCM のためにサーバを1つ増やさずに済ませるため。
 *
 * 起動は [DigestScheduler] のアラーム。取りに行った時点でまだ当日分が
 * 公開されていないことがある（パイプラインは GitHub Actions の混雑で
 * 数十分遅れることがある）ので、その場合は少し待って再試行する。
 */
class DigestWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val today = DigestScheduler.today()
        val digest = DigestRepository(applicationContext)
            .load(forceRefresh = true)
            .getOrElse {
                Log.w(TAG, "取得に失敗しました", it)
                retryOrGiveUp("取得に失敗")
                return Result.success()
            }

        when {
            digest.date == lastNotifiedDate() -> {
                // 今日の分はもう通知済み。次の朝に備える。
                Log.i(TAG, "${digest.date} は通知済みです")
                finish()
            }

            digest.date != today -> {
                // パイプラインがまだ当日分を出していない。
                // ここで黙って諦めると「通知が来ない朝」になる。
                Log.i(TAG, "当日分がまだありません（最新は ${digest.date}）")
                retryOrGiveUp("当日分がまだ無い")
            }

            digest.items.isEmpty() -> {
                Log.i(TAG, "${digest.date} は項目が空でした")
                finish()
            }

            else -> {
                notify(digest)
                rememberNotified(digest.date)
                finish()
            }
        }
        return Result.success()
    }

    /** 再試行を積むか、上限に達していれば翌朝に回す。 */
    private fun retryOrGiveUp(reason: String) {
        val attempts = retryCount() + 1
        if (attempts <= DigestScheduler.MAX_RETRIES) {
            setRetryCount(attempts)
            Log.i(
                TAG,
                "$reason ため ${DigestScheduler.RETRY_MINUTES} 分後に再試行します" +
                    "（$attempts / ${DigestScheduler.MAX_RETRIES}）",
            )
            DigestScheduler.scheduleRetry(applicationContext)
        } else {
            Log.w(TAG, "$reason 状態が続いたため、今日は諦めて翌朝に回します")
            finish()
        }
    }

    /** 今日の分は片付いた。再試行の記録を消して次の朝を仕掛ける。 */
    private fun finish() {
        setRetryCount(0)
        DigestScheduler.scheduleNextMorning(applicationContext)
    }

    private fun notify(digest: Digest) {
        val granted = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            MainActivity.launchIntent(applicationContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val highlights = digest.highlights.joinToString("\n").ifBlank {
            digest.items.firstOrNull()?.titleJa.orEmpty()
        }
        // 残高の警告は本文の先頭に置く。畳んだ状態でも見えるよう contentText にも出す。
        val alert = BudgetSettings(applicationContext).state(digest.budget).alertMessage()
        val body = alert?.let { "$it\n\n$highlights" } ?: highlights
        val summary = alert ?: digest.highlights.firstOrNull().orEmpty()

        val notification = NotificationCompat.Builder(
            applicationContext,
            InfomasterApp.CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("今朝のAIダイジェスト（${digest.items.size}件）")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun prefs() =
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun lastNotifiedDate(): String? = prefs().getString(KEY_LAST_DATE, null)

    private fun rememberNotified(date: String) {
        prefs().edit().putString(KEY_LAST_DATE, date).apply()
    }

    private fun retryCount(): Int = prefs().getInt(KEY_RETRIES, 0)

    private fun setRetryCount(value: Int) {
        prefs().edit().putInt(KEY_RETRIES, value).apply()
    }

    companion object {
        const val UNIQUE_NAME = "daily-digest-fetch"
        private const val TAG = "DigestWorker"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "infomaster"
        private const val KEY_LAST_DATE = "last_notified_date"
        private const val KEY_RETRIES = "retry_count"
    }
}
