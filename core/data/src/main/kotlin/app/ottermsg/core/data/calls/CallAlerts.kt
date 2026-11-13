package app.ottermsg.core.data.calls

/** Android side of calls (implemented in :app by CallNotifier). Called on CallManager's event loop; must not block. */
interface CallAlerts {
    fun incoming(call: CallState.Incoming)
    fun missed(peer: Peer, conversationId: Long)
    fun released()
    companion object {
        val NONE = object : CallAlerts {
            override fun incoming(call: CallState.Incoming) {}
            override fun missed(peer: Peer, conversationId: Long) {}
            override fun released() {}
        }
    }
}
