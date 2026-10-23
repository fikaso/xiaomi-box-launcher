package hr.lagani.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Nakon paljenja boxa označi da launcher treba otvoriti TiviMate (ako je to uključeno). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Prefs prefs = new Prefs(context);
        if (!prefs.autostart()) return;
        prefs.setAutostartPending(true);
        // Na Androidu 9 i starijem možemo odmah otvoriti aplikaciju; na novijima to
        // napravi launcher čim se prikaže (vidi HomeActivity.onResume).
        Intent launch = HomeActivity.launchIntentFor(context, prefs.autostartPackage());
        if (launch != null && android.os.Build.VERSION.SDK_INT < 29) {
            try {
                context.startActivity(launch);
                prefs.setAutostartPending(false);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
