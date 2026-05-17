# Caption Lens — Complete Rewrite (v7.0)

## Summary

Full rewrite from scratch fixing the crash-on-start-capture bug and all related issues.
All folder paths, package names, and UI are **identical** to the original APK.

---

## Crash Root Causes & Fixes

### 1. `startForeground()` called too late in `SpeechCaptureService` — **PRIMARY CRASH**

**Original**: `startForeground()` was only called after checking intent extras in `onStartCommand()`.  
On Android 12+ (`ForegroundServiceStartNotAllowedException`) and Android 14 (`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` enforcement), any service that doesn't call `startForeground()` **immediately in `onCreate()`** is killed within ~10 seconds.

**Fix**: `startForeground()` is the very first call inside `onCreate()` with the correct compound type:
```kotlin
startForeground(NOTIF_ID, buildNotification("Initialising…"),
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
```

---

### 2. Double MethodChannel result delivery — `pendingProjectionResult` race condition

**Original**: `requestAudioPermissionThenProjection()` stored the result in `pendingProjectionResult`,  
then `onRequestPermissionsResult()` called `requestMediaProjection()` which also tried to use/store the same field — leaving it in an inconsistent state, often delivering two results from one Flutter call (crash).

**Fix**:
- `deliverPendingFailure()` always clears a stale pending result before writing a new one.
- `onRequestPermissionsResult()` reads then clears `pendingProjectionResult` **before** passing it to `requestMediaProjection()`.
- Every code path guaranteed to deliver exactly one result per call.

---

### 3. MediaProjection token misuse on API 34

**Original**: The code passed the projection intent as a Parcelable extra to the service and called `getMediaProjection()` from there — valid on older APIs but unreliable on API 34 where the token is truly one-use.

**Fix**: `getMediaProjection(resultCode, resultData)` is called **inside `SpeechCaptureService.onStartCommand()`** from the raw extras, exactly as the Android docs require for API 34+.

---

### 4. `AudioRecord` type mismatch

**Original**: Used `AudioRecord(MediaRecorder.AudioSource.MIC, ...)` constructor as a fallback,  
which requires physical microphone hardware and fails on internal audio capture paths on API 34.

**Fix**: Only `AudioRecord.Builder().setAudioPlaybackCaptureConfig(captureConfig).build()` is used — the correct API for internal audio capture.

---

### 5. `minSdk 26` — AudioPlaybackCaptureConfiguration only exists from API 29

**Original**: `minSdk 26` (Android 8). `AudioPlaybackCaptureConfiguration` is API 29+.  
The code had a runtime check but could still be installed on API 26–28 and crash.

**Fix**: `minSdk 29` in `build.gradle` — the OS will reject installation on Android < 10.

---

### 6. `SpeechCaptureService.foregroundServiceType` missing `microphone`

**Original**: Manifest declared `mediaProjection` only. On API 34, `AudioRecord` with  
`AudioPlaybackCaptureConfiguration` **also** requires `FOREGROUND_SERVICE_TYPE_MICROPHONE`  
in both the manifest and `startForeground()` call.

**Fix**: Manifest `foregroundServiceType="mediaProjection|microphone"` AND runtime bitwise-OR.

---

## Folder Paths (identical to original)

| What | Path |
|------|------|
| Vosk model | `context.filesDir/vosk-model/` |
| Partial download | `context.filesDir/vosk_model_download.zip.part` |
| Version stamp | `context.filesDir/vosk_model_version` |
| Model URL | `https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip` |
| LibreTranslate | `http://localhost:5000/translate` |

---

## How to Build

```bash
cd nihongo_v7
flutter pub get
flutter build apk --release
# APK: build/app/outputs/flutter-apk/app-release.apk
```

Or from Android Studio: open `nihongo_v7/android`, build normally.

---

## First Launch Behaviour

1. App checks if model is present → shows **Download** button (orange card).
2. User taps **Download** → `ModelDownloadService` starts, downloads 1.8 GB from alphacephei.com with HTTP Range resume.
3. Progress bar updates in real time. Download survives screen-off (WakeLock held).
4. After unzip, version stamp is written → card turns green "Speech Model Ready".
5. User taps **Allow** (overlay permission) → system settings opens → user grants.
6. User taps **START — CAPTURE VIDEO AUDIO** → system shows "Start recording?" dialog → user approves.
7. Subtitles appear as floating overlay. Translation via LibreTranslate on localhost:5000.
