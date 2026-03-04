package com.gcn.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "WidgetBridge")
public class WidgetBridgePlugin extends Plugin {

    private BroadcastReceiver widgetActionReceiver;

    @Override
    public void load() {
        widgetActionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getStringExtra("action");
                if (action != null) {
                    JSObject data = new JSObject();
                    data.put("action", action);
                    notifyListeners("widgetAction", data);
                }
            }
        };

        IntentFilter filter = new IntentFilter("com.gcn.voice.FROM_WIDGET");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(widgetActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(widgetActionReceiver, filter);
        }
    }

    @Override
    protected void handleOnDestroy() {
        if (widgetActionReceiver != null) {
            try {
                getContext().unregisterReceiver(widgetActionReceiver);
            } catch (Exception ignored) {}
        }
    }

    @PluginMethod()
    public void updateState(PluginCall call) {
        boolean inCall = call.getBoolean("inCall", false);
        String roomName = call.getString("roomName", "");
        boolean isMuted = call.getBoolean("isMuted", false);
        int peerCount = call.getInt("peerCount", 0);

        SharedPreferences prefs = getContext()
            .getSharedPreferences(VoiceWidget.PREFS_NAME, Context.MODE_PRIVATE);

        prefs.edit()
            .putBoolean("inCall", inCall)
            .putString("roomName", roomName)
            .putBoolean("isMuted", isMuted)
            .putInt("peerCount", peerCount)
            .apply();

        VoiceWidget.refreshAll(getContext());

        call.resolve();
    }

    @PluginMethod()
    public void clearState(PluginCall call) {
        SharedPreferences prefs = getContext()
            .getSharedPreferences(VoiceWidget.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();

        VoiceWidget.refreshAll(getContext());

        call.resolve();
    }
}
