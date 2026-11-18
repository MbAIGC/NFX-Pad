package org.telegram.ui.tablet;

import android.content.res.Configuration;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Single gate for the "Enhanced Tablet Mode" feature.
 *
 * <p>Deliberately NOT built on {@link AndroidUtilities#isTablet()}: in this fork that value can be
 * forced on a phone or disabled on a tablet by the user ({@code NekoConfig#tabletMode}), so it
 * describes "tablet-ness" rather than "the dual pane layout is actually on screen". The predicate
 * below mirrors {@code LaunchActivity#isTabletSplitMode()}, which is what the shell really renders.
 */
public final class TabletModeManager {

    private TabletModeManager() {
    }

    /** The user facing switch, default off. */
    public static boolean isEnabled() {
        return NekoConfig.tabletOptimizedMode != null && NekoConfig.tabletOptimizedMode.Bool();
    }

    /** True only when the switch is on <em>and</em> the split (dual pane) tablet layout is in use. */
    public static boolean shouldApplyEnhancedMode() {
        return isEnabled() && isTabletSplitLayout();
    }

    /**
     * Extra lift added on top of the mandatory gesture inset for the video control band.
     * Returns -1 for "auto" (the built in 8dp), otherwise the configured amount in dp.
     */
    public static int getVideoControlsExtraInsetDp() {
        try {
            int index = NekoConfig.tabletVideoExtraBottomInset.Int();
            if (index < 0) {
                // self healing: an earlier build stored -1 as its "auto" sentinel, which the selector cell
                // cannot index; rewrite it to the index based value
                index = 0;
                NekoConfig.tabletVideoExtraBottomInset.setConfigInt(0);
            }
            if (index == 0) {
                return -1;
            }
            if (index > 5) {
                return 32;
            }
            return (index - 1) * 8;
        } catch (Throwable ignore) {
            return -1;
        }
    }

    /** Mirrors {@code LaunchActivity#isTabletSplitMode()}. */
    public static boolean isTabletSplitLayout() {
        if (!AndroidUtilities.isTabletInternal() || AndroidUtilities.isInMultiwindow) {
            return false;
        }
        if (AndroidUtilities.isSmallTablet()) {
            if (ApplicationLoader.applicationContext == null) {
                return false;
            }
            return ApplicationLoader.applicationContext.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
        }
        return true;
    }
}
