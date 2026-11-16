package org.vicentric.indoorlens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Findings {
    private static final String[] LABELS = {
        "Door", "Door", "Door", "Chair", "Table", "Couch", "Cabinet",
        "Refrigerator", "Window", "Pole", "Stairs", "Ramp"
    };

    record Box(float left, float top, float right, float bottom) {
        float width() { return right - left; }
        float height() { return bottom - top; }
        float x() { return (left + right) / 2; }
        float y() { return (top + bottom) / 2; }
        float iou(Box other) {
            float intersection = Math.max(0, Math.min(right, other.right) - Math.max(left, other.left))
                * Math.max(0, Math.min(bottom, other.bottom) - Math.max(top, other.top));
            return intersection / (width() * height() + other.width() * other.height() - intersection);
        }
    }
    record ObjectHit(String label, float score, Box box, boolean held) {
        ObjectHit(String label, float score, Box box) { this(label, score, box, false); }
        boolean isDoor() { return "Door".equals(label); }
    }
    record TextHit(String text, Box box) {}
    record Scene(List<ObjectHit> objects, List<TextHit> text, boolean objectsOk, boolean ocrOk) {}

    static List<ObjectHit> decode(float[][] output, float padX, float padY, float width, float height) {
        if (output.length != LABELS.length + 4) throw new IllegalArgumentException("Unexpected model output");
        var candidates = new ArrayList<ObjectHit>();
        for (int i = 0; i < output[0].length; i++) {
            int best = 0;
            for (int c = 1; c < LABELS.length; c++) {
                if (output[c + 4][i] > output[best + 4][i]) best = c;
            }
            float score = output[best + 4][i];
            if (!(score >= 0.40f)) continue;
            float x = output[0][i] - padX, y = output[1][i] - padY;
            float w = output[2][i] / 2, h = output[3][i] / 2;
            Box box = new Box(clip((x - w) / width), clip((y - h) / height),
                clip((x + w) / width), clip((y + h) / height));
            if (box.width() > .015f && box.height() > .015f) {
                candidates.add(new ObjectHit(LABELS[best], score, box));
            }
        }
        candidates.sort(Comparator.comparingDouble(ObjectHit::score).reversed());
        var results = new ArrayList<ObjectHit>();
        for (var candidate : candidates) {
            if (results.stream().noneMatch(hit -> hit.label().equals(candidate.label())
                    && hit.box().iou(candidate.box()) > .45f)) results.add(candidate);
        }
        results.sort(Comparator.comparing(ObjectHit::isDoor).reversed()
            .thenComparing(Comparator.comparingDouble(ObjectHit::score).reversed()));
        return new ArrayList<>(results.subList(0, Math.min(8, results.size())));
    }

    // A text block can be inside, above, or beside a single door. Treat competing doors
    // as ambiguous and keep the text in the general OCR results.
    static Map<TextHit, ObjectHit> nearbyText(Scene scene) {
        Map<TextHit, ObjectHit> result = new HashMap<>();
        for (var text : scene.text()) {
            ObjectHit match = null;
            for (var door : scene.objects()) {
                if (!door.isDoor()) continue;
                var box = door.box();
                float x = text.box().x(), y = text.box().y();
                if (x < box.left() - .22f * box.width() || x > box.right() + .22f * box.width()
                    || y < box.top() - .18f * box.height() || y > box.top() + .85f * box.height()) continue;
                if (match != null) { match = null; break; }
                match = door;
            }
            if (match != null && !match.held()) result.put(text, match);
        }
        return result;
    }

    static String spokenSummary(Scene scene) {
        var nearby = nearbyText(scene);
        var phrases = new ArrayList<String>();
        var otherObjects = new LinkedHashSet<String>();
        for (var hit : scene.objects()) {
            if (!hit.isDoor()) otherObjects.add(hit.label());
        }
        scene.objects().stream().filter(ObjectHit::isDoor)
            .sorted(Comparator.comparingDouble(hit -> hit.box().x())).forEach(door -> {
                float x = door.box().x();
                String direction = x < .38f ? "on the left" : x > .62f ? "on the right" : "in the center";
                phrases.add((door.held() ? "Door last seen " : "Door ") + direction);
                String signs = readText(scene.text(), nearby, door);
                if (!signs.isEmpty()) phrases.add("Nearby text reads " + signs);
            });
        if (!otherObjects.isEmpty()) phrases.add("Also in view: " + String.join(", ", otherObjects));
        String otherText = readText(scene.text(), nearby, null);
        if (!otherText.isEmpty()) phrases.add("Text in view reads " + otherText);
        return phrases.isEmpty() ? "" : String.join(". ", phrases) + ".";
    }

    private static String readText(List<TextHit> blocks, Map<TextHit, ObjectHit> nearby, ObjectHit door) {
        var phrases = new LinkedHashSet<String>();
        for (var block : blocks) {
            if (!Objects.equals(nearby.get(block), door)) continue;
            phrases.add(block.text());
            if (phrases.size() == 3) break;
        }
        String text = String.join(", ", phrases);
        return text.length() > 180 ? text.substring(0, 179) + "…" : text;
    }

    static float clip(float value) { return Math.max(0, Math.min(1, value)); }
}
