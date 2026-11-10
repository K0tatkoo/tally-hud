package dev.kotatko.tally;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kotatko.tally.math.Angles;
import org.junit.jupiter.api.Test;

class AnglesTest {
	@Test
	void headingFollowsTheCompass() {
		assertEquals(180, Angles.heading(0), 1e-9, "yaw 0 looks south");
		assertEquals(270, Angles.heading(90), 1e-9, "yaw 90 looks west");
		assertEquals(0, Angles.heading(180), 1e-9, "yaw 180 looks north");
		assertEquals(0, Angles.heading(-180), 1e-9);
		assertEquals(90, Angles.heading(-90), 1e-9, "yaw -90 looks east");
		assertEquals(90, Angles.heading(270 + 360 * 3), 1e-9, "yaw keeps counting past a full turn");
	}

	@Test
	void directionMatchesTheGame() {
		double[] south = Angles.direction(0, 0);
		assertEquals(0, south[0], 1e-9);
		assertEquals(1, south[2], 1e-9);
		double[] west = Angles.direction(90, 0);
		assertEquals(-1, west[0], 1e-9);
		double[] up = Angles.direction(37, -90);
		assertEquals(1, up[1], 1e-9, "negative pitch looks up");
	}

	@Test
	void yawAndPitchRoundTrip() {
		for (double yaw = -170; yaw < 180; yaw += 35) {
			for (double pitch = -80; pitch <= 80; pitch += 20) {
				double[] d = Angles.direction(yaw, pitch);
				assertEquals(yaw, Angles.yawOf(d[0], d[2]), 1e-6);
				assertEquals(pitch, Angles.pitchOf(d[0], d[1], d[2]), 1e-6);
			}
		}
	}

	@Test
	void wrapStaysInRange() {
		assertEquals(-170, Angles.wrap(190), 1e-9);
		assertEquals(170, Angles.wrap(-190), 1e-9);
		assertEquals(-180, Angles.wrap(180), 1e-9);
		assertEquals(10, Angles.wrap(370), 1e-9);
	}

	@Test
	void clockPositions() {
		assertEquals(12, Angles.clock(0));
		assertEquals(3, Angles.clock(90));
		assertEquals(6, Angles.clock(180));
		assertEquals(6, Angles.clock(-180));
		assertEquals(9, Angles.clock(-90));
		assertEquals(1, Angles.clock(30));
		assertEquals(11, Angles.clock(-30));
		assertEquals(12, Angles.clock(-10));
	}

	@Test
	void angleBetween() {
		assertEquals(90, Angles.between(1, 0, 0, 0, 0, 1), 1e-9);
		assertEquals(0, Angles.between(0, 2, 0, 0, 5, 0), 1e-6);
		assertEquals(180, Angles.between(0, 0, 1, 0, 0, -3), 1e-6);
	}
}
