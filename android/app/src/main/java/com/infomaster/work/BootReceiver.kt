package com.infomaster.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 再起動でアラームは消えるので、起動時に仕掛け直す。
 *
 * これが無いと、端末を再起動した翌朝から通知が来なくなる。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.i(TAG, "${intent.action} を受信しました。アラームを仕掛け直します。")
        DigestScheduler.scheduleNextMorning(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
