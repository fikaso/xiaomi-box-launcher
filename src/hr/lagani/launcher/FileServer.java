package hr.lagani.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Mali HTTP server za primanje datoteka iz preglednika na istoj mreži.
 * GET /  -> stranica za odabir datoteka
 * PUT /upload?name=... -> tijelo zahtjeva se spremi kao datoteka u {@code dir}
 */
final class FileServer {

    interface Listener {
        void onReceived(File file);
    }

    private final File dir;
    private final String page;
    private final Listener listener;
    private ServerSocket server;
    private volatile boolean running;

    FileServer(File dir, String page, Listener listener) {
        this.dir = dir;
        this.page = page;
        this.listener = listener;
    }

    /** Pokreće server na prvom slobodnom portu od {@code firstPort}; vraća port. */
    int start(int firstPort) throws IOException {
        IOException last = null;
        for (int port = firstPort; port < firstPort + 10; port++) {
            try {
                server = new ServerSocket(port);
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) throw last;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() { acceptLoop(); }
        }, "file-server");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running) {
            final Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() { handle(s); }
            }, "file-server-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";

            if (method.equals("GET") && (target.equals("/") || target.startsWith("/?"))) {
                respond(out, "200 OK", "text/html; charset=utf-8", page);
            } else if ((method.equals("PUT") || method.equals("POST")) && target.startsWith("/upload?")) {
                receive(in, out, target, headers);
            } else {
                respond(out, "404 Not Found", "text/plain; charset=utf-8", "Nema");
            }
        } catch (IOException ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void receive(InputStream in, OutputStream out, String target, Map<String, String> headers)
            throws IOException {
        String name = safeName(query(target, "name"));
        long length;
        try {
            length = Long.parseLong(headers.get("content-length"));
        } catch (RuntimeException e) {
            respond(out, "411 Length Required", "text/plain; charset=utf-8", "Nedostaje Content-Length");
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            respond(out, "500 Internal Server Error", "text/plain; charset=utf-8", "Ne mogu spremiti");
            return;
        }

        File part = new File(dir, name + ".part");
        byte[] buf = new byte[64 * 1024];
        long remaining = length;
        try (FileOutputStream f = new FileOutputStream(part)) {
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                f.write(buf, 0, n);
                remaining -= n;
            }
        }
        if (remaining > 0) {
            part.delete();
            return;
        }
        File dest = unique(name);
        if (!part.renameTo(dest)) {
            part.delete();
            respond(out, "500 Internal Server Error", "text/plain; charset=utf-8", "Ne mogu spremiti");
            return;
        }
        listener.onReceived(dest);
        respond(out, "200 OK", "text/plain; charset=utf-8", dest.getName());
    }

    private File unique(String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    static String safeName(String raw) {
        String n = raw == null ? "" : raw.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isEmpty()) n = "datoteka";
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

    private static String query(String target, String key) {
        String q = target.substring(target.indexOf('?') + 1);
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) {
                try {
                    return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void respond(OutputStream out, String status, String type, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    /** Čita jednu liniju zaglavlja (do CRLF); null na kraju toka. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 16 * 1024) throw new IOException("predugačko zaglavlje");
        }
        if (c == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }
}
