package hr.lagani.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sljedeća utakmica Hajduka s FotMoba (neslužbeni, ali javni JSON): protivnik, natjecanje i
 * vrijeme iz podataka o momčadi, a hrvatski TV kanal iz njihovog TV rasporeda (samo ~10 dana
 * unaprijed, pa kanal zna doći naknadno). Sofascore odbija zahtjeve koji nisu iz preglednika.
 */
final class Hajduk {

    private static final String TEAM = "https://www.fotmob.com/api/data/teams?id=10154&ccode3=HRV";
    private static final String TV = "https://www.fotmob.com/api/data/tvlistings?countryCode=HR";
    private static final long REFRESH_MS = 3 * 60 * 60 * 1000L;
    /** Utakmica se pokazuje još dva sata nakon početka. */
    private static final long SHOW_AFTER_KICKOFF_MS = 2 * 60 * 60 * 1000L;
    private static final String[] DAYS = {"ned", "pon", "uto", "sri", "čet", "pet", "sub"};

    static final class Match {
        final String home, away, competition, channels;
        final long kickoff;

        Match(String home, String away, String competition, String channels, long kickoff) {
            this.home = home;
            this.away = away;
            this.competition = competition;
            this.channels = channels;
            this.kickoff = kickoff;
        }
    }

    private Hajduk() {
    }

    /** Zadnja poznata utakmica; dohvaća novu svaka 3 sata ili kad je stara prošla. Izvan glavne niti. */
    static Match next(Context context) {
        File cache = new File(context.getFilesDir(), "hajduk.json");
        Match m = read(cache);
        long now = System.currentTimeMillis();
        boolean stale = !cache.isFile() || now - cache.lastModified() > REFRESH_MS
                || (m != null && now > m.kickoff + SHOW_AFTER_KICKOFF_MS);
        if (stale) {
            try {
                JSONObject fresh = fetchMatch();
                write(cache, fresh.toString());
                m = parse(fresh);
            } catch (Exception ignored) {
                // Nema mreže ili se FotMob promijenio: ostaje zadnja poznata.
            }
        }
        if (m == null || now > m.kickoff + SHOW_AFTER_KICKOFF_MS) return null;
        return m;
    }

    private static JSONObject fetchMatch() throws Exception {
        JSONObject next = new JSONObject(get(TEAM)).getJSONObject("overview").getJSONObject("nextMatch");
        JSONObject out = new JSONObject();
        out.put("home", next.getJSONObject("home").getString("name"));
        out.put("away", next.getJSONObject("away").getString("name"));
        out.put("competition", next.optJSONObject("tournament") == null ? ""
                : next.getJSONObject("tournament").optString("name"));
        out.put("utc", next.getJSONObject("status").getString("utcTime"));
        out.put("channels", channels(String.valueOf(next.getLong("id"))));
        return out;
    }

    /** TV kanali (bez "To Go" aplikacija), bez ponavljanja, odvojeni zarezom; "" ako ih još nema. */
    private static String channels(String matchId) {
        try {
            JSONArray list = new JSONObject(get(TV)).optJSONArray(matchId);
            if (list == null) return "";
            List<String> names = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject station = list.getJSONObject(i).optJSONObject("station");
                if (station == null || !"TV".equals(station.optString("type"))) continue;
                String name = station.optString("name").trim();
                if (name.isEmpty() || name.toLowerCase(Locale.ROOT).contains("to go") || names.contains(name)) continue;
                names.add(name);
            }
            return android.text.TextUtils.join(", ", names);
        } catch (Exception e) {
            return "";
        }
    }

    private static Match parse(JSONObject o) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        long kickoff = f.parse(o.getString("utc").substring(0, 19)).getTime();
        return new Match(shortName(o.getString("home")), shortName(o.getString("away")),
                o.optString("competition"), o.optString("channels"), kickoff);
    }

    /** "Hajduk Split" → "Hajduk", "NK Istra 1961" → "Istra 1961". */
    private static String shortName(String name) {
        if (name.startsWith("Hajduk")) return "Hajduk";
        return name.replaceFirst("^(H?NK|FK|GNK) ", "");
    }

    /** "sub 26. 10. u 18:00" ili "danas u 18:00". */
    static String when(Match m) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(m.kickoff);
        Calendar today = Calendar.getInstance();
        int diff = c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
                + 365 * (c.get(Calendar.YEAR) - today.get(Calendar.YEAR));
        String day = diff <= 0 ? "danas" : diff == 1 ? "sutra"
                : DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + ". " + (c.get(Calendar.MONTH) + 1) + ".";
        return day + String.format(Locale.ROOT, " u %d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        // Bez User-Agenta CloudFront vraća 403.
        c.setRequestProperty("User-Agent", "LaganiLauncher/1.10 (Android TV)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static Match read(File f) {
        if (!f.isFile()) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] b = new byte[(int) f.length()];
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
                return parse(new JSONObject(new String(b, 0, n, "UTF-8")));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, String s) throws IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try {
            out.write(s.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
