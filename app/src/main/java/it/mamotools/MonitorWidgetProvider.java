package it.mamotools;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/** Widget System Monitor: pile di grafici (CPU, RAM, rete) da scorrere con uno swipe. */
public class MonitorWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_monitor);
            Intent svc = new Intent(ctx, MonitorWidgetViewsService.class);
            svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
            rv.setRemoteAdapter(R.id.mon_stack, svc);
            rv.setEmptyView(R.id.mon_stack, R.id.mon_empty);

            Intent open = new Intent(ctx, SystemMonitorActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // FLAG_MUTABLE (1 << 25): serve perche' ogni pagina completa l'intent al tocco
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | (1 << 25));
            rv.setPendingIntentTemplate(R.id.mon_stack, pi);
            mgr.updateAppWidget(id, rv);
        }
        MonitorWidgetService.start(ctx);
    }

    @Override
    public void onEnabled(Context ctx) {
        MonitorWidgetService.start(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        ctx.stopService(new Intent(ctx, MonitorWidgetService.class));
    }
}
