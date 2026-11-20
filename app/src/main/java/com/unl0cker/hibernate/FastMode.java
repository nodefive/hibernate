package com.unl0cker.hibernate;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;

import com.unl0cker.hibernate.adb.AdbConnection;
import com.unl0cker.hibernate.adb.AdbDiscovery;
import com.unl0cker.hibernate.adb.AdbKey;

import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Stops apps through the phone's own wireless debugging: instant, invisible, works with the
 * screen off. Needs Wi-Fi, WRITE_SECURE_SETTINGS (to switch wireless debugging) and this app's
 * adb key authorized once (see SetupActivity).
 */
final class FastMode {

    enum Status { DISABLED, NO_PERMISSION, NOT_AUTHORIZED, NO_WIFI, READY }

    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";
    private static final String ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time";
    private static final long DISCOVERY_TIMEOUT_MS = 25_000;
    private static final long RECHECK_TIMEOUT_MS = 8_000;
    private static final String KEY_LAST_PORT = "adb_last_port";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");

    interface Progress {
        void step(String pkg);
    }

    private FastMode() {
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean onWifi(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return true;
            }
        }
        return false;
    }

    static Status status(Context context) {
        SharedPreferences prefs = Prefs.get(context);
        if (!Prefs.fastEnabled(prefs)) {
            return Status.DISABLED;
        }
        if (!hasPermission(context)) {
            return Status.NO_PERMISSION;
        }
        if (!Prefs.adbAuthorized(prefs)) {
            return Status.NOT_AUTHORIZED;
        }
        if (!onWifi(context)) {
            return Status.NO_WIFI;
        }
        return Status.READY;
    }

    /** Force-stops each package; calls progress after each one. Not on the main thread. */
    static void run(Context context, List<String> packages, Progress progress,
                    BooleanSupplier cancelled) throws Exception {
        withConnection(context, connection -> {
            for (String pkg : packages) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                if (PACKAGE.matcher(pkg).matches()) {
                    connection.exec("am force-stop " + pkg);
                }
                progress.step(pkg);
            }
        });
    }

    /** Connects and runs a harmless command. Returns the wireless debugging port. */
    static int test(Context context) throws Exception {
        int[] port = new int[1];
        withConnection(context, connection -> {
            String out = connection.exec("echo ok").trim();
            if (!"ok".equals(out)) {
                throw new IllegalStateException("unexpected answer: " + out);
            }
        }, p -> port[0] = p);
        return port[0];
    }

    private interface Body {
        void run(AdbConnection connection) throws Exception;
    }

    private interface PortSink {
        void accept(int port);
    }

    private static void withConnection(Context context, Body body) throws Exception {
        withConnection(context, body, p -> { });
    }

    /**
     * Turns wireless debugging on if needed (Hibernate's accessibility service presses Allow
     * if the phone asks about the network), connects, runs body, then restores the setting.
     */
    private static void withConnection(Context context, Body body, PortSink portSink)
            throws Exception {
        ContentResolver cr = context.getContentResolver();
        boolean wasOn = Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 1;
        try {
            if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 1) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
            }
            // Never let the phone forget this app's key after a few idle days.
            Settings.Global.putLong(cr, ADB_ALLOWED_CONNECTION_TIME, 0L);
            SharedPreferences prefs = Prefs.get(context);
            AdbConnection connection = null;
            int port = prefs.getInt(KEY_LAST_PORT, 0);
            // The port stays the same while wireless debugging stays on: try the last one first.
            if (wasOn && port > 0) {
                connection = tryOpen(context, port);
            }
            if (connection == null) {
                try {
                    // Turning on (or, if it was already on, just listening) announces the port.
                    port = AdbDiscovery.findLocalPort(context,
                            wasOn ? RECHECK_TIMEOUT_MS : DISCOVERY_TIMEOUT_MS,
                            wasOn ? null : () -> turnOn(cr));
                } catch (TimeoutException e) {
                    // No announcement: switch it off and on again while listening.
                    Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 0);
                    SystemClock.sleep(1000);
                    port = AdbDiscovery.findLocalPort(context, DISCOVERY_TIMEOUT_MS,
                            () -> turnOn(cr));
                }
                connection = tryOpen(context, port);
                if (connection == null) {
                    // adbd sometimes drops a connection made right after it starts listening.
                    SystemClock.sleep(500);
                    connection = AdbConnection.open(AdbKey.get(context), port, 0);
                }
            }
            prefs.edit().putInt(KEY_LAST_PORT, port).putBoolean(Prefs.KEY_FAST_VERIFIED, true).apply();
            portSink.accept(port);
            try (AdbConnection c = connection) {
                body.run(c);
            }
        } catch (AdbConnection.NotAuthorizedException e) {
            // Usually "Allow" was tapped without "Always allow", or the keys were revoked.
            Prefs.get(context).edit().putBoolean(Prefs.KEY_ADB_AUTHORIZED, false)
                    .putBoolean(Prefs.KEY_FAST_VERIFIED, false).apply();
            throw e;
        } finally {
            CloseService.armWifiAllow(0);
            if (!wasOn && Prefs.wifiOffAfter(Prefs.get(context))) {
                Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 0);
            }
        }
    }

    private static void turnOn(ContentResolver cr) {
        // If the phone asks about this network, the accessibility service presses Allow.
        CloseService.armWifiAllow(DISCOVERY_TIMEOUT_MS);
        Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1);
    }

    /** Opens a connection; null if nothing usable answers there (not an authorization error). */
    private static AdbConnection tryOpen(Context context, int port) throws Exception {
        try {
            return AdbConnection.open(AdbKey.get(context), port, 0);
        } catch (AdbConnection.NotAuthorizedException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One-time authorization: with adbd in "adb tcpip 5555" mode, offer this app's key so the
     * phone shows "Allow USB debugging?". Afterwards adbd goes back to USB mode.
     */
    static boolean authorize(Context context, int port, int promptMs) throws Exception {
        try (AdbConnection connection = AdbConnection.open(AdbKey.get(context), port, promptMs)) {
            Prefs.get(context).edit().putBoolean(Prefs.KEY_ADB_AUTHORIZED, true)
                    .putBoolean(Prefs.KEY_FAST_VERIFIED, false).apply();
            try {
                connection.service("usb:");
            } catch (Exception ignored) {
                // adbd restarts in USB mode and drops the connection; that is expected.
            }
            return connection.prompted();
        }
    }
}
