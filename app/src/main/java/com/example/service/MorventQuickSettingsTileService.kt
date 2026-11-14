package com.example.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.R

/**
 * Android System Quick Settings Tile ("مورڤينت") accessible from the system shade
 * anywhere in Android OS (launcher, lock screen, or inside any app).
 */
class MorventQuickSettingsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (Settings.canDrawOverlays(this)) {
            if (MorventFloatingAgentService.isRunning) {
                MorventFloatingAgentService.stop(this)
            } else {
                MorventFloatingAgentService.start(this)
            }
            updateTileState()
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_START_VOICE, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    101,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val running = MorventFloatingAgentService.isRunning
        tile.label = getString(R.string.app_name)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "نشط في النظام" else "تشغيل الوكيل"
        }
        tile.updateTile()
    }
}
