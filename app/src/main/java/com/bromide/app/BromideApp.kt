package com.bromide.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.bromide.engine.Lut
import com.bromide.engine.Stock
import java.util.LinkedHashMap

class BromideApp : Application() {
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        Roll.init(this)
        Billing.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_service_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(PHONE_CHANNEL_ID, getString(R.string.channel_phone), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_phone_desc)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "bromide_service"
        const val PHONE_CHANNEL_ID = "bromide_phone"
    }
}

/** Loads baked film LUTs from the app's assets, keeping a few in memory. */
object LutStore {
    private val cache = object : LinkedHashMap<String, Lut>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lut>?): Boolean = size > 3
    }

    @Synchronized
    fun get(context: Context, stock: Stock): Lut {
        cache[stock.lut]?.let { return it }
        val lut = context.assets.open("luts/${stock.lut}.lut").buffered(1 shl 16).use { Lut.read(it) }
        cache[stock.lut] = lut
        return lut
    }
}
