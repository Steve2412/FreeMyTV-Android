package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest, and
 * the Home button always opens Free My TV (RemoteKeys.home). The system waits for it on every key (up to half a
 * second), so it runs in a process of its own and does next to nothing. The person turns it on in Android's
 * Accessibility settings; this build never turns it on or off through ADB.
 */
public class RemoteKeyService extends AccessibilityService {
    /** Connected this soon after the TV started: the TV just booted, so its own launcher is on screen. */
    private static final long JUST_BOOTED_MS = 4 * 60_000L;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        // After a boot the TV opens its own default home screen: open ours over it (an accessibility service may start
        // an activity from the background). Not when the person just turned the service on in the settings.
        if (SystemClock.elapsedRealtime() < JUST_BOOTED_MS && HomeChooser.homeButtonOn(this) && !HomeChooser.isDefault(this)) {
            RemoteKeys.openHome(this);
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
