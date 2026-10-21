package com.waz.hinditap

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings tile. This is the workflow for PDF readers (Adobe, Drive, Xodo…)
 * that use their own selection toolbar and therefore never show PROCESS_TEXT options:
 *
 *   select text -> Copy -> swipe down -> tap the हिंदी tile
 *
 * The tile launches TranslateActivity, which is a floating dialog, so the PDF
 * stays on screen behind the translation.
 */
@RequiresApi(Build.VERSION_CODES.N)
class HindiTileService : TileService() {

    override fun onClick() {
        super.onClick()

        val intent = Intent(this, TranslateActivity::class.java).apply {
            action = TranslateActivity.ACTION_TRANSLATE_CLIPBOARD
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Android 14+ requires a PendingIntent here; older versions take the Intent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
