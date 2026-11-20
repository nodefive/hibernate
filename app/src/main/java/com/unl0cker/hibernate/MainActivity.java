package com.unl0cker.hibernate;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Memory, the hibernate button, and the awake / hibernated / failed lists. */
public class MainActivity extends Activity implements Hibernator.Listener {

    private static final int TAB_AWAKE = 0;
    private static final int TAB_ASLEEP = 1;
    private static final int TAB_FAILED = 2;
    private static final long MEMORY_REFRESH_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private View header;
    private View footer;
    private PullRefreshLayout pull;
    private long refreshStarted;
    private MemoryRing ring;
    private SegmentedTabs tabs;
    private final RowAdapter adapter = new RowAdapter();
    private AppScanner.Snapshot snapshot;
    private int tab = TAB_AWAKE;
    private boolean loading;
    private boolean reloadQueued;

    private final Runnable memoryTick = new Runnable() {
        @Override
        public void run() {
            updateMemory();
            handler.postDelayed(this, MEMORY_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);

        ListView list = findViewById(R.id.list);
        LayoutInflater inflater = getLayoutInflater();
        header = inflater.inflate(R.layout.main_header, list, false);
        footer = inflater.inflate(R.layout.main_footer, list, false);
        list.addHeaderView(header, null, false);
        list.addFooterView(footer, null, false);
        list.setAdapter(adapter);

        pull = findViewById(R.id.pull);
        pull.setOnRefreshListener(() -> {
            refreshStarted = SystemClock.uptimeMillis();
            updateStatus();
            updateMemory();
            reload();
        });

        ring = header.findViewById(R.id.ring);
        header.findViewById(R.id.open_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        header.findViewById(R.id.open_access).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        header.findViewById(R.id.hibernate).setOnClickListener(v -> onHibernateClicked());
        header.findViewById(R.id.mode_line).setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class)));

        tabs = header.findViewById(R.id.tabs);
        tabs.setTabs(R.string.tab_awake, R.string.tab_asleep, R.string.tab_failed);
        tabs.setOnSelectListener(this::selectTab);
        header.findViewById(R.id.stat_awake).setOnClickListener(v -> selectTab(TAB_AWAKE));
        header.findViewById(R.id.stat_asleep).setOnClickListener(v -> selectTab(TAB_ASLEEP));
        header.findViewById(R.id.stat_failed).setOnClickListener(v -> selectTab(TAB_FAILED));

        header.findViewById(R.id.retry_all).setOnClickListener(v -> {
            if (snapshot == null || snapshot.failed.isEmpty()) {
                return;
            }
            List<String> pkgs = new ArrayList<>();
            for (AppScanner.Entry e : snapshot.failed) {
                pkgs.add(e.pkg);
            }
            hibernate(pkgs);
        });
        header.findViewById(R.id.clear_failed).setOnClickListener(v -> {
            Prefs.putSet(prefs, Prefs.KEY_FAILED, Collections.emptySet());
            reload();
        });

        ((ImageView) header.findViewById(R.id.access_dot)).setImageTintList(
                ColorStateList.valueOf(getColor(R.color.warn)));
        selectTab(TAB_AWAKE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Hibernator.get().addListener(this);
        handler.post(memoryTick);
        updateStatus();
        updateButton();
        reload();
    }

    @Override
    protected void onPause() {
        Hibernator.get().removeListener(this);
        handler.removeCallbacks(memoryTick);
        super.onPause();
    }

    @Override
    public void onHibernateChanged() {
        updateButton();
        Hibernator h = Hibernator.get();
        if (!h.isRunning() && h.last() != null) {
            showResult(h.last());
            reload();
            updateMemory();
        }
    }

    // ---- Data

    private void reload() {
        if (loading) {
            reloadQueued = true;
            return;
        }
        loading = true;
        if (snapshot == null) {
            footer.findViewById(R.id.loading).setVisibility(View.VISIBLE);
            footer.findViewById(R.id.empty).setVisibility(View.GONE);
        }
        new Thread(() -> {
            AppScanner.Snapshot snap = AppScanner.scan(this, true);
            runOnUiThread(() -> {
                loading = false;
                if (isDestroyed()) {
                    return;
                }
                snapshot = snap;
                showSnapshot();
                if (reloadQueued) {
                    reloadQueued = false;
                    reload();
                } else if (pull.isRefreshing()) {
                    // Keep the spinner up long enough to be seen, even on a fast scan.
                    long shown = SystemClock.uptimeMillis() - refreshStarted;
                    handler.postDelayed(() -> pull.setRefreshing(false), Math.max(0, 600 - shown));
                }
            });
        }, "scan").start();
    }

    private void showSnapshot() {
        footer.findViewById(R.id.loading).setVisibility(View.GONE);
        setText(R.id.stat_awake_n, String.valueOf(snapshot.awake.size()));
        setText(R.id.stat_asleep_n, String.valueOf(snapshot.asleep.size()));
        setText(R.id.stat_failed_n, String.valueOf(snapshot.failed.size()));
        ((TextView) header.findViewById(R.id.stat_failed_n)).setTextColor(getColor(
                snapshot.failed.isEmpty() ? R.color.text : R.color.warn));
        selectTab(tab);
        updateButton();
    }

    private List<AppScanner.Entry> current() {
        if (snapshot == null) {
            return Collections.emptyList();
        }
        switch (tab) {
            case TAB_ASLEEP:
                return snapshot.asleep;
            case TAB_FAILED:
                return snapshot.failed;
            default:
                return snapshot.awake;
        }
    }

    private void selectTab(int which) {
        tab = which;
        // Stat taps and refreshes move the pill too; a tap on a tab has already moved it.
        tabs.select(which, true);
        for (int i = 0; i < 3; i++) {
            tabs.setCount(i, snapshot == null ? -1 : (i == TAB_AWAKE ? snapshot.awake.size()
                    : i == TAB_ASLEEP ? snapshot.asleep.size() : snapshot.failed.size()));
        }
        int hint = which == TAB_AWAKE ? R.string.hint_awake
                : which == TAB_ASLEEP ? R.string.hint_asleep : R.string.hint_failed;
        setText(R.id.tab_hint, getString(hint));
        header.findViewById(R.id.failed_actions).setVisibility(
                which == TAB_FAILED && snapshot != null && !snapshot.failed.isEmpty()
                        ? View.VISIBLE : View.GONE);

        TextView empty = footer.findViewById(R.id.empty);
        boolean none = snapshot != null && current().isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        empty.setText(which == TAB_AWAKE ? R.string.empty_awake
                : which == TAB_ASLEEP ? R.string.empty_asleep : R.string.empty_failed);
        adapter.notifyDataSetChanged();
    }

    // ---- Status, memory, button

    private void updateStatus() {
        boolean access = serviceEnabled(this);
        header.findViewById(R.id.access_card).setVisibility(access ? View.GONE : View.VISIBLE);
        FastMode.Status fast = FastMode.status(this);
        String line;
        if (fast == FastMode.Status.READY) {
            line = getString(R.string.mode_fast);
        } else if (access) {
            line = getString(R.string.mode_click, getString(fastReason(fast)));
        } else {
            line = getString(R.string.mode_none);
        }
        setText(R.id.mode_line, line + "  " + getString(R.string.chevron));
    }

    static int fastReason(FastMode.Status s) {
        switch (s) {
            case DISABLED:
                return R.string.fast_disabled;
            case NO_PERMISSION:
                return R.string.fast_no_permission;
            case NOT_AUTHORIZED:
                return R.string.fast_not_authorized;
            default:
                return R.string.fast_no_wifi;
        }
    }

    private void updateMemory() {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(info);
        long used = info.totalMem - info.availMem;
        ring.setFraction(info.totalMem > 0 ? (float) used / info.totalMem : 0f);
        setText(R.id.mem_used, Format.size(used));
        setText(R.id.mem_total, getString(R.string.mem_total_fmt, Format.size(info.totalMem)));
        setText(R.id.mem_free, getString(R.string.mem_free_fmt, Format.size(info.availMem)));
    }

    private void updateButton() {
        Hibernator h = Hibernator.get();
        ProgressBar bar = header.findViewById(R.id.hib_progress);
        View button = header.findViewById(R.id.hibernate);
        if (h.isRunning()) {
            bar.setVisibility(View.VISIBLE);
            bar.setMax(Math.max(1, h.total()));
            bar.setProgress(h.done());
            button.setBackground(null);
            setText(R.id.hib_text, h.total() == 0 ? getString(R.string.hibernate_starting)
                    : getString(R.string.hibernate_progress, h.done(), h.total()));
            button.setAlpha(1f);
        } else {
            bar.setVisibility(View.INVISIBLE);
            button.setBackgroundResource(R.drawable.button_accent);
            int n = snapshot == null ? 0 : snapshot.targets;
            setText(R.id.hib_text, n == 0 ? getString(R.string.hibernate_none)
                    : getResources().getQuantityString(R.plurals.hibernate_n, n, n));
            button.setAlpha(n == 0 ? 0.5f : 1f);
        }
    }

    private void showResult(Hibernator.Result r) {
        TextView line = header.findViewById(R.id.result_line);
        String text = Hibernator.summary(this, r);
        if (r.fastError != null) {
            text += "\n" + getString(R.string.result_fast_error, r.fastError);
        }
        line.setText(text);
        line.setVisibility(View.VISIBLE);
        updateStatus();
    }

    private void onHibernateClicked() {
        Hibernator h = Hibernator.get();
        if (h.isRunning()) {
            h.cancel();
            return;
        }
        if (snapshot == null || snapshot.targets == 0) {
            return;
        }
        hibernate(null);
    }

    private void hibernate(List<String> only) {
        if (FastMode.status(this) != FastMode.Status.READY && CloseService.instance() == null) {
            Toast.makeText(this, R.string.needs_method, Toast.LENGTH_LONG).show();
            return;
        }
        header.findViewById(R.id.result_line).setVisibility(View.GONE);
        Hibernator.get().start(this, Hibernator.Source.APP, only);
    }

    // ---- Rows

    private void showMenu(View anchor, AppScanner.Entry e) {
        PopupMenu menu = new PopupMenu(this, anchor);
        Menu m = menu.getMenu();
        Set<String> exceptions = Prefs.set(prefs, Prefs.KEY_EXCEPTIONS);
        boolean isException = exceptions.contains(e.pkg);
        if (!e.stopped) {
            m.add(0, 1, 0, R.string.menu_hibernate);
        }
        m.add(0, 2, 1, R.string.menu_app_info);
        m.add(0, 3, 2, isException ? R.string.menu_remove_exception : R.string.menu_add_exception);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    hibernate(Collections.singletonList(e.pkg));
                    return true;
                case 2:
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", e.pkg, null)));
                    return true;
                case 3:
                    if (isException) {
                        exceptions.remove(e.pkg);
                    } else {
                        exceptions.add(e.pkg);
                    }
                    Prefs.putSet(prefs, Prefs.KEY_EXCEPTIONS, exceptions);
                    reload();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return current().size();
        }

        @Override
        public Object getItem(int position) {
            return current().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_row, parent, false);
            AppScanner.Entry e = current().get(position);
            ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(
                    AppScanner.icon(MainActivity.this, e.pkg));
            ((TextView) row.findViewById(R.id.label)).setText(e.label);
            TextView sub = row.findViewById(R.id.sub);
            if (!e.stopped && e.reason != 0) {
                sub.setText(e.reason);
            } else if (e.system) {
                sub.setText(R.string.sub_system);
            } else {
                sub.setText(e.pkg);
            }
            View body = row.findViewById(R.id.row_body);
            body.setAlpha(!e.stopped && e.reason != 0 ? 0.6f : 1f);
            body.setOnClickListener(v -> showMenu(v, e));

            View action = row.findViewById(R.id.action);
            boolean canHibernate = !e.stopped;
            action.setVisibility(canHibernate ? View.VISIBLE : View.GONE);
            action.setOnClickListener(v -> hibernate(Collections.singletonList(e.pkg)));
            return row;
        }
    }

    // ---- Helpers

    private void setText(int id, CharSequence text) {
        ((TextView) header.findViewById(id)).setText(text);
    }

    /** Whether the accessibility service is switched on in Settings. */
    static boolean serviceEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName self = new ComponentName(context, CloseService.class);
        for (String entry : enabled.split(":")) {
            if (self.equals(ComponentName.unflattenFromString(entry))) {
                return true;
            }
        }
        return false;
    }
}
