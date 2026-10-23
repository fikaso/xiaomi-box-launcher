package hr.lagani.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Location for the forecast and DHMZ warnings. Chosen with a line "location: Osijek" in {@code settings.txt}
 * (sent via "Primi datoteke" (receive files), like calendars.txt); coordinates and county are looked up
 * once with Open-Meteo geocoding and stored. Without settings it is Zagreb.
 */
final class Place {

    private static final Place DEFAULT = new Place("Zagreb", 45.81, 15.98, "Grad Zagreb");

    final String name;
    final double lat;
    final double lon;
    /** County as DHMZ writes it: "Osječko-baranjska", "Grad Zagreb". */
    final String county;

    private Place(String name, double lat, double lon, String county) {
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.county = county;
    }

    /** Stored location, without network; null until the location from settings has been found. */
    static Place current(Context context) {
        String wanted = configured(context);
        if (wanted == null) return DEFAULT;
        SharedPreferences sp = store(context);
        if (!wanted.equalsIgnoreCase(sp.getString("query", ""))) return null;
        return new Place(sp.getString("name", wanted), Double.parseDouble(sp.getString("lat", "0")),
                Double.parseDouble(sp.getString("lon", "0")), sp.getString("county", ""));
    }

    /** Like {@link #current}, but looks up a new location from settings online. Called off the main thread. */
    static Place resolve(Context context) throws IOException {
        Place p = current(context);
        if (p != null) return p;
        String wanted = configured(context);
        try {
            String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=hr&countryCode=HR&name="
                    + URLEncoder.encode(wanted, "UTF-8");
            JSONArray results = new JSONObject(get(url)).optJSONArray("results");
            if (results == null || results.length() == 0) throw new IOException("No location " + wanted);
            JSONObject r = results.getJSONObject(0);
            String county = r.optString("admin1").replace(" županija", "").trim();
            store(context).edit()
                    .putString("query", wanted)
                    .putString("name", r.getString("name"))
                    .putString("lat", String.valueOf(r.getDouble("latitude")))
                    .putString("lon", String.valueOf(r.getDouble("longitude")))
                    .putString("county", county)
                    .apply();
            return current(context);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    private static String configured(Context context) {
        return UserConfig.get(context, "location");
    }

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences("place", Context.MODE_PRIVATE);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
