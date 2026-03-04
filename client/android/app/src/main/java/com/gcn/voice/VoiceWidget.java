package com.gcn.voice;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

public class VoiceWidget extends AppWidgetProvider {

    public static final String PREFS_NAME = "GCNVoiceWidgetPrefs";
    public static final String ACTION_TOGGLE_MUTE = "com.gcn.voice.WIDGET_TOGGLE_MUTE";
    public static final String ACTION_HANGUP = "com.gcn.voice.WIDGET_HANGUP";
    public static final String ACTION_OPEN_APP = "com.gcn.voice.WIDGET_OPEN_APP";
    public static final String ACTION_REFRESH = "com.gcn.voice.WIDGET_REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case ACTION_TOGGLE_MUTE: {
                Intent fwd = new Intent("com.gcn.voice.FROM_WIDGET");
                fwd.putExtra("action", "toggleMute");
                fwd.setPackage(context.getPackageName());
                context.sendBroadcast(fwd);
                break;
            }
            case ACTION_HANGUP: {
                Intent fwd = new Intent("com.gcn.voice.FROM_WIDGET");
                fwd.putExtra("action", "hangup");
                fwd.setPackage(context.getPackageName());
                context.sendBroadcast(fwd);
                break;
            }
            case ACTION_OPEN_APP: {
                Intent launch = new Intent(context, MainActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(launch);
                break;
            }
            case ACTION_REFRESH: {
                refreshAll(context);
                break;
            }
        }
    }

    static void updateWidget(Context context, AppWidgetManager mgr, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean inCall = prefs.getBoolean("inCall", false);
        String roomName = prefs.getString("roomName", "");
        boolean isMuted = prefs.getBoolean("isMuted", false);
        int peerCount = prefs.getInt("peerCount", 0);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_voice);

        if (inCall && roomName != null && !roomName.isEmpty()) {
            String status = "In Call — " + roomName;
            if (peerCount > 0) {
                status += " (" + peerCount + " peer" + (peerCount != 1 ? "s" : "") + ")";
            }
            views.setTextViewText(R.id.widget_status, status);
            views.setViewVisibility(R.id.widget_mute_btn, View.VISIBLE);
            views.setViewVisibility(R.id.widget_hangup_btn, View.VISIBLE);

            String muteIcon = isMuted ? "🔇" : "🎙";
            views.setTextViewText(R.id.widget_mute_icon, muteIcon);
        } else {
            views.setTextViewText(R.id.widget_status, "Not in a call");
            views.setViewVisibility(R.id.widget_mute_btn, View.GONE);
            views.setViewVisibility(R.id.widget_hangup_btn, View.GONE);
        }

        // Mute button intent
        views.setOnClickPendingIntent(R.id.widget_mute_btn,
            makePendingBroadcast(context, 0, ACTION_TOGGLE_MUTE));

        // Hangup button intent
        views.setOnClickPendingIntent(R.id.widget_hangup_btn,
            makePendingBroadcast(context, 1, ACTION_HANGUP));

        // Open app button intent
        views.setOnClickPendingIntent(R.id.widget_open_btn,
            makePendingBroadcast(context, 2, ACTION_OPEN_APP));

        // Tapping title/status also opens the app
        views.setOnClickPendingIntent(R.id.widget_title,
            makePendingBroadcast(context, 3, ACTION_OPEN_APP));
        views.setOnClickPendingIntent(R.id.widget_status,
            makePendingBroadcast(context, 4, ACTION_OPEN_APP));

        mgr.updateAppWidget(widgetId, views);
    }

    public static void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, VoiceWidget.class));
        for (int id : ids) {
            updateWidget(context, mgr, id);
        }
    }

    private static PendingIntent makePendingBroadcast(Context context, int requestCode, String action) {
        Intent intent = new Intent(context, VoiceWidget.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
