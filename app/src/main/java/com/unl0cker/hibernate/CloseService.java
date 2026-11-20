package com.unl0cker.hibernate;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessibility mode: opens each app's App info page and presses Force stop, then OK. Also
 * hosts "hibernate when the screen turns off", and presses Allow on the "wireless debugging on
 * this network" prompt, only during the seconds right after fast mode turned debugging on.
 */
public class CloseService extends AccessibilityService {

    private static final String SETTINGS = "com.android.settings";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String ID_FORCE_STOP = SETTINGS + ":id/button3";
    private static final String ID_DIALOG_OK = "android:id/button1";

    private static final long OPEN_TIMEOUT_MS = 5000;
    private static final long CONFIRM_TIMEOUT_MS = 3000;
    private static final long VERIFY_TIMEOUT_MS = 2000;
    private static final long LABEL_GRACE_MS = 1500;
    private static final long POLL_MS = 120;

    private static volatile CloseService instance;
    private static volatile long wifiAllowUntil;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ClickJob job;
    private SharedPreferences prefs;

    static CloseService instance() {
        return instance;
    }

    /** Lets the service press Allow on the wireless debugging prompt for the next ms. */
    static void armWifiAllow(long ms) {
        wifiAllowUntil = ms > 0 ? SystemClock.uptimeMillis() + ms : 0;
    }

    private final Runnable screenOffRun = () ->
            Hibernator.get().start(this, Hibernator.Source.SCREEN_OFF, null);

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(screenOffRun);
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && Prefs.screenOff(prefs)
                    && job == null) {
                handler.postDelayed(screenOffRun, Prefs.screenOffDelay(prefs) * 1000L);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        prefs = Prefs.get(this);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (job != null) {
            job.end();
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return;
        }
        if (SYSTEMUI.contentEquals(pkg) && SystemClock.uptimeMillis() < wifiAllowUntil) {
            allowWifiDebugging();
        } else if (SETTINGS.contentEquals(pkg) && job != null) {
            job.poke();
        }
    }

    @Override
    public void onInterrupt() {
    }

    /** Swipes the notification shade away (used by the Quick Settings tile). */
    void dismissShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        }
    }

    // ---- Wireless debugging prompt

    private void allowWifiDebugging() {
        for (AccessibilityNodeInfo root : roots(SYSTEMUI)) {
            List<AccessibilityNodeInfo> ok = root.findAccessibilityNodeInfosByViewId(ID_DIALOG_OK);
            if (ok.isEmpty()) {
                continue;
            }
            // "Always allow on this network" keeps it quiet until the next reboot.
            checkBoxes(root);
            ok.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            wifiAllowUntil = 0;
            return;
        }
    }

    private static void checkBoxes(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        if (node.isCheckable() && !node.isChecked()
                && String.valueOf(node.getClassName()).endsWith("CheckBox")) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            checkBoxes(node.getChild(i));
        }
    }

    // ---- Accessibility mode

    void runClicks(List<String> packages, Hibernator.Source source, FastMode.Progress progress,
                   Runnable done) {
        if (job != null) {
            done.run();
            return;
        }
        job = new ClickJob(packages, source, progress, done);
        job.begin();
    }

    void cancelClicks() {
        handler.post(() -> {
            if (job != null) {
                job.cancelled = true;
            }
        });
    }

    private List<AccessibilityNodeInfo> roots(String pkg) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityWindowInfo window : getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && root.getPackageName() != null
                    && pkg.contentEquals(root.getPackageName())) {
                out.add(root);
            }
        }
        return out;
    }

    private AccessibilityNodeInfo findInSettings(String viewId) {
        for (AccessibilityNodeInfo root : roots(SETTINGS)) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
            if (!nodes.isEmpty()) {
                return nodes.get(0);
            }
        }
        return null;
    }

    private boolean settingsShows(String text) {
        for (AccessibilityNodeInfo root : roots(SETTINGS)) {
            if (!root.findAccessibilityNodeInfosByText(text).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private final class ClickJob {
        private static final int OPEN = 0;
        private static final int CONFIRM = 1;
        private static final int VERIFY = 2;

        final List<String> packages;
        final Hibernator.Source source;
        final FastMode.Progress progress;
        final Runnable done;
        boolean cancelled;

        int index = -1;
        int stage;
        String pkg;
        String label;
        long stageStart;
        long deadline;
        boolean ended;

        PowerManager.WakeLock wakeLock;
        View overlay;
        TextView overlayText;
        ProgressBar overlayBar;

        private final Runnable tick = this::step;

        ClickJob(List<String> packages, Hibernator.Source source, FastMode.Progress progress,
                 Runnable done) {
            this.packages = packages;
            this.source = source;
            this.progress = progress;
            this.done = done;
        }

        void begin() {
            PowerManager pm = getSystemService(PowerManager.class);
            boolean screenOff = !pm.isInteractive();
            if (screenOff) {
                // The Settings screens only respond with the display on.
                @SuppressWarnings("deprecation")
                int flags = PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
                wakeLock = pm.newWakeLock(flags, "hibernate:clicks");
                wakeLock.acquire(60_000L + packages.size() * 10_000L);
            }
            if (screenOff || Prefs.overlay(prefs)) {
                showOverlay(screenOff);
            }
            next();
        }

        void poke() {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }

        void next() {
            index++;
            if (cancelled || index >= packages.size()) {
                end();
                return;
            }
            pkg = packages.get(index);
            label = AppScanner.label(CloseService.this, pkg);
            updateOverlay();
            stage = OPEN;
            stageStart = SystemClock.uptimeMillis();
            deadline = stageStart + OPEN_TIMEOUT_MS;
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            try {
                startActivity(details);
            } catch (RuntimeException e) {
                finishApp();
                return;
            }
            handler.postDelayed(tick, POLL_MS * 2);
        }

        void step() {
            handler.removeCallbacks(tick);
            if (ended || pkg == null) {
                return;
            }
            if (cancelled) {
                end();
                return;
            }
            long now = SystemClock.uptimeMillis();
            switch (stage) {
                case OPEN: {
                    // Make sure the page is this app's, not the previous one still on screen.
                    boolean page = now - stageStart > LABEL_GRACE_MS || settingsShows(label);
                    AccessibilityNodeInfo stop = page ? findInSettings(ID_FORCE_STOP) : null;
                    if (stop != null) {
                        if (!stop.isEnabled()) {
                            finishApp(); // already stopped
                            return;
                        }
                        stop.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        enter(CONFIRM, CONFIRM_TIMEOUT_MS);
                    }
                    break;
                }
                case CONFIRM: {
                    AccessibilityNodeInfo ok = findInSettings(ID_DIALOG_OK);
                    if (ok != null) {
                        ok.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        enter(VERIFY, VERIFY_TIMEOUT_MS);
                    }
                    break;
                }
                case VERIFY: {
                    if (AppScanner.isStopped(CloseService.this, pkg)) {
                        finishApp();
                        return;
                    }
                    break;
                }
                default:
                    break;
            }
            if (SystemClock.uptimeMillis() > deadline) {
                finishApp(); // counted as failed by the final check
                return;
            }
            handler.postDelayed(tick, POLL_MS);
        }

        private void enter(int next, long timeout) {
            stage = next;
            stageStart = SystemClock.uptimeMillis();
            deadline = stageStart + timeout;
        }

        void finishApp() {
            handler.removeCallbacks(tick);
            String finished = pkg;
            pkg = null;
            progress.step(finished);
            handler.postDelayed(this::next, Prefs.actionDelay(prefs));
        }

        void end() {
            if (ended) {
                return;
            }
            ended = true;
            handler.removeCallbacks(tick);
            if (index >= 0 && !packages.isEmpty()) {
                // Leave the App info page (the only screen in its task).
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            hideOverlay();
            if (wakeLock != null) {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
                if (source == Hibernator.Source.SCREEN_OFF) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                }
            } else if (source == Hibernator.Source.APP) {
                startActivity(new Intent(CloseService.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
            job = null;
            done.run();
        }

        private void showOverlay(boolean dark) {
            Context themed = new ContextThemeWrapper(CloseService.this, R.style.AppTheme);
            overlay = LayoutInflater.from(themed).inflate(R.layout.overlay_progress, null);
            if (dark) {
                overlay.setBackgroundColor(0xFF000000);
            }
            overlayText = overlay.findViewById(R.id.overlay_text);
            overlayBar = overlay.findViewById(R.id.overlay_bar);
            overlayBar.setMax(packages.size());
            overlay.findViewById(R.id.overlay_cancel).setOnClickListener(v -> cancelled = true);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE);
            try {
                getSystemService(WindowManager.class).addView(overlay, lp);
            } catch (RuntimeException e) {
                overlay = null;
            }
        }

        private void updateOverlay() {
            if (overlay == null) {
                return;
            }
            overlayText.setText(getString(R.string.progress_fmt, index + 1, packages.size(), label));
            overlayBar.setProgress(index);
        }

        private void hideOverlay() {
            if (overlay != null) {
                try {
                    getSystemService(WindowManager.class).removeView(overlay);
                } catch (RuntimeException ignored) {
                }
                overlay = null;
            }
        }
    }
}
