package hr.lagani.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Weather for the location from settings (see {@link Place}) from Open-Meteo (free, no key or
 * sign-up): current conditions, today and the next 3 days.
 * The last response is stored, so it shows right at startup, even before the box connects to the network.
 */
final class Weather {

    static final long REFRESH_MS = 30 * 60 * 1000L;
    /** Anything older is not shown: better nothing than the wrong weather. */
    private static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;

    /** Goes after "?latitude=..&longitude=..". */
    private static final String QUERY = "&current=temperature_2m,weather_code,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
            + "&timezone=Europe%2FZagreb&forecast_days=4";

    private static final String[] DAY_NAMES_HR = {"Ned", "Pon", "Uto", "Sri", "Čet", "Pet", "Sub"};
    private static final String[] DAY_NAMES_EN = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    /** One forecast day; days.get(0) is today. */
    static final class Day {
        final String name;
        final int code;
        final int max;
        final int min;

        Day(String name, int code, int max, int min) {
            this.name = name;
            this.code = code;
            this.max = max;
            this.min = min;
        }
    }

    final String place;
    final int temp;
    final int code;
    final boolean day;
    final List<Day> days;
    final long fetchedAt;

    private Weather(String place, String json, long fetchedAt) throws JSONException {
        this.place = place;
        JSONObject root = new JSONObject(json);
        JSONObject current = root.getJSONObject("current");
        temp = (int) Math.round(current.getDouble("temperature_2m"));
        code = current.getInt("weather_code");
        day = current.optInt("is_day", 1) == 1;

        JSONObject daily = root.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");
        List<Day> list = new ArrayList<>();
        for (int i = 0; i < dates.length(); i++) {
            list.add(new Day(dayName(dates.getString(i)), codes.getInt(i),
                    (int) Math.round(max.getDouble(i)), (int) Math.round(min.getDouble(i))));
        }
        days = list;
        this.fetchedAt = fetchedAt;
    }

    private static String dayName(String isoDate) {
        String[] p = isoDate.split("-");
        Calendar c = Calendar.getInstance();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        return (Lang.hr() ? DAY_NAMES_HR : DAY_NAMES_EN)[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    boolean isFresh() { return System.currentTimeMillis() - fetchedAt < REFRESH_MS; }

    boolean isUsable() { return System.currentTimeMillis() - fetchedAt < MAX_AGE_MS && !days.isEmpty(); }

    /** Description for the WMO code returned by Open-Meteo. */
    static String description(int code, boolean day) {
        switch (code) {
            case 0: return day ? Lang.t("Sunny", "Sunčano") : Lang.t("Clear", "Vedro");
            case 1: return Lang.t("Mostly clear", "Pretežno vedro");
            case 2: return Lang.t("Partly cloudy", "Djelomično oblačno");
            case 3: return Lang.t("Cloudy", "Oblačno");
            case 45: case 48: return Lang.t("Fog", "Magla");
            case 51: case 53: case 55: return Lang.t("Drizzle", "Rosulja");
            case 56: case 57: case 66: case 67: return Lang.t("Freezing rain", "Ledena kiša");
            case 61: return Lang.t("Light rain", "Slaba kiša");
            case 63: return Lang.t("Rain", "Kiša");
            case 65: return Lang.t("Heavy rain", "Jaka kiša");
            case 71: case 73: case 77: return Lang.t("Snow", "Snijeg");
            case 75: return Lang.t("Heavy snow", "Jak snijeg");
            case 80: case 81: return Lang.t("Showers", "Pljuskovi");
            case 82: return Lang.t("Heavy showers", "Jaki pljuskovi");
            case 85: case 86: return Lang.t("Snow showers", "Snježni pljuskovi");
            case 95: return Lang.t("Thunderstorm", "Grmljavina");
            case 96: case 99: return Lang.t("Thunderstorm with hail", "Grmljavina s tučom");
            default: return "";
        }
    }

    static String symbol(int code, boolean day) {
        if (code == 0 || code == 1) return day ? "☀" : "☾";
        if (code == 2) return day ? "⛅" : "☁";
        if (code == 3) return "☁";
        if (code == 45 || code == 48) return "≋";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄";
        if (code >= 95) return "⚡";
        return "☂";
    }

    // ---------------------------------------------------------------- fetching and caching

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** Fetches, stores and returns. Called off the main thread. */
    static Weather fetch(Context context) throws IOException, JSONException {
        Place place = Place.resolve(context);
        String url = String.format(java.util.Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f", place.lat, place.lon) + QUERY;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        String json;
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            json = out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
        long now = System.currentTimeMillis();
        Weather w = new Weather(place.name, json, now); // throws before storing if the response is invalid
        store(context).edit().putString("json", json).putLong("at", now).putString("place", place.name).apply();
        return w;
    }

    /** Stored weather, but only for the location currently in settings. */
    static Weather load(Context context) {
        SharedPreferences sp = store(context);
        String json = sp.getString("json", null);
        Place place = Place.current(context);
        if (json == null || place == null || !place.name.equals(sp.getString("place", ""))) return null;
        try {
            return new Weather(place.name, json, sp.getLong("at", 0));
        } catch (JSONException e) {
            return null;
        }
    }
}
