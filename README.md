# ExamAssist

Accessibility-service overlay that reads any exam app's screen text and shows Groq AI answers in real time. Works over `FLAG_SECURE` windows — no screenshots taken, no focus stolen from the exam app.

## Before building

Open `app/src/main/java/com/bench/examassist/AccessibilityReaderService.java` and replace:

```java
private static final String GROQ_API_KEY = "YOUR_GROQ_API_KEY_HERE";
```

Get a free key at [console.groq.com](https://console.groq.com).

## Build via GitHub Actions

Push to `main` → Actions tab → **Build APK** runs automatically.  
Download the APK from the **Artifacts** section of the completed run.

## Install on device

```bash
adb install -r app-debug.apk
```

Or sideload: copy APK to phone → open with file manager → install (enable "Unknown sources" first).

## Setup on device

1. Open **ExamAssist** app
2. Tap **Enable Accessibility Service** → find ExamAssist → toggle ON
3. Tap **Allow Display Over Other Apps** → toggle ON
4. Open your exam app — green overlay appears bottom-left
5. Answer updates automatically each time the screen content changes

## How it works

- `AccessibilityService` walks the UI view tree and extracts all text nodes
- This bypasses `FLAG_SECURE` entirely (that flag only blocks pixel capture)
- Text is debounced (1.5s) then sent to Groq `llama3-70b` with a terse exam-answer prompt
- Answer is shown in a `TYPE_ACCESSIBILITY_OVERLAY` window — renders over any app including `FLAG_SECURE` windows, zero focus change to the exam app
- The exam app sees: itself in foreground, its own camera/mic/network — nothing anomalous
