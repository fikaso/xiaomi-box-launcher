package hr.lagani.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Minimal iCal (.ics) calendar reader: the public holiday calendar and the "secret address in iCal format"
 * of a shared Google calendar. Supports recurrence (RRULE) for DAILY/WEEKLY/MONTHLY/YEARLY
 * with INTERVAL, COUNT, UNTIL and BYDAY (weekly), which covers birthdays and weekly chores.
 */
final class Ics {

    static final class Event {
        final String title;
        final long start;      // for all-day events: local midnight
        final long end;
        final boolean allDay;
        final String freq;     // null for one-off events, otherwise DAILY/WEEKLY/MONTHLY/YEARLY

        Event(String title, long start, long end, boolean allDay, String freq) {
            this.title = title;
            this.start = start;
            this.end = end;
            this.allDay = allDay;
            this.freq = freq;
        }
    }

    private Ics() {
    }

    static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** All events (and recurrences) that overlap [from, to). */
    static List<Event> between(String ics, long from, long to) {
        List<Event> result = new ArrayList<>();
        // "Unfold" long lines: a continuation starts with a space or tab.
        String text = ics.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "");
        String summary = null, dtstart = null, dtend = null, rrule = null;
        boolean inEvent = false, cancelled = false;
        for (String line : text.split("\n")) {
            if (line.equals("BEGIN:VEVENT")) {
                inEvent = true;
                summary = dtstart = dtend = rrule = null;
                cancelled = false;
            } else if (line.equals("END:VEVENT")) {
                inEvent = false;
                if (!cancelled && summary != null && dtstart != null) {
                    expand(unescape(summary), dtstart, dtend, rrule, from, to, result);
                }
            } else if (inEvent) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon);
                String value = line.substring(colon + 1).trim();
                String key = name.contains(";") ? name.substring(0, name.indexOf(';')) : name;
                switch (key) {
                    case "SUMMARY": summary = value; break;
                    case "DTSTART": dtstart = line; break;
                    case "DTEND": dtend = line; break;
                    case "RRULE": rrule = value; break;
                    case "STATUS": cancelled = "CANCELLED".equals(value); break;
                }
            }
        }
        return result;
    }

    private static void expand(String title, String dtstartLine, String dtendLine, String rrule,
                               long from, long to, List<Event> out) {
        boolean allDay = dtstartLine.contains("VALUE=DATE") && !dtstartLine.contains("VALUE=DATE-TIME")
                || value(dtstartLine).length() == 8;
        Calendar start = parse(dtstartLine);
        if (start == null) return;
        long duration;
        Calendar end = dtendLine == null ? null : parse(dtendLine);
        if (end != null) duration = end.getTimeInMillis() - start.getTimeInMillis();
        else duration = allDay ? 24 * 3600 * 1000L : 0;

        if (rrule == null) {
            add(title, null, start.getTimeInMillis(), duration, allDay, from, to, out);
            return;
        }
        String freq = part(rrule, "FREQ");
        int interval = parseInt(part(rrule, "INTERVAL"), 1);
        int count = parseInt(part(rrule, "COUNT"), Integer.MAX_VALUE);
        String untilRaw = part(rrule, "UNTIL");
        long until = Long.MAX_VALUE;
        if (untilRaw != null) {
            Calendar u = parse("UNTIL:" + untilRaw);
            if (u != null) until = u.getTimeInMillis() + (untilRaw.length() == 8 ? 24 * 3600 * 1000L - 1 : 0);
        }
        String byDay = part(rrule, "BYDAY");

        Calendar cur = (Calendar) start.clone();
        int made = 0;
        for (int guard = 0; guard < 20000 && made < count; guard++) {
            long t = cur.getTimeInMillis();
            if (t > until || t >= to) break;
            if ("MONTHLY".equals(freq) && byDay != null) {
                // E.g. BYDAY=1MO (first Monday), -1FR (last Friday), MO (every Monday).
                for (Calendar day : monthDays(cur, byDay)) {
                    if (made >= count) break;
                    if (day.before(start)) continue;
                    long dt = day.getTimeInMillis();
                    if (dt > until || dt >= to) break;
                    made++;
                    add(title, freq, dt, duration, allDay, from, to, out);
                }
                cur.set(Calendar.DAY_OF_MONTH, 1);
                cur.add(Calendar.MONTH, interval);
                continue;
            }
            if ("WEEKLY".equals(freq) && byDay != null) {
                // Week by week; within a week, all listed days.
                Calendar week = (Calendar) cur.clone();
                week.set(Calendar.DAY_OF_WEEK, week.getFirstDayOfWeek());
                for (int d = 0; d < 7 && made < count; d++) {
                    Calendar day = (Calendar) week.clone();
                    day.add(Calendar.DAY_OF_MONTH, d);
                    if (day.before(start) || !byDay.contains(dayCode(day.get(Calendar.DAY_OF_WEEK)))) continue;
                    long dt = day.getTimeInMillis();
                    if (dt > until || dt >= to) break;
                    made++;
                    add(title, freq, dt, duration, allDay, from, to, out);
                }
                cur.add(Calendar.WEEK_OF_YEAR, interval);
                continue;
            }
            made++;
            add(title, freq, t, duration, allDay, from, to, out);
            if ("DAILY".equals(freq)) cur.add(Calendar.DAY_OF_MONTH, interval);
            else if ("WEEKLY".equals(freq)) cur.add(Calendar.WEEK_OF_YEAR, interval);
            else if ("MONTHLY".equals(freq)) cur.add(Calendar.MONTH, interval);
            else if ("YEARLY".equals(freq)) cur.add(Calendar.YEAR, interval);
            else break;
        }
    }

    /** Days in the month of {@code month} that match BYDAY (with the time from {@code month}), in order. */
    private static List<Calendar> monthDays(Calendar month, String byDay) {
        List<Calendar> days = new ArrayList<>();
        int last = month.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int d = 1; d <= last; d++) {
            Calendar day = (Calendar) month.clone();
            day.set(Calendar.DAY_OF_MONTH, d);
            String code = dayCode(day.get(Calendar.DAY_OF_WEEK));
            int nth = (d - 1) / 7 + 1;              // which occurrence of this weekday in the month
            int nthFromEnd = -((last - d) / 7 + 1);
            for (String rule : byDay.split(",")) {
                if (!rule.endsWith(code)) continue;
                String n = rule.substring(0, rule.length() - 2);
                if (n.isEmpty() || n.equals(String.valueOf(nth)) || n.equals("+" + nth)
                        || n.equals(String.valueOf(nthFromEnd))) {
                    days.add(day);
                    break;
                }
            }
        }
        return days;
    }

    private static void add(String title, String freq, long start, long duration, boolean allDay,
                            long from, long to, List<Event> out) {
        long end = start + Math.max(duration, 0);
        if (end <= from && !(duration == 0 && start >= from)) return;
        if (start >= to) return;
        out.add(new Event(title, start, end, allDay, freq));
    }

    /** "DTSTART;TZID=Europe/Zagreb:20261022T180000", "...:20261022T160000Z" or "...;VALUE=DATE:20261022". */
    private static Calendar parse(String line) {
        String v = value(line);
        try {
            int y = Integer.parseInt(v.substring(0, 4));
            int m = Integer.parseInt(v.substring(4, 6)) - 1;
            int d = Integer.parseInt(v.substring(6, 8));
            Calendar c;
            if (v.length() == 8) {
                c = Calendar.getInstance();                 // all-day: local date
                c.clear();
                c.set(y, m, d);
                return c;
            }
            int hh = Integer.parseInt(v.substring(9, 11));
            int mm = Integer.parseInt(v.substring(11, 13));
            int ss = v.length() >= 15 ? Integer.parseInt(v.substring(13, 15)) : 0;
            TimeZone tz = TimeZone.getDefault();
            if (v.endsWith("Z")) tz = TimeZone.getTimeZone("UTC");
            else {
                int i = line.indexOf("TZID=");
                if (i >= 0) {
                    String id = line.substring(i + 5, line.indexOf(':', i));
                    if (id.contains(";")) id = id.substring(0, id.indexOf(';'));
                    tz = TimeZone.getTimeZone(id.replace("\"", ""));
                }
            }
            c = Calendar.getInstance(tz);
            c.clear();
            c.set(y, m, d, hh, mm, ss);
            Calendar local = Calendar.getInstance();       // the rest of the math is in the local zone
            local.setTimeInMillis(c.getTimeInMillis());
            return local;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String value(String line) {
        return line.substring(line.lastIndexOf(':') + 1).trim();
    }

    private static String part(String rrule, String key) {
        for (String p : rrule.split(";")) {
            if (p.startsWith(key + "=")) return p.substring(key.length() + 1);
        }
        return null;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String dayCode(int dayOfWeek) {
        return new String[]{"SU", "MO", "TU", "WE", "TH", "FR", "SA"}[dayOfWeek - 1];
    }

    private static String unescape(String s) {
        return s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }
}
