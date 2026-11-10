package dev.kotatko.tally.math;

/** Angle helpers in Minecraft's conventions. No game classes, so it can be unit-tested. */
public final class Angles {
	private Angles() {
	}

	public static final double DEG = Math.PI / 180.0;

	/** Wraps to [-180, 180). */
	public static double wrap(double deg) {
		double d = deg % 360.0;
		if (d >= 180.0) {
			d -= 360.0;
		}
		if (d < -180.0) {
			d += 360.0;
		}
		return d;
	}

	/**
	 * Compass heading (0 = north, 90 = east) from a Minecraft yaw
	 * (0 = south, 90 = west, 180 = north, -90 = east).
	 */
	public static double heading(double yaw) {
		double h = (yaw + 180.0) % 360.0;
		return h < 0 ? h + 360.0 : h;
	}

	/** Unit look vector for a Minecraft yaw / pitch (pitch positive = looking down). */
	public static double[] direction(double yaw, double pitch) {
		double cy = Math.cos(yaw * DEG), sy = Math.sin(yaw * DEG);
		double cp = Math.cos(pitch * DEG), sp = Math.sin(pitch * DEG);
		return new double[] {-sy * cp, -sp, cy * cp};
	}

	/** Minecraft yaw that looks along (x, z). */
	public static double yawOf(double x, double z) {
		return Math.atan2(-x, z) / DEG;
	}

	/** Minecraft pitch (positive = down) that looks along the vector. */
	public static double pitchOf(double x, double y, double z) {
		double h = Math.sqrt(x * x + z * z);
		return -Math.atan2(y, h) / DEG;
	}

	/** Angle in degrees between two vectors; 0 when either is zero-length. */
	public static double between(double ax, double ay, double az, double bx, double by, double bz) {
		double la = Math.sqrt(ax * ax + ay * ay + az * az);
		double lb = Math.sqrt(bx * bx + by * by + bz * bz);
		if (la < 1e-9 || lb < 1e-9) {
			return 0;
		}
		double c = (ax * bx + ay * by + az * bz) / (la * lb);
		return Math.acos(Math.max(-1, Math.min(1, c))) / DEG;
	}

	/**
	 * Clock position (1..12) of a bearing relative to the nose, where
	 * {@code relYaw} is the target's yaw minus own yaw in Minecraft degrees
	 * (positive = to the right).
	 */
	public static int clock(double relYaw) {
		double r = wrap(relYaw);
		int c = (int) Math.round(r / 30.0);
		if (c <= 0) {
			c += 12;
		}
		return c;
	}
}
