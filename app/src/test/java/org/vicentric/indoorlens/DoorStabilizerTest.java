package org.vicentric.indoorlens;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class DoorStabilizerTest {
    private final DoorStabilizer stabilizer = new DoorStabilizer();
    private final Findings.ObjectHit door = new Findings.ObjectHit("Door", .9f, new Findings.Box(.1f, .2f, .4f, .9f));
    private final byte[] steady = new byte[24 * 24];
    private final Findings.Scene detected = new Findings.Scene(
        Collections.singletonList(door), Collections.emptyList(), true, true);
    private final Findings.Scene missed = new Findings.Scene(
        Collections.emptyList(), Collections.emptyList(), true, true);

    @Test public void holdsForOneMissAndDoesNotDuplicateARecoveredDoor() {
        assertEquals(detected, stabilizer.update(detected, steady, 1000));
        assertTrue(stabilizer.update(missed, steady, 2000).objects().get(0).held());
        assertEquals(missed, stabilizer.update(missed, steady, 3000));
        assertEquals(detected, stabilizer.update(detected, steady, 4000));
        assertEquals(detected, stabilizer.update(detected, steady, 5000));
    }

    @Test public void changedViewAndLongScanGapClearOldBoxes() {
        byte[] changed = steady.clone();
        java.util.Arrays.fill(changed, (byte) 100);
        stabilizer.update(detected, steady, 1000);
        assertEquals(missed, stabilizer.update(missed, changed, 2000));
        stabilizer.update(detected, changed, 3000);
        assertEquals(missed, stabilizer.update(missed, changed, 6000));
    }
}
