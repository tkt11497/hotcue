package com.gcn.voice;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(WidgetBridgePlugin.class);
        registerPlugin(NativeWebRTCPlugin.class);
        super.onCreate(savedInstanceState);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GCNVoice::VoiceCallCPU"
        );

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GCNVoice::WifiLock");
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            WebView wv = getBridge().getWebView();
            if (wv != null) {
                wv.onResume();
                wv.resumeTimers();
            }
        }, 500);

        if (cpuWakeLock != null && !cpuWakeLock.isHeld()) {
            cpuWakeLock.acquire(4 * 60 * 60 * 1000L);
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    @Override
    public void onDestroy() {
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        super.onDestroy();
    }
}
