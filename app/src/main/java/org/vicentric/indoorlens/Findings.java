package org.vicentric.indoorlens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Findings {
    static final int MAX_OBJECTS = 12;
    private static final String[] LABELS = {
        "Door", "Door", "Door", "Chair", "Table", "Couch", "Cabinet",
        "Refrigerator", "Window", "Pole", "Stairs", "Ramp"
    };
    private static final String[] DOOR_LABELS = {"Door", null, null, null};

    record Box(float left, float top, float right, float bottom) {
        float width() { return right - left; }
        float height() { return bottom - top; }
        float x() { return (left + right) / 2; }
        float y() { return (top + bottom) / 2; }
        boolean centersClose(Box other) {
            return Math.abs(x() - other.x()) < .22f * Math.min(width(), other.width())
                && Math.abs(y() - other.y()) < .22f * Math.min(height(), other.height());
        }
        float overlapWidth(Box other) {
            return Math.max(0, Math.min(right, other.right) - Math.max(left, other.left));
        }
        float intersection(Box other) {
            return overlapWidth(other) * Math.max(0, Math.min(bottom, other.bottom) - Math.max(top, other.top));
        }
        float area() { return width() * height(); }
        float iou(Box other) {
            float overlap = intersection(other);
            return overlap / (area() + other.area() - overlap);
        }
        Box union(Box other) {
            return new Box(Math.min(left, other.left), Math.min(top, other.top),
                Math.max(right, other.right), Math.max(bottom, other.bottom));
        }
    }
    record ObjectHit(String label, float score, Box box, boolean held, boolean tentative) {
        ObjectHit(String label, float score, Box box) { this(label, score, box, false, false); }
        ObjectHit(String label, float score, Box box, boolean held) { this(label, score, box, held, false); }
        boolean isDoor() { return "Door".equals(label); }
        String displayLabel() { return tentative ? "Possible door" : label; }
    }
    record TextHit(String text, Box box) {}
    record Scene(List<ObjectHit> objects, List<TextHit> text, boolean objectsOk, boolean ocrOk) {}

    static List<ObjectHit> decode(float[][] output, float padX, float padY, float width, float height) {
        return decode(output, padX, padY, width, height, LABELS, .40f);
    }

    static List<ObjectHit> decodeDoors(float[][] output, float padX, float padY, float width, float height) {
        return decode(output, padX, padY, width, height, DOOR_LABELS, .36f);
    }

    private static List<ObjectHit> decode(float[][] output, float padX, float padY, float width, float height,
                                          String[] labels, float minimumScore) {
        if (output.length != labels.length + 4) throw new IllegalArgumentException("Unexpected model output");
        var candidates = new ArrayList<ObjectHit>();
        for (int i = 0; i < output[0].length; i++) {
            int best = 0;
            for (int c = 1; c < labels.length; c++) {
                if (output[c + 4][i] > output[best + 4][i]) best = c;
            }
            float score = output[best + 4][i];
            if (labels[best] == null || !(score >= minimumScore)) continue;
            float x = output[0][i] - padX, y = output[1][i] - padY;
            float w = output[2][i] / 2, h = output[3][i] / 2;
            Box box = new Box(clip((x - w) / width), clip((y - h) / height),
                clip((x + w) / width), clip((y + h) / height));
            if (box.width() > .015f && box.height() > .015f) {
                candidates.add(new ObjectHit(labels[best], score, box));
            }
        }
        return selectObjects(candidates);
    }

    private static List<ObjectHit> selectObjects(List<ObjectHit> candidates) {
        candidates.sort(Comparator.comparing(ObjectHit::isDoor).reversed()
            .thenComparing(Comparator.comparingDouble(ObjectHit::score).reversed()));
        var results = new ArrayList<ObjectHit>();
        for (var candidate : candidates) {
            if (results.stream().noneMatch(hit -> hit.label().equals(candidate.label())
                    && hit.box().centersClose(candidate.box()) && hit.box().iou(candidate.box()) > .45f)) {
                if ("Cabinet".equals(candidate.label())) {
                    mergeCabinet(results, candidate);
                } else if (results.size() < MAX_OBJECTS) {
                    results.add(candidate);
                }
            }
        }
        return results;
    }

    private static void mergeCabinet(List<ObjectHit> results, ObjectHit candidate) {
        Box cabinet = candidate.box();
        float score = candidate.score();
        // Drawers in the same cabinet usually share a column and meet vertically.
        for (int i = 0; i < results.size();) {
            var other = results.get(i);
            if ("Cabinet".equals(other.label()) && sameCabinet(cabinet, other.box())) {
                cabinet = cabinet.union(other.box());
                score = Math.max(score, other.score());
                results.remove(i);
                i = 0;
            } else {
                i++;
            }
        }
        if (results.size() < MAX_OBJECTS) results.add(new ObjectHit("Cabinet", score, cabinet));
    }

    private static boolean sameCabinet(Box a, Box b) {
        if (a.intersection(b) >= .86f * Math.min(a.area(), b.area())) return true;
        float narrower = Math.min(a.width(), b.width());
        float wider = Math.max(a.width(), b.width());
        float verticalGap = Math.max(a.top(), b.top()) - Math.min(a.bottom(), b.bottom());
        return narrower >= .65f * wider && a.overlapWidth(b) >= .8f * wider
            && verticalGap <= .25f * Math.min(a.height(), b.height());
    }

    static List<ObjectHit> withDoorDetections(List<ObjectHit> primary, List<ObjectHit> extraDoors) {
        var results = new ArrayList<ObjectHit>(primary);
        for (var door : extraDoors) {
            boolean overlapsExisting = results.stream().anyMatch(hit -> {
                if (hit.isDoor()) return hit.box().iou(door.box()) > .30f;
                return ("Cabinet".equals(hit.label()) || "Refrigerator".equals(hit.label()))
                    && hit.box().intersection(door.box()) >= .55f * door.box().area();
            });
            if (!overlapsExisting) {
                results.add(new ObjectHit(door.label(), door.score(), door.box(), false, true));
            }
        }
        results.sort(Comparator.comparing(ObjectHit::isDoor).reversed());
        return new ArrayList<>(results.subList(0, Math.min(MAX_OBJECTS, results.size())));
    }

    static List<String> otherObjectCounts(Scene scene) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var hit : scene.objects()) {
            if (!hit.isDoor()) counts.merge(hit.label(), 1, Integer::sum);
        }
        var labels = new ArrayList<String>();
        counts.forEach((label, count) -> {
            String plural = switch (label) {
                case "Stairs" -> "sets of stairs";
                case "Couch" -> "couches";
                default -> label.toLowerCase(java.util.Locale.ROOT) + "s";
            };
            labels.add(count == 1 ? label : count + " " + plural);
        });
        return labels;
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
            if (match != null && !match.held() && !match.tentative()) result.put(text, match);
        }
        return result;
    }

    static String spokenSummary(Scene scene) {
        var nearby = nearbyText(scene);
        var phrases = new ArrayList<String>();
        var otherObjects = otherObjectCounts(scene);
        scene.objects().stream().filter(ObjectHit::isDoor)
            .sorted(Comparator.comparingDouble(hit -> hit.box().x())).forEach(door -> {
                float x = door.box().x();
                String direction = x < .38f ? "on the left" : x > .62f ? "on the right" : "in the center";
                phrases.add((door.held() ? "Door last seen " : door.displayLabel() + " ") + direction);
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
