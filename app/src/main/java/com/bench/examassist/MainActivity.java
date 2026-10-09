// language: Java, file: MainActivity.java, target: Android API 26+
// setup screen — checks permissions, opens relevant settings, shows status

package com.bench.examassist;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.app.Activity;

import java.util.List;

public class MainActivity extends Activity {

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);

        Button btnAccessibility = findViewById(R.id.btnAccessibility);
        Button btnOverlay       = findViewById(R.id.btnOverlay);

        btnAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        btnOverlay.setOnClickListener(v -> {
            Intent i = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean accessOk = isAccessibilityEnabled();
        boolean overlayOk = Settings.canDrawOverlays(this);

        StringBuilder sb = new StringBuilder();
        sb.append("Accessibility service: ").append(accessOk ? "✓ ON" : "✗ OFF").append('\n');
        sb.append("Overlay permission:    ").append(overlayOk ? "✓ ON" : "✗ OFF").append('\n');

        if (accessOk && overlayOk) {
            sb.append("\n● Active — open your exam app.");
        } else {
            sb.append("\nEnable both above, then open your exam app.");
        }

        statusText.setText(sb.toString());
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabled =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String pkg = getPackageName();
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getId().startsWith(pkg)) return true;
        }
        return false;
    }
}
