package android.os

object SystemClock {
    fun elapsedRealtime(): Long = 0
    fun uptimeMillis(): Long = 0
    fun sleep(ms: Long) {}
}
