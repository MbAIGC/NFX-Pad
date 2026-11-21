package org.telegram.utils.pip;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.app.PictureInPictureParams;
import android.app.PictureInPictureUiState;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.media3.session.MediaSession;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.utils.WindowVisibilityManager;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * System PiP of the whole activity. At most one session at a time.
 * Owns PictureInPictureParams while it has a candidate; otherwise resets them.
 */
final class SystemHost {
    private static final float MIN_RATIO = 0.45f;
    private static final float MAX_RATIO = 2.35f;

    private final PipCoordinator coordinator;

    private @Nullable Activity activity;
    private @Nullable SystemPipLayout layout;
    private @Nullable WindowVisibilityManager.Controller contentVisibility;

    private @Nullable PipSession candidate;
    private @Nullable PipSession active;
    private @Nullable PipTextureView activeTarget;
    private @Nullable View activeSystemView;
    private boolean isInPip;
    private boolean isOwner;

    private @Nullable MediaSession mediaSession;

    private final Rect tmpRect = new Rect();
    private final RectF tmpRectF = new RectF();
    private final int[] tmpLocation = new int[2];

    /**
     * The system draws an action into a box of its own fixed size, and an icon drawn in its own
     * 24dp canvas keeps padding the glyph cannot use: the app's only lever is what the icon
     * itself paints. Icons are re-rendered one box size up with the glyph filling most of it.
     */
    private static final int ACTION_ICON_BOX_DP = 32;
    private static final float ACTION_ICON_FILL = 1f;

    /** Rendered action icons by key ("r:<res>" or "t:<value>"); the params are rebuilt often. */
    private final HashMap<String, Icon> actionIcons = new HashMap<>();

    SystemHost(PipCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    void attach(@NonNull Activity activity, @Nullable WindowVisibilityManager.Controller contentVisibility) {
        this.activity = activity;
        this.contentVisibility = contentVisibility;
        appliedParamsKey = null;
        layout = new SystemPipLayout(activity);
        ((ViewGroup) activity.getWindow().getDecorView()).addView(layout);
    }

    void detach() {
        releaseMediaSession();
        actionIcons.clear();
        actionIntents.clear();
        if (receiverRegistered && activity != null) {
            receiverRegistered = false;
            try {
                activity.unregisterReceiver(actionReceiver);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (layout != null) {
            AndroidUtilities.removeFromParent(layout);
            layout = null;
        }
        if (contentVisibility != null) {
            contentVisibility.destroy();
            contentVisibility = null;
        }
        activity = null;
        candidate = null;
        active = null;
        activeTarget = null;
        isOwner = false;
    }

    boolean isOwner() {
        return isOwner;
    }

    /* Params */

    void update() {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        if (active != null) {
            // not while entering: the system reads the params during its enter transition, and a
            // change then (auto-enter off, a hint taken from the shrinking window) cancels its animation
            if (!entering) {
                applyParams(active);
            }
            updateMediaSession(active);
            return;
        }
        candidate = findCandidate();
        if (candidate != null) {
            isOwner = true;
            applyParams(candidate);
        } else if (isOwner) {
            isOwner = false;
            appliedParamsKey = null;
            AndroidUtilities.resetPictureInPictureParams(activity);
        }
        updateMediaSession(candidate);
    }

    /**
     * Content shown in its primary UI wins (the user is looking at it); otherwise an in-app
     * floating window with the highest priority, the most recent on a tie.
     */
    private @Nullable PipSession findCandidate() {
        if (!PipEnvironment.canUseSystemPip(activity) || PipEnvironment.canDrawOverlays(activity)) {
            return null;
        }
        final ArrayList<PipSession> sessions = coordinator.getSessions();
        for (int i = sessions.size() - 1; i >= 0; i--) {
            final PipSession session = sessions.get(i);
            if (session.state == PipSession.STATE_PRIMARY && session.content.getFloatingView() == null && session.content.isPrimaryVisible() && session.content.needsPipOutsideApp()) {
                return session;
            }
        }
        PipSession best = null;
        for (int i = sessions.size() - 1; i >= 0; i--) {
            final PipSession session = sessions.get(i);
            if (session.state != PipSession.STATE_FLOATING || !session.content.needsPipOutsideApp()) {
                continue;
            }
            final boolean inAppWindow;
            if (session.floating != null) {
                inAppWindow = !session.floating.isOverlay() && !session.floating.hasFloatingView();
            } else {
                inAppWindow = session.ownFloating && !session.ownFloatingOverlay;
            }
            if (inAppWindow && (best == null || session.content.getPriority() > best.content.getPriority())) {
                best = session;
            }
        }
        if (best != null) {
            return best;
        }
        for (int i = sessions.size() - 1; i >= 0; i--) {
            final PipSession session = sessions.get(i);
            if (session.state == PipSession.STATE_PRIMARY && session.content.canEnterWithoutUi() && session.content.needsPipOutsideApp()
                && (best == null || session.content.getPriority() > best.content.getPriority())) {
                best = session;
            }
        }
        return best;
    }

    /** What was set last: the same params are not sent again (the hint is refreshed often). */
    private @Nullable String appliedParamsKey;

    /**
     * The params are rebuilt every time the content says something changed, which is on every
     * progress tick. Building them is not free (three icons, three pending intents), so the key is
     * computed first and the params are only built when the key really changed.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void applyParams(@NonNull PipSession session) {
        final String key = buildParamsKey(session);
        if (key.equals(appliedParamsKey)) {
            return;
        }
        final PictureInPictureParams params = buildParams(session);
        appliedParamsKey = key;
        AndroidUtilities.setPictureInPictureParams(activity, params);
    }

    /** Between the start of entering system PiP and PiP mode reported by the activity. */
    private boolean entering;

    /** Source rect hint taken while the content was shown in its primary UI or floating window. */
    private final Rect lastHint = new Rect();
    private boolean lastHintValid;

    private final StringBuilder keyBuilder = new StringBuilder();

    /**
     * The same inputs {@link #buildParams} uses, in the same order, without building anything: what
     * decides whether the system has to be told again.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private String buildParamsKey(@NonNull PipSession session) {
        keyBuilder.setLength(0);
        final int width = session.content.getContentWidth();
        final int height = session.content.getContentHeight();
        if (width > 0 && height > 0) {
            final Rational rational = getAspectRatio(width, height);
            keyBuilder.append(rational);
        }
        // exactly what buildParams does with the hint: measured while the content is not in the
        // system window yet, and otherwise the one kept from before (it does not move there)
        if (session.state != PipSession.STATE_SYSTEM) {
            if (getSourceRect(session, tmpRect)) {
                keyBuilder.append('|').append(tmpRect.toShortString());
            }
        } else if (lastHintValid) {
            keyBuilder.append('|').append(lastHint.toShortString());
        }
        actionsTmp.clear();
        session.content.getSystemActions(actionsTmp, activity.getMaxNumPictureInPictureActions());
        for (int i = 0; i < actionsTmp.size(); i++) {
            final PipAction action = actionsTmp.get(i);
            keyBuilder.append('|').append(action.id).append(action.iconRes).append(action.enabled);
            if (action.iconText != null) {
                keyBuilder.append('"').append(action.arrow).append(action.iconText);
            }
        }
        actionsTmp.clear();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            keyBuilder.append('|').append(PipEnvironment.useAutoEnter() && session.state != PipSession.STATE_SYSTEM);
        }
        return keyBuilder.toString();
    }

    private static @NonNull Rational getAspectRatio(int width, int height) {
        final float ratio = width / (float) height;
        if (ratio < MIN_RATIO) {
            return new Rational(45, 100);
        } else if (ratio > MAX_RATIO) {
            return new Rational(235, 100);
        }
        return new Rational(width, height);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private PictureInPictureParams buildParams(@NonNull PipSession session) {
        // once per change now, not once per tick: the key is checked before this is called
        PipCoordinator.log("params changed, content=" + session.content.getClass().getSimpleName()
                + " state=" + session.state);
        final PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        final int width = session.content.getContentWidth();
        final int height = session.content.getContentHeight();
        if (width > 0 && height > 0) {
            builder.setAspectRatio(getAspectRatio(width, height));
        }
        // the primary UI is gone in PiP: keep the hint taken before, the system may read the
        // params again while the enter transition starts (an empty hint cancels its animation)
        if (session.state != PipSession.STATE_SYSTEM) {
            lastHintValid = getSourceRect(session, lastHint);
        }
        if (lastHintValid) {
            builder.setSourceRectHint(lastHint);
        }
        builder.setActions(buildActions(session));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(PipEnvironment.useAutoEnter() && session.state != PipSession.STATE_SYSTEM);
        }
        return builder.build();
    }

    /* Actions */

    private static final String ACTION_INTENT = "org.telegram.utils.pip.ACTION";
    private static final String EXTRA_ACTION_ID = "action_id";

    private final ArrayList<PipAction> actionsTmp = new ArrayList<>();
    /** The pending intent per action id: it never changes, and building one is a binder call. */
    private final HashMap<Integer, PendingIntent> actionIntents = new HashMap<>();
    private boolean receiverRegistered;

    private final BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_INTENT.equals(intent.getAction())) {
                return;
            }
            final PipSession session = active != null ? active : candidate;
            if (session != null) {
                session.content.onSystemAction(intent.getIntExtra(EXTRA_ACTION_ID, -1));
            }
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.O)
    private ArrayList<RemoteAction> buildActions(@NonNull PipSession session) {
        final ArrayList<RemoteAction> result = new ArrayList<>();
        actionsTmp.clear();
        session.content.getSystemActions(actionsTmp, activity.getMaxNumPictureInPictureActions());
        for (int i = 0; i < actionsTmp.size(); i++) {
            final PipAction action = actionsTmp.get(i);
            PendingIntent pendingIntent = actionIntents.get(action.id);
            if (pendingIntent == null) {
                final Intent intent = new Intent(ACTION_INTENT);
                intent.setPackage(activity.getPackageName());
                intent.putExtra(EXTRA_ACTION_ID, action.id);
                // the request code is the action id: the same PendingIntent is reused, not piled up
                pendingIntent = PendingIntent.getBroadcast(activity, action.id, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                actionIntents.put(action.id, pendingIntent);
            }
            final Icon icon = action.iconText != null
                    ? actionTextIcon(activity, action.iconText, action.arrow)
                    : actionIcon(activity, action.iconRes);
            final RemoteAction remoteAction = new RemoteAction(icon, action.title, action.title, pendingIntent);
            remoteAction.setEnabled(action.enabled);
            result.add(remoteAction);
        }
        actionsTmp.clear();
        if (!result.isEmpty() && !receiverRegistered) {
            receiverRegistered = true;
            final IntentFilter filter = new IntentFilter(ACTION_INTENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(actionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(actionReceiver, filter);
            }
        }
        return result;
    }

    /** The icon of one action, drawn larger than the glyph of the resource as it is. */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private @NonNull Icon actionIcon(@NonNull Activity host, @DrawableRes int iconRes) {
        final String key = "r:" + iconRes;
        final Icon cached = actionIcons.get(key);
        if (cached != null) {
            return cached;
        }
        final Drawable drawable = ContextCompat.getDrawable(host, iconRes);
        if (drawable == null) {
            final Icon fallback = Icon.createWithResource(host, iconRes);
            actionIcons.put(key, fallback);
            return fallback;
        }
        // drawn at 48dp for crisp edges, then measured: the glyph rarely fills its own canvas
        final int source = Math.max(1, AndroidUtilities.dp(48));
        final Bitmap sourceBitmap = Bitmap.createBitmap(source, source, Bitmap.Config.ARGB_8888);
        final Canvas sourceCanvas = new Canvas(sourceBitmap);
        drawable.setBounds(0, 0, source, source);
        drawable.draw(sourceCanvas);
        int left = source, top = source, right = -1, bottom = -1;
        final int[] row = new int[source];
        for (int y = 0; y < source; y++) {
            sourceBitmap.getPixels(row, 0, source, 0, y, source, 1);
            for (int x = 0; x < source; x++) {
                if ((row[x] >>> 24) > 8) {
                    if (x < left) {
                        left = x;
                    }
                    if (x > right) {
                        right = x;
                    }
                    if (top == source) {
                        top = y;
                    }
                    bottom = y;
                }
            }
        }
        if (right < left) {
            left = 0;
            top = 0;
            right = source - 1;
            bottom = source - 1;
        }
        final int box = Math.max(1, AndroidUtilities.dp(ACTION_ICON_BOX_DP));
        final Bitmap icon = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888);
        icon.setDensity(host.getResources().getDisplayMetrics().densityDpi);
        final Rect src = new Rect(left, top, right + 1, bottom + 1);
        final float inset = box * (1f - ACTION_ICON_FILL) / 2f;
        final float scale = Math.min((box - 2 * inset) / src.width(), (box - 2 * inset) / src.height());
        final float width = src.width() * scale;
        final float height = src.height() * scale;
        final float dx = (box - width) / 2f;
        final float dy = (box - height) / 2f;
        final Canvas canvas = new Canvas(icon);
        canvas.drawBitmap(sourceBitmap, src, new RectF(dx, dy, dx + width, dy + height), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        final Icon result = Icon.createWithBitmap(icon);
        actionIcons.put(key, result);
        return result;
    }

    /**
     * An action whose value is its icon: the value is drawn into the button box, in the same place a
     * resource glyph would be. The system draws no title, so this is the only way the current
     * playback speed can be read off the small window.
     *
     * <p>Width is what runs out first: "1.25x" is five glyphs in a square button, so the digits are
     * condensed (TextScaleX 0.80) and their spacing tightened, the "x" is drawn smaller on the same
     * baseline, and the result is cropped to its ink: the system fits the bitmap's bounding square
     * into the button, so any margin left around the glyph is space the glyph does not get.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private @NonNull Icon actionTextIcon(@NonNull Activity host, @NonNull CharSequence text, int arrow) {
        final String value = text.toString();
        final String key = "t:" + arrow + ":" + value;
        final Icon cached = actionIcons.get(key);
        if (cached != null) {
            return cached;
        }
        final boolean hasArrow = arrow != 0;
        final boolean hasSuffix = value.length() > 1 && value.endsWith("x");
        final String digits = hasSuffix ? value.substring(0, value.length() - 1) : value;

        final int box = Math.max(1, AndroidUtilities.dp(ACTION_ICON_BOX_DP));
        final Bitmap bitmap = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(host.getResources().getDisplayMetrics().densityDpi);
        final Canvas canvas = new Canvas(bitmap);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(AndroidUtilities.bold());
        // the system gives the icon one small box and nothing else: the only lever left for visual
        // weight is how thick the strokes are. The play/pause glyph is a solid shape, so plain bold
        // text always looked lighter next to it.
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        // a soft shadow instead of an outline: glyphs this small would fill their own counters
        paint.setShadowLayer(Math.max(1f, box * 0.05f), 0, 0, 0xB3000000);
        // condensed and tight: the same width then holds a larger type size, which is the only way a
        // multi glyph value can grow inside a square button
        paint.setTextScaleX(0.80f);
        paint.setLetterSpacing(-0.04f);

        // the marker and the "x" each get a strip of their own, so nothing ever shares pixels with
        // the digits; the digits get what is left, which is where the larger type size comes from
        final float zoneLeft = box * (hasArrow ? 0.02f : 0.0f);
        final float zoneRight = box * (hasArrow ? 0.74f : 0.99f);
        final float maxWidth = zoneRight - zoneLeft;
        final float maxHeight = box * 0.94f;

        final float suffixScale = 0.62f;
        float size = maxHeight;
        for (int i = 0; i < 8; i++) {
            paint.setTextSize(size);
            float width = paint.measureText(digits);
            if (hasSuffix) {
                paint.setTextSize(size * suffixScale);
                width += paint.measureText("x");
            }
            if (width <= maxWidth && width > 0) {
                break;
            }
            size *= maxWidth / width;
            if (size <= 1f) {
                size = 1f;
                break;
            }
        }
        paint.setTextSize(size);
        final Paint.FontMetrics fm = paint.getFontMetrics();
        if (fm.descent - fm.ascent > maxHeight) {
            size *= maxHeight / (fm.descent - fm.ascent);
            paint.setTextSize(size);
        }
        final Paint.FontMetrics metrics = paint.getFontMetrics();
        final float baseline = box / 2f - (metrics.ascent + metrics.descent) / 2f;
        paint.setTextSize(size);
        final float digitsWidth = paint.measureText(digits);
        float suffixWidth = 0;
        if (hasSuffix) {
            paint.setTextSize(size * suffixScale);
            suffixWidth = paint.measureText("x");
        }
        // the whole line is centred in its zone: the ink crop then scales it as a unit, so a gap
        // between the digits and the x would only make everything smaller
        final float lineWidth = digitsWidth + suffixWidth;
        final float startX = zoneLeft + (maxWidth - lineWidth) / 2f;
        paint.setTextSize(size);
        canvas.drawText(digits, startX, baseline, paint);
        if (hasSuffix) {
            // a small x on the same baseline, the way a speed value is usually written
            paint.setTextSize(size * suffixScale);
            canvas.drawText("x", startX + digitsWidth, baseline, paint);
        }
        if (hasArrow) {
            drawArrow(canvas, box, arrow > 0);
        }
        final Icon icon = Icon.createWithBitmap(cropToInk(bitmap));
        actionIcons.put(key, icon);
        return icon;
    }

    /**
     * Crops the bitmap to what is actually drawn, as a square. The system fits the icon's bounding box
     * into the button, so margins around the glyph waste exactly the space the glyph needs.
     */
    private static @NonNull Bitmap cropToInk(@NonNull Bitmap source) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        final int[] row = new int[width];
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) {
            source.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                final int pixel = row[x];
                // the soft shadow is dark and would widen the crop: only bright ink counts
                if (((pixel >>> 24) & 0xFF) > 24 && (pixel & 0x00FFFFFF) > 0x303030) {
                    if (x < left) {
                        left = x;
                    }
                    if (x > right) {
                        right = x;
                    }
                    if (top == height) {
                        top = y;
                    }
                    bottom = y;
                }
            }
        }
        if (right < left || bottom < top) {
            return source;
        }
        int side = Math.max(right - left + 1, bottom - top + 1);
        final int limit = Math.min(width, height);
        if (side > limit) {
            side = limit;
        }
        final int pad = Math.max(1, Math.round(side * 0.02f));
        side = Math.min(limit, side + 2 * pad);
        final int centerX = (left + right) / 2;
        final int centerY = (top + bottom) / 2;
        final int x = Math.max(0, Math.min(width - side, centerX - side / 2));
        final int y = Math.max(0, Math.min(height - side, centerY - side / 2));
        final Bitmap cropped = Bitmap.createBitmap(source, x, y, side, side);
        // the crop is a new bitmap: keep the density, or the system scales it by the default one
        cropped.setDensity(source.getDensity());
        return cropped;
    }

    /**
     * The solid triangle that says which way a seek entry goes. One marker, not two: two thin ones
     * only made the digits smaller and still looked like a second glyph next to them.
     */
    private void drawArrow(@NonNull Canvas canvas, int box, boolean forward) {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setShadowLayer(Math.max(1f, box * 0.05f), 0, 0, 0xB3000000);
        final float left = box * 0.78f;
        final float right = box * 0.98f;
        final float h = box * 0.44f;
        final float cy = box / 2f;
        final Path p = new Path();
        if (forward) {
            p.moveTo(right, cy);
            p.lineTo(left, cy - h / 2f);
            p.lineTo(left, cy + h / 2f);
        } else {
            p.moveTo(left, cy);
            p.lineTo(right, cy - h / 2f);
            p.lineTo(right, cy + h / 2f);
        }
        p.close();
        canvas.drawPath(p, paint);
    }

    /** Enter system PiP right now, e.g. from a PiP button in the content's own UI. */
    boolean enterNow(@NonNull PipSession session) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || active != null || !PipEnvironment.canUseSystemPip(activity)) {
            return false;
        }
        final PictureInPictureParams params = buildParams(session);
        startEnter(session);
        try {
            if (activity.enterPictureInPictureMode(params)) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        exitToApp();
        return false;
    }

    /** Source rect hint in activity window coordinates (the decor view). */
    private boolean getSourceRect(@NonNull PipSession session, @NonNull Rect out) {
        if (activity == null) {
            return false;
        }
        if (session.isFloating()) {
            session.floating.getRect(tmpRectF);
            tmpRectF.round(out);
        } else {
            session.content.getPrimaryVideoRect(out);
        }
        if (out.isEmpty()) {
            return false;
        }
        final View decorView = activity.getWindow().getDecorView();
        decorView.getLocationOnScreen(tmpLocation);
        out.offset(-tmpLocation[0], -tmpLocation[1]);
        final float aspect = out.width() / (float) out.height();
        if (!out.intersect(0, 0, decorView.getWidth(), decorView.getHeight())) {
            return false;
        }
        // cut by the window edge (zoomed in): another aspect than the content, the system rejects it
        return Math.abs(out.width() / (float) out.height() / aspect - 1f) < 0.02f;
    }

    /* Media session */

    private void updateMediaSession(@Nullable PipSession session) {
        final Player player = session != null ? session.content.getMediaSessionPlayer() : null;
        if (player == null) {
            releaseMediaSession();
            return;
        }
        if (mediaSession != null && mediaSession.getPlayer() == player) {
            return;
        }
        releaseMediaSession();
        try {
            mediaSession = new MediaSession.Builder(activity, player)
                .setId("utils-pip-media-session")
                .build();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void releaseMediaSession() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
    }

    /* Activity callbacks */

    void onUserLeaveHint() {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || PipEnvironment.useAutoEnter()) {
            return;
        }
        if (active != null) {
            return;
        }
        update();
        if (candidate == null) {
            return;
        }
        final PictureInPictureParams params = buildParams(candidate);
        startEnter(candidate);
        try {
            if (!activity.enterPictureInPictureMode(params)) {
                exitToApp();
            }
        } catch (Exception e) {
            FileLog.e(e);
            exitToApp();
        }
    }

    void onPause() {
        if (activity != null && PipEnvironment.useAutoEnter() && active == null && candidate != null && AndroidUtilities.isInPictureInPictureMode(activity)) {
            startEnter(candidate);
        }
    }

    void onResume() {
        if (active != null && activity != null && !AndroidUtilities.isInPictureInPictureMode(activity)) {
            exitToApp();
        }
    }

    void onStop() {

    }

    void onPictureInPictureUiStateChanged(@NonNull Object state) {
        if (Build.VERSION.SDK_INT >= 35 && state instanceof PictureInPictureUiState) {
            if (((PictureInPictureUiState) state).isTransitioningToPip() && active == null && candidate != null) {
                startEnter(candidate);
            }
        }
    }

    void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        if (active == null) {
            if (isInPictureInPictureMode && candidate != null) {
                startEnter(candidate);
            }
            if (active == null) {
                // nothing of ours is shown: never hide the activity content for an empty window
                return;
            }
        }
        if (isInPictureInPictureMode) {
            completeEnter();
        } else if (coordinator.isActivityStarted()) {
            exitToApp();
        } else {
            closeFromSystem();
        }
    }

    /* Transitions */

    private void startEnter(@NonNull PipSession session) {
        if (layout == null || activity == null) {
            return;
        }
        PipCoordinator.log("system: start enter, from state " + session.state);
        final Rect hint = new Rect();
        if (!getSourceRect(session, hint)) {
            hint.setEmpty();
        }
        active = session;
        candidate = null;
        entering = true;
        session.stateBeforeSystem = session.state;

        final View systemView = session.floating == null ? session.content.createSystemView(activity) : null;
        // Without a hint (calls) the system covers the window with the app icon while it shrinks:
        // the own UI stays until PiP mode is reported, the system view would fill the whole
        // window before that. With a hint the content is hidden now, not when PiP mode is
        // reported: the primary UI (e.g. the viewer's own window) goes away below, and the
        // activity content would show through for a frame.
        final boolean showOnComplete = systemView != null && hint.isEmpty();
        if (contentVisibility != null && !showOnComplete) {
            contentVisibility.setHidden(true);
        }
        final PipTextureView target;
        if (systemView != null) {
            target = null;
            if (showOnComplete) {
                systemView.setVisibility(View.INVISIBLE);
            }
            layout.setTarget(systemView, hint);
            session.content.onSystemEnter(systemView);
        } else if (session.state == PipSession.STATE_FLOATING && session.floating != null) {
            target = session.floating.detachForSystem();
            layout.setTarget(target, hint);
        } else {
            target = new PipTextureView(activity);
            layout.setTarget(target, hint);
            session.content.releasePrimary(target);
        }
        layout.bringToFront();
        activeTarget = target;
        activeSystemView = systemView;
        session.state = PipSession.STATE_SYSTEM;
    }

    private void completeEnter() {
        PipCoordinator.log("system: complete enter");
        isInPip = true;
        if (entering && active != null && activeSystemView != null) {
            active.content.onSystemEntered(activeSystemView);
        }
        if (entering) {
            entering = false;
            if (active != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applyParams(active);
            }
        }
        if (contentVisibility != null) {
            contentVisibility.setHidden(true);
        }
        if (activeSystemView != null) {
            activeSystemView.setVisibility(View.VISIBLE);
        }
        if (layout != null) {
            layout.requestLayout();
        }
    }

    private void exitToApp() {
        final PipSession session = active;
        final PipTextureView target = activeTarget;
        final View systemView = activeSystemView;
        PipCoordinator.log("system: exit to app");
        active = null;
        activeTarget = null;
        activeSystemView = null;
        isInPip = false;
        entering = false;
        if (contentVisibility != null) {
            contentVisibility.setHidden(false);
        }
        if (layout != null) {
            layout.setTarget(null, null);
        }
        if (session != null && systemView != null) {
            session.state = session.stateBeforeSystem;
            session.content.onSystemExit(systemView);
            session.content.releaseSystemView(systemView);
            coordinator.invalidateSystem();
            return;
        }
        if (session == null || target == null) {
            coordinator.invalidateSystem();
            return;
        }
        if (session.stateBeforeSystem == PipSession.STATE_FLOATING && session.floating != null) {
            session.state = PipSession.STATE_FLOATING;
            session.floating.attachAfterSystem(target);
        } else {
            session.state = PipSession.STATE_PRIMARY;
            android.graphics.SurfaceTexture texture = target.takeSurfaceTexture();
            if (texture == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // entering failed before the target was drawn: the content still has to take its
                // UI back (it released it in releasePrimary), with an empty texture
                texture = new android.graphics.SurfaceTexture(false);
            }
            if (texture != null) {
                session.content.restorePrimary(texture);
            }
        }
        coordinator.invalidateSystem();
    }

    private void closeFromSystem() {
        PipCoordinator.log("system: closed by the user");
        isInPip = false;
        entering = false;
        if (contentVisibility != null) {
            contentVisibility.setHidden(false);
        }
        if (active != null) {
            active.dismiss();
        }
    }

    void onSessionDismissed(@NonNull PipSession session, int prevState) {
        if (session == candidate) {
            candidate = null;
        }
        if (session != active) {
            return;
        }
        final PipTextureView target = activeTarget;
        final View systemView = activeSystemView;
        active = null;
        activeTarget = null;
        activeSystemView = null;
        if (layout != null) {
            layout.setTarget(null, null);
        }
        if (target != null) {
            target.releaseSurfaceTexture();
        }
        if (systemView != null) {
            session.content.releaseSystemView(systemView);
        }
        if (activity != null && isInPip && AndroidUtilities.isInPictureInPictureMode(activity)) {
            // the content ended while shown in system PiP
            activity.moveTaskToBack(false);
        }
        isInPip = false;
        if (contentVisibility != null) {
            contentVisibility.setHidden(false);
        }
    }
}
