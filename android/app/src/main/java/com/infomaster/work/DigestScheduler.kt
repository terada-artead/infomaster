package com.infomaster.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 朝の取得を起動するアラームの管理。
 *
 * WorkManager の定期実行を使わないのは、時刻を指定できないため。
 * PeriodicWorkRequest(1日) は「24時間の枠内のどこかで1回」という仕様で、
 * Doze や端末側のバッテリー制限が加わると、アプリを開くまで実行されない
 * ことがある（実際にそれが起きた）。
 *
 * AlarmManager の allowWhileIdle 系は Doze 中でも発火するので、
 * 起動のトリガーはこちらに任せ、実際の取得は WorkManager に流す。
 */
object DigestScheduler {

    /** ダイジェストを取りに行く時刻。 */
    private val WAKE_TIME: LocalTime = LocalTime.of(8, 0)

    /** 当日分がまだ無かったときの再試行間隔。 */
    const val RETRY_MINUTES = 20L

    /** 再試行の上限。8:00 開始で 20 分間隔なら 12:00 頃まで粘る。 */
    const val MAX_RETRIES = 12

    private const val TAG = "DigestScheduler"
    private const val REQUEST_CODE = 2001

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 次の朝 6 時にアラームを仕掛ける。すでに過ぎていれば翌朝。 */
    fun scheduleNextMorning(context: Context) {
        val now = ZonedDateTime.now(zone)
        var next = ZonedDateTime.of(LocalDate.now(zone), WAKE_TIME, zone)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        set(context, next.toInstant().toEpochMilli(), "次回 $next")
    }

    /** 当日分がまだ公開されていないときに、少し待ってから再試行する。 */
    fun scheduleRetry(context: Context, minutes: Long = RETRY_MINUTES) {
        val at = System.currentTimeMillis() + minutes * 60_000
        set(context, at, "$minutes 分後に再試行")
    }

    /** その端末の時刻で今日の日付。ダイジェストの date と突き合わせる。 */
    fun today(): String = LocalDate.now(zone).toString()

    /** 次に朝 6 時が来るまでの分数。WorkManager の保険用。 */
    fun minutesUntilNextMorning(now: LocalDateTime = LocalDateTime.now(zone)): Long {
        var next = LocalDateTime.of(now.toLocalDate(), WAKE_TIME)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        return java.time.Duration.between(now, next).toMinutes()
    }

    private fun set(context: Context, triggerAtMillis: Long, reason: String) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DigestAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 正確なアラームは Android 12 以降で許可が要る。取れていれば使い、
        // 取れていなければ Doze でも発火する不正確版に落とす。
        // 不正確版でも数分〜十数分のずれで収まるので、朝の通知には十分。
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()

        try {
            if (canBeExact) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, intent
                )
            } else {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, intent
                )
            }
            Log.i(TAG, "アラームを設定しました（$reason, exact=$canBeExact）")
        } catch (e: SecurityException) {
            // 許可が途中で取り消された場合に落とさない
            Log.w(TAG, "正確なアラームを設定できないため不正確版にします", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        }
    }
}
