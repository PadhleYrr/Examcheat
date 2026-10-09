// language: Java, file: CaptureService.java, target: Android API 26+
// foreground service: captures screen every 2s via MediaProjection,
// encodes to JPEG base64, sends to Groq vision, shows answer in overlay

package com.bench.examassist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {

    private static final String GROQ_API_KEY  = "YOUR_GROQ_API_KEY_HERE";
    // llama-4 scout supports vision (image input)
    private static final String GROQ_MODEL    = "meta-llama/llama-4-scout-17b-16e-instruct";
    private static final int    CAPTURE_MS    = 2000; // capture every 2 seconds
    private static final String CHANNEL_ID    = "examassist_channel";

    private MediaProjection  projection;
    private VirtualDisplay   virtualDisplay;
    private ImageReader      imageReader;
    private WindowManager    wm;

    private TextView tvStatus;
    private TextView tvCapture;
    private TextView tvAnswer;
    private boolean  overlayAdded = false;

    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final AtomicBoolean   running     = new AtomicBoolean(false);
    private String                lastAnswer  = "";

    // ── LIFECYCLE ─────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());

        int    code = intent.getIntExtra("code", -1);
        Intent data = intent.getParcelableExtra("data");

        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);

        setupOverlay();
        setupCapture();
        running.set(true);
        scheduleCapture();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        if (virtualDisplay != null) virtualDisplay.release();
        if (projection    != null) projection.stop();
        if (overlayAdded  && wm != null && tvStatus != null)
            wm.removeView(tvStatus.getRootView());
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ── NOTIFICATION (required for foreground service) ────────────────────────

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "ExamAssist", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ExamAssist running")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build();
    }

    // ── OVERLAY ───────────────────────────────────────────────────────────────

    private void setupOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.argb(220, 10, 10, 10));
        container.setPadding(16, 10, 16, 10);

        tvStatus  = makeLabel("● starting...", Color.YELLOW, 11f);
        tvCapture = makeLabel("last capture: —", Color.parseColor("#888888"), 10f);
        tvAnswer  = makeLabel("answer: —", Color.parseColor("#00FF88"), 12f);

        container.addView(tvStatus);
        container.addView(tvCapture);
        container.addView(tvAnswer);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.92f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        tv.setMaxLines(5);
        tv.setPadding(0, 3, 0, 3);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        return tv;
    }

    private void setStatus(String s)  { mainHandler.post(() -> { if (overlayAdded) tvStatus.setText(s); }); }
    private void setCapture(String s) { mainHandler.post(() -> { if (overlayAdded) tvCapture.setText("last capture: " + s); }); }
    private void setAnswer(String s)  { mainHandler.post(() -> { if (overlayAdded) tvAnswer.setText("answer: " + s); }); }

    // ── SCREEN CAPTURE SETUP ──────────────────────────────────────────────────

    private void setupCapture() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width  = dm.widthPixels;
        int height = dm.heightPixels;
        int dpi    = dm.densityDpi;

        // capture at half resolution — enough for text, smaller payload to groq
        int capW = width  / 2;
        int capH = height / 2;

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);

        virtualDisplay = projection.createVirtualDisplay(
            "ExamAssist",
            capW, capH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null, null
        );
    }

    // ── CAPTURE LOOP ──────────────────────────────────────────────────────────

    private void scheduleCapture() {
        if (!running.get()) return;
        mainHandler.postDelayed(() -> {
            executor.submit(this::doCapture);
        }, CAPTURE_MS);
    }

    private void doCapture() {
        if (!running.get()) return;

        try {
            setStatus("⏳ capturing screen...");
            Image image = imageReader.acquireLatestImage();

            if (image == null) {
                setStatus("● waiting for frame...");
                scheduleCapture();
                return;
            }

            // convert Image to Bitmap
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer    = planes[0].getBuffer();
            int rowStride        = planes[0].getRowStride();
            int pixelStride      = planes[0].getPixelStride();
            int width            = image.getWidth();
            int height           = image.getHeight();
            int rowPadding       = rowStride - pixelStride * width;

            Bitmap bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            image.close();

            // crop to exact size
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            bitmap.recycle();

            // encode to JPEG base64
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 70, baos);
            cropped.recycle();
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

            setCapture(System.currentTimeMillis() / 1000 + "s, " + (b64.length() / 1024) + "KB");
            setStatus("⏳ sending to groq vision...");

            String answer = askGroqVision(b64);

            if (!answer.equals(lastAnswer)) {
                lastAnswer = answer;
                setAnswer(answer);
            }
            setStatus("● done — next in 2s");

        } catch (Exception e) {
            setStatus("● capture err: " + e.getMessage());
        }

        scheduleCapture();
    }

    // ── GROQ VISION API ───────────────────────────────────────────────────────

    private String askGroqVision(String base64Jpeg) {
        try {
            URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + GROQ_API_KEY);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            // vision message: image + text
            JSONObject imageUrl = new JSONObject()
                .put("url", "data:image/jpeg;base64," + base64Jpeg);

            JSONObject imagePart = new JSONObject()
                .put("type", "image_url")
                .put("image_url", imageUrl);

            JSONObject textPart = new JSONObject()
                .put("type", "text")
                .put("text",
                    "This is a screenshot of an exam app. " +
                    "Find the question being asked. " +
                    "If MCQ: reply with the correct option letter and a max 8-word reason. " +
                    "If descriptive: reply with 1-2 sentence answer. " +
                    "If no question visible yet: reply 'waiting...'. " +
                    "Be extremely terse. No preamble.");

            JSONObject userMsg = new JSONObject()
                .put("role", "user")
                .put("content", new JSONArray().put(imagePart).put(textPart));

            JSONObject sysMsg = new JSONObject()
                .put("role", "system")
                .put("content", "Silent exam assistant. Answer only. No explanation of what you see.");

            JSONObject body = new JSONObject()
                .put("model", GROQ_MODEL)
                .put("max_tokens", 200)
                .put("temperature", 0.1)
                .put("messages", new JSONArray().put(sysMsg).put(userMsg));

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            java.io.InputStream stream = code == 200
                ? conn.getInputStream() : conn.getErrorStream();

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
}
