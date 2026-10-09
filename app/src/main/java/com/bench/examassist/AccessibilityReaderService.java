// language: Java, file: AccessibilityReaderService.java, target: Android API 26+
// reads view tree text from any foreground app, bypasses FLAG_SECURE,
// sends to Groq API, overlays the answer bottom-left always-on-top

package com.bench.examassist;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AccessibilityReaderService extends AccessibilityService {

    // ── CONFIG ────────────────────────────────────────────────────────────────
    private static final String GROQ_API_KEY = "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";
    private static final String GROQ_MODEL   = "llama3-70b-8192";
    private static final int    DEBOUNCE_MS  = 1500; // avoid spamming API mid-scroll
    // ─────────────────────────────────────────────────────────────────────────

    private WindowManager      wm;
    private TextView           overlay;
    private boolean            overlayAdded = false;

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private Runnable              pendingTask;

    // last screen text — skip API call if screen didn't change
    private String lastText = "";

    @Override
    public void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = DEBOUNCE_MS;
        info.flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        setupOverlay();
    }

    // ── OVERLAY ───────────────────────────────────────────────────────────────

    private void setupOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlay = new TextView(this);
        overlay.setTextColor(Color.GREEN);
        overlay.setBackgroundColor(Color.argb(200, 0, 0, 0));
        overlay.setTextSize(12f);
        overlay.setPadding(14, 8, 14, 8);
        overlay.setText("● ready");

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // TYPE_ACCESSIBILITY_OVERLAY renders over FLAG_SECURE windows
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.BOTTOM | Gravity.START;
        p.x = 16;
        p.y = 100;

        wm.addView(overlay, p);
        overlayAdded = true;
    }

    private void setOverlayText(String text) {
        if (overlayAdded) overlay.setText(text);
    }

    // ── ACCESSIBILITY EVENT ───────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // debounce: cancel previous pending call, reschedule
        if (pendingTask != null) mainHandler.removeCallbacks(pendingTask);

        pendingTask = () -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            String text = extractText(root).trim();
            if (text.length() < 20)  return; // transitional / empty screen
            if (text.equals(lastText)) return; // screen hasn't changed
            lastText = text;

            setOverlayText("⏳ thinking...");
            final String captured = text;

            executor.submit(() -> {
                String answer = askGroq(captured);
                mainHandler.post(() -> setOverlayText(answer));
            });
        };

        mainHandler.postDelayed(pendingTask, DEBOUNCE_MS);
    }

    // ── VIEW TREE TEXT EXTRACTION ─────────────────────────────────────────────

    private String extractText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();

        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null && t.length() > 0) sb.append(t).append('\n');
        if (d != null && d.length() > 0) sb.append(d).append('\n');

        for (int i = 0; i < node.getChildCount(); i++) {
            sb.append(extractText(node.getChild(i)));
        }
        return sb.toString();
    }

    // ── GROQ API ──────────────────────────────────────────────────────────────

    private String askGroq(String screenContent) {
        try {
            URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            JSONObject systemMsg = new JSONObject()
                    .put("role", "system")
                    .put("content",
                            "You are a silent exam assistant. " +
                            "Given raw screen text from an exam app, identify the current question. " +
                            "For MCQ: reply with the correct option letter and a max 10-word reason. " +
                            "For descriptive: reply with a 1-2 sentence answer. " +
                            "If multiple questions are visible, number each answer. " +
                            "Be terse. No preamble.");

            JSONObject userMsg = new JSONObject()
                    .put("role", "user")
                    .put("content", "SCREEN:\n" + screenContent);

            JSONObject body = new JSONObject()
                    .put("model", GROQ_MODEL)
                    .put("max_tokens", 250)
                    .put("temperature", 0.1)
                    .put("messages", new JSONArray().put(systemMsg).put(userMsg));

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            java.io.InputStream stream = (code == 200)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            Scanner sc = new Scanner(stream, "UTF-8");
            StringBuilder resp = new StringBuilder();
            while (sc.hasNextLine()) resp.append(sc.nextLine());
            sc.close();

            if (code != 200) return "API err " + code + ": " + resp.toString().substring(0, Math.min(80, resp.length()));

            JSONObject json = new JSONObject(resp.toString());
            return json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();

        } catch (Exception e) {
            return "err: " + e.getMessage();
        }
    }

    // ── CLEANUP ───────────────────────────────────────────────────────────────

    @Override
    public void onInterrupt() {
        if (overlayAdded && wm != null && overlay != null) {
            wm.removeView(overlay);
            overlayAdded = false;
        }
    }
}
