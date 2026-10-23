package hr.lagani.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Prefs {
    static final String TIVIMATE = "ar.tvplayer.tv";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("lagani", Context.MODE_PRIVATE);
    }

    boolean autostart() { return sp.getBoolean("autostart", false); }
    void setAutostart(boolean on) { sp.edit().putBoolean("autostart", on).apply(); }

    String autostartPackage() { return sp.getString("autostart_pkg", TIVIMATE); }

    void setAutostartPackage(String pkg) { sp.edit().putString("autostart_pkg", pkg).apply(); }

    boolean autostartPending() { return sp.getBoolean("autostart_pending", false); }
    void setAutostartPending(boolean on) { sp.edit().putBoolean("autostart_pending", on).apply(); }

    Set<String> hidden() { return new HashSet<>(sp.getStringSet("hidden", new HashSet<String>())); }
    void setHidden(Set<String> hidden) { sp.edit().putStringSet("hidden", hidden).apply(); }

    /** These apps always come first, in this order. */
    static final List<String> FAVOURITES = Arrays.asList(
            TIVIMATE,
            "com.stremio.one",                   // Stremio
            "org.smarttube.stable",              // SmartTube (new package)
            "org.smarttube.beta",                // SmartTube beta (new package)
            "com.teamsmart.videomanager.tv",     // SmartTube (old package)
            "com.liskovsoft.smarttubetv.beta",   // SmartTube beta (old package)
            "com.google.android.youtube.tv");    // YouTube

    static int rank(String pkg) {
        int i = FAVOURITES.indexOf(pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }
}
