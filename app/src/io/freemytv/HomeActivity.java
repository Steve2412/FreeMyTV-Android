package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
    }

    @Override protected void onResume() {
        super.onResume();
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        Updates.tellOnce(this);   // an update the week's check got ready: said once
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
        else home.reset(true);
    }

    // MARK: - Change icon (the app menu): a picture from the gallery or a file manager

    private static final int PICK_ICON = 7301;

    /** Asks for a picture for this app's icon; where the TV has nothing that picks pictures, says what to install. */
    void pickIcon(String pkg, String label) {
        getSharedPreferences("home-ui", MODE_PRIVATE).edit().putString("iconFor", pkg).putString("iconLabel", label).apply();
        Intent[] ways = {
            new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
            new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
            new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
        };
        for (Intent way : ways) {
            if (way.resolveActivity(getPackageManager()) == null) continue;
            try {
                startActivityForResult(way, PICK_ICON);
                return;
            } catch (RuntimeException e) {
                AppLog.w("icon picker: " + e.getMessage());
            }
        }
        Toast.makeText(this, Ui.tr("This TV has no app to pick pictures. Install a file manager (X-plore, for example) and try again."),
                Toast.LENGTH_LONG).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ICON || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.content.SharedPreferences ui = getSharedPreferences("home-ui", MODE_PRIVATE);
        final String pkg = ui.getString("iconFor", null), label = ui.getString("iconLabel", "");
        if (pkg == null) return;
        final android.net.Uri uri = data.getData();
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok = HomeApps.saveCustomIcon(app, pkg, uri);
            runOnUiThread(() -> {
                String text = ok ? Ui.trf("New icon: {app}", "app", label) : Ui.tr("Couldn't read that picture.");
                if (home != null) home.iconChanged(text);
                else Toast.makeText(app, text, Toast.LENGTH_LONG).show();
            });
        }, "custom-icon").start();
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
