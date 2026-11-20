package com.unl0cker.hibernate;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs one hibernation at a time: fast mode first when it is ready, then accessibility mode
 * for whatever is left. Listeners are called on the main thread.
 */
final class Hibernator {

    private static final String TAG = "Hibernate";

    enum Source { APP, TILE, SCREEN_OFF }

    enum Mode { FAST, CLICK }

    interface Listener {
        void onHibernateChanged();
    }

    static final class Result {
        final int closed;
        final int failed;
        final long freedBytes;
        final String fastError;
        final boolean nothingToDo;
        final boolean noMethod;

        Result(int closed, int failed, long freedBytes, String fastError, boolean nothingToDo,
               boolean noMethod) {
            this.closed = closed;
            this.failed = failed;
            this.freedBytes = freedBytes;
            this.fastError = fastError;
            this.nothingToDo = nothingToDo;
            this.noMethod = noMethod;
        }
    }

    private static final Hibernator INSTANCE = new Hibernator();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile int done;
    private volatile int total;
    private volatile String current;
    private volatile Mode mode;
    private volatile Result last;

    private Hibernator() {
    }

    static Hibernator get() {
        return INSTANCE;
    }

    boolean isRunning() {
        return running;
    }

    int done() {
        return done;
    }

    int total() {
        return total;
    }

    String current() {
        return current;
    }

    Mode mode() {
        return mode;
    }

    Result last() {
        return last;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Hibernates the given packages, or every current target when only is null. */
    synchronized boolean start(Context context, Source source, List<String> only) {
        if (running) {
            return false;
        }
        running = true;
        cancelled = false;
        done = 0;
        total = 0;
        current = null;
        mode = null;
        Context app = context.getApplicationContext();
        changed();
        new Thread(() -> run(app, source, only), "hibernate").start();
        return true;
    }

    void cancel() {
        cancelled = true;
        CloseService service = CloseService.instance();
        if (service != null) {
            service.cancelClicks();
        }
    }

    private void run(Context context, Source source, List<String> only) {
        List<String> packages = only != null ? only : AppScanner.targets(context);
        total = packages.size();
        changed();
        if (packages.isEmpty()) {
            finish(context, source, new Result(0, 0, 0, null, true, false));
            return;
        }

        long before = availableMemory(context);
        Set<String> attempted = new HashSet<>();
        Set<String> pending = new LinkedHashSet<>(packages);
        String fastError = null;

        FastMode.Status fast = FastMode.status(context);
        if (fast == FastMode.Status.READY) {
            mode = Mode.FAST;
            changed();
            try {
                FastMode.run(context, packages, pkg -> step(context, pkg, attempted, pending),
                        () -> cancelled);
            } catch (Exception e) {
                Log.w(TAG, "fast mode failed", e);
                fastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
        }

        boolean noMethod = false;
        if (!pending.isEmpty() && !cancelled) {
            CloseService service = CloseService.instance();
            if (service != null) {
                mode = Mode.CLICK;
                changed();
                CountDownLatch latch = new CountDownLatch(1);
                List<String> left = new ArrayList<>(pending);
                main.post(() -> service.runClicks(left, source,
                        pkg -> step(context, pkg, attempted, pending), latch::countDown));
                try {
                    // Generous: a slow phone needs a few seconds per app.
                    latch.await(30 + left.size() * 15L, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            } else {
                noMethod = attempted.isEmpty();
            }
        }

        SystemClock.sleep(400);
        int closed = 0;
        SharedPreferences prefs = Prefs.get(context);
        Set<String> failedSet = Prefs.set(prefs, Prefs.KEY_FAILED);
        List<String> failed = new ArrayList<>();
        for (String pkg : packages) {
            boolean stopped = AppScanner.isStopped(context, pkg);
            if (stopped) {
                closed++;
                failedSet.remove(pkg);
            } else if (attempted.contains(pkg)) {
                failed.add(pkg);
                failedSet.add(pkg);
            }
        }
        Prefs.putSet(prefs, Prefs.KEY_FAILED, failedSet);
        long freed = Math.max(0, availableMemory(context) - before);
        finish(context, source, new Result(closed, failed.size(), freed, fastError, false, noMethod));
    }

    private void step(Context context, String pkg, Set<String> attempted, Set<String> pending) {
        attempted.add(pkg);
        pending.remove(pkg);
        done = attempted.size();
        current = AppScanner.label(context, pkg);
        changed();
    }

    private void finish(Context context, Source source, Result result) {
        main.post(() -> {
            last = result;
            running = false;
            current = null;
            for (Listener l : listeners) {
                l.onHibernateChanged();
            }
            if (source == Source.TILE) {
                Toast.makeText(context, summary(context, result), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void changed() {
        main.post(() -> {
            for (Listener l : listeners) {
                l.onHibernateChanged();
            }
        });
    }

    static long availableMemory(Context context) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(info);
        return info.availMem;
    }

    static String summary(Context context, Result r) {
        if (r.nothingToDo) {
            return context.getString(R.string.result_nothing);
        }
        if (r.noMethod) {
            return context.getString(R.string.result_no_method);
        }
        String text = context.getResources().getQuantityString(R.plurals.result_closed, r.closed, r.closed);
        if (r.freedBytes > 0) {
            text += " · " + context.getString(R.string.result_freed, Format.size(r.freedBytes));
        }
        if (r.failed > 0) {
            text += " · " + context.getResources().getQuantityString(R.plurals.result_failed, r.failed, r.failed);
        }
        return text;
    }
}
