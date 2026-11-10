package dev.kotatko.tally;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kotatko.tally.math.Ballistics;
import org.junit.jupiter.api.Test;

class BallisticsTest {
	/**
	 * Flies a projectile exactly the way AbstractArrow.tick does (move, then drag, then gravity)
	 * against a target moving in a straight line, and returns the closest they get.
	 */
	private static double closestApproach(double[] rel, double[] tv, double[] sv, Ballistics.Solution s, double speed, double gravity, double drag) {
		double px = 0, py = 0, pz = 0;
		double vx = s.x() * speed + sv[0], vy = s.y() * speed + sv[1], vz = s.z() * speed + sv[2];
		double best = Double.MAX_VALUE;
		int ticks = (int) Math.ceil(s.ticks()) + 2;
		for (int n = 0; n < ticks; n++) {
			// sample along this tick's movement, as the game sweeps the segment for hits
			for (int i = 0; i <= 20; i++) {
				double f = i / 20.0;
				double t = n + f;
				double dx = px + vx * f - (rel[0] + tv[0] * t);
				double dy = py + vy * f - (rel[1] + tv[1] * t);
				double dz = pz + vz * f - (rel[2] + tv[2] * t);
				best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
			px += vx;
			py += vy;
			pz += vz;
			vx *= drag;
			vy *= drag;
			vz *= drag;
			vy -= gravity;
		}
		return best;
	}

	private static void assertHits(double[] rel, double[] tv, double[] sv, double speed, double gravity, double drag) {
		Ballistics.Solution s = Ballistics.solve(rel, tv, sv, speed, gravity, drag, 160);
		assertNotNull(s, "expected a firing solution");
		assertEquals(1.0, Math.sqrt(s.x() * s.x() + s.y() * s.y() + s.z() * s.z()), 1e-9, "aim must be a unit vector");
		double miss = closestApproach(rel, tv, sv, s, speed, gravity, drag);
		assertTrue(miss < 0.35, "missed by " + miss + " blocks");
	}

	@Test
	void stationaryTargetFromTheGround() {
		assertHits(new double[] {40, 3, 10}, new double[] {0, 0, 0}, new double[] {0, 0, 0}, 3.0, 0.05, 0.99);
	}

	@Test
	void crossingTarget() {
		assertHits(new double[] {0, 5, 60}, new double[] {1.4, 0, 0}, new double[] {0, 0, 0}, 3.0, 0.05, 0.99);
	}

	@Test
	void bothGlidingFast() {
		// Shooter doing 30 m/s north-east, target crossing and descending.
		assertHits(new double[] {35, -12, 55}, new double[] {-1.2, -0.3, 0.9}, new double[] {1.0, -0.1, 1.1}, 3.0, 0.05, 0.99);
	}

	@Test
	void shooterFlyingAwayFromTheTarget() {
		assertHits(new double[] {-30, 4, -20}, new double[] {0.5, 0, 0.5}, new double[] {1.2, 0, 0.8}, 3.15, 0.05, 0.99);
	}

	@Test
	void fireworkRocketHasNoDrop() {
		assertHits(new double[] {20, 10, 30}, new double[] {0.6, 0.1, -0.4}, new double[] {0.3, 0, 0.9}, 1.6, 0, 1);
	}

	@Test
	void halfDrawnBow() {
		assertHits(new double[] {12, 1, 18}, new double[] {0, 0, 0.3}, new double[] {0, 0, 0}, 1.4, 0.05, 0.99);
	}

	@Test
	void targetOutrunningTheShotIsUnreachable() {
		// Directly away at 4 blocks/tick; a 3 blocks/tick arrow from a standing shooter never gets there.
		assertNull(Ballistics.solve(new double[] {0, 0, 50}, new double[] {0, 0, 4}, new double[] {0, 0, 0}, 3.0, 0.05, 0.99, 160));
	}

	@Test
	void interceptMeetsTheTarget() {
		double[] rel = {50, 10, 0};
		double[] tv = {0, 0, 1.0};
		Ballistics.Solution s = Ballistics.intercept(rel, tv, 1.6, 200);
		assertNotNull(s);
		double t = s.ticks();
		assertEquals(rel[0] + tv[0] * t, s.x() * 1.6 * t, 0.05);
		assertEquals(rel[1] + tv[1] * t, s.y() * 1.6 * t, 0.05);
		assertEquals(rel[2] + tv[2] * t, s.z() * 1.6 * t, 0.05);
	}
}
