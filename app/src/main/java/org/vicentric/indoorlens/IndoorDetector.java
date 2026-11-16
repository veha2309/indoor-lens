package org.vicentric.indoorlens;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.List;

/** Owned by the serial camera worker, including initialization and teardown. */
final class IndoorDetector implements AutoCloseable {
    private static final int SIDE = 640;
    private static final int AREA = SIDE * SIDE;
    private static final long[] SHAPE = {1, 3, SIDE, SIDE};
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final Bitmap input = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(input);
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] pixels = new int[AREA];
    private final FloatBuffer rgb = ByteBuffer.allocateDirect(3 * AREA * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();

    IndoorDetector(Context context) throws IOException, OrtException {
        try (var asset = context.getAssets().openFd("indoor_detector.onnx");
             var model = asset.createInputStream();
             var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            // Map the bundled weights directly instead of copying 38 MB into the Java heap.
            var bytes = model.getChannel().map(FileChannel.MapMode.READ_ONLY,
                asset.getStartOffset(), asset.getLength());
            session = env.createSession(bytes, options);
        } catch (IOException | OrtException | RuntimeException e) {
            input.recycle();
            throw e;
        }
    }

    List<Findings.ObjectHit> detect(Bitmap frame) throws OrtException {
        float scale = (float) SIDE / Math.max(frame.getWidth(), frame.getHeight());
        int w = Math.round(scale * frame.getWidth()), h = Math.round(scale * frame.getHeight());
        int x = (SIDE - w) / 2, y = (SIDE - h) / 2;
        canvas.drawColor(Color.rgb(114, 114, 114));
        canvas.drawBitmap(frame, null, new Rect(x, y, x + w, y + h), filter);
        input.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE);
        for (int i = 0; i < AREA; i++) {
            int color = pixels[i];
            rgb.put(i, ((color >> 16) & 255) / 255f);
            rgb.put(AREA + i, ((color >> 8) & 255) / 255f);
            rgb.put(2 * AREA + i, (color & 255) / 255f);
        }
        try (var tensor = OnnxTensor.createTensor(env, rgb, SHAPE);
             var result = session.run(Collections.singletonMap("images", tensor))) {
            return Findings.decode(((float[][][]) result.get(0).getValue())[0], x, y, w, h);
        }
    }

    @Override public void close() throws OrtException {
        input.recycle();
        session.close();
    }
}
