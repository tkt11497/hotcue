# Native Call Engine Rollout

## Feature Flag

- `VITE_NATIVE_CALL_ENGINE=true` (default): use Android native signaling + native WebRTC service.
- `VITE_NATIVE_CALL_ENGINE=false`: disable native bridge usage on web/native JS layer.

## Diagnostics Added

- Native service tag: `VoiceCallService`
- Native WebRTC tag: `NativeWebRtc`
- Broadcast event: `com.gcn.voice.call.STATE_UPDATED`
- JS bridge event: `callState`

## Manual Test Matrix

- Screen off for 30+ minutes while in active call.
- Device lock/unlock repeatedly (5+ cycles).
- App moved to background, then foreground.
- App swiped away from recents while call is active.
- Network handoff Wi-Fi -> LTE and LTE -> Wi-Fi.
- Airplane mode toggle and recovery.
- Long soak call for 2+ hours.

## Acceptance Criteria

- Foreground call notification remains present while in call.
- Mute/hangup notification actions work while screen is locked.
- Peer state updates continue through lock-screen periods.
- Call can recover from transient network changes without full app restart.
