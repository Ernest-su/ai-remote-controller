package ernest.airc

/** A press ID owns one physical-style key hold; terminal edges never affect a newer ID. */
interface RemoteKeySender {
    fun down(key: String): String?
    fun up(pressId: String, cancelled: Boolean)
    fun cancelAll()
}
