package dev.kotatko.tally;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kotatko.tally.math.Gpws;
import dev.kotatko.tally.math.Gpws.Level;
import dev.kotatko.tally.math.Gpws.Surface;
import org.junit.jupiter.api.Test;

class GpwsTest {
	@Test
	void divingAtTheGroundWarnsThenScreams() {
		// 40 m/s, sinking at 30: caution a few seconds out, warning inside the lead time.
		assertEquals(Level.NONE, Gpws.assess(200, Surface.GROUND, 40, 26, -30, 1.5));
		assertEquals(Level.TERRAIN, Gpws.assess(100, Surface.GROUND, 40, 26, -30, 1.5));
		assertEquals(Level.PULL_UP, Gpws.assess(50, Surface.GROUND, 40, 26, -30, 1.5));
	}

	@Test
	void shallowLandingIsSilentAtAnySpeed() {
		assertEquals(Level.NONE, Gpws.assess(10, Surface.GROUND, 35, 34.5, -6, 1.5));
	}

	@Test
	void wallAheadWarnsEvenInLevelFlight() {
		assertEquals(Level.PULL_UP, Gpws.assess(30, Surface.WALL, 30, 30, 0, 1.5));
		assertEquals(Level.NONE, Gpws.assess(3, Surface.WALL, 5, 5, 0, 1.5), "too slow to be hurt");
	}

	@Test
	void waterNeverWarns() {
		assertEquals(Level.NONE, Gpws.assess(5, Surface.WATER, 60, 20, -55, 1.5));
	}

	@Test
	void nothingAheadIsNothing() {
		assertEquals(Level.NONE, Gpws.assess(Double.NaN, null, 40, 40, 0, 1.5));
	}

	@Test
	void bankGrowsWithSpeedAndTurnRate() {
		assertEquals(0, Gpws.coordinatedBank(30, 0, 20), 1e-9);
		double gentle = Gpws.coordinatedBank(25, 20, 20);
		double hard = Gpws.coordinatedBank(25, 90, 20);
		assertTrue(gentle > 15 && gentle < 30, "gentle turn: " + gentle);
		assertTrue(hard > 55 && hard < 70, "hard turn: " + hard);
		assertEquals(-hard, Gpws.coordinatedBank(25, -90, 20), 1e-9, "left turn banks left");
	}

	@Test
	void loadFactorReadsOneInSteadyFlight() {
		assertEquals(1.0, Gpws.loadFactor(0, 0, 0, 9.81), 1e-9);
		assertEquals(0.0, Gpws.loadFactor(0, -9.81, 0, 9.81), 1e-9, "free fall");
		assertTrue(Gpws.loadFactor(40, 0, 0, 9.81) > 4, "a hard turn");
	}
}
