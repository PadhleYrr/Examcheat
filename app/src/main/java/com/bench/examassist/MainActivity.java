// language: Java, file: MainActivity.java, target: Android API 26+

package com.bench.examassist;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQUEST_CAPTURE = 100;
    private MediaProjectionManager mpm;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop  = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> {
            // shows system dialog "ExamAssist will capture your screen"
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE);
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            tvStatus.setText("● stopped");
        });
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQUEST_CAPTURE && result == RESULT_OK) {
            Intent svc = new Intent(this, CaptureService.class);
            svc.putExtra("code", result);
            svc.putExtra("data", data);
            startForegroundService(svc);
            tvStatus.setText("● running — open your exam app");
            // go to home so exam app can be opened
            moveTaskToBack(true);
        } else {
            tvStatus.setText("● permission denied — try again");
        }
    }
}
