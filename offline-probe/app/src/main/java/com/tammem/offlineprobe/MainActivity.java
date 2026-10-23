package com.tammem.offlineprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

/** Runs the on-device compile test on launch, shows the report and writes it for CI. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setPadding(24, 48, 24, 24);
        tv.setTextSize(12);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);

        writeMarker("STARTED", "launched\n");
        new Thread(() -> {
            OfflineCompiler c = new OfflineCompiler(this);
            long t0 = System.currentTimeMillis();
            c.run();
            long ms = System.currentTimeMillis() - t0;
            boolean pass = c.ecjOk && c.d8Ok;
            String report = "RESULT=" + (pass ? "PASS" : "FAIL") + " (" + ms + "ms)\n\n" + c.log;
            runOnUiThread(() -> tv.setText(report));
            writeResult(pass, report);
        }).start();
    }

    private void writeResult(boolean pass, String report) {
        writeMarker("probe.log", report);
        writeMarker(pass ? "PASS" : "FAIL", "1");
    }

    private void writeMarker(String name, String content) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(new File(dir, name))) {
                o.write(content.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }
}
