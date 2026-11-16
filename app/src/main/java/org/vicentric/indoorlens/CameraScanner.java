package org.vicentric.indoorlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.camera.core.ImageProxy;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.Collections;

final class CameraScanner implements AutoCloseable {
    private final Context context;
    private final PowerManager power;
    private final com.google.mlkit.vision.text.TextRecognizer ocr =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private IndoorDetector detector;
    private boolean detectorFailed;
    private long readyAt;

    CameraScanner(Context context) {
        this.context = context.getApplicationContext();
        power = context.getSystemService(PowerManager.class);
    }

    /** Returns null for a skipped frame, leaving CPU time for the preview and other apps. */
    Findings.Scene analyze(ImageProxy image) {
        if (SystemClock.elapsedRealtime() < readyAt) return null;
        var raw = image.toBitmap();
        var crop = image.getCropRect();
        var rotation = new Matrix();
        rotation.postRotate(image.getImageInfo().getRotationDegrees());
        Bitmap frame = raw;
        try {
            frame = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height(), rotation, true);
            var objects = Collections.<Findings.ObjectHit>emptyList();
            boolean objectsOk = false;
            try {
                if (!detectorFailed) {
                    if (detector == null) detector = new IndoorDetector(context);
                    objects = detector.detect(frame);
                    objectsOk = true;
                }
            } catch (Exception | LinkageError e) {
                detectorFailed = true;
                Log.e("IndoorLens", "Object detection is unavailable", e);
            }
            var text = new ArrayList<Findings.TextHit>();
            boolean ocrOk = false;
            try {
                var recognized = Tasks.await(ocr.process(InputImage.fromBitmap(frame, 0)));
                for (var block : recognized.getTextBlocks()) {
                    var b = block.getBoundingBox();
                    String value = block.getText().replaceAll("\\s+", " ").trim();
                    if (value.isEmpty() || b == null) continue;
                    text.add(new Findings.TextHit(value, new Findings.Box(
                        Findings.clip((float) b.left / frame.getWidth()),
                        Findings.clip((float) b.top / frame.getHeight()),
                        Findings.clip((float) b.right / frame.getWidth()),
                        Findings.clip((float) b.bottom / frame.getHeight()))));
                }
                ocrOk = true;
            } catch (Exception e) {
                Log.w("IndoorLens", "Could not read this frame", e);
            }
            return new Findings.Scene(objects, text, objectsOk, ocrOk);
        } finally {
            if (frame != raw) frame.recycle();
            raw.recycle();
            readyAt = SystemClock.elapsedRealtime() + (power.isPowerSaveMode()
                || (android.os.Build.VERSION.SDK_INT >= 29
                    && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE)
                ? 2400L : 750L);
        }
    }

    @Override public void close() {
        ocr.close();
        if (detector != null) {
            try { detector.close(); }
            catch (Exception e) { Log.w("IndoorLens", "Could not close detector", e); }
        }
    }
}
