package com.infomaster.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
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
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 毎朝ダイジェストを取得してローカル通知を出す。
 *
 * サーバから push するのではなく端末側から取りに行く形にしているのは、
 * FCM のためにサーバを1つ増やさずに済ませるため。個人用途では
 * 「起きる頃に取りに行く」で十分に間に合う。
 */
class DigestWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = DigestRepository(applicationContext)
        val result = repository.load(forceRefresh = true)

        val digest = result.getOrElse {
            // 生成が遅れているだけの可能性があるので、次回の実行に賭ける。
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }

        if (digest.items.isEmpty()) {
            return Result.success()
        }
        if (digest.date == lastNotifiedDate()) {
            // 同じ日のダイジェストで二度通知しない
            return Result.success()
        }

        notify(digest)
        rememberNotified(digest.date)
        return Result.success()
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

    private fun lastNotifiedDate(): String? =
        applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DATE, null)

    private fun rememberNotified(date: String) {
        applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DATE, date)
            .apply()
    }

    companion object {
        const val UNIQUE_NAME = "daily-digest-fetch"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "infomaster"
        private const val KEY_LAST_DATE = "last_notified_date"

        /** 次の朝6時までの待ち時間。すでに6時を過ぎていれば翌朝。 */
        fun initialDelayMinutes(now: Calendar = Calendar.getInstance()): Long {
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 6)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (!target.after(now)) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return TimeUnit.MILLISECONDS.toMinutes(target.timeInMillis - now.timeInMillis)
        }
    }
}
