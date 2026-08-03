package com.infomaster.data

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 朝の通知が実際に届く状態になっているかの点検。
 *
 * 権限が揃っていても、端末側の省電力設定に引っかかるとアラームは
 * 遅延・抑制される。とくにメーカー独自の省電力機能を持つ端末では、
 * アプリを開くまで何も起きないという形で現れる（実際にそうなった）。
 * 黙って効かないより、何が足りないかを画面に出す。
 */
enum class DeliveryIssue(val label: String, val description: String) {
    BATTERY_OPTIMIZED(
        "省電力の対象から外してください",
        "省電力の対象になっていると、朝の取得が端末に止められて通知が届きません。",
    ),
    INEXACT_ALARM(
        "アラームの権限を許可してください",
        "許可が無いと通知の時刻が数十分ずれることがあります。",
    ),
}

object DeliveryReadiness {

    /** 足りていない設定を返す。空なら問題なし。 */
    fun check(context: Context): List<DeliveryIssue> {
        val issues = mutableListOf<DeliveryIssue>()

        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
            issues += DeliveryIssue.BATTERY_OPTIMIZED
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            if (!alarms.canScheduleExactAlarms()) {
                issues += DeliveryIssue.INEXACT_ALARM
            }
        }
        return issues
    }

    /** その問題を直せる設定画面を開く。 */
    fun settingsIntent(context: Context, issue: DeliveryIssue): Intent = when (issue) {
        DeliveryIssue.BATTERY_OPTIMIZED ->
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        DeliveryIssue.INEXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.fromParts("package", context.packageName, null))
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            }
    }
}
