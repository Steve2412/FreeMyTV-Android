package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest, and
 * the Home button always opens Free My TV (RemoteKeys.home). The system waits for it on every key (up to half a
 * second), so it runs in a process of its own and does next to nothing. The person turns it on in Android's
 * Accessibility settings; this build never turns it on or off through ADB.
 *
 * Taking the Home key is not always enough: some TVs (CVTE and MediaTek boards with their own hot-key dispatcher)
 * still open their own launcher after it, or a moment later, and leaving an app with Back lands on the TV's default
 * launcher, which stays the TV's own. So whenever one of the TV's launchers comes to the front while Free My TV is the
 * chosen home screen, ours is opened again over it (the only window event this service reads: which app came up).
 */
public class RemoteKeyService extends AccessibilityService {
    /** Connected this soon after the TV started: the TV just booted, so its own launcher is on screen. */
    private static final long JUST_BOOTED_MS = 4 * 60_000L;
    /** Never two openings closer than this: one bounce per time the TV's launcher shows up. */
    private static final long MIN_GAP_MS = 700;

    private final Set<String> otherHomes = new HashSet<>();
    private long homesAt;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.notificationTimeout = 50;
            setServiceInfo(info);
        }
        refreshHomes();
        // After a boot the TV opens its own default home screen: open ours over it (an accessibility service may start
        // an activity from the background). Not when the person just turned the service on in the settings.
        if (SystemClock.elapsedRealtime() < JUST_BOOTED_MS && HomeChooser.homeButtonOn(this) && !HomeChooser.isDefault(this)) {
            RemoteKeys.openHome(this);
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || pkg.toString().equals(HomeMode.PKG)) return;
        long now = SystemClock.uptimeMillis();
        if (now - homesAt > 10 * 60_000L) refreshHomes();   // a launcher installed or updated meanwhile
        if (!otherHomes.contains(pkg.toString())) return;
        if (now - RemoteKeys.openedAt < MIN_GAP_MS) return;
        if (!HomeChooser.homeButtonOn(this)) return;   // Original chosen, or the pause after crashes
        AppLog.w("the TV's launcher came up (" + pkg + "): opening Free My TV again");
        RemoteKeys.openHome(this);
    }

    private void refreshHomes() {
        otherHomes.clear();
        otherHomes.addAll(HomeChooser.otherHomes(this));
        homesAt = SystemClock.uptimeMillis();
        AppLog.w("the TV's launchers: " + otherHomes);
    }

    @Override public void onInterrupt() {}
}
