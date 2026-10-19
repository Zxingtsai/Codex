package android.os;
/** Controllable elapsed clock; does not emulate Android suspend/uptime behavior. */
public final class SystemClock {
    private static long elapsed;
    private SystemClock() {}
    public static long elapsedRealtime() { return elapsed; }
    public static void setElapsedRealtime(long value) { elapsed = value; }
}
