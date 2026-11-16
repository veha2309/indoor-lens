package org.vicentric.indoorlens;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class FindingsTest {
    @Test public void letterboxingIsUndoneAndDoorClassesDoNotProduceDuplicateDoors() {
        float[][] prediction = new float[16][4];
        anchor(prediction, 0, 320, 320, 160, 320, 0, .8f);
        anchor(prediction, 1, 321, 321, 160, 320, 2, .7f);
        anchor(prediction, 2, 225, 320, 80, 100, 3, .95f);
        anchor(prediction, 3, 320, 320, 160, 320, 11, .1f);
        var result = Findings.decode(prediction, 160, 0, 320, 640);
        assertEquals(2, result.size());
        assertEquals("Door", result.get(0).label());
        assertEquals(.25f, result.get(0).box().left(), .001f);
        assertEquals(.75f, result.get(0).box().right(), .001f);
        assertEquals("Chair", result.get(1).label());
    }

    @Test public void textNearOneDoorIsShownButNoDoorIsInventedFromOcr() {
        var sign = new Findings.TextHit("Room 204", new Findings.Box(.2f, .12f, .27f, .17f));
        var poster = new Findings.TextHit("Meeting today", new Findings.Box(.7f, .12f, .9f, .2f));
        var first = new Findings.ObjectHit("Door", .9f, new Findings.Box(.1f, .22f, .4f, .9f));
        assertEquals(Collections.singletonMap(sign, first), Findings.nearbyText(new Findings.Scene(
            Collections.singletonList(first), Arrays.asList(sign, poster), true, true)));
        assertTrue(Findings.nearbyText(new Findings.Scene(
            Collections.emptyList(), Collections.singletonList(sign), true, true)).isEmpty());
    }

    @Test public void keepsSeparateOverlappingInstancesAndReportsTheirCount() {
        float[][] prediction = new float[16][4];
        anchor(prediction, 0, 240, 300, 200, 180, 3, .88f);
        anchor(prediction, 1, 244, 306, 195, 177, 3, .78f);
        anchor(prediction, 2, 300, 300, 200, 180, 3, .73f);
        anchor(prediction, 3, 280, 370, 250, 160, 4, .7f);
        var objects = Findings.decode(prediction, 0, 0, 640, 640);
        var scene = new Findings.Scene(objects, Collections.emptyList(), true, true);

        assertEquals(3, objects.size());
        assertEquals(Arrays.asList("2 chairs", "Table"), Findings.otherObjectCounts(scene));
        assertTrue(Findings.spokenSummary(scene).contains("2 chairs"));
    }

    @Test public void textBetweenCompetingDoorsStaysUnassigned() {
        var sign = new Findings.TextHit("Exit", new Findings.Box(.43f, .2f, .45f, .24f));
        var left = new Findings.ObjectHit("Door", .9f, new Findings.Box(.1f, .28f, .4f, .95f));
        var right = new Findings.ObjectHit("Door", .7f, new Findings.Box(.49f, .28f, .8f, .95f));
        var scene = new Findings.Scene(Arrays.asList(left, right), Collections.singletonList(sign), true, true);
        assertTrue(Findings.nearbyText(scene).isEmpty());
        assertEquals("Door on the left. Door on the right. Text in view reads Exit.", Findings.spokenSummary(scene));
    }

    @Test public void spokenSummaryPairsSignsOnlyWithFreshDoorDetections() {
        var left = new Findings.ObjectHit("Door", .9f, new Findings.Box(.1f, .22f, .4f, .9f));
        var right = new Findings.ObjectHit("Door", .7f, new Findings.Box(.6f, .22f, .9f, .9f), true);
        var room = new Findings.TextHit("Room 204", new Findings.Box(.2f, .3f, .27f, .35f));
        var exit = new Findings.TextHit("Exit", new Findings.Box(.7f, .3f, .78f, .35f));
        var scene = new Findings.Scene(Arrays.asList(right, left), Arrays.asList(room, exit), true, true);
        assertEquals("Door on the left. Nearby text reads Room 204. Door last seen on the right. Text in view reads Exit.",
            Findings.spokenSummary(scene));
    }

    private static void anchor(float[][] data, int index, float x, float y, float w, float h, int cls, float score) {
        data[0][index] = x;
        data[1][index] = y;
        data[2][index] = w;
        data[3][index] = h;
        data[4 + cls][index] = score;
    }
}
