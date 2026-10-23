package hr.lagani.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/**
 * The "Primi datoteke" (receive files) screen: while it is open, you open the shown address
 * in a browser on a laptop or phone and send files. An APK is installed by clicking it.
 */
public class ReceiveActivity extends Activity {

    private static final int PORT = 8080;
    private static final int ACCENT = Color.rgb(66, 165, 245);

    private FileServer server;
    private TextView address;
    private TextView status;
    private ArrayAdapter<String> adapter;
    private final List<File> files = new ArrayList<>();

    /** Download/TV Box if we may write to Download, otherwise the app's folder (no permission needed). */
    static File receiveDir(Context context) {
        boolean canWritePublic = Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (canWritePublic) {
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TV Box");
        }
        File dir = context.getExternalFilesDir("Received");
        return dir != null ? dir : new File(context.getFilesDir(), "Received");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // The save folder depends on the permission, so restart the server.
        stopServer();
        startServer();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startServer();
    }

    @Override
    protected void onStop() {
        stopServer();
        super.onStop();
    }

    private void startServer() {
        if (server != null) return;
        File dir = receiveDir(this);
        server = new FileServer(dir, UploadPage.HTML, new FileServer.Listener() {
            @Override
            public void onReceived(final File file) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("Primljeno: " + file.getName());
                        refreshFiles();
                    }
                });
            }
        });
        try {
            int port = server.start(PORT);
            String ip = localIp();
            address.setText(ip == null ? "Box nije spojen na mrežu" : "http://" + ip + (port == 80 ? "" : ":" + port));
            status.setText("Čekam datoteke…  (sprema se u " + dir.getAbsolutePath() + ")");
        } catch (IOException e) {
            server = null;
            address.setText("Ne mogu pokrenuti primanje");
            status.setText(String.valueOf(e.getMessage()));
        }
        refreshFiles();
    }

    private void stopServer() {
        if (server != null) server.stop();
        server = null;
    }

    private static String localIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String name = ni.getName();
                    if (name.startsWith("eth") || name.startsWith("wlan")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (IOException ignored) {
        }
        return fallback;
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Glass.background(this));
        root.setPadding(dp(48), dp(27), dp(48), dp(16));

        TextView title = text("Primi datoteke", 28, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        root.addView(text("Na laptopu ili mobitelu (ista mreža) otvori u pregledniku:", 16,
                Color.argb(200, 255, 255, 255)), withTop(dp(18)));

        address = text("…", 40, ACCENT);
        address.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(address, withTop(dp(6)));

        status = text("", 14, Color.argb(160, 255, 255, 255));
        root.addView(status, withTop(dp(6)));

        root.addView(text("Primljene datoteke (OK: otvori ili instaliraj, dugi pritisak: obriši)", 15,
                Color.argb(200, 255, 255, 255)), withTop(dp(22)));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(focusBackground());
        list.setDrawSelectorOnTop(false);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Color.WHITE);
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(files.get(position));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(files.get(position));
                return true;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        root.addView(list, lp);
        return root;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static LinearLayout.LayoutParams withTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private StateListDrawable focusBackground() {
        GradientDrawable on = Glass.pane(dp(12), 46, 90, 1);
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, on);
        d.addState(new int[]{android.R.attr.state_selected}, on);
        d.addState(new int[]{android.R.attr.state_pressed}, on);
        return d;
    }

    // ---------------------------------------------------------------- Files

    private void refreshFiles() {
        files.clear();
        File[] all = receiveDir(this).listFiles();
        if (all != null) {
            for (File f : all) if (f.isFile() && !f.getName().endsWith(".part")) files.add(f);
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        List<String> labels = new ArrayList<>();
        for (File f : files) labels.add(f.getName() + "   (" + size(f.length()) + ")");
        adapter.clear();
        adapter.addAll(labels);
    }

    private static String size(long b) {
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", b / (1024.0 * 1024.0));
    }

    private void open(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(FilesProvider.uriFor(f), FilesProvider.mimeOf(f.getName()));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Nema aplikacije koja otvara ovu datoteku", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Obrisati " + f.getName() + "?")
                .setPositiveButton("Obriši", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        f.delete();
                        refreshFiles();
                    }
                })
                .setNegativeButton("Odustani", null)
                .show();
    }
}
