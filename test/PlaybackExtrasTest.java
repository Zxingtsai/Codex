import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.widget.Toast;
import com.rea.walkman.PlaybackExtras;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.TimeZone;
import org.json.JSONObject;

/** JVM service-logic integration tests; this does not execute Android UI or native audio. */
public final class PlaybackExtrasTest {
    enum State { PLAYING, PAUSED, FF, REW, PAUSED_FF }
    private static int checks;
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        repeatedPlayingPauseAndSavedDays();
        checkpointAndShutdown();
        timerBeforeAtAndAfterDeadline();
        cancelTimer();
        nonPlayingStates();
        restoreSavedPreferences();
        invalidTimer();
        System.out.println("PlaybackExtrasTest: " + checks + " checks passed");
        System.out.println("Scope: actual PlaybackExtras methods with fake Android scheduling/storage; no UI, native audio, device lifecycle or disk durability validation.");
    }

    private static Context fresh() throws Exception {
        Handler.clearQueue();
        SystemClock.setElapsedRealtime(0L);
        Toast.shown = 0;
        resetStatics();
        return new Context();
    }
    private static void resetStatics() throws Exception {
        for (String name : new String[]{"prefs", "clock", "playing", "deadlineWall", "deadlineElapsed"}) {
            Field field = PlaybackExtras.class.getDeclaredField(name); field.setAccessible(true);
            if (field.getType() == boolean.class) field.setBoolean(null, false);
            else if (field.getType() == long.class) field.setLong(null, 0L);
            else field.set(null, null);
        }
    }
    private static void elapsed(long value) { SystemClock.setElapsedRealtime(value); }
    private static long total(Context context) { return ((Number) context.values.get("total")).longValue(); }
    private static long dailySum(Context context) {
        JSONObject json = new JSONObject((String) context.values.get("days"));
        long result = 0L;
        for (Iterator<String> it = json.keys(); it.hasNext();) result += json.optLong(it.next(), -1L);
        return result;
    }

    private static void repeatedPlayingPauseAndSavedDays() throws Exception {
        Context context = fresh();
        PlaybackExtras.onState(context, State.PLAYING);
        eq(1L, Handler.queuedCount());
        elapsed(3000L); PlaybackExtras.onState(context, State.PLAYING);
        eq(1L, Handler.queuedCount());
        elapsed(7000L); PlaybackExtras.onState(context, State.PAUSED);
        eq(7000L, total(context));
        eq(7000L, dailySum(context));
        eq(0L, Handler.queuedCount());
        elapsed(20000L); Handler.runDue(); PlaybackExtras.onState(context, State.PAUSED);
        eq(7000L, total(context));
        eq(true, context.commits > 0);
    }
    private static void checkpointAndShutdown() throws Exception {
        Context context = fresh();
        PlaybackExtras.onState(context, State.PLAYING);
        elapsed(9999L); Handler.runDue(); eq(0L, total(context));
        elapsed(10000L); Handler.runDue(); eq(10000L, total(context));
        eq(10000L, dailySum(context)); eq(1L, Handler.queuedCount());
        elapsed(25000L); PlaybackExtras.onServiceStop(context);
        eq(25000L, total(context)); eq(0L, Handler.queuedCount());
        elapsed(90000L); Handler.runDue(); eq(25000L, total(context));
        eq(true, context.applies > 0);
    }
    private static void timerBeforeAtAndAfterDeadline() throws Exception {
        Context context = fresh();
        PlaybackExtras.onState(context, State.PLAYING);
        PlaybackExtras.setTimer(context, 1);
        eq(true, PlaybackExtras.timerActive(context));
        eq(true, ((Number) context.values.get("deadline")).longValue() > 0L);
        elapsed(59999L); eq(false, PlaybackExtras.onTrackCompleted(context));
        eq(true, PlaybackExtras.timerActive(context));
        elapsed(60000L); eq(true, PlaybackExtras.onTrackCompleted(context));
        eq(false, PlaybackExtras.timerActive(context));
        eq(0L, ((Number) context.values.get("deadline")).longValue());
        eq(60000L, total(context)); eq(60000L, dailySum(context));
        Handler.runDue(); eq(1L, Toast.shown); eq(0L, Handler.queuedCount());
        elapsed(90000L); eq(false, PlaybackExtras.onTrackCompleted(context));
        eq(60000L, total(context));
    }
    private static void cancelTimer() throws Exception {
        Context context = fresh();
        PlaybackExtras.setTimer(context, 1);
        PlaybackExtras.cancelTimer(context);
        eq(false, PlaybackExtras.timerActive(context));
        elapsed(120000L); eq(false, PlaybackExtras.onTrackCompleted(context));
        eq(0L, ((Number) context.values.get("deadline")).longValue());
        eq(0L, Toast.shown);
    }
    private static void nonPlayingStates() throws Exception {
        Context context = fresh();
        PlaybackExtras.onState(context, State.PLAYING);
        elapsed(2000L); PlaybackExtras.onState(context, State.FF);
        elapsed(4000L); PlaybackExtras.onState(context, State.REW);
        elapsed(6000L); PlaybackExtras.onState(context, State.PAUSED_FF);
        elapsed(8000L); PlaybackExtras.onState(context, null);
        elapsed(10000L); PlaybackExtras.onState(context, State.PLAYING);
        elapsed(13000L); PlaybackExtras.onServiceStop(context);
        eq(5000L, total(context)); eq(5000L, dailySum(context)); eq(0L, Handler.queuedCount());
    }
    private static void restoreSavedPreferences() throws Exception {
        Context context = fresh();
        context.values.put("total", 12000L);
        context.values.put("days", "{\"2026-10-01\":12000}");
        PlaybackExtras.onState(context, State.PLAYING);
        elapsed(10000L); Handler.runDue();
        eq(22000L, total(context)); eq(22000L, dailySum(context));
        JSONObject saved = new JSONObject((String) context.values.get("days"));
        eq(12000L, saved.optLong("2026-10-01", -1L));
        // Model process restart: the already saved active interval is not re-counted.
        Handler.clearQueue(); resetStatics(); elapsed(60000L);
        PlaybackExtras.onState(context, State.PLAYING);
        elapsed(61000L); PlaybackExtras.onServiceStop(context);
        eq(23000L, total(context)); eq(23000L, dailySum(context));
    }
    private static void invalidTimer() throws Exception {
        Context context = fresh();
        boolean rejected = false;
        try { PlaybackExtras.setTimer(context, 0); } catch (IllegalArgumentException expected) { rejected = true; }
        eq(true, rejected); eq(false, PlaybackExtras.timerActive(context));
    }
    private static void eq(long expected, long actual) {
        checks++; if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static void eq(boolean expected, boolean actual) {
        checks++; if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
