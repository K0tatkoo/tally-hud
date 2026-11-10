package dev.kotatko.tally.flight;

import dev.kotatko.tally.TallyConfig;
import dev.kotatko.tally.math.Angles;
import dev.kotatko.tally.math.Gpws;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** What the aircraft is doing: sampled once per game tick, smoothed per frame for display. */
public final class FlightState {
	/** Reference gravity for the g-meter; with Earth's value an elytra turn reads like a jet's. */
	private static final double G_REF = 9.81;
	/** Gravity used for the banking camera. Chosen so a 90 degree/s turn at cruise banks about 60 degrees. */
	private static final double BANK_GRAVITY = 20.0;
	private static final double MAX_BANK = 75.0;

	public boolean gliding;
	public int glideTicks;
	/** Velocity in blocks per tick, this tick and the one before. */
	public Vec3 velocity = Vec3.ZERO;
	public Vec3 prevVelocity = Vec3.ZERO;
	/** Metres per second. */
	public double speed;
	public double horizontalSpeed;
	public double verticalSpeed;
	public double loadFactor = 1;
	/** Degrees per second, positive = turning right. */
	public double yawRate;
	/** Angle of attack in degrees: nose above the flight path is positive. */
	public double aoa;

	/** Height above whatever is below, or NaN when nothing is within reach. */
	public double radarAltitude = Double.NaN;
	public boolean overWater;
	/** Distance along the flight path to the first obstacle, or NaN. */
	public double hitDistance = Double.NaN;
	public Gpws.Surface hitSurface;

	public int rockets;
	/** Seconds of gliding the elytra has left, or -1 with none worn. */
	public int elytraSeconds = -1;

	/** Camera bank in degrees, positive = right wing down. Smoothed every frame. */
	public double bank;
	private double bankTarget;
	/** Display values eased every frame so the tapes do not step at 20 Hz. */
	public double shownSpeed;
	public double shownVerticalSpeed;

	private float lastYaw;
	private boolean hasLast;

	public void reset() {
		gliding = false;
		glideTicks = 0;
		velocity = prevVelocity = Vec3.ZERO;
		speed = horizontalSpeed = verticalSpeed = 0;
		loadFactor = 1;
		yawRate = 0;
		radarAltitude = hitDistance = Double.NaN;
		hitSurface = null;
		bankTarget = 0;
		hasLast = false;
	}

	public void tick(Minecraft mc, TallyConfig cfg, boolean probeTerrain) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			reset();
			return;
		}

		gliding = p.isFallFlying();
		glideTicks = gliding ? glideTicks + 1 : 0;

		prevVelocity = velocity;
		Vec3 moved = new Vec3(p.getX() - p.xo, p.getY() - p.yo, p.getZ() - p.zo);
		// A teleport is not a velocity.
		velocity = moved.lengthSqr() > 100 ? p.getDeltaMovement() : moved;
		horizontalSpeed = velocity.horizontalDistance() * 20;
		verticalSpeed = velocity.y * 20;
		speed = velocity.length() * 20;

		float yaw = p.getYRot();
		if (hasLast) {
			double rawRate = Mth.wrapDegrees(yaw - lastYaw) * 20.0;
			yawRate += (rawRate - yawRate) * 0.35;
			Vec3 a = velocity.subtract(prevVelocity).scale(400);
			double rawG = Gpws.loadFactor(a.x, a.y, a.z, G_REF);
			loadFactor += (Math.min(rawG, 15) - loadFactor) * 0.3;
		}
		lastYaw = yaw;
		hasLast = true;

		if (speed > 2) {
			double pathPitch = Angles.pitchOf(velocity.x, velocity.y, velocity.z);
			aoa = pathPitch - p.getXRot();
		} else {
			aoa = 0;
		}

		bankTarget = gliding ? Mth.clamp(Gpws.coordinatedBank(horizontalSpeed, yawRate, BANK_GRAVITY), -MAX_BANK, MAX_BANK) : 0;

		if (probeTerrain) {
			probe(p, level, cfg);
		} else {
			radarAltitude = hitDistance = Double.NaN;
			hitSurface = null;
		}

		rockets = p.getInventory().countItem(Items.FIREWORK_ROCKET);
		elytraSeconds = elytraSeconds(p, level);
	}

	private void probe(LocalPlayer p, ClientLevel level, TallyConfig cfg) {
		Vec3 feet = p.position();
		Vec3 below = new Vec3(feet.x, Math.max(level.getMinY() - 1, feet.y - 384), feet.z);
		BlockHitResult down = level.clip(new ClipContext(feet, below, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
		if (down.getType() == HitResult.Type.MISS) {
			radarAltitude = Double.NaN;
			overWater = false;
		} else {
			radarAltitude = Math.max(0, feet.y - down.getLocation().y);
			overWater = level.getFluidState(down.getBlockPos()).is(FluidTags.WATER);
		}

		hitDistance = Double.NaN;
		hitSurface = null;
		if (speed > 3) {
			double pullUp = cfg.pullUpTime / 10.0;
			double reach = Math.min(speed * pullUp * Gpws.CAUTION_FACTOR + 2, 220);
			Vec3 from = feet.add(0, p.getBbHeight() * 0.5, 0);
			Vec3 to = from.add(velocity.normalize().scale(reach));
			BlockHitResult ahead = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
			if (ahead.getType() != HitResult.Type.MISS) {
				hitDistance = from.distanceTo(ahead.getLocation());
				boolean water = level.getFluidState(ahead.getBlockPos()).is(FluidTags.WATER)
					&& level.getBlockState(ahead.getBlockPos()).getCollisionShape(level, ahead.getBlockPos()).isEmpty();
				if (water) {
					hitSurface = Gpws.Surface.WATER;
				} else if (ahead.getDirection() == Direction.UP && level.getFluidState(ahead.getBlockPos()).isEmpty()) {
					hitSurface = Gpws.Surface.GROUND;
				} else {
					// Walls, ceilings and lava: all of them hurt at speed.
					hitSurface = Gpws.Surface.WALL;
				}
			}
		}
	}

	private static int elytraSeconds(LocalPlayer p, ClientLevel level) {
		ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
		if (chest.isEmpty() || !LivingEntity.canGlideUsing(chest, EquipmentSlot.CHEST)) {
			return -1;
		}
		if (!chest.isDamageableItem()) {
			return Integer.MAX_VALUE;
		}
		// One durability point per second of gliding; the last point is kept, the elytra just stops working.
		int left = Math.max(0, chest.getMaxDamage() - chest.getDamageValue() - 1);
		int unbreaking = level.registryAccess()
			.lookupOrThrow(Registries.ENCHANTMENT)
			.get(Enchantments.UNBREAKING)
			.map(h -> EnchantmentHelper.getItemEnchantmentLevel(h, chest))
			.orElse(0);
		return left * (unbreaking + 1);
	}

	/** Called every rendered frame. */
	public void frame(double dt, TallyConfig cfg) {
		double target = bankTarget * cfg.cameraBank / 100.0;
		bank += (target - bank) * (1 - Math.exp(-dt / 0.22));
		if (Math.abs(bank) < 0.01 && target == 0) {
			bank = 0;
		}
		double k = 1 - Math.exp(-dt / 0.09);
		shownSpeed += (speed - shownSpeed) * k;
		shownVerticalSpeed += (verticalSpeed - shownVerticalSpeed) * k;
	}
}
