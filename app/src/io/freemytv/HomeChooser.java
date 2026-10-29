package io.freemytv;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The home screen chosen the Android way: nothing is turned off and nothing goes through ADB. Android itself asks which
 * home screen to use (its "default home app" request on Android 10 and newer, the Home app setting where the TV has
 * one, or the "Open with" choice Home shows when no app is the default), and the person picks.
 *
 * Many TVs give the Home button to their own launcher anyway (a system app may claim a higher priority than any app
 * can), so the accessibility service (RemoteKeyService, enabled by the person in Android's Accessibility settings) can
 * also send the Home button here, as LTvLauncher's "Home Button Fix" does. That service is on only while
 * {@link #homeButtonOn} says so: choosing Original turns it off without leaving Android's settings.
 */
final class HomeChooser {
    private HomeChooser() {}

    static final int REQUEST_ROLE = 4471;
    static final ComponentName HOME = new ComponentName(HomeMode.PKG, HomeMode.PKG + ".HomeActivity");
    private static final String SERVICE = HomeMode.PKG + "/" + HomeMode.PKG + ".RemoteKeyService";
    private static final String SERVICE_SHORT = HomeMode.PKG + "/.RemoteKeyService";

    /** Asks Android to make Free My TV the home screen. */
    static void ask(Activity a) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager roles = a.getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                        toast(a, Ui.tr("Free My TV is already the home screen."));
                        return;
                    }
                    Intent request = roles.createRequestRoleIntent(RoleManager.ROLE_HOME);
                    if (resolves(a, request)) {
                        a.startActivityForResult(request, REQUEST_ROLE);
                        return;
                    }
                }
            } catch (RuntimeException e) {
                AppLog.w("home role request: " + e.getMessage());
            }
        }
        fallback(a);
    }

    /** The role request came back: refused or not shown (some TVs have no such dialog), so the next way. */
    static void onResult(Activity a, int resultCode) {
        if (resultCode == Activity.RESULT_OK || isDefault(a)) {
            toast(a, Ui.tr("Free My TV is now the home screen."));
        } else {
            fallback(a);
        }
    }

    /** The TV's Home app setting, else the system's own "Open with" choice for Home. */
    private static void fallback(Activity a) {
        if (isDefault(a)) {
            toast(a, Ui.tr("Free My TV is already the home screen."));
            return;
        }
        for (String action : new String[] {Settings.ACTION_HOME_SETTINGS, "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"}) {
            Intent settings = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!resolves(a, settings)) continue;
            try {
                a.startActivity(settings);
                toast(a, Ui.tr("Pick Free My TV as the home app."));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("home settings: " + e.getMessage());
            }
        }
        // Home with no default: Android asks which app (pick Free My TV, then Always). With the TV's own launcher as
        // the default it just opens that one; then the Home button option (below) does the job.
        try {
            a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            toast(a, Ui.tr("If Android asks, pick Free My TV and then “Always”. If it doesn't, turn on the Home button option."));
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("home chooser: " + e.getMessage());
        }
    }

    /** Back to the TV's own home screen: ours stops being the preferred one and the TV's opens. */
    static void original(Activity a) {
        try {
            a.getPackageManager().clearPackagePreferredActivities(a.getPackageName());
        } catch (RuntimeException e) {
            AppLog.w("clear preferred home: " + e.getMessage());
        }
        Intent stock = stockHome(a);
        boolean roleHeld = false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                RoleManager roles = a.getSystemService(RoleManager.class);
                roleHeld = roles != null && roles.isRoleHeld(RoleManager.ROLE_HOME);
            } catch (RuntimeException ignored) {
            }
        }
        if (roleHeld) {
            // Android 10+: only the person can hand the role on, in the TV's settings (or the "Open with" choice).
            Intent settings = new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (resolves(a, settings)) {
                try {
                    a.startActivity(settings);
                    toast(a, Ui.tr("Pick the TV's own home app."));
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                }
            }
        }
        if (stock != null) {
            try {
                a.startActivity(stock);
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("stock home: " + e.getMessage());
            }
        }
    }

    /** The TV's own home screen (any HOME activity that isn't ours or Android's bare fallback), or null. */
    static Intent stockHome(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> list = pm.queryIntentActivities(home, 0);
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName, name = ri.activityInfo.name;
            if (pkg.equals(HomeMode.PKG) || name.contains("FallbackHome") || pkg.equals(HomeMode.GOOGLE_SETUP)) continue;
            return new Intent(home).setComponent(new ComponentName(pkg, name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    /** Is our home screen what Home opens on its own? */
    static boolean isDefault(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && HomeMode.PKG.equals(ri.activityInfo.packageName);
    }

    // MARK: - The Home button (RemoteKeyService)

    /** The person turned on our accessibility service in Android's settings. */
    static boolean serviceEnabled(Context c) {
        try {
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(list)) return false;
            for (String one : list.split(":")) if (one.equals(SERVICE) || one.equals(SERVICE_SHORT)) return true;
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Android's Accessibility settings, where the person turns the service on or off. */
    static void openAccessibility(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            toast(a, Ui.tr("Turn on “Free My TV” in the list: then Home always opens Free My TV."));
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(a, Ui.tr("This TV has no Accessibility settings screen."));
        }
    }

    /** With Original chosen the service leaves the Home button alone, even while it stays on in Android's settings. */
    private static File offFile(Context c) {
        return new File(c.getFilesDir(), "home-button-off");
    }

    /**
     * Off when Original was chosen (an empty file, for good), or for a short pause after our home screen crashed 3 times
     * (the file holds when the pause ends): then the TV's own launcher takes Home, and our home screen isn't opened
     * into a crash loop. The service reads this on Home and when a launcher comes to the front, never on other keys.
     */
    static boolean homeButtonOn(Context c) {
        File f = offFile(c);
        if (!f.exists()) return true;
        if (f.length() == 0) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[32];
            int n = in.read(b);
            long until = Long.parseLong(new String(b, 0, Math.max(0, n), java.nio.charset.StandardCharsets.US_ASCII).trim());
            if (System.currentTimeMillis() < until) return false;
            //noinspection ResultOfMethodCallIgnored
            f.delete();   // the pause is over
            return true;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    static void setHomeButton(Context c, boolean on) {
        File f = offFile(c);
        if (on) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } else {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.flush();   // empty: off until Free My TV is chosen again
            } catch (IOException e) {
                AppLog.w("home button: " + e.getMessage());
            }
        }
    }

    /** After 3 crashes in a row: the Home button goes to the TV's launcher for a while, then comes back by itself. */
    static void pauseHomeButton(Context c, long ms) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(offFile(c))) {
            out.write(String.valueOf(System.currentTimeMillis() + ms).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (IOException e) {
            AppLog.w("home button: " + e.getMessage());
        }
    }

    /**
     * The TV's own launchers (every app that answers Home but ours and Android's own screens): when one comes to the
     * front, the service opens ours again (RemoteKeyService).
     */
    static java.util.Set<String> otherHomes(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(HomeMode.PKG) || pkg.equals(HomeMode.GOOGLE_SETUP) || ri.activityInfo.name.contains("FallbackHome")
                        || pkg.equals("com.android.settings") || pkg.equals("com.android.tv.settings")
                        || pkg.equals("com.android.provision")) continue;
                out.add(pkg);
            }
        } catch (RuntimeException e) {
            AppLog.w("home screens: " + e.getMessage());
        }
        out.add(HomeMode.TV_LAUNCHER);
        out.add("com.google.android.apps.tv.launcherx");
        return out;
    }

    private static boolean resolves(Context c, Intent i) {
        return i.resolveActivity(c.getPackageManager()) != null;
    }

    private static void toast(Context c, String text) {
        Toast.makeText(c, text, Toast.LENGTH_LONG).show();
    }
}
