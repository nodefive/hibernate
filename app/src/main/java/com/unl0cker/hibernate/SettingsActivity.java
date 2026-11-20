package com.unl0cker.hibernate;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Which apps, automation, fast mode, accessibility mode and backup. */
public class SettingsActivity extends Activity {

    private static final int REQ_EXPORT = 1;
    private static final int REQ_IMPORT = 2;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = Prefs.get(this);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.title)).setText(R.string.settings);

        bindSwitch(R.id.row_user_apps, R.id.switch_user_apps, Prefs.KEY_USER_APPS);
        bindSwitch(R.id.row_system_apps, R.id.switch_system_apps, Prefs.KEY_SYSTEM_APPS);
        bindSwitch(R.id.row_only_list, R.id.switch_only_list, Prefs.KEY_ONLY_LIST);
        bindSwitch(R.id.row_screen_off, R.id.switch_screen_off, Prefs.KEY_SCREEN_OFF);
        bindSwitch(R.id.row_fast, R.id.switch_fast, Prefs.KEY_FAST);
        bindSwitch(R.id.row_wifi_off, R.id.switch_wifi_off, Prefs.KEY_WIFI_OFF_AFTER);
        bindSwitch(R.id.row_overlay, R.id.switch_overlay, Prefs.KEY_OVERLAY);

        bindSlider(R.id.slider_screen_off_delay, R.id.value_screen_off_delay, R.string.seconds_fmt,
                Prefs.KEY_SCREEN_OFF_DELAY, Prefs.SCREEN_OFF_DELAY_MIN, Prefs.SCREEN_OFF_DELAY_MAX,
                Prefs.SCREEN_OFF_DELAY_STEP);
        bindSlider(R.id.slider_action_delay, R.id.value_action_delay, R.string.ms_fmt,
                Prefs.KEY_ACTION_DELAY, Prefs.ACTION_DELAY_MIN, Prefs.ACTION_DELAY_MAX,
                Prefs.ACTION_DELAY_STEP);

        findViewById(R.id.row_list).setOnClickListener(v -> startActivity(
                new Intent(this, AppPickerActivity.class)
                        .putExtra(AppPickerActivity.EXTRA_KEY, Prefs.KEY_LIST)));
        findViewById(R.id.row_exceptions).setOnClickListener(v -> startActivity(
                new Intent(this, AppPickerActivity.class)
                        .putExtra(AppPickerActivity.EXTRA_KEY, Prefs.KEY_EXCEPTIONS)));
        findViewById(R.id.row_setup).setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class)));
        findViewById(R.id.row_export).setOnClickListener(v -> startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "hibernate-settings.json"), REQ_EXPORT));
        findViewById(R.id.row_import).setOnClickListener(v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"), REQ_IMPORT));

        TextView version = findViewById(R.id.version);
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            version.setText(getString(R.string.version_fmt, name));
        } catch (PackageManager.NameNotFoundException e) {
            version.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        int[] switches = {
                R.id.switch_user_apps, R.id.switch_system_apps, R.id.switch_only_list,
                R.id.switch_screen_off, R.id.switch_fast, R.id.switch_wifi_off, R.id.switch_overlay
        };
        String[] keys = {
                Prefs.KEY_USER_APPS, Prefs.KEY_SYSTEM_APPS, Prefs.KEY_ONLY_LIST,
                Prefs.KEY_SCREEN_OFF, Prefs.KEY_FAST, Prefs.KEY_WIFI_OFF_AFTER, Prefs.KEY_OVERLAY
        };
        for (int i = 0; i < keys.length; i++) {
            ((Switch) findViewById(switches[i])).setChecked(Prefs.bool(prefs, keys[i]));
        }
        setCount(R.id.summary_list, Prefs.set(prefs, Prefs.KEY_LIST).size());
        setCount(R.id.summary_exceptions, Prefs.set(prefs, Prefs.KEY_EXCEPTIONS).size());

        int status;
        switch (FastMode.status(this)) {
            case READY:
                status = R.string.fast_status_ready;
                break;
            case NO_WIFI:
                status = R.string.fast_status_wifi;
                break;
            case NO_PERMISSION:
                status = R.string.fast_status_permission;
                break;
            case NOT_AUTHORIZED:
                status = R.string.fast_status_authorize;
                break;
            default:
                status = R.string.fast_status_off;
                break;
        }
        ((TextView) findViewById(R.id.summary_setup)).setText(status);

        boolean onlyList = Prefs.onlyList(prefs);
        setRowEnabled(R.id.row_list, onlyList);
        boolean screenOff = Prefs.screenOff(prefs);
        setRowEnabled(R.id.row_screen_off_delay, screenOff);
        findViewById(R.id.slider_screen_off_delay).setEnabled(screenOff);
        boolean fast = Prefs.fastEnabled(prefs);
        setRowEnabled(R.id.row_setup, fast);
        setRowEnabled(R.id.row_wifi_off, fast);
    }

    private void setCount(int id, int n) {
        ((TextView) findViewById(id)).setText(getResources().getQuantityString(R.plurals.apps_count, n, n));
    }

    private void setRowEnabled(int id, boolean enabled) {
        View row = findViewById(id);
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.4f);
    }

    private void bindSwitch(int rowId, int switchId, String key) {
        Switch toggle = findViewById(switchId);
        findViewById(rowId).setOnClickListener(v -> {
            toggle.toggle();
            prefs.edit().putBoolean(key, toggle.isChecked()).apply();
            refresh();
        });
    }

    private void bindSlider(int sliderId, int valueId, int formatId, String key,
                            int min, int max, int step) {
        SeekBar slider = findViewById(sliderId);
        TextView value = findViewById(valueId);
        int current = Prefs.integer(prefs, key);
        slider.setMax((max - min) / step);
        slider.setProgress((Math.max(min, Math.min(max, current)) - min) / step);
        value.setText(getString(formatId, current));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(getString(formatId, min + progress * step));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.edit().putInt(key, min + bar.getProgress() * step).apply();
            }
        });
    }

    // ---- Backup

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT) {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    out.write(exportJson().toString(2).getBytes(StandardCharsets.UTF_8));
                }
                Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_IMPORT) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) > 0 && buf.size() < 1024 * 1024) {
                        buf.write(chunk, 0, n);
                    }
                }
                importJson(new JSONObject(buf.toString(StandardCharsets.UTF_8.name())));
                Toast.makeText(this, R.string.imported, Toast.LENGTH_SHORT).show();
                recreate();
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show();
        }
    }

    private JSONObject exportJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("format", "hibernate-settings-1");
        for (String k : Prefs.BOOLEAN_KEYS) {
            o.put(k, Prefs.bool(prefs, k));
        }
        for (String k : Prefs.INT_KEYS) {
            o.put(k, Prefs.integer(prefs, k));
        }
        for (String k : Prefs.SET_KEYS) {
            JSONArray a = new JSONArray();
            for (String pkg : Prefs.set(prefs, k)) {
                a.put(pkg);
            }
            o.put(k, a);
        }
        return o;
    }

    private void importJson(JSONObject o) throws Exception {
        if (!"hibernate-settings-1".equals(o.optString("format"))) {
            throw new IllegalArgumentException("not a Hibernate settings file");
        }
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : Prefs.BOOLEAN_KEYS) {
            if (o.has(k)) {
                ed.putBoolean(k, o.getBoolean(k));
            }
        }
        for (String k : Prefs.INT_KEYS) {
            if (o.has(k)) {
                ed.putInt(k, o.getInt(k));
            }
        }
        for (String k : Prefs.SET_KEYS) {
            JSONArray a = o.optJSONArray(k);
            if (a != null) {
                Set<String> s = new HashSet<>();
                for (int i = 0; i < a.length(); i++) {
                    s.add(a.getString(i));
                }
                ed.putStringSet(k, s);
            }
        }
        ed.apply();
    }
}
