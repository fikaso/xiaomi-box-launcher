package hr.lagani.launcher;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * {@code postavke.txt} u mapi za primanje (šalje se preko "Primi datoteke"): reci "ključ: vrijednost",
 * npr. "mjesto: Osijek" i "klub: Rijeka". Prazni reci i # komentari se preskaču.
 */
final class Postavke {

    static final String FILE = "postavke.txt";

    private Postavke() {
    }

    /** Vrijednost za ključ ili null ako je nema (ili je prazna). */
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
