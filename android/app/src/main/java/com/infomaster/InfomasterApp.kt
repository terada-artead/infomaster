package com.infomaster

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.infomaster.work.DigestScheduler
import com.infomaster.work.DigestWorker
import java.util.concurrent.TimeUnit

class InfomasterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DigestScheduler.scheduleNextMorning(this)
        scheduleFallback()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * アラームが取りこぼされたときの保険。
     *
     * 通常の起動は [DigestScheduler] のアラームが担当する。こちらは
     * 「アラームが端末側の都合で消えていた」場合に、遅れてでも取得を
     * 走らせるためのもの。時刻は守れないが、通知の重複は
     * 通知済み日付の記録で防いでいる。
     */
    private fun scheduleFallback() {
        val request = PeriodicWorkRequestBuilder<DigestWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(DigestScheduler.minutesUntilNextMorning(), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DigestWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val CHANNEL_ID = "daily-digest"
    }
}
