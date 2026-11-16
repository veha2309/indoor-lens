package org.vicentric.indoorlens;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final int BG = 0xff101917, CARD = 0xff1c2b26, MINT = 0xffa7f0cc;
    private static final int INK = 0xfff3faf6, MUTED = 0xffb5c8bf, AMBER = 0xffffdc99;
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile int generation;
    private CameraScanner scanner; // accessed only by worker
    private boolean requested, active, speechReady;
    private TextToSpeech speech;
    private PreviewView preview;
    private ScannerOverlay overlay;
    private TextView badge, headline, objects, text, hint;
    private Button camera, read;
    private ProcessCameraProvider provider;
    private ImageAnalysis analysis;
    private Findings.Scene scene;
    private long displayedAt;
    private final Runnable expire = () -> {
        if (active && scene != null) showWaiting("Waiting for a fresh scan…");
    };
    private final androidx.activity.result.ActivityResultLauncher<String> permission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) { requested = true; startCamera(); }
            else {
                requested = false;
                showWaiting("Allow camera access to start scanning.");
                camera.setText("Allow camera");
            }
        });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requested = state != null ? state.getBoolean("scan") : hasCamera();
        buildScreen();
        speech = new TextToSpeech(this, status -> runOnUiThread(() -> {
            if (isDestroyed() || status != TextToSpeech.SUCCESS || speech == null) return;
            // Avoid selecting a voice that requires sending observed text to a network service.
            var voices = speech.getVoices();
            if (voices == null) return;
            var offline = voices.stream().filter(v -> !v.isNetworkConnectionRequired()
                && v.getLocale().getLanguage().equals("en")
                && !v.getFeatures().contains("notInstalled")).min(
                    java.util.Comparator.comparingInt(v -> v.getLocale().equals(Locale.ENGLISH)
                        || v.getLocale().equals(Locale.getDefault()) ? 0 : 1));
            speechReady = offline.isPresent() && speech.setVoice(offline.get()) == TextToSpeech.SUCCESS;
        }));
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("scan", requested);
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume() {
        super.onResume();
        if (requested && hasCamera()) startCamera();
    }

    @Override protected void onPause() {
        stopCamera();
        if (speech != null) speech.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        if (speech != null) speech.shutdown();
        super.onDestroy();
    }

    private boolean hasCamera() { return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }

    private void toggleCamera() {
        if (active) {
            requested = false;
            stopCamera();
            return;
        }
        if (!hasCamera()) {
            if (getPreferences(MODE_PRIVATE).getBoolean("asked", false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                requested = true;
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
            } else {
                getPreferences(MODE_PRIVATE).edit().putBoolean("asked", true).apply();
                permission.launch(Manifest.permission.CAMERA);
            }
            return;
        }
        requested = true;
        startCamera();
    }

    private void startCamera() {
        if (active || isDestroyed()) return;
        active = true;
        int token = ++generation;
        badge.setText("●  LIVE");
        badge.setTextColor(MINT);
        camera.setText("Pause camera");
        showWaiting("Looking for doors, objects and text…");
        preview.post(() -> {
            if (generation != token) return;
            var future = ProcessCameraProvider.getInstance(this);
            future.addListener(() -> {
                if (generation != token) return;
                try {
                    provider = future.get();
                    var viewport = preview.getViewPort();
                    if (viewport == null) throw new IllegalStateException("Preview is not ready");
                    int rotation = preview.getDisplay().getRotation();
                    var previewCase = new Preview.Builder().setTargetRotation(rotation).build();
                    previewCase.setSurfaceProvider(preview.getSurfaceProvider());
                    analysis = new ImageAnalysis.Builder().setTargetRotation(rotation)
                        .setResolutionSelector(new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setResolutionStrategy(new androidx.camera.core.resolutionselector.ResolutionStrategy(
                                new android.util.Size(1280, 720),
                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                    analysis.setAnalyzer(worker, image -> {
                        try {
                            if (token != generation) return;
                            if (scanner == null) scanner = new CameraScanner(this);
                            var result = scanner.analyze(image);
                            if (result != null) runOnUiThread(() -> {
                                if (token == generation) render(result);
                            });
                        } catch (Exception e) {
                            android.util.Log.e("IndoorLens", "Camera analysis failed", e);
                            runOnUiThread(() -> {
                                if (token == generation) cameraError("Scan failed. Try starting the camera again.");
                            });
                        } finally {
                            image.close();
                        }
                    });
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, new UseCaseGroup.Builder()
                        .setViewPort(viewport).addUseCase(previewCase).addUseCase(analysis).build());
                } catch (Exception e) {
                    android.util.Log.e("IndoorLens", "Camera could not start", e);
                    cameraError("Camera unavailable. Try again.");
                }
            }, getMainExecutorCompat());
        });
    }

    private java.util.concurrent.Executor getMainExecutorCompat() {
        return androidx.core.content.ContextCompat.getMainExecutor(this);
    }

    private void cameraError(String message) {
        requested = false;
        stopCamera();
        showWaiting(message);
    }

    private void stopCamera() {
        if (active) {
            ++generation;
            active = false;
            if (analysis != null) analysis.clearAnalyzer();
            if (provider != null) provider.unbindAll();
            analysis = null;
            provider = null;
            worker.execute(() -> {
                if (scanner != null) scanner.close();
                scanner = null;
            });
        }
        badge.setText("○  PAUSED");
        badge.setTextColor(MUTED);
        camera.setText(hasCamera() ? "Start camera" : "Allow camera");
        showWaiting("Point the camera at a doorway or sign.");
    }

    private void showWaiting(String message) {
        scene = null;
        overlay.removeCallbacks(expire);
        overlay.update(null);
        headline.setText(active ? "Scanning" : "Ready to scan");
        objects.setText(message);
        text.setText("Sign text appears here");
        text.setTextColor(MUTED);
        hint.setText("On-device scan  •  No setup needed");
        read.setEnabled(false);
    }

    private void render(Findings.Scene result) {
        scene = result;
        displayedAt = SystemClock.elapsedRealtime();
        overlay.update(result);
        overlay.removeCallbacks(expire);
        overlay.postDelayed(expire, 5500);
        long doors = result.objects().stream().filter(Findings.ObjectHit::isDoor).count();
        var labels = new LinkedHashSet<String>();
        for (var item : result.objects()) if (!item.isDoor()) labels.add(item.label());
        boolean empty = doors == 0 && labels.isEmpty();
        headline.setText(doors > 0 ? (doors == 1 ? "Door detected" : doors + " doors detected")
            : !result.objectsOk() ? "Object scan unavailable"
            : empty ? (result.text().isEmpty() ? "No objects detected" : "Text detected") : "Objects detected");
        objects.setText(labels.isEmpty() ? (empty ? "Aim steadily and include the whole doorway." : "Keep the sign in view to read it.")
            : String.join("  ·  ", labels));
        var near = Findings.nearbyText(result);
        String doorText = joinText(result, near, true), sceneText = joinText(result, near, false);
        String textResult = (doorText.isEmpty() ? "" : "Near door: " + doorText)
            + (!doorText.isEmpty() && !sceneText.isEmpty() ? "\n" : "")
            + (sceneText.isEmpty() ? "" : "Text in view: " + sceneText);
        text.setText(textResult.isEmpty() ? (result.ocrOk() ? "No readable text" : "Text scan unavailable") : textResult);
        text.setTextColor(textResult.isEmpty() ? MUTED : AMBER);
        hint.setText(result.ocrOk() ? "On-device scan  •  No setup needed" : "Text scan unavailable on this frame");
        read.setEnabled(!empty || !textResult.isEmpty());
    }

    private static String joinText(Findings.Scene scene, java.util.Map<Findings.TextHit, Findings.ObjectHit> near, boolean matched) {
        var parts = new LinkedHashSet<String>();
        for (var block : scene.text()) {
            if (near.containsKey(block) == matched) parts.add(block.text());
            if (parts.size() >= 3) break;
        }
        String output = String.join("  ·  ", parts);
        return output.length() > 180 ? output.substring(0, 179) + "…" : output;
    }

    private void speak() {
        if (scene == null || SystemClock.elapsedRealtime() - displayedAt > 5500) return;
        if (!speechReady) {
            Toast.makeText(this, "Install an offline English voice to read results aloud.", Toast.LENGTH_SHORT).show();
            return;
        }
        speech.speak(headline.getText() + ". " + (scene.objects().stream().anyMatch(hit -> !hit.isDoor())
            ? objects.getText() + ". " : "") + (scene.text().isEmpty() ? "" : text.getText()),
            TextToSpeech.QUEUE_FLUSH, null, "scan");
    }

    private void buildScreen() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        var root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(14), dp(20), dp(12));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(dp(20) + bars.left, dp(14) + bars.top, dp(20) + bars.right, dp(12) + bars.bottom);
            }
            return insets;
        });
        setContentView(root);
        var header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        var title = label("Indoor Lens", 28, INK, true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        badge = label("○  PAUSED", 11, MUTED, true);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(surface(CARD, 20));
        header.addView(badge);
        root.addView(header);
        root.addView(label("Doors, indoor objects and sign text", 14, MUTED, false));
        gap(root, 14);

        var viewport = new FrameLayout(this);
        viewport.setBackground(surface(0xff080e0c, 20));
        viewport.setClipToOutline(true);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        viewport.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        overlay = new ScannerOverlay();
        viewport.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));

        gap(root, 12);
        var card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(10), dp(16), dp(10));
        card.setBackground(surface(CARD, 18));
        headline = label("", 21, INK, true);
        objects = label("", 14, MUTED, false);
        text = label("", 14, AMBER, false);
        // Reserve result space so the camera viewport and overlay stay aligned as text changes.
        headline.setSingleLine(true);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        objects.setLines(2);
        text.setLines(2);
        objects.setEllipsize(TextUtils.TruncateAt.END);
        text.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(headline);
        card.addView(objects);
        card.addView(text);
        root.addView(card);
        gap(root, 8);

        var controls = new LinearLayout(this);
        camera = button(hasCamera() ? "Start camera" : "Allow camera", CARD, INK);
        read = button("Read aloud", MINT, BG);
        controls.addView(camera, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(new Space(this), new LinearLayout.LayoutParams(dp(8), 1));
        controls.addView(read, new LinearLayout.LayoutParams(0, -2, 1));
        camera.setOnClickListener(v -> toggleCamera());
        read.setOnClickListener(v -> speak());
        root.addView(controls);
        hint = label("", 11, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, 0);
        root.addView(hint);
        showWaiting("Point the camera at a doorway or sign.");
    }

    private Button button(String value, int bg, int fg) {
        var button = new Button(this);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(14);
        button.setTextColor(new android.content.res.ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]}, new int[] {0xff52695e, fg}));
        button.setMinHeight(dp(48));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x336d9a84), surface(bg, 14), null));
        return button;
    }

    private GradientDrawable surface(int color, int radius) {
        var background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        var label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private void gap(LinearLayout root, int height) { root.addView(new Space(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private final class ScannerOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Findings.Scene result;
        ScannerOverlay() {
            super(MainActivity.this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        void update(Findings.Scene scene) { result = scene; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            paint.setColor(0xaab5c8bf);
            paint.setStrokeWidth(dp(2));
            float x = w / 2, y = h / 2, r = dp(8);
            if (result == null || result.objects().isEmpty()) {
                canvas.drawLine(x - r, y, x + r, y, paint);
                canvas.drawLine(x, y - r, x, y + r, paint);
            }
            if (result == null) return;
            for (var hit : result.objects()) {
                var box = hit.box();
                float left = box.left() * w, top = box.top() * h, right = box.right() * w, bottom = box.bottom() * h;
                paint.setColor(hit.isDoor() ? MINT : 0xffc2d6ff);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRoundRect(left, top, right, bottom, dp(6), dp(6), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(dp(13));
                float tagWidth = paint.measureText(hit.label()) + dp(14);
                float tagX = Math.min(left, w - tagWidth), tagY = Math.max(0, top - dp(21));
                canvas.drawRoundRect(tagX, tagY, tagX + tagWidth, tagY + dp(21), dp(4), dp(4), paint);
                paint.setColor(Color.BLACK);
                canvas.drawText(hit.label(), tagX + dp(7), tagY + dp(15), paint);
            }
        }
    }
}
