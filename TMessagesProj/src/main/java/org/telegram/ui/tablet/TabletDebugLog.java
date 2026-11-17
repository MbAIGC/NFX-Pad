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

    /**
     * On while the tablet work is being verified on a device. Flip to false (or delete this class and its
     * call sites) before any build that is handed to someone else: the lines are cheap but they describe
     * the user's navigation. No user content is ever logged - see {@link #redact(CharSequence)}.
     */
    public static final boolean ENABLED = true;

    private TabletDebugLog() {
    }

    public static void d(String message) {
        if (ENABLED) {
            Log.d(TAG, message);
        }
    }

    /**
     * Never put user content (search keywords, hashtags, message text, ids of real peers) into a log line.
     * A shape descriptor is enough to diagnose state transitions and cannot be turned back into the text.
     */
    public static String redact(CharSequence value) {
        if (value == null) {
            return "null";
        }
        final int n = value.length();
        return n == 0 ? "empty" : ("len=" + n);
    }

    public static void d(String area, String message) {
        if (ENABLED) {
            Log.d(TAG, area + " | " + message);
        }
    }
}
