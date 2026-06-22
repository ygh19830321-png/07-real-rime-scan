# Real Rime Scan

Real Rime Scan is an Android book-scanning MVP that turns camera frames into editable text in real time.

## Current Features

- Live camera preview with Korean ML Kit OCR
- Real-time recognized text preview
- Automatic append-to-document mode with local autosave
- Editable saved text field for immediate typo correction
- Manual append, pause/resume OCR, copy, and clear actions

## Build

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```
