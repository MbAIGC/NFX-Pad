package org.telegram.ui.tablet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostics for the Enhanced Tablet Mode work.
 *
 * <p>Two rules this class exists to enforce:
 * <ol>
 *     <li><b>No user content in a log line.</b> Search keywords, hashtags, message text, peer or message
 *     ids must go through {@link #redact(CharSequence)}. A shape descriptor (length) is enough to follow a
 *     state transition and cannot be turned back into what the user typed.</li>
 *     <li><b>The trail must be obtainable without a computer.</b> Lines go to logcat and to a small ring
 *     file in the cache dir that can be shared from the settings screen, so a device without adb can
 *     still produce a report.</li>
 * </ol>
 *
 * <p>{@link #ENABLED} is on while the feature is being verified. Turn it off (or delete this class and its
 * call sites) before a build leaves the project.
 */
public final class TabletDebugLog {

    public static final String TAG = "NFX-Pad";

    /** On while the tablet work is being verified on a device. */
    public static final boolean ENABLED = true;

    /** Also mirror to the shareable file. */
    private static final boolean FILE_ENABLED = true;

    private static final String FILE_NAME = "nfxp-diag.log";
    private static final long MAX_BYTES = 512 * 1024L;

    /**
     * Areas that are recorded. Only what the two open questions need: how a back press is routed, which
     * fragment consumed it, whether a search session is alive, and the video control inset numbers.
     * Anything else stays out of the file so an export is short and readable.
     */
    public static final boolean AREA_BACK = true;
    public static final boolean AREA_ROUTE = true;
    public static final boolean AREA_ABL = true;
    public static final boolean AREA_CHAT = true;
    public static final boolean AREA_MEDIA = true;

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static boolean headerWritten;

    private TabletDebugLog() {
    }

    public static void d(String message) {
        write("diag", message);
    }

    public static void d(String area, String message) {
        write(area, message);
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

    private static boolean isAreaEnabled(String area) {
        return switch (area) {
            case "back" -> AREA_BACK;
            case "route" -> AREA_ROUTE;
            case "abl" -> AREA_ABL;
            case "chat" -> AREA_CHAT;
            case "media" -> AREA_MEDIA;
            default -> false;
        };
    }

    private static void write(String area, String message) {
        if (!ENABLED || !isAreaEnabled(area)) {
            return;
        }
        final String line = area + " | " + message;
        Log.d(TAG, line);
        if (FILE_ENABLED) {
            appendToFile(line);
        }
    }

    private static synchronized void appendToFile(String line) {
        try {
            final File file = logFile();
            if (file == null) {
                return;
            }
            if (file.length() > MAX_BYTES) {
                final File previous = new File(file.getParentFile(), FILE_NAME + ".1");
                if (previous.exists() && !previous.delete()) {
                    // keep going with the current file rather than growing without a bound
                }
                file.renameTo(previous);
            }
            final FileWriter writer = new FileWriter(file, true);
            try {
                if (!headerWritten) {
                    headerWritten = true;
                    writer.write("# NFX-Pad diagnostics\n");
                    writer.write("# sdk=" + Build.VERSION.SDK_INT
                            + " device=" + Build.MANUFACTURER + " " + Build.MODEL
                            + " package=" + BuildConfig.APPLICATION_ID + "\n");
                }
                writer.write(TIME.format(new Date()) + "  " + line + "\n");
            } finally {
                writer.close();
            }
        } catch (Throwable ignore) {
            // diagnostics must never break the app
        }
    }

    private static File logFile() {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return new File(context.getCacheDir(), FILE_NAME);
    }

    /** True when there is something worth sharing. */
    public static boolean hasLog() {
        final File file = logFile();
        return file != null && file.exists() && file.length() > 0;
    }

    public static File getLogFileForTesting() {
        return logFile();
    }

    /**
     * Hands the log to the system share sheet. The cache dir is already exposed by the app FileProvider
     * ({@code cache-path name="cache_root" path="."}), so no new provider path or permission is needed.
     *
     * @return false when there is nothing to share
     */
    public static boolean share(Activity activity) {
        if (activity == null || !hasLog()) {
            return false;
        }
        try {
            final File file = logFile();
            final Uri uri = FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".provider", file);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, "NFX-Pad diagnostics"));
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }
}
