package de.omatv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * Startet Oma TV von selbst, wenn der Stick neu hochfaehrt.
 * Ab Android 10 erlaubt das System das nur, wenn fuer die App
 * "Ueber anderen Apps einblenden" eingeschaltet wurde (siehe Anleitung).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        String sources = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_SOURCES, "");
        if (sources == null || sources.trim().isEmpty()) return; // noch nicht eingerichtet
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(context)) return;
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        } catch (Exception ignored) {
            // Manche Geraete verbieten den Autostart ganz. Dann startet Oma die App ueber die Startseite.
        }
    }
}
