package dev.lyo.telread

import android.app.Application
import dev.lyo.telread.data.TdClient

class TelreadApp : Application() {

    val tdClient: TdClient by lazy { TdClient.create(this) }

    override fun onCreate() {
        super.onCreate()
        // Touch lazy to start native loading + auth state machine eagerly.
        tdClient.start()
    }
}
