package com.rea.walkman;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Optional UI and service hooks. All audio operations remain in Sony's service. */
public final class PlaybackExtras {
    private static final String PREFS = "rea_sleep_listening_v1";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static SharedPreferences prefs;
    private static ListeningClock clock;
    private static boolean playing;
    private static long deadlineWall;
    private static long deadlineElapsed;
    private static final long CHECKPOINT_MS = 10000L;
    private static final Runnable CHECKPOINT = new Runnable() {
        @Override public void run() {
            synchronized (PlaybackExtras.class) {
                if (!playing || clock == null) return;
                flush(false);
                MAIN.postDelayed(this, CHECKPOINT_MS);
            }
        }
    };

    private PlaybackExtras() {}

    private static void ensure(Context context) {
        if (clock != null) return;
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, Long> daily = new HashMap<String, Long>();
        try {
            JSONObject json = new JSONObject(prefs.getString("days", "{}"));
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                daily.put(key, Math.max(0L, json.optLong(key, 0L)));
            }
        } catch (Exception ignored) { /* Recover a damaged optional record without blocking playback. */ }
        clock = new ListeningClock(Math.max(0L, prefs.getLong("total", 0L)), daily);
        deadlineWall = prefs.getLong("deadline", 0L);
        if (deadlineWall > 0L) {
            long remaining = Math.max(0L, deadlineWall - System.currentTimeMillis());
            deadlineElapsed = SystemClock.elapsedRealtime() + remaining;
        }
    }

    /** Called only after the original service has stored its new playback state. */
    public static synchronized void onState(Context context, Enum<?> state) {
        ensure(context);
        boolean next = state != null && "PLAYING".equals(state.name());
        clock.onState(next, SystemClock.elapsedRealtime(), System.currentTimeMillis(), TimeZone.getDefault());
        boolean changed = next != playing;
        playing = next;
        if (changed) {
            MAIN.removeCallbacks(CHECKPOINT);
            save(!next);
            if (next) MAIN.postDelayed(CHECKPOINT, CHECKPOINT_MS);
        }
    }

    /** Flush at service shutdown; never count the period while a dead process is absent. */
    public static synchronized void onServiceStop(Context context) {
        ensure(context);
        clock.onState(false, SystemClock.elapsedRealtime(), System.currentTimeMillis(), TimeZone.getDefault());
        playing = false;
        MAIN.removeCallbacks(CHECKPOINT);
        save(true);
    }

    /** Evaluated at natural completion BEFORE the original service resets or advances the track. */
    public static synchronized boolean onTrackCompleted(Context context) {
        ensure(context);
        if (deadlineWall <= 0L || SystemClock.elapsedRealtime() < deadlineElapsed) return false;
        clock.onState(false, SystemClock.elapsedRealtime(), System.currentTimeMillis(), TimeZone.getDefault());
        playing = false;
        MAIN.removeCallbacks(CHECKPOINT);
        deadlineWall = 0L;
        deadlineElapsed = 0L;
        save(true);
        final Context app = context.getApplicationContext();
        MAIN.post(new Runnable() {
            @Override public void run() { Toast.makeText(app, "睡眠定时已结束，本曲播完后暂停", Toast.LENGTH_LONG).show(); }
        });
        return true;
    }

    /** Avoid handing native gapless playback another track while a sleep timer is armed. */
    public static synchronized boolean timerActive(Context context) {
        ensure(context);
        return deadlineWall > 0L;
    }

    private static void flush(boolean sync) {
        clock.flush(SystemClock.elapsedRealtime(), System.currentTimeMillis(), TimeZone.getDefault());
        save(sync);
    }

    private static void save(boolean sync) {
        JSONObject json = new JSONObject(clock.getDaily());
        SharedPreferences.Editor edit = prefs.edit().putLong("total", clock.getTotal())
            .putString("days", json.toString()).putLong("deadline", deadlineWall);
        if (sync) edit.commit(); else edit.apply();
    }

    public static synchronized void setTimer(Context context, int minutes) {
        ensure(context);
        if (minutes < 1 || minutes > 1440) throw new IllegalArgumentException("minutes");
        long delay = minutes * 60000L;
        deadlineWall = System.currentTimeMillis() + delay;
        deadlineElapsed = SystemClock.elapsedRealtime() + delay;
        save(true);
    }

    public static synchronized void cancelTimer(Context context) {
        ensure(context);
        deadlineWall = 0L;
        deadlineElapsed = 0L;
        save(true);
    }

    private static synchronized String timerSummary(Context context) {
        ensure(context);
        if (deadlineWall <= 0L) return "未开启 · 到点后当前歌曲播完再暂停";
        long remaining = deadlineElapsed - SystemClock.elapsedRealtime();
        if (remaining <= 0L) return "已到时间 · 等待当前歌曲自然播完";
        long seconds = (remaining + 999L) / 1000L;
        return String.format(Locale.getDefault(), "剩余 %d:%02d · 本曲播完再暂停", seconds / 60L, seconds % 60L);
    }

    private static String duration(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return String.format(Locale.getDefault(), "%d小时 %02d分 %02d秒", seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L);
    }

    private static String dateKey(long wall) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date(wall));
    }

    private static synchronized String statsSummary(Context context) {
        ensure(context);
        clock.flush(SystemClock.elapsedRealtime(), System.currentTimeMillis(), TimeZone.getDefault());
        return "今日 " + duration(clock.getDaily(dateKey(System.currentTimeMillis()))) + "\n累计 " + duration(clock.getTotal());
    }

    /** Two self-contained rows in the existing scrollable Settings list. */
    public static void appendSettings(final LinearLayout list) {
        final Context context = list.getContext();
        final LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
        TextView heading = new TextView(context);
        heading.setText("睡眠与听歌记录");
        heading.setTextSize(14);
        heading.setTextColor(0xff85c8ff);
        heading.setPadding(dp(context, 16), dp(context, 12), 0, dp(context, 4));
        section.addView(heading);
        final TextView timer = row(context, "睡眠定时", timerSummary(context));
        final TextView stats = row(context, "播放时长记录", statsSummary(context));
        timer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTimer(context); }
        });
        stats.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showStats(context); }
        });
        section.addView(timer);
        section.addView(stats);
        list.addView(section, 0);
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                timer.setText("睡眠定时\n" + timerSummary(context));
                stats.setText("播放时长记录\n" + statsSummary(context));
                if (section.isAttachedToWindow()) section.postDelayed(this, 1000L);
            }
        };
        section.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { section.removeCallbacks(refresh); section.post(refresh); }
            @Override public void onViewDetachedFromWindow(View v) { section.removeCallbacks(refresh); }
        });
    }

    private static TextView row(Context context, String title, String summary) {
        TextView row = new TextView(context);
        row.setText(title + "\n" + summary);
        row.setTextSize(16);
        row.setTextColor(Color.WHITE);
        row.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        row.setMinHeight(dp(context, 64));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setFocusable(true);
        return row;
    }

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private static void showTimer(final Context context) {
        final String[] items = {"15分钟", "30分钟", "45分钟", "60分钟", "90分钟", "120分钟", "自定义分钟数", "关闭睡眠定时"};
        new AlertDialog.Builder(context).setTitle("睡眠定时")
            .setItems(items, (dialog, which) -> {
                if (which == 7) {
                    cancelTimer(context);
                    Toast.makeText(context, "已关闭睡眠定时", Toast.LENGTH_SHORT).show();
                } else if (which == 6) {
                    showCustomTimer(context);
                } else {
                    int[] minutes = {15, 30, 45, 60, 90, 120};
                    confirmTimer(context, minutes[which]);
                }
            }).setNegativeButton("返回", null).show();
    }

    private static void showCustomTimer(final Context context) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1–1440 分钟");
        input.setSingleLine(true);
        input.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16));
        final AlertDialog dialog = new AlertDialog.Builder(context).setTitle("自定义睡眠定时")
            .setMessage("时间到后，等待当前歌曲自然结束再暂停。")
            .setView(input).setPositiveButton("开启", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int minutes = Integer.parseInt(input.getText().toString().trim());
                if (minutes < 1 || minutes > 1440) throw new NumberFormatException();
                confirmTimer(context, minutes);
                dialog.dismiss();
            } catch (NumberFormatException ex) { input.setError("请输入 1–1440 之间的整数"); }
        }));
        dialog.show();
    }

    private static void confirmTimer(Context context, int minutes) {
        setTimer(context, minutes);
        Toast.makeText(context, "已设定 " + minutes + " 分钟；到点后本曲播完再暂停", Toast.LENGTH_LONG).show();
    }

    private static void showStats(Context context) {
        final String text;
        synchronized (PlaybackExtras.class) {
            ensure(context);
            flush(true);
            StringBuilder body = new StringBuilder(statsSummary(context));
            body.append("\n\n每日记录\n");
            ArrayList<String> days = new ArrayList<String>(clock.getDaily().keySet());
            Collections.sort(days, Collections.reverseOrder());
            if (days.isEmpty()) body.append("开始播放后会自动记录。\n");
            for (String day : days) body.append(day).append("  ").append(duration(clock.getDaily(day))).append('\n');
            body.append("\n仅记录本共存版实际播放时间；暂停、快进和倒退不计时。记录保存在本机。\n异常结束时最多可能丢失最后10秒。");
            text = body.toString();
        }
        TextView details = new TextView(context);
        details.setText(text);
        details.setTextSize(16);
        details.setTextIsSelectable(true);
        details.setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(details);
        new AlertDialog.Builder(context).setTitle("播放时长记录").setView(scroll).setPositiveButton("关闭", null).show();
    }
}
