// language: Java, file: MainActivity.java, target: Android API 26+
// runs on phone B — pulls screen from phone A via ADB over USB-C,
// sends to Groq vision, displays answer full screen

package com.bench.phoneb;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final String GROQ_API_KEY = "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";
    private static final String GROQ_MODEL   = "meta-llama/llama-4-scout-17b-16e-instruct";
    private static final int    INTERVAL_MS  = 2000;

    private TextView     tvStatus;
    private TextView     tvAnswer;
    private ImageView    ivPreview;
    private Button       btnStart;
    private Button       btnStop;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler         handler  = new Handler(Looper.getMainLooper());
    private final AtomicBoolean   running  = new AtomicBoolean(false);
    private Runnable              loop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // keep screen on always
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        tvStatus  = findViewById(R.id.tvStatus);
        tvAnswer  = findViewById(R.id.tvAnswer);
        ivPreview = findViewById(R.id.ivPreview);
        btnStart  = findViewById(R.id.btnStart);
        btnStop   = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> startLoop());
        btnStop.setOnClickListener(v  -> stopLoop());
    }

    // ── LOOP ──────────────────────────────────────────────────────────────────

    private void startLoop() {
        if (running.get()) return;
        running.set(true);
        setStatus("● starting...", Color.YELLOW);
        scheduleNext();
    }

    private void stopLoop() {
        running.set(false);
        if (loop != null) handler.removeCallbacks(loop);
        setStatus("■ stopped", Color.GRAY);
    }

    private void scheduleNext() {
        if (!running.get()) return;
        loop = () -> executor.submit(this::tick);
        handler.postDelayed(loop, INTERVAL_MS);
    }

    private void tick() {
        // 1. pull screenshot from phone A via ADB shell
        byte[] png = pullScreen();
        if (png == null) {
            setStatus("● ADB: no frame — is USB debugging on?", Color.RED);
            scheduleNext();
            return;
        }

        // 2. show preview on phone B
        Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bmp != null) handler.post(() -> ivPreview.setImageBitmap(bmp));

        // 3. encode to base64 JPEG at lower quality to keep payload small
        String b64 = bitmapToBase64(bmp);
        setStatus("⏳ asking groq... (" + (b64.length() / 1024) + " KB)", Color.YELLOW);

        // 4. ask groq vision
        String answer = askGroq(b64);
        setStatus("● done — next in 2s", Color.GREEN);
        setAnswer(answer);

        scheduleNext();
    }

    // ── ADB SCREEN PULL ───────────────────────────────────────────────────────
    // uses Runtime.exec to call the adb binary bundled in the APK assets,
    // or falls back to /system/bin — on LADB environment adb is in PATH

    private byte[] pullScreen() {
        try {
            // "adb exec-out screencap -p" streams raw PNG bytes to stdout
            Process proc = Runtime.getRuntime().exec(
                new String[]{"adb", "-d", "exec-out", "screencap", "-p"});

            InputStream is = proc.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            proc.waitFor();

            byte[] data = baos.toByteArray();
            if (data.length < 100) return null; // empty / error
            return data;

        } catch (Exception e) {
            setStatus("● exec err: " + e.getMessage(), Color.RED);
            return null;
        }
    }

    // ── IMAGE ENCODE ──────────────────────────────────────────────────────────

    private String bitmapToBase64(Bitmap bmp) {
        if (bmp == null) return "";
        // scale down to save bandwidth
        Bitmap scaled = Bitmap.createScaledBitmap(bmp,
            bmp.getWidth() / 2, bmp.getHeight() / 2, true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 65, baos);
        scaled.recycle();
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

    // ── GROQ VISION ───────────────────────────────────────────────────────────

    private String askGroq(String b64Jpeg) {
        try {
            URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            JSONObject imageUrl  = new JSONObject().put("url", "data:image/jpeg;base64," + b64Jpeg);
            JSONObject imgPart   = new JSONObject().put("type", "image_url").put("image_url", imageUrl);
            JSONObject txtPart   = new JSONObject().put("type", "text").put("text",
                "This is a screenshot of an exam app on another phone. " +
                "Find the current question. " +
                "MCQ: reply ONLY the correct option letter + max 8 word reason. " +
                "Descriptive: reply 1-2 sentence answer. " +
                "No question visible: reply 'waiting...'. " +
                "No preamble. Be terse.");

            JSONObject sysMsg = new JSONObject()
                .put("role", "system")
                .put("content", "Silent exam assistant. Answer only. Nothing else.");
            JSONObject usrMsg = new JSONObject()
                .put("role", "user")
                .put("content", new JSONArray().put(imgPart).put(txtPart));

            JSONObject body = new JSONObject()
                .put("model", GROQ_MODEL)
                .put("max_tokens", 200)
                .put("temperature", 0.1)
                .put("messages", new JSONArray().put(sysMsg).put(usrMsg));

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            InputStream stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            Scanner sc = new Scanner(stream, "UTF-8");
            StringBuilder resp = new StringBuilder();
            while (sc.hasNextLine()) resp.append(sc.nextLine());
            sc.close();

            if (code != 200)
                return "API " + code + ": " + resp.toString().substring(0, Math.min(80, resp.length()));

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

    // ── UI HELPERS ────────────────────────────────────────────────────────────

    private void setStatus(String s, int color) {
        handler.post(() -> {
            tvStatus.setText(s);
            tvStatus.setTextColor(color);
        });
    }

    private void setAnswer(String s) {
        handler.post(() -> tvAnswer.setText(s));
    }
}
