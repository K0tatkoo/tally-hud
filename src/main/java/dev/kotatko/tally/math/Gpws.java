package dev.kotatko.tally.math;

/**
 * Ground-proximity logic, kept free of game classes so the thresholds can be unit-tested.
 *
 * <p>What actually hurts with an elytra (from {@code LivingEntity}):
 * <ul>
 *   <li>flying into a wall: damage = lost horizontal speed (blocks/tick) * 10 - 3,
 *       so anything above 0.3 blocks/tick (6 m/s) of lost speed hurts;</li>
 *   <li>landing: fall distance only builds up while sinking faster than
 *       0.5 blocks/tick (10 m/s), so a shallow touchdown is free at any forward speed;</li>
 *   <li>water: never hurts.</li>
 * </ul>
 */
public final class Gpws {
	private Gpws() {
	}

	public enum Surface {
		/** Top face of a block: you land on it. */
		GROUND,
		/** Side or underside of a block: you fly into it. */
		WALL,
		WATER
	}

	public enum Level {
		NONE, TERRAIN, PULL_UP
	}

	/** Sink rate (m/s, positive down) above which a touchdown starts to cost health. */
	public static final double SAFE_SINK = 10.0;
	/** Horizontal speed (m/s) above which hitting a wall costs health. */
	public static final double SAFE_WALL_SPEED = 7.0;
	/** The caution comes this many times earlier than the warning. */
	public static final double CAUTION_FACTOR = 2.2;

	/** Whether hitting this surface at this velocity would do damage. */
	public static boolean dangerous(Surface surface, double horizontalSpeed, double verticalSpeed) {
		return switch (surface) {
			case WATER -> false;
			case GROUND -> -verticalSpeed > SAFE_SINK;
			case WALL -> horizontalSpeed > SAFE_WALL_SPEED;
		};
	}

	/**
	 * @param hitDistance      distance along the velocity vector to the first obstacle, or NaN for none
	 * @param speed            total speed, m/s
	 * @param horizontalSpeed  m/s
	 * @param verticalSpeed    m/s, negative when sinking
	 * @param pullUpSeconds    warning lead time
	 */
	public static Level assess(double hitDistance, Surface surface, double speed, double horizontalSpeed, double verticalSpeed, double pullUpSeconds) {
		if (Double.isNaN(hitDistance) || surface == null || speed < 1e-3) {
			return Level.NONE;
		}
		if (!dangerous(surface, horizontalSpeed, verticalSpeed)) {
			return Level.NONE;
		}
		double t = hitDistance / speed;
		if (t <= pullUpSeconds) {
			return Level.PULL_UP;
		}
		if (t <= pullUpSeconds * CAUTION_FACTOR) {
			return Level.TERRAIN;
		}
		return Level.NONE;
	}

	/**
	 * Bank angle (degrees, positive = right wing down) of a coordinated turn.
	 *
	 * @param speed   m/s
	 * @param yawRate degrees per second, positive = turning right
	 * @param gravity m/s^2
	 */
	public static double coordinatedBank(double speed, double yawRate, double gravity) {
		return Math.atan(speed * yawRate * Angles.DEG / gravity) / Angles.DEG;
	}

	/**
	 * Load factor in g from the acceleration vector (m/s^2). Unaccelerated flight reads 1
	 * whatever reference gravity is passed in.
	 */
	public static double loadFactor(double ax, double ay, double az, double gravity) {
		double y = ay + gravity;
		return Math.sqrt(ax * ax + y * y + az * az) / gravity;
	}
}
