package com.unl0cker.hibernate;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Checkbox list of apps for "My list" or "Never hibernate", with search. */
public class AppPickerActivity extends Activity {

    static final String EXTRA_KEY = "key";

    private static final class App {
        final String pkg;
        final String label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private SharedPreferences prefs;
    private String key;
    private Set<String> chosen;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);
        prefs = Prefs.get(this);
        key = getIntent().getStringExtra(EXTRA_KEY);
        if (!Prefs.KEY_LIST.equals(key)) {
            key = Prefs.KEY_EXCEPTIONS;
        }
        chosen = Prefs.set(prefs, key);
        boolean list = Prefs.KEY_LIST.equals(key);
        ((TextView) findViewById(R.id.title)).setText(
                list ? R.string.pick_list_title : R.string.pick_exceptions_title);
        ((TextView) findViewById(R.id.explain)).setText(
                list ? R.string.pick_list_explain : R.string.pick_exceptions_explain);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        ListView listView = findViewById(R.id.list);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String pkg = shown.get(position).pkg;
            if (!chosen.remove(pkg)) {
                chosen.add(pkg);
            }
            Prefs.putSet(prefs, key, chosen);
            ((CheckBox) view.findViewById(R.id.check)).setChecked(chosen.contains(pkg));
        });

        ((EditText) findViewById(R.id.search)).addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                filter();
            }
        });

        new Thread(this::load, "load-apps").start();
    }

    /** User apps and launchable system apps; chosen first, then alphabetical. */
    private void load() {
        PackageManager pm = getPackageManager();
        Set<String> launchable = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            launchable.add(ri.activityInfo.packageName);
        }
        List<App> loaded = new ArrayList<>();
        for (ApplicationInfo info : pm.getInstalledApplications(0)) {
            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (info.packageName.equals(getPackageName())
                    || (system && !launchable.contains(info.packageName))) {
                continue;
            }
            loaded.add(new App(info.packageName, pm.getApplicationLabel(info).toString()));
            AppScanner.icon(this, info.packageName);
        }
        Collator collator = Collator.getInstance();
        Set<String> initial = new HashSet<>(chosen);
        loaded.sort(Comparator.comparing((App a) -> !initial.contains(a.pkg))
                .thenComparing(a -> a.label, collator));
        runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }
            all.clear();
            all.addAll(loaded);
            filter();
            findViewById(R.id.loading).setVisibility(View.GONE);
        });
    }

    private void filter() {
        shown.clear();
        for (App a : all) {
            if (query.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(query)
                    || a.pkg.contains(query)) {
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_pick, parent, false);
            App app = shown.get(position);
            ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(
                    AppScanner.icon(AppPickerActivity.this, app.pkg));
            ((TextView) row.findViewById(R.id.label)).setText(app.label);
            ((TextView) row.findViewById(R.id.pkg)).setText(app.pkg);
            ((CheckBox) row.findViewById(R.id.check)).setChecked(chosen.contains(app.pkg));
            return row;
        }
    }
}
