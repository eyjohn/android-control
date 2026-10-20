package com.evdev.androidcontrol.signaling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evdev.androidcontrol.model.SessionPayload
import com.evdev.androidcontrol.session.SessionManager

class LocalSignalingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.evdev.androidcontrol.LOCAL_OFFER") {
            val rawData = intent.getStringExtra("data") ?: return
            val payload = SessionPayload.parse(rawData).getOrNull() ?: return
            SessionManager.getInstance(context).handleOffer(payload)
        }
    }
}
