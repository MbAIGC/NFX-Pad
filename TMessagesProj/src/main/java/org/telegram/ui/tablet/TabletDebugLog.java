package org.telegram.ui.tablet;

import android.util.Log;

/**
 * Temporary diagnostics for the Enhanced Tablet Mode work. Everything goes through this class so the
 * whole trail can be switched off (or deleted) in one place once the behaviour has been verified.
 *
 * <p>Visible in logcat as {@code NFX-Pad: ...}. Deliberately not routed through FileLog: release builds
 * ship with logging disabled, and these lines are exactly what a tester needs.
 */
public final class TabletDebugLog {

    public static final String TAG = "NFX-Pad";
    public static final boolean ENABLED = true;

    private TabletDebugLog() {
    }

    public static void d(String message) {
        if (ENABLED) {
            Log.d(TAG, message);
        }
    }

    public static void d(String area, String message) {
        if (ENABLED) {
            Log.d(TAG, area + " | " + message);
        }
    }
}
