import com.rea.walkman.ListeningClock;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class ListeningClockTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static int checks;

    public static void main(String[] args) throws Exception {
        pausedAndRepeatedState();
        midnightAndBackground();
        clockChangesAndElapsedRollback();
        daylightSaving();
        midnightDaylightSaving();
        restoreAndSnapshot();
        eq(false, ListeningClock.due(0L, 100L));
        eq(false, ListeningClock.due(100L, 99L));
        eq(true, ListeningClock.due(100L, 100L));
        System.out.println("ListeningClockTest: " + checks + " checks passed");
    }

    private static void pausedAndRepeatedState() throws Exception {
        long wall = wall("2026-10-18 12:00:00", UTC);
        ListeningClock clock = new ListeningClock();
        clock.onState(false, 1000L, wall, UTC);
        clock.flush(6000L, wall + 5000L, UTC);
        eq(0L, clock.getTotal());
        clock.onState(true, 6000L, wall + 5000L, UTC);
        clock.onState(true, 10000L, wall + 9000L, UTC);
        clock.onState(false, 16000L, wall + 15000L, UTC);
        clock.flush(30000L, wall + 29000L, UTC);
        eq(10000L, clock.getTotal());
        eq(10000L, clock.getDaily("2026-10-18"));
        eq(false, clock.isPlaying());
    }

    private static void midnightAndBackground() throws Exception {
        long start = wall("2026-10-18 23:59:50", UTC);
        ListeningClock clock = new ListeningClock();
        clock.onState(true, 0L, start, UTC);
        clock.flush(30000L, start + 30000L, UTC);
        eq(10000L, clock.getDaily("2026-10-18"));
        eq(20000L, clock.getDaily("2026-10-19"));
        clock.flush(30000L, start + 30000L, UTC);
        eq(30000L, clock.getTotal());
        ListeningClock longSegment = new ListeningClock();
        start = wall("2026-10-18 12:00:00", UTC);
        long duration = 3L * 86400000L;
        longSegment.onState(true, 100L, start, UTC);
        longSegment.flush(100L + duration, start + duration, UTC);
        eq(duration, longSegment.getTotal());
        eq(43200000L, longSegment.getDaily("2026-10-18"));
        eq(86400000L, longSegment.getDaily("2026-10-19"));
        eq(86400000L, longSegment.getDaily("2026-10-20"));
        eq(43200000L, longSegment.getDaily("2026-10-21"));
    }

    private static void clockChangesAndElapsedRollback() throws Exception {
        long start = wall("2026-10-18 12:00:00", UTC);
        ListeningClock clock = new ListeningClock();
        clock.onState(true, 5000L, start, UTC);
        // Jump the civil clock a day forward and then two days backward.
        clock.flush(6000L, start + 86400000L, UTC);
        clock.flush(7000L, start - 86400000L, UTC);
        eq(2000L, clock.getTotal());
        eq(1000L, clock.getDaily("2026-10-19"));
        eq(1000L, clock.getDaily("2026-10-17"));
        clock.flush(100L, start, UTC);
        eq(2000L, clock.getTotal());
        clock.flush(1100L, start + 1000L, UTC);
        eq(3000L, clock.getTotal());
        clock.flush(-1L, start + 1000L, UTC);
        clock.flush(500L, start + 1500L, UTC);
        eq(3000L, clock.getTotal());
        clock.flush(1000L, start + 2000L, UTC);
        eq(3500L, clock.getTotal());
    }

    private static void daylightSaving() throws Exception {
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        long start = wall("2026-03-07 23:00:00", ny);
        long end = wall("2026-03-09 01:00:00", ny);
        ListeningClock clock = new ListeningClock();
        clock.onState(true, 0L, start, ny);
        clock.flush(end - start, end, ny);
        eq(3600000L, clock.getDaily("2026-03-07"));
        eq(23L * 3600000L, clock.getDaily("2026-03-08"));
        eq(3600000L, clock.getDaily("2026-03-09"));
        eq(25L * 3600000L, clock.getTotal());
    }

    private static void midnightDaylightSaving() throws Exception {
        TimeZone santiago = TimeZone.getTimeZone("America/Santiago");
        // This day began at 01:00. Its following day still begins at 00:00.
        long start = wall("2026-09-06 23:59:50", santiago);
        ListeningClock clock = new ListeningClock();
        clock.onState(true, 0L, start, santiago);
        clock.flush(30000L, start + 30000L, santiago);
        eq(10000L, clock.getDaily("2026-09-06"));
        eq(20000L, clock.getDaily("2026-09-07"));
        eq(30000L, clock.getTotal());
    }

    private static void restoreAndSnapshot() throws Exception {
        Map<String, Long> saved = new LinkedHashMap<String, Long>();
        saved.put("2026-10-18", 1000L);
        ListeningClock clock = new ListeningClock(1000L, saved);
        saved.put("2026-10-18", 999L);
        eq(1000L, clock.getDaily("2026-10-18"));
        ListeningClock.Snapshot snapshot = clock.snapshot();
        long start = wall("2026-10-18 12:00:00", UTC);
        clock.onState(true, 100L, start, UTC);
        clock.flush(200L, start + 100L, UTC);
        eq(1100L, clock.getTotal());
        eq(1000L, snapshot.totalMillis);
        eq(1000L, snapshot.dailyMillis.get("2026-10-18").longValue());
        boolean immutable = false;
        try { snapshot.dailyMillis.put("x", 1L); }
        catch (UnsupportedOperationException expected) { immutable = true; }
        eq(true, immutable);
        eq(0L, clock.getDaily("missing"));
    }

    private static long wall(String value, TimeZone zone) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(zone);
        return format.parse(value).getTime();
    }

    private static void eq(long expected, long actual) {
        checks++;
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static void eq(boolean expected, boolean actual) {
        checks++;
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
