package com.infomaster

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.infomaster.work.DigestWorker
import java.util.concurrent.TimeUnit

class InfomasterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduleDailyFetch()
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

    private fun scheduleDailyFetch() {
        val request = PeriodicWorkRequestBuilder<DigestWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(DigestWorker.initialDelayMinutes(), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        // KEEP なので、アプリを開くたびにスケジュールが作り直されることはない。
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DigestWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val CHANNEL_ID = "daily-digest"
    }
}
