package com.evdev.androidcontrol.session

import com.evdev.androidcontrol.model.SessionPayload

object OfferGate {
    sealed class Decision {
        data object Accept : Decision()
        data object IgnoreDuplicate : Decision()
        data class Reject(val reason: String) : Decision()
    }

    fun evaluate(
        offer: SessionPayload,
        isConnectionsEnabled: Boolean,
        currentUserEmail: String?,
        lastAppliedSessionId: String?,
        lastAppliedSdpHash: Int,
        lastAppliedTimestampMs: Long
    ): Decision {
        if (!isConnectionsEnabled) {
            return Decision.Reject("Connections disabled — request blocked")
        }

        if (currentUserEmail.isNullOrBlank()) {
            return Decision.Reject("Signed out — sign in to allow connection")
        }

        if (!offer.account.isNullOrBlank() && !offer.account.equals(currentUserEmail, ignoreCase = true)) {
            return Decision.Reject("Rejected: Account mismatch (${offer.account})")
        }

        val sdpHash = offer.sdp.hashCode()
        if (offer.sessionId == lastAppliedSessionId && sdpHash == lastAppliedSdpHash) {
            return Decision.IgnoreDuplicate
        }

        if (offer.timestampMs > 0 && offer.timestampMs <= lastAppliedTimestampMs) {
            return Decision.IgnoreDuplicate
        }

        return Decision.Accept
    }
}
