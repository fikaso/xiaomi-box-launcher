package hr.lagani.launcher;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * {@code settings.txt} in the receive folder (sent via "Primi datoteke" (receive files)): lines "key: value",
 * e.g. "location: Osijek" and "club: HNK Rijeka". Blank lines and # comments are skipped.
 */
final class UserConfig {

    static final String FILE = "settings.txt";

    private UserConfig() {
    }

    /** Value for the key, or null if missing (or empty). */
    static String get(Context context, String key) {
        File f = new File(ReceiveActivity.receiveDir(context), FILE);
        if (!f.isFile()) return null;
        String prefix = key.toLowerCase(Locale.ROOT) + ":";
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            try {
                for (String line; (line = r.readLine()) != null; ) {
                    line = line.trim();
                    if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        String value = line.substring(prefix.length()).trim();
                        return value.isEmpty() ? null : value;
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
