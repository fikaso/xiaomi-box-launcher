package hr.lagani.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The club's next match from FotMob (unofficial but public JSON): opponent, competition and
 * time from the team data, and the Croatian TV channel from their TV listings (only ~10 days
 * ahead, so the channel may show up later). Sofascore rejects requests that don't come from a browser.
 *
 * The club is chosen with a line "club: HNK Rijeka" in settings.txt (the name is looked up on FotMob,
 * a FotMob id also works); without the line it is HNK Hajduk Split, and "club: none" hides the tile.
 */
final class Club {

    private static final String DEFAULT_NAME = "HNK Hajduk Split";
    private static final String DEFAULT_ID = "10154";
    private static final String TEAM = "https://www.fotmob.com/api/data/teams?ccode3=HRV&id=";
    private static final String TV = "https://www.fotmob.com/api/data/tvlistings?countryCode=HR";
    private static final String SEARCH = "https://www.fotmob.com/api/data/search/suggest?lang=en&term=";
    private static final long REFRESH_MS = 3 * 60 * 60 * 1000L;
    /** The match is still shown for two hours after kickoff. */
    private static final long SHOW_AFTER_KICKOFF_MS = 2 * 60 * 60 * 1000L;
    private static final String[] DAYS_HR = {"ned", "pon", "uto", "sri", "čet", "pet", "sub"};
    private static final String[] DAYS_EN = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MONTHS_EN = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

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

    private Club() {
    }

    /** Last known match; fetches a new one every 3 hours or once the old one is over. Off the main thread. */
    static Match next(Context context) {
        String wanted = UserConfig.get(context, "club");
        if (wanted == null) wanted = DEFAULT_NAME;
        if (wanted.equalsIgnoreCase("none") || wanted.equals("-")) return null;

        File cache = new File(context.getFilesDir(), "club.json");
        Match m = read(cache, wanted);
        long now = System.currentTimeMillis();
        boolean stale = m == null || now - cache.lastModified() > REFRESH_MS || now > m.kickoff + SHOW_AFTER_KICKOFF_MS;
        if (stale) {
            try {
                String id = teamId(context, wanted);
                JSONObject fresh = fetchMatch(id, wanted);
                write(cache, fresh.toString());
                m = parse(fresh);
            } catch (Exception ignored) {
                // No network or FotMob changed: keep the last known one.
            }
        }
        if (m == null || now > m.kickoff + SHOW_AFTER_KICKOFF_MS) return null;
        return m;
    }

    /** The club's FotMob id: a number from settings, Hajduk, or the first club from search (stored). */
    private static String teamId(Context context, String wanted) throws Exception {
        if (wanted.matches("\\d+")) return wanted;
        if (wanted.equalsIgnoreCase(DEFAULT_NAME)) return DEFAULT_ID;
        SharedPreferences sp = context.getSharedPreferences("club", Context.MODE_PRIVATE);
        if (wanted.equalsIgnoreCase(sp.getString("query", ""))) return sp.getString("id", "");
        JSONArray groups = new JSONArray(get(SEARCH + URLEncoder.encode(wanted, "UTF-8")));
        for (int g = 0; g < groups.length(); g++) {
            JSONArray list = groups.getJSONObject(g).optJSONArray("suggestions");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject s = list.getJSONObject(i);
                if (!"team".equals(s.optString("type"))) continue;
                sp.edit().putString("query", wanted).putString("id", s.getString("id")).apply();
                return s.getString("id");
            }
        }
        throw new IOException("No club " + wanted);
    }

    private static JSONObject fetchMatch(String teamId, String ourName) throws Exception {
        JSONObject next = new JSONObject(get(TEAM + teamId)).getJSONObject("overview").getJSONObject("nextMatch");
        JSONObject home = next.getJSONObject("home"), away = next.getJSONObject("away");
        JSONObject out = new JSONObject();
        // Our club is written as the user entered it ("Hajduk"), the opponent shortened.
        out.put("home", teamId.equals(home.optString("id")) ? ourName : shortName(home.getString("name")));
        out.put("away", teamId.equals(away.optString("id")) ? ourName : shortName(away.getString("name")));
        out.put("competition", next.optJSONObject("tournament") == null ? ""
                : next.getJSONObject("tournament").optString("name"));
        out.put("utc", next.getJSONObject("status").getString("utcTime"));
        out.put("channels", channels(String.valueOf(next.getLong("id"))));
        out.put("for", ourName);
        return out;
    }

    /** TV channels (without "To Go" apps), without duplicates, comma-separated; "" if none yet. */
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
        return new Match(o.getString("home"), o.getString("away"),
                o.optString("competition"), o.optString("channels"), kickoff);
    }

    /** "NK Istra 1961" → "Istra 1961", "HNK Rijeka" → "Rijeka". */
    private static String shortName(String name) {
        return name.replaceFirst("^(H?NK|FK|GNK) ", "");
    }

    /** "sub 26. 10. u 18:00" / "danas u 18:00", or in English "Sat 26 Oct, 18:00" / "today, 18:00". */
    static String when(Match m) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(m.kickoff);
        Calendar today = Calendar.getInstance();
        int diff = c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
                + 365 * (c.get(Calendar.YEAR) - today.get(Calendar.YEAR));
        String time = String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        int weekday = c.get(Calendar.DAY_OF_WEEK) - 1;
        if (!Lang.hr()) {
            String day = diff <= 0 ? "today" : diff == 1 ? "tomorrow"
                    : DAYS_EN[weekday] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS_EN[c.get(Calendar.MONTH)];
            return day + ", " + time;
        }
        String day = diff <= 0 ? "danas" : diff == 1 ? "sutra"
                : DAYS_HR[weekday] + " " + c.get(Calendar.DAY_OF_MONTH) + ". " + (c.get(Calendar.MONTH) + 1) + ".";
        return day + " u " + time;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        // Without a User-Agent, CloudFront returns 403.
        c.setRequestProperty("User-Agent", "LiteLauncher (Android TV)");
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

    /** Stored match, but only if it is for the club currently in settings. */
    private static Match read(File f, String wanted) {
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
                JSONObject o = new JSONObject(new String(b, 0, n, "UTF-8"));
                return wanted.equals(o.optString("for")) ? parse(o) : null;
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
