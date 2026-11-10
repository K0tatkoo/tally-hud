package dev.kotatko.tally.radar;

import dev.kotatko.tally.TallyConfig;
import dev.kotatko.tally.audio.Sounds;
import dev.kotatko.tally.edition.Edition;
import dev.kotatko.tally.math.Angles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The radar: a cone ahead of the nose. Anything inside it and in line of sight becomes a tracked
 * contact that the HUD boxes and that can be locked. Separately, nearby players in line of sight are
 * kept as bearings for the warning receiver, from any direction.
 */
public final class Radar {
	/** A contact is kept this long after it was last seen, drawn dimmed. */
	private static final int COAST_TICKS = 40;
	/** A lock survives this long without sight of the target. */
	private static final int LOCK_MEMORY_TICKS = 60;
	/** A lock is dropped beyond this multiple of the radar range. */
	private static final double LOCK_RANGE_FACTOR = 1.25;
	private static final double THREAT_RANGE = 96;
	private static final double THREAT_ANGLE = 6;
	private static final int THREAT_TICKS = 10;
	/** A player who left the warning radius chirps again after staying away this long. */
	private static final int PROXIMITY_REARM_TICKS = 100;
	private static final int INACTIVE_CLEAR_TICKS = 60;

	public static final class Contact {
		public final int id;
		public Entity entity;
		public boolean player;
		/** Centre of the target when last seen. */
		public Vec3 lastPos = Vec3.ZERO;
		public long lastSeen;
		public long firstSeen;
		public boolean visible;
		public boolean inCone;
		public double range;
		/** Closing speed in m/s, positive = getting closer. */
		public double closure;
		/** Degrees between the nose and the target. */
		public double angleOff;
		private boolean touched;

		Contact(int id) {
			this.id = id;
		}

		public String name() {
			return entity.getName().getString();
		}
	}

	/** A nearby player as the warning receiver knows them: a direction and a distance. */
	public record Bearing(double relativeYaw, double range, boolean threat) {
	}

	private final Map<Integer, Contact> contacts = new LinkedHashMap<>();
	private final List<Bearing> bearings = new ArrayList<>();
	private final Map<Integer, Integer> aimedTicks = new HashMap<>();
	private final Map<Integer, Long> lastNear = new HashMap<>();
	private int lockedId = -1;
	private long lockTick;
	private long tick;
	private int inactiveTicks;

	/** Set for one tick when a player newly enters the warning radius. */
	public boolean newContact;
	/** Whether some player has been pointing straight at us for a moment. */
	public boolean threat;
	/** Direction of the nearest such player relative to the nose, Minecraft degrees (positive = right). */
	public double threatRelativeYaw;

	public Collection<Contact> contacts() {
		return contacts.values();
	}

	public List<Bearing> bearings() {
		return bearings;
	}

	public Contact locked() {
		return lockedId == -1 ? null : contacts.get(lockedId);
	}

	/** Ticks since the current lock was taken. */
	public long lockAge() {
		return tick - lockTick;
	}

	public long now() {
		return tick;
	}

	public void clear() {
		contacts.clear();
		bearings.clear();
		aimedTicks.clear();
		lastNear.clear();
		lockedId = -1;
		newContact = false;
		threat = false;
	}

	public void tick(Minecraft mc, TallyConfig cfg, boolean active) {
		tick++;
		newContact = false;
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			clear();
			return;
		}
		if (!active) {
			// A short touchdown should not throw the picture away.
			if (++inactiveTicks > INACTIVE_CLEAR_TICKS) {
				clear();
			}
			threat = false;
			bearings.clear();
			return;
		}
		inactiveTicks = 0;

		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1.0f);
		bearings.clear();
		boolean anyThreat = false;
		double threatRange = Double.MAX_VALUE;
		for (Contact c : contacts.values()) {
			c.touched = false;
		}

		for (Entity e : candidates(level, p, cfg)) {
			if (e == p || !e.isAlive() || e.isSpectator() || e == p.getVehicle()) {
				continue;
			}
			// An invisible player gives no return unless something makes them glow.
			if (e.isInvisible() && !e.isCurrentlyGlowing()) {
				continue;
			}
			Vec3 centre = e.position().add(0, e.getBbHeight() * 0.5, 0);
			Vec3 rel = centre.subtract(eye);
			double dist = rel.length();
			boolean isPlayer = e instanceof Player;

			if (isPlayer) {
				boolean aimed = false;
				if (dist <= THREAT_RANGE) {
					Vec3 theirLook = e.getViewVector(1.0f);
					Vec3 toMe = eye.subtract(e.getEyePosition());
					double ang = Angles.between(theirLook.x, theirLook.y, theirLook.z, toMe.x, toMe.y, toMe.z);
					if (ang < THREAT_ANGLE && clear(level, p, eye, e.getEyePosition())) {
						int n = aimedTicks.merge(e.getId(), 1, Integer::sum);
						aimed = n >= THREAT_TICKS;
					} else {
						aimedTicks.remove(e.getId());
					}
				} else {
					aimedTicks.remove(e.getId());
				}
				double relYaw = Angles.wrap(Angles.yawOf(rel.x, rel.z) - p.getYRot());
				if (aimed && dist < threatRange) {
					anyThreat = true;
					threatRange = dist;
					threatRelativeYaw = relYaw;
				}
				// A player behind blocks is not given away (Edition.NEARBY_THROUGH_BLOCKS is a compile-time
				// constant, false in the public edition, so there the line-of-sight test is unconditional).
				if (dist <= cfg.proximityRadius && (Edition.NEARBY_THROUGH_BLOCKS || sees(level, p, eye, e, centre))) {
					Long last = lastNear.put(e.getId(), tick);
					if (last == null || tick - last > PROXIMITY_REARM_TICKS) {
						newContact = true;
					}
					bearings.add(new Bearing(relYaw, dist, aimed));
				} else if (aimed) {
					bearings.add(new Bearing(relYaw, dist, true));
				}
			}

			boolean isLocked = e.getId() == lockedId;
			if (dist > cfg.radarRange * (isLocked ? LOCK_RANGE_FACTOR : 1.0)) {
				continue;
			}
			double angleOff = Angles.between(look.x, look.y, look.z, rel.x, rel.y, rel.z);
			boolean inCone = angleOff <= cfg.radarCone;
			Contact c = contacts.get(e.getId());
			if (!inCone && c == null) {
				continue;
			}
			// A lock is held outside the cone too: the HUD then points back to the target.
			boolean visible = (inCone || isLocked) && (!Edition.lineOfSightOnly() || sees(level, p, eye, e, centre));
			if (c == null) {
				if (!visible) {
					continue;
				}
				c = new Contact(e.getId());
				c.firstSeen = tick;
				c.range = dist;
				contacts.put(c.id, c);
			}
			c.entity = e;
			c.player = isPlayer;
			c.touched = true;
			c.inCone = inCone;
			c.visible = visible;
			c.angleOff = angleOff;
			if (visible) {
				c.closure = (c.range - dist) * 20.0;
				c.range = dist;
				c.lastPos = centre;
				c.lastSeen = tick;
			}
		}

		for (Iterator<Contact> it = contacts.values().iterator(); it.hasNext(); ) {
			Contact c = it.next();
			if (!c.touched) {
				// Gone from the world, or out of range.
				c.visible = false;
				if (c.entity == null || !c.entity.isAlive() || c.entity.isRemoved()) {
					if (c.id == lockedId) {
						dropLock(cfg);
					}
					it.remove();
					continue;
				}
			}
			int memory = c.id == lockedId ? LOCK_MEMORY_TICKS : COAST_TICKS;
			if (tick - c.lastSeen > memory) {
				if (c.id == lockedId) {
					dropLock(cfg);
				}
				it.remove();
			}
		}

		lastNear.values().removeIf(t -> tick - t > PROXIMITY_REARM_TICKS * 4L);
		threat = anyThreat;
	}

	private static Iterable<? extends Entity> candidates(ClientLevel level, LocalPlayer p, TallyConfig cfg) {
		if (cfg.targets == TallyConfig.Targets.PLAYERS) {
			return level.players();
		}
		List<Entity> out = new ArrayList<>();
		double reach = Math.max(cfg.radarRange * LOCK_RANGE_FACTOR, THREAT_RANGE);
		double reachSq = reach * reach;
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof Player) {
				out.add(e);
			} else if (e.distanceToSqr(p) <= reachSq) {
				if (cfg.targets == TallyConfig.Targets.ALL ? (e instanceof LivingEntity && !(e instanceof ArmorStand)) : e instanceof Enemy) {
					out.add(e);
				}
			}
		}
		return out;
	}

	private static boolean clear(ClientLevel level, LocalPlayer p, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
	}

	private static boolean sees(ClientLevel level, LocalPlayer p, Vec3 eye, Entity e, Vec3 centre) {
		return clear(level, p, eye, centre) || clear(level, p, eye, e.getEyePosition());
	}

	private void dropLock(TallyConfig cfg) {
		lockedId = -1;
		if (cfg.lockTones) {
			Sounds.UNLOCK.play();
		}
	}

	/**
	 * The lock key. Locks the visible contact nearest the nose; pressed again on the same target
	 * (or with nothing in the cone) it lets go.
	 */
	public void toggleLock(TallyConfig cfg) {
		Contact best = null;
		for (Contact c : contacts.values()) {
			if (c.visible && c.inCone && c.entity != null && c.entity.isAlive() && (best == null || c.angleOff < best.angleOff)) {
				best = c;
			}
		}
		if (best == null || best.id == lockedId) {
			if (lockedId != -1) {
				dropLock(cfg);
			} else if (cfg.lockTones) {
				Sounds.NO_TARGET.play();
			}
			return;
		}
		lockedId = best.id;
		lockTick = tick;
		if (cfg.lockTones) {
			Sounds.LOCK.play();
		}
	}
}
