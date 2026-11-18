package com.rewind.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The "Save clip" icon: something a frontend or a mapped button can launch. Saves and closes
 * without drawing anything; if Rewind isn't recording, opens it instead.
 */
class SaveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (RecorderService.live != null) RecorderService.save(this)
        else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
