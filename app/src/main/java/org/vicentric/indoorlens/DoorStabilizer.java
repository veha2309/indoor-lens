package org.vicentric.indoorlens;

import java.util.ArrayList;

/** Keeps a door visible for one sampled miss when the camera view is steady. */
final class DoorStabilizer {
    private static final long MAX_SCAN_GAP_MS = 2000;
    private Findings.Scene previous;
    private byte[] previousLuma;
    private long previousTime;

    Findings.Scene update(Findings.Scene current, byte[] luma, long timestamp) {
        var last = previous;
        var lastLuma = previousLuma;
        long gap = timestamp - previousTime;
        // Store the raw observations so that a held box can never carry across two misses.
        previous = current;
        previousLuma = luma;
        previousTime = timestamp;
        if (!current.objectsOk() || last == null || !last.objectsOk()
                || gap < 0 || gap > MAX_SCAN_GAP_MS
                || !looksSteady(lastLuma, luma)) return current;

        var displayed = new ArrayList<>(current.objects());
        for (var hit : last.objects()) {
            if (displayed.size() >= 8) break;
            if (hit.isDoor() && current.objects().stream().noneMatch(
                    match -> match.isDoor() && match.box().iou(hit.box()) > .25f)) {
                displayed.add(new Findings.ObjectHit(hit.label(), hit.score(), hit.box(), true));
            }
        }
        return new Findings.Scene(displayed, current.text(), current.objectsOk(), current.ocrOk());
    }

    private static boolean looksSteady(byte[] previous, byte[] current) {
        int difference = 0;
        int changedPixels = 0;
        for (int i = 0; i < current.length; i++) {
            int delta = Math.abs(Byte.toUnsignedInt(previous[i]) - Byte.toUnsignedInt(current[i]));
            difference += delta;
            if (delta > 18) changedPixels++;
        }
        return difference <= 6 * current.length && changedPixels * 10 <= current.length;
    }
}
