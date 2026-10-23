package hr.lagani.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Todo iz Todoista: neodrađeni zadaci iz projekta "TV" (zajednički projekt obitelji).
 * U kalendari.txt redak "todoist: <API ključ>" (po želji iza ključa ime projekta, inače "TV").
 * Zadnji odgovor se pamti, pa bez mreže ostaje zadnje poznato stanje.
 */
final class Todo {

    static final class Item {
        final String id;
        final String text;

        Item(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    private static final String API = "https://api.todoist.com/api/v1/";
    private static final long REFRESH_MS = 5 * 60 * 1000L;
    /** Zadaci označeni na TV-u ostaju skriveni i ako se stari odgovor još koristi. */
    private static final long HIDE_MS = 60 * 60 * 1000L;

    private Todo() {
    }

    /** {ključ, ime projekta} iz kalendari.txt, ili null ako Todoist nije postavljen. */
    private static String[] config(Context context) {
        File f = new File(ReceiveActivity.receiveDir(context), Agenda.CONFIG);
        if (!f.isFile()) return null;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            try {
                for (String line; (line = r.readLine()) != null; ) {
                    line = line.trim();
                    if (!line.toLowerCase(java.util.Locale.ROOT).startsWith("todoist")) continue;
                    String rest = line.substring(line.indexOf(':') + 1).trim();
                    if (rest.isEmpty()) continue;
                    String[] parts = rest.split("\\s+", 2);
                    return new String[]{parts[0], parts.length > 1 ? parts[1].trim() : "TV"};
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    static boolean configured(Context context) {
        return config(context) != null;
    }

    private static android.content.SharedPreferences store(Context context) {
        return context.getSharedPreferences("todo", Context.MODE_PRIVATE);
    }

    /** Zove se izvan glavne niti. */
    static List<Item> open(Context context, int max) {
        List<Item> items = new ArrayList<>();
        String[] cfg = config(context);
        if (cfg == null) return items;
        File cache = new File(context.getFilesDir(), "todoist.json");
        String json = null;
        if (!cache.isFile() || System.currentTimeMillis() - cache.lastModified() > REFRESH_MS) {
            try {
                json = fetchTasks(cfg[0], cfg[1]);
                write(cache, json);
            } catch (IOException | JSONException ignored) {
                // Nema mreže ili ključ ne valja: ostaje zadnje poznato.
            }
        }
        if (json == null) json = read(cache);
        if (json == null) return items;
        try {
            JSONArray tasks = new JSONArray(json);
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < tasks.length(); i++) list.add(tasks.getJSONObject(i));
            // Prvo oni s rokom (najraniji prvi), zatim redoslijed iz aplikacije.
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    String da = due(a), db = due(b);
                    if (!da.equals(db)) {
                        if (da.isEmpty()) return 1;
                        if (db.isEmpty()) return -1;
                        return da.compareTo(db);
                    }
                    return Integer.compare(a.optInt("child_order"), b.optInt("child_order"));
                }
            });
            for (JSONObject t : list) {
                String id = t.optString("id");
                if (t.optBoolean("checked") || hiddenLocally(context, id)) continue;
                items.add(new Item(id, t.optString("content").trim()));
                if (items.size() >= max) break;
            }
        } catch (JSONException ignored) {
        }
        return items;
    }

    private static String due(JSONObject task) {
        JSONObject d = task.optJSONObject("due");
        return d == null ? "" : d.optString("date");
    }

    /** Označi zadatak odrađenim u Todoistu. Zove se izvan glavne niti. */
    static boolean markDone(Context context, Item item) {
        String[] cfg = config(context);
        if (cfg == null) return false;
        try {
            HttpURLConnection c = open(cfg[0], API + "tasks/" + item.id + "/close");
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.getOutputStream().close();
            try {
                int code = c.getResponseCode();
                if (code / 100 != 2) return false;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
        store(context).edit().putLong("done|" + item.id, System.currentTimeMillis()).apply();
        return true;
    }

    private static boolean hiddenLocally(Context context, String id) {
        long at = store(context).getLong("done|" + id, 0);
        return at > 0 && System.currentTimeMillis() - at < HIDE_MS;
    }

    // ---------------------------------------------------------------- API

    /** Svi aktivni zadaci projekta, kao JSON polje. */
    private static String fetchTasks(String token, String projectName) throws IOException, JSONException {
        String projectId = null;
        for (JSONObject p : getAll(token, API + "projects")) {
            if (p.optString("name").trim().equalsIgnoreCase(projectName)) {
                projectId = p.optString("id");
                break;
            }
        }
        if (projectId == null) throw new IOException("Nema projekta " + projectName);
        JSONArray out = new JSONArray();
        for (JSONObject t : getAll(token, API + "tasks?project_id=" + projectId)) out.put(t);
        return out.toString();
    }

    /** GET s listanjem po stranicama ({"results": [...], "next_cursor": ...}). */
    private static List<JSONObject> getAll(String token, String url) throws IOException, JSONException {
        List<JSONObject> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            String u = url + (url.contains("?") ? "&" : "?") + "limit=200"
                    + (cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8"));
            HttpURLConnection c = open(token, u);
            String body;
            try {
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                body = readAll(c.getInputStream());
            } finally {
                c.disconnect();
            }
            JSONArray results;
            String trimmed = body.trim();
            if (trimmed.startsWith("[")) {          // stariji oblik odgovora: samo polje
                results = new JSONArray(trimmed);
                cursor = null;
            } else {
                JSONObject o = new JSONObject(trimmed);
                results = o.optJSONArray("results");
                cursor = o.isNull("next_cursor") ? null : o.optString("next_cursor", null);
            }
            if (results != null) for (int i = 0; i < results.length(); i++) all.add(results.getJSONObject(i));
            if (cursor == null || cursor.isEmpty()) break;
        }
        return all;
    }

    private static HttpURLConnection open(String token, String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static void write(File f, String s) throws IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try {
            out.write(s.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static String read(File f) {
        if (!f.isFile()) return null;
        try {
            return readAll(new java.io.FileInputStream(f));
        } catch (IOException e) {
            return null;
        }
    }
}
