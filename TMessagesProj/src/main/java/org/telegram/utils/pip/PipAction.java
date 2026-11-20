package org.telegram.utils.pip;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** A button of the system PiP window (RemoteAction), described by the content. */
public final class PipAction {
    public final int id;
    public final @DrawableRes int iconRes;
    /**
     * Drawn on top of the button instead of {@link #iconRes} when set: the system draws no title,
     * so a value that changes (the playback speed) has to be in the icon to be visible.
     */
    public final @Nullable CharSequence iconText;
    public final @NonNull CharSequence title;
    public final boolean enabled;

    public PipAction(int id, @DrawableRes int iconRes, @NonNull CharSequence title, boolean enabled) {
        this(id, iconRes, null, title, enabled);
    }

    public PipAction(int id, @DrawableRes int iconRes, @Nullable CharSequence iconText, @NonNull CharSequence title, boolean enabled) {
        this.id = id;
        this.iconRes = iconRes;
        this.iconText = iconText;
        this.title = title;
        this.enabled = enabled;
    }
}
