package dev.kotatko.tally.math;

/**
 * Lead computation: which way to launch something so that it meets a moving target.
 *
 * <p>Units are blocks and game ticks. The projectile model is Minecraft's own
 * (see {@code AbstractArrow.tick}): each tick the projectile first moves by its
 * velocity, then the velocity is multiplied by the drag and gravity is
 * subtracted from its y. After {@code n} ticks that gives
 *
 * <pre>
 *   pos(n) = p0 + v0 * S(n) - (0, G(n), 0)
 *   S(n)   = (1 - d^n) / (1 - d)
 *   G(n)   = g * (n - S(n)) / (1 - d)
 * </pre>
 *
 * and the launch velocity is the aim direction times the launch speed plus the
 * shooter's own velocity, which matters a lot from a gliding elytra.
 */
public final class Ballistics {
	private Ballistics() {
	}

	/** A solved shot: unit aim direction and the flight time in ticks. */
	public record Solution(double x, double y, double z, double ticks) {
	}

	private static double s(double n, double drag) {
		return drag >= 0.999999 ? n : (1 - Math.pow(drag, n)) / (1 - drag);
	}

	private static double g(double n, double drag, double gravity) {
		if (gravity == 0) {
			return 0;
		}
		return drag >= 0.999999 ? gravity * n * (n - 1) / 2.0 : gravity * (n - s(n, drag)) / (1 - drag);
	}

	/** Aim vector (not normalised) needed to arrive after exactly {@code n} ticks. */
	private static double[] aimFor(double n, double[] rel, double[] tv, double[] sv, double drag, double gravity) {
		double sn = s(n, drag);
		double vx = (rel[0] + tv[0] * n) / sn - sv[0];
		double vy = (rel[1] + tv[1] * n + g(n, drag, gravity)) / sn - sv[1];
		double vz = (rel[2] + tv[2] * n) / sn - sv[2];
		return new double[] {vx, vy, vz};
	}

	private static double len(double[] v) {
		return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
	}

	/**
	 * @param rel       target position minus launch position
	 * @param targetVel target velocity, blocks per tick
	 * @param shooterVel velocity the projectile inherits from the shooter
	 * @param speed     launch speed, blocks per tick
	 * @param gravity   per-tick gravity (0.05 for arrows, 0 for none)
	 * @param drag      per-tick velocity multiplier (0.99 for arrows, 1 for none)
	 * @param maxTicks  give up beyond this flight time
	 * @return the solution with the shortest flight time, or null when the target cannot be reached
	 */
	public static Solution solve(double[] rel, double[] targetVel, double[] shooterVel, double speed, double gravity, double drag, int maxTicks) {
		if (speed <= 1e-6) {
			return null;
		}
		// f(n) = |aim needed for n ticks| - speed. The first n with f <= 0 brackets the answer.
		double prevF = Double.NaN;
		double prevN = 0;
		final double step = 0.5;
		for (double n = step; n <= maxTicks; n += step) {
			double f = len(aimFor(n, rel, targetVel, shooterVel, drag, gravity)) - speed;
			if (f <= 0) {
				double lo = Double.isNaN(prevF) ? n : prevN;
				double hi = n;
				if (!Double.isNaN(prevF)) {
					for (int i = 0; i < 24; i++) {
						double mid = (lo + hi) / 2;
						double fm = len(aimFor(mid, rel, targetVel, shooterVel, drag, gravity)) - speed;
						if (fm > 0) {
							lo = mid;
						} else {
							hi = mid;
						}
					}
				}
				double[] a = aimFor(hi, rel, targetVel, shooterVel, drag, gravity);
				double l = len(a);
				if (l < 1e-9) {
					return null;
				}
				return new Solution(a[0] / l, a[1] / l, a[2] / l, hi);
			}
			prevF = f;
			prevN = n;
		}
		return null;
	}

	/** Straight-line intercept for something with no gravity or drag, e.g. flying a collision course. */
	public static Solution intercept(double[] rel, double[] targetVel, double speed, int maxTicks) {
		return solve(rel, targetVel, new double[] {0, 0, 0}, speed, 0, 1, maxTicks);
	}
}
