package it.mamotools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Aggiorna il widget System Monitor ogni secondo finche' lo schermo e' acceso.
 * Parte quando c'e' almeno un widget e si ferma da sola quando non ce ne sono piu'.
 */
public class MonitorWidgetService extends Service {

    private static final String CHANNEL = "monitor_widget";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverOn = false;
    private boolean running = false;

    static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, MonitorWidgetService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                stopLoop();
            } else {
                startLoop();
            }
        }
    };

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            int[] ids = ids();
            if (ids.length == 0) {
                stopSelf();
                return;
            }
            try {
                MonitorSampler.get().sample(MonitorWidgetService.this);
                AppWidgetManager.getInstance(MonitorWidgetService.this)
                        .notifyAppWidgetViewDataChanged(ids, R.id.mon_list);
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, 1000);
        }
    };

    private int[] ids() {
        return AppWidgetManager.getInstance(this).getAppWidgetIds(
                new ComponentName(this, MonitorWidgetProvider.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(2, notification());
        if (ids().length == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!receiverOn) {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screen, f);
            receiverOn = true;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isInteractive()) {
            startLoop();
        }
        return START_STICKY;
    }

    private void startLoop() {
        if (running) {
            return;
        }
        running = true;
        MonitorSampler.get().resetNet();
        handler.post(loop);
    }

    private void stopLoop() {
        running = false;
        handler.removeCallbacks(loop);
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Widget System Monitor", NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, SystemMonitorActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Widget System Monitor attivo")
                .setContentText("Si aggiorna solo a schermo acceso")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopLoop();
        if (receiverOn) {
            try {
                unregisterReceiver(screen);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
