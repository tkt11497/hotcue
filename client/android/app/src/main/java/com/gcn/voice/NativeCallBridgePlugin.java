package com.gcn.voice;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.gcn.voice.call.VoiceCallForegroundService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(
    name = "NativeCallBridge",
    permissions = {
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications")
    }
)
public class NativeCallBridgePlugin extends Plugin {

    private BroadcastReceiver callStateReceiver;
    private String lastStateJson = null;

    @Override
    public void load() {
        callStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String stateJson = intent.getStringExtra("state");
                if (stateJson == null) return;
                lastStateJson = stateJson;
                JSObject payload = new JSObject();
                try {
                    payload.put("state", JSObject.fromJSONObject(new JSONObject(stateJson)));
                } catch (JSONException e) {
                    payload.put("state", new JSObject());
                }
                notifyListeners("callState", payload);
            }
        };

        IntentFilter filter = new IntentFilter(VoiceCallForegroundService.ACTION_CALL_STATE_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(callStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(callStateReceiver, filter);
        }
    }

    @Override
    protected void handleOnDestroy() {
        if (callStateReceiver != null) {
            try {
                getContext().unregisterReceiver(callStateReceiver);
            } catch (Exception ignored) {}
        }
    }

    @PluginMethod
    public void startCall(PluginCall call) {
        String roomId = call.getString("roomId");
        String roomName = call.getString("roomName");
        String userId = call.getString("userId");
        String username = call.getString("username");
        String firebaseApiKey = call.getString("firebaseApiKey");
        String firebaseAppId = call.getString("firebaseAppId");
        String firebaseProjectId = call.getString("firebaseProjectId");
        String firebaseStorageBucket = call.getString("firebaseStorageBucket");
        String firebaseMessagingSenderId = call.getString("firebaseMessagingSenderId");
        if (roomId == null || userId == null || username == null) {
            call.reject("roomId, userId and username are required");
            return;
        }
        Intent intent = VoiceCallForegroundService.buildStartIntent(
            getContext(),
            roomId,
            roomName == null ? roomId : roomName,
            userId,
            username,
            firebaseApiKey,
            firebaseAppId,
            firebaseProjectId,
            firebaseStorageBucket,
            firebaseMessagingSenderId
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
        call.resolve();
    }

    @PluginMethod
    public void toggleMute(PluginCall call) {
        Intent i = new Intent(getContext(), VoiceCallForegroundService.class);
        i.setAction(VoiceCallForegroundService.ACTION_TOGGLE_MUTE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(i);
        } else {
            getContext().startService(i);
        }
        call.resolve();
    }

    @PluginMethod
    public void hangup(PluginCall call) {
        Intent i = new Intent(getContext(), VoiceCallForegroundService.class);
        i.setAction(VoiceCallForegroundService.ACTION_HANGUP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(i);
        } else {
            getContext().startService(i);
        }
        call.resolve();
    }

    @PluginMethod
    public void getCallState(PluginCall call) {
        if (lastStateJson == null) {
            JSObject state = new JSObject();
            state.put("inCall", false);
            state.put("connected", false);
            state.put("isMuted", false);
            state.put("roomId", JSONObject.NULL);
            state.put("roomName", JSONObject.NULL);
            state.put("myId", JSONObject.NULL);
            state.put("myUsername", JSONObject.NULL);
            state.put("callPhase", "idle");
            state.put("users", new JSONArray());
            state.put("peers", new JSONArray());
            JSObject res = new JSObject();
            res.put("state", state);
            call.resolve(res);
            return;
        }
        try {
            JSObject res = new JSObject();
            res.put("state", JSObject.fromJSONObject(new JSONObject(lastStateJson)));
            call.resolve(res);
        } catch (JSONException e) {
            call.reject("Failed to parse call state", e);
        }
    }

    @PluginMethod
    public void openBatteryOptimizationSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to open battery optimization settings", e);
        }
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            call.resolve();
            return;
        }
        requestPermissionForAlias("notifications", call, "notificationPermsResult");
    }

    @PermissionCallback
    private void notificationPermsResult(PluginCall call) {
        call.resolve();
    }
}
