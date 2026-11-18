package com.rewind.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick settings tile: saves the clip while recording, otherwise opens Rewind to start it. */
class SaveTile : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        val on = RecorderService.live != null
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Save clip"
        tile.subtitle = if (on) "Last ${RecorderService.seconds(this)} s" else "Not recording"
        tile.updateTile()
    }

    override fun onClick() {
        if (RecorderService.live != null) { RecorderService.save(this); return }
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(open)
        }
    }
}
