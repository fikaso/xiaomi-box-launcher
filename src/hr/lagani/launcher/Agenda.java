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
 * Upcoming events from two sources:
 * 1. iCal URLs: the built-in public calendar of Croatian holidays and URLs from {@code calendars.txt}
 *    (one per line, e.g. the "secret address in iCal format" of a shared Google calendar);
 *    the file is sent via "Primi datoteke" (receive files) and stays in the receive folder;
 * 2. calendars Android syncs with the Google account (on Android TV this often
 *    no longer works, but if it does, they are shown too).
 */
final class Agenda {

    /** How many days ahead we look; the tile only shows the first 4 events anyway. */
    static final int DAYS = 30;
    static final String CONFIG = "calendars.txt";
    private static final String HOLIDAYS =
            "https://calendar.google.com/calendar/ical/hr.croatian%23holiday%40group.v.calendar.google.com/public/basic.ics";
    private static final long ICS_REFRESH_MS = 30 * 60 * 1000L;
    /** The todo sheet is small and changes more often, so it is fetched every 5 min. */
    private static final long SHEET_REFRESH_MS = 5 * 60 * 1000L;
    private static final String[] DAY_NAMES = {"Ned", "Pon", "Uto", "Sri", "Čet", "Pet", "Sub"};
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    static final class Item {
        final long day;      // local midnight of the day the event is shown on
        final boolean allDay;
        final long begin;
        final String time;   // "18:00" or "" for all-day events
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

    /** Up to {@code max} events from today onward. Called off the main thread (fetches iCal). */
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
            // By time, so the nearest occurrence of a recurring event is the one kept.
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
                    // Weekly and daily ones (e.g. trash) only today and tomorrow; each recurring one only once.
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
        // The same holiday can come from several calendars.
        if (seen.add(it.day + "|" + it.title.toLowerCase(Locale.ROOT))) items.add(it);
    }

    /** All URLs from calendars.txt in order (blank lines and # comments are skipped). */
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

    /** Whether calendars.txt has anything personal (calendar, sheet or Todoist); without it the tile shows the month. */
    static boolean personal(Context context) {
        return !lines(context).isEmpty() || Todo.configured(context);
    }

    /** Public holidays in [from, to): local midnight of the day → name. Called off the main thread. */
    static java.util.TreeMap<Long, String> holidays(Context context, long from, long to) {
        java.util.TreeMap<Long, String> days = new java.util.TreeMap<>();
        String ics = cachedIcs(context, HOLIDAYS);
        if (ics == null) return days;
        // Google's calendar also has observances (Mother's Day, Orthodox Easter...); only events
        // described as "Državni praznik" (public holiday) are days off.
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

    /** Built-in holidays + calendars from calendars.txt (sheets and scripts are skipped). */
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

    /** Text from a URL, with a copy stored on the box; refetched every 30 min, the old copy is used when offline. */
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
                // No network: try the old copy.
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
        // One day earlier because all-day events start at UTC midnight.
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
                    // All-day events are stored as UTC midnight; convert to the local date.
                    startDay = utcDateToLocal(begin);
                    endDay = utcDateToLocal(end);          // exclusive
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
            // Calendar unavailable: show what we have.
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
