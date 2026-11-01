package it.mamotools;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

/**
 * Elenco quasi infinito di pagine (CPU, RAM, rete, CPU, RAM...) alte quanto il widget:
 * scorrendo verso l'alto si passa al grafico successivo.
 */
public class MonitorWidgetViewsService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(),
                intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0));
    }

    private static final class Factory implements RemoteViewsFactory {
        private final Context ctx;
        private final int widgetId;

        Factory(Context c, int id) {
            ctx = c;
            widgetId = id;
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
        }

        @Override
        public void onDestroy() {
        }

        @Override
        public int getCount() {
            return 300;
        }

        @Override
        public RemoteViews getViewAt(int position) {
            SharedPreferences p = ctx.getSharedPreferences(MonitorWidgetProvider.PREFS,
                    Context.MODE_PRIVATE);
            int wDp = p.getInt("w" + widgetId, 250) - MonitorWidgetProvider.PADDING_DP;
            int hDp = p.getInt("h" + widgetId, 110) - MonitorWidgetProvider.PADDING_DP;

            int idx = 0;
            for (int i = 0; i < MonitorWidgetProvider.PAGE_H.length; i++) {
                if (MonitorWidgetProvider.PAGE_H[i] <= hDp) {
                    idx = i;
                }
            }
            int pageHdp = MonitorWidgetProvider.PAGE_H[idx];
            float d = ctx.getResources().getDisplayMetrics().density;
            int wPx = Math.max(200, Math.min(1000, Math.round(Math.max(120, wDp) * d)));
            int hPx = Math.max(100, Math.round(pageHdp * d));

            RemoteViews rv = new RemoteViews(ctx.getPackageName(),
                    MonitorWidgetProvider.PAGE_LAYOUTS[idx]);
            rv.setImageViewBitmap(R.id.mon_page_img, WidgetGraphs.render(position % 3, wPx, hPx));
            rv.setOnClickFillInIntent(R.id.mon_page_img, new Intent());
            return rv;
        }

        @Override
        public RemoteViews getLoadingView() {
            return new RemoteViews(ctx.getPackageName(), R.layout.widget_monitor_loading);
        }

        @Override
        public int getViewTypeCount() {
            return MonitorWidgetProvider.PAGE_LAYOUTS.length + 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}
