package com.gcn.voice;

import android.os.Bundle;
import android.os.PowerManager;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private PowerManager.WakeLock cpuWakeLock;

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
    }

    @Override
    public void onPause() {
        super.onPause();

        WebView wv = getBridge().getWebView();
        if (wv != null) {
            wv.onResume();
            wv.resumeTimers();
        }

        if (cpuWakeLock != null && !cpuWakeLock.isHeld()) {
            cpuWakeLock.acquire(4 * 60 * 60 * 1000L);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
    }

    @Override
    public void onDestroy() {
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
        super.onDestroy();
    }
}
