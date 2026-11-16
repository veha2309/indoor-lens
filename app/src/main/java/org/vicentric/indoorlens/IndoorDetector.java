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
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final int side;
    private final Bitmap input;
    private final Canvas canvas;
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] pixels;
    private final FloatBuffer rgb;

    IndoorDetector(Context context, String filename, int side) throws IOException, OrtException {
        this.side = side;
        try (var asset = context.getAssets().openFd(filename);
             var model = asset.createInputStream();
             var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            // Map the bundled weights directly so the Java heap does not hold a copy.
            var bytes = model.getChannel().map(FileChannel.MapMode.READ_ONLY,
                asset.getStartOffset(), asset.getLength());
            session = env.createSession(bytes, options);
        }
        input = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(input);
        pixels = new int[side * side];
        rgb = ByteBuffer.allocateDirect(3 * pixels.length * Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    List<Findings.ObjectHit> detect(Bitmap frame) throws OrtException {
        return detect(frame, 0f, false);
    }

    List<Findings.ObjectHit> detectDoors(Bitmap frame) throws OrtException {
        // Give narrow door panels more input pixels on the small model.
        return detect(frame, .85f, true);
    }

    private List<Findings.ObjectHit> detect(Bitmap frame, float stretch, boolean doorsOnly) throws OrtException {
        float scale = (float) side / Math.max(frame.getWidth(), frame.getHeight());
        int w = Math.round(scale * frame.getWidth() * (1 - stretch) + side * stretch);
        int h = Math.round(scale * frame.getHeight() * (1 - stretch) + side * stretch);
        int x = (side - w) / 2, y = (side - h) / 2;
        canvas.drawColor(Color.rgb(114, 114, 114));
        canvas.drawBitmap(frame, null, new Rect(x, y, x + w, y + h), filter);
        input.getPixels(pixels, 0, side, 0, 0, side, side);
        int area = pixels.length;
        for (int i = 0; i < area; i++) {
            int color = pixels[i];
            rgb.put(i, ((color >> 16) & 255) / 255f);
            rgb.put(area + i, ((color >> 8) & 255) / 255f);
            rgb.put(2 * area + i, (color & 255) / 255f);
        }
        try (var tensor = OnnxTensor.createTensor(env, rgb, new long[]{1, 3, side, side});
             var result = session.run(Collections.singletonMap("images", tensor))) {
            var prediction = ((float[][][]) result.get(0).getValue())[0];
            return doorsOnly ? Findings.decodeDoors(prediction, x, y, w, h)
                : Findings.decode(prediction, x, y, w, h);
        }
    }

    @Override public void close() throws OrtException {
        input.recycle();
        session.close();
    }
}
