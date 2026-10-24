package hr.lagani.launcher;

import android.content.Context;

import java.util.Locale;

/**
 * UI language from the "language:" line in settings.txt: "en" (default) or "hr".
 * Loaded when a screen is created or resumed; background threads only read the flag.
 */
final class Lang {

    private static volatile boolean hr;

    private Lang() {
    }

    /** Re-reads settings.txt; returns true if the language changed. */
    static boolean load(Context context) {
        String v = UserConfig.get(context, "language");
        boolean now = v != null && v.toLowerCase(Locale.ROOT).startsWith("hr");
        boolean changed = now != hr;
        hr = now;
        return changed;
    }

    static boolean hr() {
        return hr;
    }

    /** The English or the Croatian text, depending on the language. */
    static String t(String en, String hrText) {
        return hr ? hrText : en;
    }

    static Locale locale() {
        return hr ? new Locale("hr") : Locale.ENGLISH;
    }
}
