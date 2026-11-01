package it.mamotools;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

/** Widget System Monitor: elenco scorrevole di grafici (CPU, RAM, rete), con swipe verticale. */
public class MonitorWidgetProvider extends AppWidgetProvider {

    static final String PREFS = "monwidget";
    /** Altezze (dp) delle pagine disponibili, nello stesso ordine di PAGE_LAYOUTS. */
    static final int[] PAGE_H = {60, 80, 100, 120, 140, 160, 180, 200, 220, 240, 260, 280, 300,
            320, 340, 360, 380, 400, 420, 440};
    static final int[] PAGE_LAYOUTS = {
            R.layout.widget_page_h60, R.layout.widget_page_h80, R.layout.widget_page_h100,
            R.layout.widget_page_h120, R.layout.widget_page_h140, R.layout.widget_page_h160,
            R.layout.widget_page_h180, R.layout.widget_page_h200, R.layout.widget_page_h220,
            R.layout.widget_page_h240, R.layout.widget_page_h260, R.layout.widget_page_h280,
            R.layout.widget_page_h300, R.layout.widget_page_h320, R.layout.widget_page_h340,
            R.layout.widget_page_h360, R.layout.widget_page_h380, R.layout.widget_page_h400,
            R.layout.widget_page_h420, R.layout.widget_page_h440};
    static final int PADDING_DP = 16;     // 8 dp per lato, come in widget_monitor.xml

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            storeSize(ctx, mgr, id);
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_monitor);
            Intent svc = new Intent(ctx, MonitorWidgetViewsService.class);
            svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
            rv.setRemoteAdapter(R.id.mon_list, svc);

            Intent open = new Intent(ctx, SystemMonitorActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // FLAG_MUTABLE (1 << 25): serve perche' ogni pagina completa l'intent al tocco
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | (1 << 25));
            rv.setPendingIntentTemplate(R.id.mon_list, pi);
            mgr.updateAppWidget(id, rv);
        }
        MonitorWidgetService.start(ctx);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle opts) {
        storeSize(ctx, mgr, id);
        mgr.notifyAppWidgetViewDataChanged(id, R.id.mon_list);
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (int id : ids) {
            e.remove("w" + id).remove("h" + id);
        }
        e.apply();
    }

    @Override
    public void onEnabled(Context ctx) {
        MonitorWidgetService.start(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        ctx.stopService(new Intent(ctx, MonitorWidgetService.class));
    }

    /** Salva la dimensione (dp) del widget in questo orientamento. */
    private static void storeSize(Context ctx, AppWidgetManager mgr, int id) {
        Bundle o = mgr.getAppWidgetOptions(id);
        boolean land = ctx.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int w = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int h = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (w > 0 && h > 0) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("w" + id, w).putInt("h" + id, h).apply();
        }
    }
}
