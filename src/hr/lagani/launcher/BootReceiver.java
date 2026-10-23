package hr.lagani.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After the box boots, flags that the launcher should open TiviMate (if enabled). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Prefs prefs = new Prefs(context);
        if (!prefs.autostart()) return;
        prefs.setAutostartPending(true);
        // On Android 9 and older we can open the app right away; on newer versions the
        // launcher does it as soon as it is shown (see HomeActivity.onResume).
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
