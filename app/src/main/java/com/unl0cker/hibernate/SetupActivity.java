package com.unl0cker.hibernate;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.unl0cker.hibernate.adb.AdbConnection;

import java.net.ConnectException;

/** One-time fast mode setup: permission, key authorization, test; plus accessibility. */
public class SetupActivity extends Activity {

    private static final int TCPIP_PORT = 5555;
    private static final int PROMPT_TIMEOUT_MS = 60_000;

    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        ((TextView) findViewById(R.id.title)).setText(R.string.setup_title);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.open_access).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        copyOnTap(R.id.cmd_1);
        copyOnTap(R.id.cmd_2);

        findViewById(R.id.authorize).setOnClickListener(v -> runTask(R.id.authorize,
                getString(R.string.setup_authorizing), this::authorize));
        findViewById(R.id.test).setOnClickListener(v -> runTask(R.id.test,
                getString(R.string.setup_testing), () -> {
                    try {
                        return getString(R.string.setup_test_ok, FastMode.test(this));
                    } catch (AdbConnection.NotAuthorizedException e) {
                        throw new Exception(getString(R.string.setup_test_rejected), e);
                    }
                }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean permission = FastMode.hasPermission(this);
        boolean authorized = Prefs.adbAuthorized(Prefs.get(this));
        boolean access = MainActivity.serviceEnabled(this);
        mark(1, permission);
        mark(2, authorized);
        mark(3, authorized && Prefs.fastVerified(Prefs.get(this)));
        mark(4, access);
        findViewById(R.id.open_access).setVisibility(access ? View.GONE : View.VISIBLE);
        findViewById(R.id.authorize).setEnabled(!busy && permission);
        findViewById(R.id.test).setEnabled(!busy && permission && authorized);
        findViewById(R.id.authorize).setAlpha(permission ? 1f : 0.4f);
        findViewById(R.id.test).setAlpha(permission && authorized ? 1f : 0.4f);
    }

    /**
     * Checks before acting: says so when the key already works, explains a missing
     * "adb tcpip 5555" instead of failing, and tests the key right after authorizing so a
     * plain "Allow" (without "Always allow") is caught at once.
     */
    private String authorize() throws Exception {
        if (Prefs.adbAuthorized(Prefs.get(this)) && Prefs.fastVerified(Prefs.get(this))) {
            return getString(R.string.setup_already_done);
        }
        boolean prompted;
        try {
            prompted = FastMode.authorize(this, TCPIP_PORT, PROMPT_TIMEOUT_MS);
        } catch (ConnectException e) {
            // Nothing listens on 5555: adb is not in TCP mode (any more).
            if (Prefs.adbAuthorized(Prefs.get(this))) {
                return getString(R.string.setup_already_authorized);
            }
            throw new Exception(getString(R.string.setup_need_tcpip), e);
        }
        if (!FastMode.onWifi(this)) {
            return getString(prompted ? R.string.setup_authorized_no_wifi
                    : R.string.setup_was_authorized);
        }
        // adbd restarts in USB mode after authorizing; give it a moment.
        Thread.sleep(2000);
        try {
            FastMode.test(this);
        } catch (AdbConnection.NotAuthorizedException e) {
            throw new Exception(getString(R.string.setup_not_kept), e);
        }
        return getString(prompted ? R.string.setup_authorized_tested
                : R.string.setup_was_authorized_tested);
    }

    private void mark(int step, boolean ok) {
        int dot = getResources().getIdentifier("dot_" + step, "id", getPackageName());
        int state = getResources().getIdentifier("state_" + step, "id", getPackageName());
        ((ImageView) findViewById(dot)).setImageTintList(
                ColorStateList.valueOf(getColor(ok ? R.color.ok : R.color.warn)));
        ((TextView) findViewById(state)).setText(ok ? R.string.setup_done : R.string.setup_missing);
    }

    private void copyOnTap(int id) {
        TextView view = findViewById(id);
        view.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(
                    ClipData.newPlainText("adb", view.getText()));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
    }

    private interface Task {
        String run() throws Exception;
    }

    private void runTask(int buttonId, String waiting, Task task) {
        if (busy) {
            return;
        }
        busy = true;
        Button button = findViewById(buttonId);
        TextView result = findViewById(getResources().getIdentifier(
                getResources().getResourceEntryName(buttonId) + "_result", "id", getPackageName()));
        result.setVisibility(View.VISIBLE);
        result.setTextColor(getColor(R.color.text_secondary));
        result.setText(waiting);
        refresh();
        new Thread(() -> {
            String text;
            boolean ok;
            try {
                text = task.run();
                ok = true;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                text = getString(R.string.error_fmt, msg);
                ok = false;
            }
            String finalText = text;
            boolean finalOk = ok;
            runOnUiThread(() -> {
                busy = false;
                if (isDestroyed()) {
                    return;
                }
                result.setTextColor(getColor(finalOk ? R.color.ok : R.color.warn));
                result.setText(finalText);
                button.setEnabled(true);
                refresh();
            });
        }, "setup").start();
    }
}
