package com.infomaster.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.infomaster.work.DigestWorker

/**
 * 定時（朝6時）を待たずに取得と通知を試すための、デバッグビルド専用トリガー。
 *
 * WorkManager の定期実行は次回実行時刻より前に強制実行できないため
 * （jobscheduler で force しても即座に戻る）、実機での通知確認にはこれが要る。
 *
 *   adb shell am broadcast -a com.infomaster.DEBUG_RUN_WORKER -p com.infomaster
 *
 * リリースビルドには含まれない（src/debug 配下のため）。
 */
class DebugTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "デバッグトリガーを受信しました。DigestWorker を1回だけ実行します。")
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<DigestWorker>().build()
        )
    }

    private companion object {
        const val TAG = "DebugTrigger"
    }
}
