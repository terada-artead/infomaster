package com.infomaster.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * アラームで叩き起こされて、取得の仕事を WorkManager に流す。
 *
 * BroadcastReceiver 内で直接通信すると 10 秒制限に引っかかるので、
 * ここでは積むだけにして実処理は Worker に任せる。
 */
class DigestAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "アラームを受信しました。取得を開始します。")

        val request = OneTimeWorkRequestBuilder<DigestWorker>()
            // 画面が消えている時間帯なので、待たされずに走ってほしい
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }

    private companion object {
        const val TAG = "DigestAlarmReceiver"
    }
}
