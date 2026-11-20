package com.unl0cker.hibernate;

import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sorts installed apps into awake (launched since their last stop) and hibernated (stopped),
 * and decides which awake ones are hibernation targets.
 */
final class AppScanner {

    /** Never hibernated, whatever the settings: stopping them breaks the phone or Hibernate. */
    private static final String[] CORE = {
            "android", "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.server.telecom", "com.android.shell"
    };

    static final class Entry {
        final String pkg;
        final String label;
        final boolean system;
        final boolean stopped;
        /** 0 if this app is a target; otherwise a string resource saying why not. */
        final int reason;

        Entry(String pkg, String label, boolean system, boolean stopped, int reason) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
            this.stopped = stopped;
            this.reason = reason;
        }

        boolean isTarget() {
            return !stopped && reason == 0;
        }
    }

    static final class Snapshot {
        final List<Entry> awake = new ArrayList<>();
        final List<Entry> asleep = new ArrayList<>();
        final List<Entry> failed = new ArrayList<>();
        int targets;
    }

    private static final Map<String, Drawable> ICONS = new ConcurrentHashMap<>();

    private AppScanner() {
    }

    static Drawable icon(Context context, String pkg) {
        Drawable icon = ICONS.get(pkg);
        if (icon == null) {
            PackageManager pm = context.getPackageManager();
            try {
                icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                icon = pm.getDefaultActivityIcon();
            }
            ICONS.put(pkg, icon);
        }
        return icon;
    }

    static String label(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static boolean isStopped(Context context, String pkg) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            return (info.flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    /** Packages to hibernate now, in list order. */
    static List<String> targets(Context context) {
        List<String> out = new ArrayList<>();
        for (Entry e : scan(context, false).awake) {
            if (e.isTarget()) {
                out.add(e.pkg);
            }
        }
        return out;
    }

    static Snapshot scan(Context context, boolean loadIcons) {
        PackageManager pm = context.getPackageManager();
        SharedPreferences prefs = Prefs.get(context);
        boolean users = Prefs.userApps(prefs);
        boolean systems = Prefs.systemApps(prefs);
        boolean onlyList = Prefs.onlyList(prefs);
        Set<String> list = Prefs.set(prefs, Prefs.KEY_LIST);
        Set<String> exceptions = Prefs.set(prefs, Prefs.KEY_EXCEPTIONS);
        Set<String> failed = Prefs.set(prefs, Prefs.KEY_FAILED);
        Map<String, Integer> guarded = protectedApps(context);
        Set<String> launchable = launchablePackages(pm);

        Snapshot snap = new Snapshot();
        for (ApplicationInfo info : pm.getInstalledApplications(0)) {
            String pkg = info.packageName;
            if (pkg.equals(context.getPackageName()) || !info.enabled) {
                continue;
            }
            boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            // System apps without a launcher icon are services: never offered.
            if (system ? !(systems && launchable.contains(pkg)) : !users) {
                continue;
            }
            boolean stopped = (info.flags & ApplicationInfo.FLAG_STOPPED) != 0;
            int reason = 0;
            Integer guard = guarded.get(pkg);
            if (guard != null) {
                reason = guard;
            } else if (exceptions.contains(pkg)) {
                reason = R.string.reason_exception;
            } else if (onlyList && !list.contains(pkg)) {
                reason = R.string.reason_not_in_list;
            }
            Entry entry = new Entry(pkg, pm.getApplicationLabel(info).toString(), system, stopped, reason);
            if (loadIcons) {
                icon(context, pkg);
            }
            (stopped ? snap.asleep : snap.awake).add(entry);
            if (entry.isTarget()) {
                snap.targets++;
            }
            if (failed.contains(pkg) && !stopped) {
                snap.failed.add(entry);
            }
        }

        Collator collator = Collator.getInstance();
        Comparator<Entry> byLabel = Comparator.comparing((Entry e) -> e.label, collator);
        snap.awake.sort(Comparator.comparing((Entry e) -> !e.isTarget()).thenComparing(byLabel));
        snap.asleep.sort(byLabel);
        snap.failed.sort(byLabel);
        return snap;
    }

    private static Set<String> launchablePackages(PackageManager pm) {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> out = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            out.add(ri.activityInfo.packageName);
        }
        return out;
    }

    /** Apps that must keep running, with the reason shown in the list. */
    static Map<String, Integer> protectedApps(Context context) {
        Map<String, Integer> out = new HashMap<>();
        for (String pkg : CORE) {
            out.put(pkg, R.string.reason_system);
        }
        PackageManager pm = context.getPackageManager();

        // Accessibility services and notification listeners: a force stop turns them off.
        for (String pkg : packagesIn(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)) {
            out.put(pkg, R.string.reason_accessibility);
        }
        for (String pkg : packagesIn(context, "enabled_notification_listeners")) {
            out.putIfAbsent(pkg, R.string.reason_notification_access);
        }

        String ime = secure(context, Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null) {
            ComponentName cn = ComponentName.unflattenFromString(ime);
            if (cn != null) {
                out.put(cn.getPackageName(), R.string.reason_keyboard);
            }
        }

        ResolveInfo home = pm.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null) {
            out.put(home.activityInfo.packageName, R.string.reason_launcher);
        }

        try {
            TelecomManager telecom = context.getSystemService(TelecomManager.class);
            if (telecom != null && telecom.getDefaultDialerPackage() != null) {
                out.put(telecom.getDefaultDialerPackage(), R.string.reason_phone);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(context);
            if (sms != null) {
                out.put(sms, R.string.reason_sms);
            }
        } catch (RuntimeException ignored) {
        }

        try {
            WallpaperManager wm = WallpaperManager.getInstance(context);
            if (wm.getWallpaperInfo() != null) {
                out.put(wm.getWallpaperInfo().getPackageName(), R.string.reason_wallpaper);
            }
        } catch (RuntimeException ignored) {
        }

        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        List<ComponentName> admins = dpm != null ? dpm.getActiveAdmins() : null;
        if (admins != null) {
            for (ComponentName admin : admins) {
                out.putIfAbsent(admin.getPackageName(), R.string.reason_device_admin);
            }
        }
        return out;
    }

    private static String secure(Context context, String key) {
        try {
            return Settings.Secure.getString(context.getContentResolver(), key);
        } catch (RuntimeException e) {
            // Newer Android versions refuse non-public keys to apps; treat as unknown.
            return null;
        }
    }

    private static List<String> packagesIn(Context context, String key) {
        List<String> out = new ArrayList<>();
        String value = secure(context, key);
        if (value == null || value.isEmpty()) {
            return out;
        }
        for (String entry : value.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(entry);
            if (cn != null) {
                out.add(cn.getPackageName());
            }
        }
        return out;
    }
}
