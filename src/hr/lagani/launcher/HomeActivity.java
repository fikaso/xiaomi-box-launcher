package hr.lagani.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimalni početni ekran: bez preporuka i bez mreže.
 * Samo mreža instaliranih aplikacija, sat i par gumba, u "staklenom" stilu Apple TV-a
 * (vidi {@link Glass}). Jedina animacija je kratko podizanje kartice u fokusu.
 */
public class HomeActivity extends Activity {

    private static final int COLUMNS = 5;
    private static final int GAP_DP = 28;
    private static final int RADIUS_DP = 14;
    private static final float LIFT = 1.1f;
    private static final int LIFT_MS = 140;
    private static final Interpolator EASE = new DecelerateInterpolator();

    static final class App {
        final String pkg;
        final String label;
        final Drawable art;
        final boolean isBanner;
        /** Banner sa zaobljenim kutovima za trenutnu širinu kartice (vidi Glass.roundedBanner). */
        Drawable rounded;
        int roundedWidth;

        App(String pkg, String label, Drawable art, boolean isBanner) {
            this.pkg = pkg;
            this.label = label;
            this.art = art;
            this.isBanner = isBanner;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<App> apps = new ArrayList<>();
    private Prefs prefs;
    private GridView grid;
    private Button autostartButton;
    private AppAdapter adapter;
    private boolean dirty = true;
    private int loadGeneration;
    private LinearLayout weatherPanel;
    private TextView weatherSymbol;
    private TextView weatherNow;
    private TextView weatherInfo;
    private final TextView[][] forecast = new TextView[3][3]; // [dan][naziv, znak, temperature]
    private boolean weatherLoading;
    private final TextView[][] agendaRows = new TextView[3][2]; // [redak][dan, sat i naslov]
    private TextView agendaEmpty;
    private android.widget.ScrollView todoScroll;
    private LinearLayout todoList;
    private TextView todoEmpty;
    private View agendaPanel;
    private View monthPanel;
    private TextView monthWeekday;
    private TextView monthDay;
    private TextView monthName;
    private TextView monthHoliday;
    private final LinearLayout[] monthRows = new LinearLayout[6];
    private final TextView[] monthCells = new TextView[42];
    private LinearLayout noticeRow;
    private TextView warningPill;
    private TextView matchPill;
    private TextView hint;

    /** Praznici i dan u tjednu na ploči mjeseca: topla crvena kao u Appleovom kalendaru. */
    private static final int HOLIDAY = Color.rgb(255, 107, 94);
    /** Dan s događajem iz osobnog kalendara (samo MIBOX4): plavo kao na iPhoneu. */
    private static final int EVENT = Color.rgb(100, 190, 255);
    private static final String[] WEEKDAYS = {"nedjelja", "ponedjeljak", "utorak", "srijeda", "četvrtak", "petak", "subota"};
    private static final String[] MONTHS = {"siječanj", "veljača", "ožujak", "travanj", "svibanj", "lipanj",
            "srpanj", "kolovoz", "rujan", "listopad", "studeni", "prosinac"};

    /** Svakih 30 min: vrijeme (ako je staro) i kalendar (zbog prelaska na novi dan). */
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather();
            refreshAgenda();
            refreshNotices();
            ui.postDelayed(this, Weather.REFRESH_MS);
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dirty = true;
            if (hasWindowFocus()) reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(buildUi());
        if (Agenda.personal(this) && !Agenda.allowed(this) && android.os.Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, 2);
        }

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.autostartPending()) {
            prefs.setAutostartPending(false);
            if (prefs.autostart() && launch(prefs.autostartPackage())) return;
        }
        if (dirty) reload();
        updateAutostartButton();
        showWeather(Weather.load(this));
        ui.removeCallbacks(weatherTick);
        weatherTick.run();
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(weatherTick);
        super.onPause();
    }

    // ---------------------------------------------------------------- Vrijeme

    private void refreshWeather() {
        Weather cached = Weather.load(this);
        if (weatherLoading || (cached != null && cached.isFresh())) return;
        weatherLoading = true;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Weather w = null;
                try {
                    w = Weather.fetch(app);
                } catch (Exception e) {
                    // Nema mreže ili servis ne radi: ostaje zadnje poznato vrijeme.
                    android.util.Log.w("LaganiLauncher", "Vrijeme nije dohvaćeno", e);
                }
                final Weather result = w;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        weatherLoading = false;
                        if (result != null) showWeather(result);
                    }
                });
            }
        }, "weather").start();
    }

    private void showWeather(Weather w) {
        boolean show = w != null && w.isUsable();
        weatherPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        Weather.Day today = w.days.get(0);
        weatherSymbol.setText(Weather.symbol(w.code, w.day));
        weatherNow.setText(w.temp + "°");
        String desc = Weather.description(w.code, w.day);
        weatherInfo.setText(w.place + (desc.isEmpty() ? "" : " · " + desc)
                + "\n");
        // Sve trake dijele isto mjerilo (najniža i najviša od svih prikazanih dana).
        int lo = today.min, hi = today.max;
        for (int i = 1; i < w.days.size() && i <= forecast.length; i++) {
            lo = Math.min(lo, w.days.get(i).min);
            hi = Math.max(hi, w.days.get(i).max);
        }
        weatherInfo.append("Danas  ");
        weatherInfo.append(Glass.range(this, today.min, today.max, lo, hi, dp(30), dp(5)));
        for (int i = 0; i < forecast.length; i++) {
            boolean has = i + 1 < w.days.size();
            for (TextView t : forecast[i]) t.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            if (!has) continue;
            Weather.Day d = w.days.get(i + 1);
            forecast[i][0].setText(d.name);
            forecast[i][1].setText(Weather.symbol(d.code, true));
            forecast[i][2].setText(Glass.range(this, d.min, d.max, lo, hi, dp(20), dp(5)));
        }
    }

    // ---------------------------------------------------------------- Kalendar

    private void refreshAgenda() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean personal = Agenda.personal(app);
                // Više događaja nego što stane u popis: ostali samo obojaju svoj dan u mjesecu.
                final List<Agenda.Item> items = personal ? Agenda.upcoming(app, 40) : new ArrayList<Agenda.Item>();
                final List<Todo.Item> todos = personal ? Todo.open(app, 30) : null;
                // Do kraja idućeg mjeseca, da se vidi i sljedeći praznik kad ga ovaj mjesec nema.
                java.util.Calendar from = monthStart();
                java.util.Calendar to = (java.util.Calendar) from.clone();
                to.add(java.util.Calendar.MONTH, 2);
                final java.util.TreeMap<Long, String> holidays =
                        Agenda.holidays(app, from.getTimeInMillis(), to.getTimeInMillis());
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        agendaPanel.setVisibility(personal ? View.VISIBLE : View.GONE);
                        showMonth(holidays, items);
                        if (personal) {
                            showAgenda(items.size() > agendaRows.length
                                    ? items.subList(0, agendaRows.length) : items);
                            showTodo(todos);
                        }
                    }
                });
            }
        }, "agenda").start();
    }

    private void showAgenda(List<Agenda.Item> items) {
        int n = items == null ? 0 : items.size();
        for (int i = 0; i < agendaRows.length; i++) {
            boolean has = i < n;
            for (TextView t : agendaRows[i]) t.setVisibility(has ? View.VISIBLE : View.GONE);
            if (!has) continue;
            Agenda.Item it = items.get(i);
            // Dan se piše samo uz prvi događaj tog dana, kao u kalendaru na mobitelu.
            boolean sameDay = i > 0 && items.get(i - 1).day == it.day;
            agendaRows[i][0].setText(sameDay ? "" : Agenda.dayLabel(it.day));
            agendaRows[i][1].setText(it.time.isEmpty() ? it.title : it.time + "  " + it.title);
        }
        agendaEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        agendaEmpty.setText("Nema događaja");
    }

    // ---------------------------------------------------------------- Obavijesti

    private void refreshNotices() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Dhmz.Warning> warnings = Dhmz.current(app);
                final Klub.Match match = Klub.next(app);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showMatch(match);
                        showWarnings(warnings);
                    }
                });
            }
        }, "notices").start();
    }

    /** Najvažnije DHMZ upozorenje (ono koje traje, pa najviša razina); ostala samo kao "+N". */
    private void showWarnings(List<Dhmz.Warning> warnings) {
        warningPill.setVisibility(warnings.isEmpty() ? View.GONE : View.VISIBLE);
        if (!warnings.isEmpty()) {
            Dhmz.Warning w = warnings.get(0);
            int color = w.level >= 4 ? Color.rgb(255, 59, 48) : w.level == 3 ? Color.rgb(255, 149, 0) : Color.rgb(255, 204, 0);
            android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder("●  ");
            s.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
            s.append(w.event).append("  ·  ").append(Dhmz.when(w));
            if (warnings.size() > 1) s.append("  +").append(String.valueOf(warnings.size() - 1));
            warningPill.setText(s);
        }
        updateNoticeRow();
    }

    /** "⚽ Istra 1961 – Hajduk · sub 26. 10. u 18:00 · Max Sport 1" (natjecanje samo ako nije HNL); klub iz postavki. */
    private void showMatch(Klub.Match m) {
        matchPill.setVisibility(m == null ? View.GONE : View.VISIBLE);
        if (m != null) {
            android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder("⚽  ");
            s.append(m.home).append(" – ").append(m.away);
            int dim = s.length();
            s.append("  ·  ").append(Klub.when(m));
            if (!m.competition.isEmpty() && !"HNL".equals(m.competition)) s.append("  ·  ").append(m.competition);
            s.append("  ·  ").append(m.channels.isEmpty() ? "TV kanal još nije objavljen" : m.channels);
            s.setSpan(new android.text.style.ForegroundColorSpan(Glass.TEXT_DIM), dim, s.length(), 0);
            matchPill.setText(s);
        }
        updateNoticeRow();
    }

    private void updateNoticeRow() {
        boolean any = false;
        for (int i = 0; i < noticeRow.getChildCount(); i++) {
            any |= noticeRow.getChildAt(i).getVisibility() == View.VISIBLE;
        }
        noticeRow.setVisibility(any ? View.VISIBLE : View.GONE);
        // Redak obavijesti pomakne aplikacije prema dnu, pa uputa tada ne stane ispod njih.
        hint.setVisibility(any ? View.GONE : View.VISIBLE);
    }

    /** Tanki redak staklenih "pilula" ispod ploča: utakmica kluba i DHMZ upozorenje; skriven kad nema ničeg. */
    private View buildNoticeRow() {
        noticeRow = new LinearLayout(this);
        noticeRow.setOrientation(LinearLayout.HORIZONTAL);
        noticeRow.setVisibility(View.GONE);
        matchPill = notice();
        noticeRow.addView(matchPill);
        warningPill = notice();
        noticeRow.addView(warningPill);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        noticeRow.setLayoutParams(lp);
        return noticeRow;
    }

    private TextView notice() {
        TextView t = text(14, Glass.TEXT);
        t.setBackground(Glass.pane(dp(16), 22, 40, 1));
        t.setPadding(dp(14), dp(7), dp(16), dp(7));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = wrap();
        lp.rightMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    private static java.util.Calendar monthStart() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.DAY_OF_MONTH, 1);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c;
    }

    private void showMonth(java.util.TreeMap<Long, String> holidays, List<Agenda.Item> events) {
        Set<Long> eventDays = new java.util.HashSet<>();
        for (Agenda.Item it : events) eventDays.add(it.day);
        java.util.Calendar now = java.util.Calendar.getInstance();
        int today = now.get(java.util.Calendar.DAY_OF_MONTH);
        monthWeekday.setText(WEEKDAYS[now.get(java.util.Calendar.DAY_OF_WEEK) - 1].toUpperCase(new java.util.Locale("hr")));
        monthDay.setText(String.valueOf(today));
        monthName.setText(MONTHS[now.get(java.util.Calendar.MONTH)] + " " + now.get(java.util.Calendar.YEAR));

        java.util.Calendar day = monthStart();
        int offset = (day.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7; // tjedan počinje ponedjeljkom
        int last = day.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
        long todayStart = 0;
        for (int i = 0; i < monthCells.length; i++) {
            TextView cell = monthCells[i];
            int d = i - offset + 1;
            if (d < 1 || d > last) {
                cell.setText("");
                cell.setBackground(null);
                continue;
            }
            day.set(java.util.Calendar.DAY_OF_MONTH, d);
            boolean holiday = holidays.containsKey(day.getTimeInMillis());
            cell.setText(String.valueOf(d));
            if (d == today) {
                todayStart = day.getTimeInMillis();
                GradientDrawable mark = new GradientDrawable();
                mark.setShape(GradientDrawable.OVAL);
                mark.setColor(Color.argb(240, 255, 255, 255));
                int inset = (dp(26) - dp(17)) / 2;
                cell.setBackground(new android.graphics.drawable.InsetDrawable(mark, inset, 0, inset, 0));
                cell.setTextColor(Glass.TEXT_ON_FOCUS);
            } else {
                cell.setBackground(null);
                cell.setTextColor(holiday ? HOLIDAY : eventDays.contains(day.getTimeInMillis()) ? EVENT
                        : (i % 7 >= 5 ? Glass.TEXT_DIM : Glass.TEXT));
            }
        }
        for (int r = 0; r < monthRows.length; r++) {
            monthRows[r].setVisibility(r * 7 < offset + last ? View.VISIBLE : View.GONE);
        }

        Map.Entry<Long, String> next = holidays.ceilingEntry(todayStart);
        monthHoliday.setVisibility(next == null ? View.GONE : View.VISIBLE);
        if (next != null) {
            java.util.Calendar h = java.util.Calendar.getInstance();
            h.setTimeInMillis(next.getKey());
            String when = next.getKey() == todayStart ? "Danas"
                    : h.get(java.util.Calendar.DAY_OF_MONTH) + ". " + (h.get(java.util.Calendar.MONTH) + 1) + ".";
            monthHoliday.setText(when + "  " + next.getValue());
        }
    }

    private void showTodo(List<Todo.Item> todos) {
        // Ako je fokus na zadatku, zadrži ga na istom (ili susjednom) i nakon osvježavanja.
        View focused = todoList.findFocus();
        int focusIndex = focused == null ? -1 : todoList.indexOfChild(focused) - 1;
        todoList.removeViews(1, todoList.getChildCount() - 1);
        for (final Todo.Item task : todos) {
            TextView row = text(14, Glass.TEXT);
            row.setText("○  " + task.text);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setFocusable(true);
            row.setTextColor(Glass.pillText());
            row.setBackground(Glass.focusPill(dp(10)));
            row.setPadding(dp(8), dp(3), dp(8), dp(3));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { confirmDone(task); }
            });
            LinearLayout.LayoutParams lp = rowParams();
            lp.topMargin = dp(2);
            lp.leftMargin = -dp(8);
            todoList.addView(row, lp);
        }
        todoEmpty.setVisibility(todos.isEmpty() ? View.VISIBLE : View.GONE);
        if (focusIndex >= 0 && !todos.isEmpty()) {
            todoList.getChildAt(1 + Math.min(focusIndex, todos.size() - 1)).requestFocus();
        } else if (focused != null) {
            grid.requestFocus();
        }
    }

    private void confirmDone(final Todo.Item task) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Odrađeno?")
                .setMessage(task.text)
                .setPositiveButton("Da, odrađeno", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) { markDone(task); }
                })
                .setNegativeButton("Odustani", null)
                .show();
    }

    private void markDone(final Todo.Item task) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Todo.markDone(app, task);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(ok ? "Označeno kao odrađeno" : "Nije uspjelo, provjeri internet");
                        if (ok) refreshAgenda();
                    }
                });
            }
        }, "todo-done").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        refreshAgenda();
    }

    /** Redak ispod gumba: lijevo vrijeme (danas i 3 dana), desno današnji datum i mjesec. */
    private View buildInfoRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(buildWeatherPanel());
        row.addView(buildMonthPanel());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * Ploča cijelom širinom ispod vremena i mjeseca, samo uz osobni kalendar ili Todoist:
     * lijevo "Događaji", desno "Todo".
     */
    private View buildAgendaPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setBackground(Glass.pane(dp(18), 22, 40, 1));
        panel.setPadding(dp(18), dp(10), dp(14), dp(10));

        LinearLayout events = column("Događaji");
        for (int i = 0; i < agendaRows.length; i++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            agendaRows[i][0] = text(13, Glass.TEXT_DIM);
            agendaRows[i][1] = text(14, Glass.TEXT);
            agendaRows[i][1].setSingleLine(true);
            agendaRows[i][1].setEllipsize(android.text.TextUtils.TruncateAt.END);
            line.addView(agendaRows[i][0], new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            line.addView(agendaRows[i][1], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            events.addView(line, rowParams());
        }
        agendaEmpty = text(14, Glass.TEXT_DIM);
        agendaEmpty.setVisibility(View.GONE);
        events.addView(agendaEmpty, rowParams());
        panel.addView(events, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(46, 255, 255, 255));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.leftMargin = dp(12);
        dlp.rightMargin = dp(14);
        panel.addView(divider, dlp);

        // Todo: zadaci se biraju daljinskim (gore iz aplikacija), OK ih označi odrađenima.
        // Više zadataka nego što stane: popis se pomiče za fokusom.
        LinearLayout todo = column("Todo");
        todoScroll = new android.widget.ScrollView(this);
        todoScroll.setVerticalScrollBarEnabled(false);
        todoScroll.setFillViewport(true);
        todoList = new LinearLayout(this);
        todoList.setOrientation(LinearLayout.VERTICAL);
        todoScroll.addView(todoList);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        slp.topMargin = dp(2);
        todo.addView(todoScroll, slp);
        todoEmpty = text(14, Glass.TEXT_DIM);
        todoEmpty.setText("Sve odrađeno");
        todoEmpty.setVisibility(View.GONE);
        todoList.addView(todoEmpty, rowParams());
        panel.addView(todo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        panel.setLayoutParams(lp);
        panel.setPadding(dp(18), dp(8), dp(14), dp(8));
        panel.setVisibility(Agenda.personal(this) ? View.VISIBLE : View.GONE);
        agendaPanel = panel;
        return panel;
    }

    /** Ploča kao Appleov widget kalendara: lijevo veliki današnji dan, desno cijeli mjesec. */
    private View buildMonthPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackground(Glass.pane(dp(18), 22, 40, 1));
        panel.setPadding(dp(20), dp(10), dp(16), dp(10));

        LinearLayout today = new LinearLayout(this);
        today.setOrientation(LinearLayout.VERTICAL);
        monthWeekday = text(13, HOLIDAY);
        monthWeekday.setLetterSpacing(0.08f);
        monthWeekday.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        today.addView(monthWeekday);
        monthDay = text(44, Glass.TEXT);
        monthDay.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams dlp = wrap();
        dlp.topMargin = dp(4);
        today.addView(monthDay, dlp);
        monthName = text(13, Glass.TEXT_DIM);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = dp(4);
        today.addView(monthName, nlp);
        monthHoliday = text(13, Glass.TEXT_DIM);
        monthHoliday.setSingleLine(true);
        monthHoliday.setEllipsize(android.text.TextUtils.TruncateAt.END);
        today.addView(monthHoliday, rowParams());
        panel.addView(today, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(46, 255, 255, 255));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.leftMargin = dp(12);
        vlp.rightMargin = dp(12);
        panel.addView(divider, vlp);

        LinearLayout days = new LinearLayout(this);
        days.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        for (String letter : new String[]{"P", "U", "S", "Č", "P", "S", "N"}) {
            TextView t = text(11, Glass.TEXT_DIM);
            t.setGravity(Gravity.CENTER);
            header.addView(t, new LinearLayout.LayoutParams(dp(26), dp(16)));
            t.setText(letter);
        }
        days.addView(header);
        for (int r = 0; r < monthRows.length; r++) {
            monthRows[r] = new LinearLayout(this);
            for (int c = 0; c < 7; c++) {
                TextView cell = text(12, Glass.TEXT);
                cell.setGravity(Gravity.CENTER);
                monthRows[r].addView(cell, new LinearLayout.LayoutParams(dp(26), dp(17)));
                monthCells[r * 7 + c] = cell;
            }
            days.addView(monthRows[r]);
        }
        panel.addView(days, wrap());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.leftMargin = dp(16);
        panel.setLayoutParams(lp);
        monthPanel = panel;
        return panel;
    }

    private LinearLayout column(String title) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView header = text(12, Glass.TEXT_DIM);
        header.setText(title.toUpperCase(java.util.Locale.ROOT));
        header.setLetterSpacing(0.08f);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(header);
        return col;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        return lp;
    }

    /** Staklena ploča: lijevo trenutno vrijeme i danas, desno sljedeća tri dana. */
    private View buildWeatherPanel() {
        weatherPanel = new LinearLayout(this);
        weatherPanel.setOrientation(LinearLayout.HORIZONTAL);
        weatherPanel.setGravity(Gravity.CENTER_VERTICAL);
        weatherPanel.setBackground(Glass.pane(dp(18), 22, 40, 1));
        weatherPanel.setPadding(dp(20), dp(12), dp(8), dp(12));
        weatherPanel.setVisibility(View.GONE);

        // Lijevo: znak i temperatura, ispod mjesto, opis i današnji raspon.
        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.VERTICAL);
        LinearLayout big = new LinearLayout(this);
        big.setOrientation(LinearLayout.HORIZONTAL);
        big.setGravity(Gravity.CENTER_VERTICAL);
        weatherSymbol = text(34, Glass.TEXT);
        big.addView(weatherSymbol);
        weatherNow = text(40, Glass.TEXT);
        weatherNow.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams nlp = wrap();
        nlp.leftMargin = dp(12);
        big.addView(weatherNow, nlp);
        now.addView(big);
        weatherInfo = text(13, Glass.TEXT_DIM);
        weatherInfo.setLineSpacing(dp(1), 1f);
        weatherInfo.setMaxWidth(dp(185));
        weatherInfo.setMaxLines(2);
        weatherInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ilp = wrap();
        ilp.topMargin = dp(6);
        now.addView(weatherInfo, ilp);
        LinearLayout.LayoutParams wlp = wrap();
        wlp.rightMargin = dp(10);
        weatherPanel.addView(now, wlp);

        for (int i = 0; i < forecast.length; i++) {
            View divider = new View(this);
            divider.setBackgroundColor(Color.argb(46, 255, 255, 255));
            weatherPanel.addView(divider, new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(52)));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            forecast[i][0] = text(13, Glass.TEXT_DIM);
            forecast[i][1] = text(22, Glass.TEXT);
            forecast[i][2] = text(14, Glass.TEXT);
            for (TextView t : forecast[i]) {
                t.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(t);
            }
            ((LinearLayout.LayoutParams) forecast[i][1].getLayoutParams()).topMargin = dp(4);
            ((LinearLayout.LayoutParams) forecast[i][2].getLayoutParams()).topMargin = dp(4);
            weatherPanel.addView(col, new LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        weatherPanel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return weatherPanel;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public void onBackPressed() {
        // Početni ekran nema kamo "natrag".
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            int pos = grid.getSelectedItemPosition();
            if (pos >= 0 && pos < apps.size()) showOptions(apps.get(pos));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Glass.background(this));
        root.setClipChildren(false);
        root.setPadding(dp(40), dp(27), dp(40), dp(14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), 0, dp(12), 0);

        autostartButton = topButton("");
        autostartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !prefs.autostart();
                prefs.setAutostart(on);
                updateAutostartButton();
                toast(on ? "TiviMate će se otvoriti sam nakon paljenja boxa" : "Automatsko pokretanje isključeno");
            }
        });
        top.addView(autostartButton);

        Button hiddenButton = topButton("Skrivene");
        hiddenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showHidden(); }
        });
        top.addView(hiddenButton);

        Button receive = topButton("Primi datoteke");
        receive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startSafely(new Intent(HomeActivity.this, ReceiveActivity.class)); }
        });
        top.addView(receive);

        Button settings = topButton("Postavke");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startSafely(new Intent(Settings.ACTION_SETTINGS)); }
        });
        top.addView(settings);

        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        time.setGravity(Gravity.END);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Glass.TEXT);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        time.addView(clock);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d. MMMM");
        date.setFormat12Hour("EEEE, d. MMMM");
        date.setTextColor(Glass.TEXT_DIM);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        time.addView(date);
        top.addView(time);

        root.addView(top);
        root.addView(buildInfoRow());
        root.addView(buildAgendaPanel());
        root.addView(buildNoticeRow());

        grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        // Razmak između stupaca je unutar ćelije (vidi getView), pa kartica u fokusu ima mjesta
        // da izraste. Ništa je ne smije rezati: ni mreža ni ćelija.
        grid.setHorizontalSpacing(0);
        grid.setVerticalSpacing(dp(10));
        grid.setPadding(0, dp(16), 0, dp(12));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        // Umjesto okvira oko odabrane ćelije kartica se podigne (highlight); tako ne ostane
        // nacrtan okvir kad fokus ode na gumbe gore.
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        grid.setCacheColorHint(Color.TRANSPARENT);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { highlight(true); }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { highlight(true); }
        });
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) { highlight(true); }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(apps.get(position).pkg);
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showOptions(apps.get(position));
                return true;
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        hint = new TextView(this);
        hint.setText("Dugi pritisak OK ili tipka Menu na aplikaciji: deinstaliraj, očisti cache, sakrij");
        hint.setTextColor(Color.argb(90, 255, 255, 255));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(dp(12), 0, 0, 0);
        root.addView(hint);
        return root;
    }

    private Button topButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Glass.pillText());
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setFocusable(true);
        b.setBackground(Glass.pill(dp(22), 1));
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(17), dp(9), dp(17), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    /** Kartica u fokusu se podigne i poveća, dobije sjenu i odsjaj, a ispod nje se pokaže naziv. */
    private void highlight(boolean animate) {
        int selected = grid.hasFocus() ? grid.getSelectedItemPosition() : AdapterView.INVALID_POSITION;
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            Object tag = grid.getChildAt(i).getTag();
            int pos = first + i;
            if (tag instanceof Holder && pos < apps.size()) {
                applyFocus((Holder) tag, apps.get(pos), pos == selected, animate);
            }
        }
    }

    private void applyFocus(Holder h, App app, boolean focused, boolean animate) {
        float scale = focused ? LIFT : 1f;
        // Natpis je već na banneru, pa se ispod pokazuje samo u fokusu; ikonama treba uvijek.
        float labelAlpha = focused ? 1f : (app.isBanner ? 0f : 0.7f);
        h.cell.setTranslationZ(focused ? 1 : 0); // crta se iznad susjeda
        h.card.setTranslationZ(focused ? dp(12) : 0); // sjena
        h.sheen.setAlpha(focused ? 255 : 0);
        if (animate) {
            h.card.animate().scaleX(scale).scaleY(scale).setDuration(LIFT_MS).setInterpolator(EASE).start();
            h.label.animate().alpha(labelAlpha).setDuration(LIFT_MS).start();
        } else {
            h.card.animate().cancel();
            h.card.setScaleX(scale);
            h.card.setScaleY(scale);
            h.label.animate().cancel();
            h.label.setAlpha(labelAlpha);
        }
    }

    private void updateAutostartButton() {
        autostartButton.setText(prefs.autostart() ? "Auto TiviMate: UKLJ" : "Auto TiviMate: ISKLJ");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- Aplikacije

    private void reload() {
        dirty = false;
        final int generation = ++loadGeneration;
        final Set<String> hidden = prefs.hidden();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<App> loaded = loadApps(hidden);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != loadGeneration) return;
                        apps.clear();
                        apps.addAll(loaded);
                        adapter.notifyDataSetChanged();
                        if (grid.getSelectedItemPosition() < 0 && !apps.isEmpty()) grid.setSelection(0);
                        grid.requestFocus();
                        grid.post(new Runnable() {
                            @Override
                            public void run() { highlight(false); }
                        });
                    }
                });
            }
        }, "load-apps").start();
    }

    private List<App> loadApps(Set<String> hidden) {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> byPkg = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
                byPkg.put(pkg, ri);
            }
        }

        List<App> result = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : byPkg.entrySet()) {
            ResolveInfo ri = e.getValue();
            Drawable art = null;
            boolean banner = false;
            try {
                art = ri.activityInfo.loadBanner(pm);
                if (art == null) art = pm.getApplicationBanner(ri.activityInfo.applicationInfo);
                banner = art != null;
                if (art == null) art = ri.loadIcon(pm);
            } catch (RuntimeException ignored) {
            }
            result.add(new App(e.getKey(), String.valueOf(ri.loadLabel(pm)), art, banner));
        }

        final Collator collator = Collator.getInstance();
        Collections.sort(result, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                int ra = Prefs.rank(a.pkg), rb = Prefs.rank(b.pkg);
                if (ra != rb) return ra < rb ? -1 : 1;
                return collator.compare(a.label, b.label);
            }
        });
        return result;
    }

    static Intent launchIntentFor(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    private boolean launch(String pkg) {
        Intent i = launchIntentFor(this, pkg);
        if (i == null) {
            toast("Aplikacija nije instalirana");
            return false;
        }
        return startSafely(i);
    }

    private boolean startSafely(Intent i) {
        try {
            if ((i.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            overridePendingTransition(0, 0);
            return true;
        } catch (RuntimeException e) {
            toast("Ne mogu otvoriti");
            return false;
        }
    }

    private void showOptions(final App app) {
        final String[] items = {
                "Otvori",
                "Informacije (deinstaliraj / očisti cache / prisilno zaustavi)",
                "Sakrij s početnog ekrana",
                "Otvaraj ovu aplikaciju nakon paljenja boxa",
        };
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(app.label)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                launch(app.pkg);
                                break;
                            case 1:
                                startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", app.pkg, null)));
                                break;
                            case 2:
                                Set<String> h = prefs.hidden();
                                h.add(app.pkg);
                                prefs.setHidden(h);
                                reload();
                                break;
                            case 3:
                                prefs.setAutostartPackage(app.pkg);
                                prefs.setAutostart(true);
                                updateAutostartButton();
                                toast(app.label + " će se otvoriti nakon paljenja boxa");
                                break;
                        }
                    }
                })
                .show();
    }

    private void showHidden() {
        final List<String> hidden = new ArrayList<>(prefs.hidden());
        if (hidden.isEmpty()) {
            toast("Nema skrivenih aplikacija");
            return;
        }
        Collections.sort(hidden);
        PackageManager pm = getPackageManager();
        String[] labels = new String[hidden.size()];
        for (int i = 0; i < labels.length; i++) {
            String label = hidden.get(i);
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(label, 0)));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            labels[i] = label;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Vrati na početni ekran")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Set<String> h = prefs.hidden();
                        h.remove(hidden.get(which));
                        prefs.setHidden(h);
                        reload();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- Adapter

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout cell = new LinearLayout(HomeActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setClipChildren(false);
                cell.setClipToPadding(false);
                cell.setPadding(dp(GAP_DP / 2f), dp(8), dp(GAP_DP / 2f), dp(4));

                // Kartica je staklena ploha zaobljenih rubova; banner se reže po istom obrisu.
                final int radius = dp(RADIUS_DP);
                FrameLayout glass = new FrameLayout(HomeActivity.this);
                glass.setBackground(Glass.pane(radius, 26, 40, 1));
                GradientDrawable sheen = Glass.sheen(radius, dp(1.5f));
                sheen.setAlpha(0);
                glass.setForeground(sheen);
                ImageView img = new ImageView(HomeActivity.this);
                glass.addView(img, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                // Omotač se podiže i baca sjenu; obris mu treba samo za oblik sjene.
                FrameLayout card = new FrameLayout(HomeActivity.this);
                card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                });
                card.addView(glass, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                int tileWidth = (grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight()) / COLUMNS
                        - 2 * dp(GAP_DP / 2f);
                if (tileWidth <= 0) tileWidth = dp(150);
                cell.addView(card, new LinearLayout.LayoutParams(tileWidth, tileWidth * 9 / 16));

                TextView label = new TextView(HomeActivity.this);
                label.setTextColor(Glass.TEXT);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                label.setGravity(Gravity.CENTER_HORIZONTAL);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setPadding(0, dp(12), 0, 0);
                label.setShadowLayer(dp(4), 0, dp(1), Color.argb(160, 0, 0, 0));
                cell.addView(label, new LinearLayout.LayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

                h = new Holder(cell, card, sheen, img, label);
                cell.setTag(h);
                convertView = cell;
            } else {
                h = (Holder) convertView.getTag();
            }
            App app = apps.get(position);
            if (app.isBanner) {
                int w = h.card.getLayoutParams().width, ht = h.card.getLayoutParams().height;
                if (app.rounded == null || app.roundedWidth != w) {
                    app.rounded = Glass.roundedBanner(HomeActivity.this, app.art, w, ht, dp(RADIUS_DP));
                    app.roundedWidth = w;
                }
                h.img.setImageDrawable(app.rounded);
                h.img.setScaleType(ImageView.ScaleType.FIT_XY);
                h.img.setPadding(0, 0, 0, 0);
            } else {
                h.img.setImageDrawable(app.art);
                h.img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.img.setPadding(dp(18), dp(18), dp(18), dp(18));
            }
            h.label.setText(app.label);
            applyFocus(h, app, grid.hasFocus() && position == grid.getSelectedItemPosition(), false);
            return convertView;
        }
    }

    private static final class Holder {
        final View cell;
        final View card;
        final Drawable sheen;
        final ImageView img;
        final TextView label;

        Holder(View cell, View card, Drawable sheen, ImageView img, TextView label) {
            this.cell = cell;
            this.card = card;
            this.sheen = sheen;
            this.img = img;
            this.label = label;
        }
    }
}
