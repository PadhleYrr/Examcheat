// language: Java, file: AccessibilityReaderService.java, target: Android API 26+

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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AccessibilityReaderService extends AccessibilityService {

    private static final String GROQ_API_KEY = "YOUR_GROQ_API_KEY_HERE";
    private static final String GROQ_MODEL   = "llama3-70b-8192";
    private static final int    DEBOUNCE_MS  = 800;

    private WindowManager wm;
    private TextView      tvStatus;   // top line — what it's doing
    private TextView      tvCapture;  // middle — what text was captured
    private TextView      tvAnswer;   // bottom — groq answer
    private boolean       overlayAdded = false;

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private Runnable              pendingTask;
    private String                lastText = "";

    @Override
    public void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes      = AccessibilityServiceInfo.DEFAULT;
        info.eventTypes      = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType    = AccessibilityServiceInfo.FEEDBACK_ALL_MASK;
        info.notificationTimeout = DEBOUNCE_MS;
        info.flags =
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
            AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
        setServiceInfo(info);

        setupOverlay();
        setStatus("● service connected");
    }

    // ── OVERLAY ───────────────────────────────────────────────────────────────

    private void setupOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.argb(220, 10, 10, 10));
        container.setPadding(16, 10, 16, 10);

        tvStatus = makeLabel("● ready", Color.YELLOW, 11f);
        tvCapture = makeLabel("captured: —", Color.parseColor("#888888"), 10f);
        tvAnswer  = makeLabel("answer: —", Color.parseColor("#00FF88"), 12f);

        container.addView(tvStatus);
        container.addView(tvCapture);
        container.addView(tvAnswer);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.92f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = 40;

        wm.addView(container, p);
        overlayAdded = true;
    }

    private TextView makeLabel(String text, int color, float size) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(size);
        tv.setMaxLines(4);
        tv.setPadding(0, 3, 0, 3);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        return tv;
    }

    private void setStatus(String s)  { mainHandler.post(() -> { if(overlayAdded) tvStatus.setText(s); }); }
    private void setCapture(String s) { mainHandler.post(() -> { if(overlayAdded) tvCapture.setText("captured: " + (s.length() > 80 ? s.substring(0,80)+"…" : s)); }); }
    private void setAnswer(String s)  { mainHandler.post(() -> { if(overlayAdded) tvAnswer.setText("answer: " + s); }); }

    // ── EVENT ─────────────────────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (pendingTask != null) mainHandler.removeCallbacks(pendingTask);

        pendingTask = () -> {
            setStatus("● scanning screen...");

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                setStatus("● no window (null root)");
                return;
            }

            String text = extractText(root).trim();

            if (text.length() < 15) {
                setStatus("● text too short (" + text.length() + " chars), skipping");
                return;
            }
            if (text.equals(lastText)) {
                setStatus("● screen unchanged, idle");
                return;
            }

            lastText = text;
            setCapture(text.replaceAll("\\s+", " "));
            setStatus("⏳ sending to groq (" + text.length() + " chars)...");

            final String captured = text;
            executor.submit(() -> {
                String answer = askGroq(captured);
                setStatus("● done");
                setAnswer(answer);
            });
        };

        mainHandler.postDelayed(pendingTask, DEBOUNCE_MS);
    }

    // ── TEXT EXTRACT ──────────────────────────────────────────────────────────

    private String extractText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null && t.length() > 0) sb.append(t).append('\n');
        if (d != null && d.length() > 0 && (t == null || !d.equals(t))) sb.append(d).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) sb.append(extractText(node.getChild(i)));
        return sb.toString();
    }

    // ── GROQ ──────────────────────────────────────────────────────────────────

    private String askGroq(String screen) {
        try {
            URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            JSONObject sys = new JSONObject()
                .put("role", "system")
                .put("content", "Exam assistant. Find the question on screen. MCQ: reply letter + 8 word reason. Descriptive: 1-2 sentences. Be terse.");
            JSONObject usr = new JSONObject()
                .put("role", "user")
                .put("content", "SCREEN:\n" + screen);

            JSONObject body = new JSONObject()
                .put("model", GROQ_MODEL)
                .put("max_tokens", 200)
                .put("temperature", 0.1)
                .put("messages", new JSONArray().put(sys).put(usr));

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            java.io.InputStream stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            Scanner sc = new Scanner(stream, "UTF-8");
            StringBuilder resp = new StringBuilder();
            while (sc.hasNextLine()) resp.append(sc.nextLine());
            sc.close();

            if (code != 200) return "API " + code + ": " + resp.toString().substring(0, Math.min(60, resp.length()));

            return new JSONObject(resp.toString())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim();

        } catch (Exception e) {
            return "err: " + e.getMessage();
        }
    }

    @Override
    public void onInterrupt() {
        if (overlayAdded && wm != null) {
            // cleanup handled by system
        }
    }
}
