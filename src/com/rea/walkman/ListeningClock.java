package com.rea.walkman;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Actual listening time: elapsed time measures duration; wall time labels local days. */
public final class ListeningClock {
    private long totalMillis;
    private final Map<String, Long> dailyMillis = new LinkedHashMap<String, Long>();
    private boolean initialized;
    private boolean playing;
    private long lastElapsed;

    public ListeningClock() {
        this(0L, null);
    }

    /** Restored values contain completed durations, never an active playback interval. */
    public ListeningClock(long savedTotalMillis, Map<String, Long> savedDailyMillis) {
        totalMillis = Math.max(0L, savedTotalMillis);
        if (savedDailyMillis != null) {
            for (Map.Entry<String, Long> entry : savedDailyMillis.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && entry.getValue().longValue() >= 0L) {
                    dailyMillis.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /** Flushes the preceding state before applying the new one. Repeated PLAYING is safe. */
    public synchronized void onState(boolean nowPlaying, long nowElapsed,
                                     long nowWall, TimeZone zone) {
        flush(nowElapsed, nowWall, zone);
        playing = nowPlaying;
    }

    /**
     * Commits elapsed time while playing. Wall-clock jumps never increase duration.
     * The elapsed interval is assigned backwards from its current wall-clock endpoint;
     * after a manual clock/time-zone change its day attribution follows the new clock.
     * An elapsed-clock rollback starts a fresh anchor and adds no duration.
     */
    public synchronized void flush(long nowElapsed, long nowWall, TimeZone zone) {
        if (!initialized) {
            initialized = true;
            lastElapsed = nowElapsed;
            return;
        }
        long duration = 0L;
        if (nowElapsed >= 0L && lastElapsed >= 0L && nowElapsed >= lastElapsed) {
            duration = nowElapsed - lastElapsed;
        }
        lastElapsed = nowElapsed;
        if (!playing || duration <= 0L) {
            return;
        }
        totalMillis = addSaturated(totalMillis, duration);
        splitByDay(duration, nowWall, zone == null ? TimeZone.getDefault() : zone);
    }

    private void splitByDay(long duration, long wallEnd, TimeZone zone) {
        // Epoch values used by Android are far from overflow. Clamp pathological inputs.
        long cursor = wallEnd < Long.MIN_VALUE + duration
                ? Long.MIN_VALUE : wallEnd - duration;
        long remaining = duration;
        Calendar calendar = Calendar.getInstance(zone, Locale.US);
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dayFormat.setTimeZone(zone);
        while (remaining > 0L) {
            calendar.setTimeInMillis(cursor);
            String day = dayFormat.format(calendar.getTime());
            // Advance first: today's midnight may be a DST gap normalized to 01:00.
            calendar.add(Calendar.DAY_OF_MONTH, 1);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long nextMidnight = calendar.getTimeInMillis();
            long part = remaining;
            if (nextMidnight > cursor) {
                long untilMidnight = nextMidnight - cursor;
                if (untilMidnight > 0L && untilMidnight < part) {
                    part = untilMidnight;
                }
            }
            Long saved = dailyMillis.get(day);
            dailyMillis.put(day, addSaturated(saved == null ? 0L : saved.longValue(), part));
            remaining -= part;
            if (remaining > 0L) {
                cursor += part;
            }
        }
    }

    private static long addSaturated(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    public synchronized long getTotal() {
        return totalMillis;
    }

    public synchronized long getDaily(String day) {
        Long value = dailyMillis.get(day);
        return value == null ? 0L : value.longValue();
    }

    public synchronized Map<String, Long> getDaily() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Long>(dailyMillis));
    }

    /** Call flush with current timestamps first when an up-to-date snapshot is needed. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(totalMillis, dailyMillis);
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    /** A zero or negative deadline means the sleep timer is disabled. */
    public static boolean due(long deadlineWall, long nowWall) {
        return deadlineWall > 0L && nowWall >= deadlineWall;
    }

    public static final class Snapshot {
        public final long totalMillis;
        public final Map<String, Long> dailyMillis;

        private Snapshot(long total, Map<String, Long> daily) {
            totalMillis = total;
            dailyMillis = Collections.unmodifiableMap(new LinkedHashMap<String, Long>(daily));
        }

        public long getTotal() {
            return totalMillis;
        }

        public Map<String, Long> getDaily() {
            return dailyMillis;
        }
    }
}
