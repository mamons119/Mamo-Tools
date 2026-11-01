package it.mamotools;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.util.Locale;

/** Widget Volume Boost: acceso/spento e guadagno a passi di 1 e 5 dB. */
public class BoostWidgetProvider extends AppWidgetProvider {

    private static final String ACTION_TOGGLE = "it.mamotools.widget.TOGGLE";
    private static final String ACTION_GAIN = "it.mamotools.widget.GAIN";
    private static final String EXTRA_DB = "db";
    private static final int MAX_GAIN_MB = 10000;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        refresh(ctx);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (ACTION_TOGGLE.equals(a) || ACTION_GAIN.equals(a)) {
            SharedPreferences p = ctx.getSharedPreferences("boost", Context.MODE_PRIVATE);
            SharedPreferences.Editor ed = p.edit();
            if (ACTION_TOGGLE.equals(a)) {
                boolean on = !p.getBoolean("boost_on", false);
                ed.putBoolean("boost_on", on);
                if (on && p.getInt("gain_mb", 0) <= 0) {
                    ed.putInt("gain_mb", 300);
                }
            } else {
                int g = p.getInt("gain_mb", 300) + intent.getIntExtra(EXTRA_DB, 0) * 100;
                ed.putInt("gain_mb", Math.max(0, Math.min(MAX_GAIN_MB, g)));
            }
            ed.apply();
            syncService(ctx, p);
            refresh(ctx);
            return;
        }
        super.onReceive(ctx, intent);
    }

    private static void syncService(Context ctx, SharedPreferences p) {
        boolean any = p.getBoolean("boost_on", false)
                || p.getBoolean("eq_on", false)
                || (p.getBoolean("bal_fx", true) && p.getFloat("bal", 0f) != 0f);
        Intent i = new Intent(ctx, BoostService.class);
        try {
            if (any) {
                i.setAction(BoostService.ACTION_APPLY);
                ctx.startForegroundService(i);
            } else {
                ctx.stopService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Ridisegna tutti i widget Volume Boost con lo stato attuale. */
    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, BoostWidgetProvider.class));
        if (ids.length == 0) {
            return;
        }
        SharedPreferences p = ctx.getSharedPreferences("boost", Context.MODE_PRIVATE);
        boolean on = p.getBoolean("boost_on", false);
        int mb = p.getInt("gain_mb", 300);

        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_boost);
        rv.setTextViewText(R.id.boost_gain, String.format(Locale.US, "+%.1f dB", mb / 100f));
        rv.setTextViewText(R.id.boost_toggle, on ? "ON" : "OFF");
        rv.setInt(R.id.boost_toggle, "setBackgroundResource",
                on ? R.drawable.widget_btn_on : R.drawable.widget_btn);
        rv.setTextColor(R.id.boost_toggle, on ? 0xFF000000 : 0xFFECEFF4);
        rv.setTextViewText(R.id.boost_status, on ? "Amplificazione attiva" : "Spento");

        rv.setOnClickPendingIntent(R.id.boost_toggle, broadcast(ctx, ACTION_TOGGLE, 0, 1));
        rv.setOnClickPendingIntent(R.id.boost_m5, broadcast(ctx, ACTION_GAIN, -5, 2));
        rv.setOnClickPendingIntent(R.id.boost_m1, broadcast(ctx, ACTION_GAIN, -1, 3));
        rv.setOnClickPendingIntent(R.id.boost_p1, broadcast(ctx, ACTION_GAIN, 1, 4));
        rv.setOnClickPendingIntent(R.id.boost_p5, broadcast(ctx, ACTION_GAIN, 5, 5));

        Intent open = new Intent(ctx, VolumeBoostActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(ctx, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.boost_root, openPi);
        rv.setOnClickPendingIntent(R.id.boost_title, openPi);
        rv.setOnClickPendingIntent(R.id.boost_gain, openPi);
        rv.setOnClickPendingIntent(R.id.boost_status, openPi);

        mgr.updateAppWidget(ids, rv);
    }

    private static PendingIntent broadcast(Context ctx, String action, int db, int req) {
        Intent i = new Intent(ctx, BoostWidgetProvider.class).setAction(action);
        i.putExtra(EXTRA_DB, db);
        return PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
