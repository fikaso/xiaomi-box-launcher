package hr.lagani.launcher;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Sljedeći događaji iz dva izvora:
 * 1. iCal adrese: ugrađeni javni kalendar hrvatskih praznika i adrese iz {@code kalendari.txt}
 *    (jedna po retku, npr. "tajna adresa u iCal formatu" zajedničkog Google kalendara);
 *    datoteka se šalje preko "Primi datoteke" i ostaje u mapi za primanje;
 * 2. kalendari koje Android sinkronizira s Google računom (na Android TV-u to često
 *    više ne radi, ali ako radi, i oni se prikazuju).
 */
final class Agenda {

    /** Koliko dana unaprijed gledamo; ploča ionako pokaže samo prva 4 događaja. */
    static final int DAYS = 30;
    static final String CONFIG = "kalendari.txt";
    private static final String HOLIDAYS =
            "https://calendar.google.com/calendar/ical/hr.croatian%23holiday%40group.v.calendar.google.com/public/basic.ics";
    private static final long ICS_REFRESH_MS = 30 * 60 * 1000L;
    /** Todo tablica je mala i mijenja se češće, pa se dohvaća svakih 5 min. */
    private static final long SHEET_REFRESH_MS = 5 * 60 * 1000L;
    private static final String[] DAY_NAMES = {"Ned", "Pon", "Uto", "Sri", "Čet", "Pet", "Sub"};
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    static final class Item {
        final long day;      // ponoć lokalnog dana kad se događaj prikazuje
        final boolean allDay;
        final long begin;
        final String time;   // "18:00" ili "" za cjelodnevne
        final String title;

        Item(long day, boolean allDay, long begin, String time, String title) {
            this.day = day;
            this.allDay = allDay;
            this.begin = begin;
            this.time = time;
            this.title = title;
        }
    }

    private Agenda() {
    }

    static boolean allowed(Context context) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /** Do {@code max} događaja od danas nadalje. Zove se izvan glavne niti (dohvaća iCal). */
    static List<Item> upcoming(Context context, int max) {
        long now = System.currentTimeMillis();
        long today = startOfDay(now);
        long until = today + (DAYS + 1) * DAY_MS;
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String url : icsUrls(context)) {
            String ics = cachedIcs(context, url);
            if (ics == null) continue;
            List<Ics.Event> events = Ics.between(ics, today, until);
            // Po vremenu, da od ponavljajućih ostane najbliži termin.
            Collections.sort(events, new Comparator<Ics.Event>() {
                @Override
                public int compare(Ics.Event a, Ics.Event b) { return Long.compare(a.start, b.start); }
            });
            Set<String> repeating = new HashSet<>();
            for (Ics.Event e : events) {
                long startDay = startOfDay(e.start);
                if (e.allDay) {
                    if (e.end <= today) continue;
                } else if (e.end > e.start ? e.end <= now : e.start < now) {
                    continue;
                }
                long day = Math.max(startDay, today);
                if (day >= until) continue;
                if (e.freq != null) {
                    // Tjedne i dnevne (npr. smeće) samo danas i sutra; svaki ponavljajući samo jednom.
                    boolean frequent = "WEEKLY".equals(e.freq) || "DAILY".equals(e.freq);
                    if (frequent && day > today + DAY_MS) continue;
                    if (!repeating.add(e.title.trim().toLowerCase(Locale.ROOT))) continue;
                }
                addUnique(items, seen, new Item(day, e.allDay, e.allDay ? day : e.start,
                        e.allDay ? "" : hhmm(e.start), e.title.trim()));
            }
        }
        if (allowed(context)) addSynced(context, now, today, until, items, seen);

        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.day != b.day) return a.day < b.day ? -1 : 1;
                if (a.allDay != b.allDay) return a.allDay ? -1 : 1;
                return Long.compare(a.begin, b.begin);
            }
        });
        return items.size() > max ? new ArrayList<>(items.subList(0, max)) : items;
    }

    private static void addUnique(List<Item> items, Set<String> seen, Item it) {
        // Isti blagdan zna doći iz više kalendara.
        if (seen.add(it.day + "|" + it.title.toLowerCase(Locale.ROOT))) items.add(it);
    }

    /** Sve adrese iz kalendari.txt redom (prazni reci i # komentari se preskaču). */
    static List<String> lines(Context context) {
        List<String> urls = new ArrayList<>();
        java.io.File f = new java.io.File(ReceiveActivity.receiveDir(context), CONFIG);
        if (!f.isFile()) return urls;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            try {
                for (String line; (line = r.readLine()) != null; ) {
                    line = line.trim().replace("webcal://", "https://");
                    if (line.startsWith("http") && !urls.contains(line)) urls.add(line);
                }
            } finally {
                r.close();
            }
        } catch (java.io.IOException ignored) {
        }
        return urls;
    }

    /** Ima li u kalendari.txt ičeg osobnog (kalendar, tablica ili Todoist); bez toga ploča pokazuje mjesec. */
    static boolean personal(Context context) {
        return !lines(context).isEmpty() || Todo.configured(context);
    }

    /** Državni praznici u [from, to): lokalna ponoć dana → naziv. Zove se izvan glavne niti. */
    static java.util.TreeMap<Long, String> holidays(Context context, long from, long to) {
        java.util.TreeMap<Long, String> days = new java.util.TreeMap<>();
        String ics = cachedIcs(context, HOLIDAYS);
        if (ics == null) return days;
        // Googleov kalendar ima i spomendane (Majčin dan, pravoslavni Uskrs...); neradni su samo
        // događaji s opisom "Državni praznik".
        StringBuilder only = new StringBuilder();
        for (String block : ics.replace("\r\n", "\n").split("BEGIN:VEVENT")) {
            if (block.contains("\nDESCRIPTION:Državni praznik")) only.append("BEGIN:VEVENT").append(block);
        }
        for (Ics.Event e : Ics.between(only.toString(), from, to)) {
            if (e.allDay) days.put(startOfDay(e.start), e.title.trim());
        }
        return days;
    }

    static boolean isSheet(String url) {
        return url.contains("docs.google.com/spreadsheets");
    }

    private static boolean isScript(String url) {
        return url.contains("script.google.com/");
    }

    /** Ugrađeni praznici + kalendari iz kalendari.txt (tablice i skripte se preskaču). */
    private static List<String> icsUrls(Context context) {
        List<String> urls = new ArrayList<>();
        urls.add(HOLIDAYS);
        for (String url : lines(context)) {
            if (!isSheet(url) && !isScript(url) && !urls.contains(url)) urls.add(url);
        }
        return urls;
    }

    private static String cachedIcs(Context context, String url) {
        return cachedText(context, url);
    }

    /** Tekst s adrese s kopijom u memoriji boxa; dohvaća ponovno svakih 30 min, a bez mreže koristi staru. */
    static String cachedText(Context context, String url) {
        java.io.File f = new java.io.File(context.getFilesDir(), "ics_" + Integer.toHexString(url.hashCode()) + ".txt");
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > (isSheet(url) ? SHEET_REFRESH_MS : ICS_REFRESH_MS)) {
            try {
                String fresh = Ics.fetch(url);
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                try {
                    out.write(fresh.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
                return fresh;
            } catch (java.io.IOException ignored) {
                // Nema mreže: pokušaj sa starom kopijom.
            }
        }
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
                return new String(b, 0, n, "UTF-8");
            } finally {
                in.close();
            }
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static void addSynced(Context context, long now, long today, long until,
                                  List<Item> items, Set<String> seen) {
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        // Dan ranije jer cjelodnevni događaji počinju u ponoć po UTC-u.
        ContentUris.appendId(uri, today - DAY_MS);
        ContentUris.appendId(uri, until);
        String[] projection = {
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        };

        Cursor c = null;
        try {
            c = context.getContentResolver().query(uri.build(), projection,
                    CalendarContract.Instances.VISIBLE + "=1", null, null);
            while (c != null && c.moveToNext()) {
                String title = c.getString(0);
                if (title == null || title.trim().isEmpty()) continue;
                long begin = c.getLong(1);
                long end = c.getLong(2);
                boolean allDay = c.getInt(3) == 1;
                String calendar = c.getString(4);

                long startDay, endDay;
                if (allDay) {
                    // Cjelodnevni su zapisani kao ponoć po UTC-u; prebaci na lokalni datum.
                    startDay = utcDateToLocal(begin);
                    endDay = utcDateToLocal(end);          // isključivo
                    if (endDay <= today) continue;
                } else {
                    if (end <= now) continue;
                    startDay = startOfDay(begin);
                }
                if (startDay >= until) continue;
                long day = Math.max(startDay, today);

                if ("Contacts".equalsIgnoreCase(calendar) || "Kontakti".equalsIgnoreCase(calendar)) {
                    title = "🎂 " + title;
                }
                addUnique(items, seen, new Item(day, allDay, allDay ? day : begin,
                        allDay ? "" : hhmm(begin), title.trim()));
            }
        } catch (RuntimeException ignored) {
            // Kalendar nedostupan: prikaži što imamo.
        } finally {
            if (c != null) c.close();
        }
    }

    static String dayLabel(long day) {
        long today = startOfDay(System.currentTimeMillis());
        long diff = Math.round((day - today) / (double) DAY_MS);
        if (diff <= 0) return "Danas";
        if (diff == 1) return "Sutra";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(day);
        return DAY_NAMES[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + "."
                + (c.get(Calendar.MONTH) + 1) + ".";
    }

    private static long startOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long utcDateToLocal(long utcMidnight) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(utcMidnight);
        Calendar local = Calendar.getInstance();
        local.clear();
        local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH));
        return local.getTimeInMillis();
    }

    private static String hhmm(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }
}
