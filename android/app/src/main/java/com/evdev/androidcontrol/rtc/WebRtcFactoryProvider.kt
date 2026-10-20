package com.evdev.androidcontrol.rtc

import android.content.Context
import org.webrtc.PeerConnectionFactory

object WebRtcFactoryProvider {
    @Volatile
    private var factory: PeerConnectionFactory? = null

    @Synchronized
    fun getFactory(context: Context): PeerConnectionFactory {
        val existing = factory
        if (existing != null) return existing

        val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val newFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        factory = newFactory
        return newFactory
    }
}
