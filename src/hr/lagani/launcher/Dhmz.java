package hr.lagani.launcher;

import android.content.Context;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * DHMZ upozorenja za županiju mjesta iz postavki (vidi {@link Place}) iz službenih CAP datoteka
 * za danas i sutra. Svako upozorenje je u datoteci dvaput (hr i en), uzima se hrvatsko.
 * Isteklo ostaje u datoteci, a zeleno ("Nema upozorenja!") se preskače.
 * Županija se traži po nazivu (areaDesc), jer se kodovi (EMMA_ID, NUTS3) ponavljaju među županijama.
 */
final class Dhmz {

    private static final String[] FEEDS = {
            "https://meteo.hr/upozorenja/cap_hr_today.xml",
            "https://meteo.hr/upozorenja/cap_hr_tomorrow.xml",
    };

    static final class Warning {
        final int level;          // 2 žuto, 3 narančasto, 4 crveno
        final String event;       // "Žuto upozorenje za vjetar"
        final long onset;
        final long expires;

        Warning(int level, String event, long onset, long expires) {
            this.level = level;
            this.event = event;
            this.onset = onset;
            this.expires = expires;
        }
    }

    private Dhmz() {
    }

    /** Važeća i nadolazeća upozorenja: prvo ona koja traju, pa najviša razina, pa najranija. */
    static List<Warning> current(Context context) {
        long now = System.currentTimeMillis();
        List<Warning> result = new ArrayList<>();
        Place place = Place.current(context);
        if (place == null || place.county.isEmpty()) return result;
        for (String url : FEEDS) {
            String xml = Agenda.cachedText(context, url);
            if (xml == null) continue;
            try {
                parse(xml, place.county, now, result);
            } catch (Exception ignored) {
                // Neispravna datoteka: preskoči je.
            }
        }
        final long t = now;
        Collections.sort(result, new Comparator<Warning>() {
            @Override
            public int compare(Warning a, Warning b) {
                boolean aNow = a.onset <= t, bNow = b.onset <= t;
                if (aNow != bNow) return aNow ? -1 : 1;
                if (a.level != b.level) return b.level - a.level;
                return Long.compare(a.onset, b.onset);
            }
        });
        return result;
    }

    private static void parse(String xml, String county, long now, List<Warning> out) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        String language = null, event = null, onset = null, expires = null, level = null;
        String areaDesc = null, valueName = null;
        boolean ours = false;
        for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if ("info".equals(tag)) {
                    language = event = onset = expires = level = null;
                    ours = false;
                } else if ("language".equals(tag)) {
                    language = p.nextText().trim();
                } else if ("event".equals(tag)) {
                    event = p.nextText().trim();
                } else if ("onset".equals(tag)) {
                    onset = p.nextText().trim();
                } else if ("expires".equals(tag)) {
                    expires = p.nextText().trim();
                } else if ("areaDesc".equals(tag)) {
                    areaDesc = p.nextText().trim();
                    if (county.equalsIgnoreCase(areaDesc)) ours = true;
                } else if ("valueName".equals(tag)) {
                    valueName = p.nextText().trim();
                } else if ("value".equals(tag)) {
                    String value = p.nextText().trim();
                    if ("awareness_level".equals(valueName)) level = value;
                }
            } else if (e == XmlPullParser.END_TAG && "info".equals(p.getName())) {
                if (!ours || !"hr".equals(language) || level == null || event == null) continue;
                int lvl = level.isEmpty() ? 1 : level.charAt(0) - '0';
                long from = time(onset), to = time(expires);
                if (lvl < 2 || to <= now) continue;
                out.add(new Warning(lvl, event, from, to));
            }
        }
    }

    /** "2026-10-23 06:54:53+02:00" ili s "T"; neispravno ili prazno je 0. */
    private static long time(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ssXXX", Locale.ROOT).parse(s.replace('T', ' ')).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** "do 20 h", "danas od 14 h", "sutra 8–20 h" (sati bez minuta, kao u prognozi). */
    static String when(Warning w) {
        long now = System.currentTimeMillis();
        if (w.onset <= now) return "do " + dayPrefix(w.expires) + hour(w.expires);
        return dayPrefix(w.onset).trim() + " " + hourOnly(w.onset) + "–" + hour(w.expires);
    }

    private static String dayPrefix(long t) {
        Calendar today = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        int diff = c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
                + 365 * (c.get(Calendar.YEAR) - today.get(Calendar.YEAR));
        if (diff <= 0) return "danas ";
        if (diff == 1) return "sutra ";
        return c.get(Calendar.DAY_OF_MONTH) + "." + (c.get(Calendar.MONTH) + 1) + ". ";
    }

    private static String hourOnly(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        int m = c.get(Calendar.MINUTE);
        return c.get(Calendar.HOUR_OF_DAY) + (m == 0 ? "" : String.format(Locale.ROOT, ":%02d", m));
    }

    private static String hour(long t) {
        return hourOnly(t) + " h";
    }
}
