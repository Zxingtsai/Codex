package android.os;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
/** Single-thread deterministic queue; callbacks run only when runDue() is called. */
public class Handler {
    private static final List<Task> QUEUE = new ArrayList<Task>();
    private static final class Task {
        final Handler owner; final Runnable callback; final long due;
        Task(Handler owner, Runnable callback, long due) {
            this.owner = owner; this.callback = callback; this.due = due;
        }
    }
    public Handler(Looper looper) {}
    public boolean post(Runnable callback) { return postDelayed(callback, 0L); }
    public boolean postDelayed(Runnable callback, long delay) {
        QUEUE.add(new Task(this, callback, SystemClock.elapsedRealtime() + Math.max(0L, delay)));
        return true;
    }
    public void removeCallbacks(Runnable callback) {
        for (Iterator<Task> it = QUEUE.iterator(); it.hasNext();) {
            Task task = it.next();
            if (task.owner == this && task.callback == callback) it.remove();
        }
    }
    public static void runDue() {
        int executions = 0;
        while (true) {
            Task selected = null;
            for (Task task : QUEUE) {
                if (task.due <= SystemClock.elapsedRealtime()
                        && (selected == null || task.due < selected.due)) selected = task;
            }
            if (selected == null) return;
            if (++executions > 1000) throw new AssertionError("Handler queue did not settle");
            QUEUE.remove(selected);
            selected.callback.run();
        }
    }
    public static int queuedCount() { return QUEUE.size(); }
    public static void clearQueue() { QUEUE.clear(); }
}
