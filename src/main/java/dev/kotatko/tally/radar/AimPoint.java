package dev.kotatko.tally.radar;

import dev.kotatko.tally.TallyConfig;
import dev.kotatko.tally.flight.FlightState;
import dev.kotatko.tally.math.Angles;
import dev.kotatko.tally.math.Ballistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;

/**
 * Where to point for the locked target: a firing solution for the bow, crossbow or trident in hand,
 * or an intercept course. The HUD draws it as a symbol; it never moves the view.
 */
public final class AimPoint {
	private AimPoint() {
	}

	/** Where to point. {@code ballistic} is true when it is a firing solution for the held weapon. */
	public record Aim(Vec3 direction, boolean ballistic, double ticks) {
	}

	/** Launch speed (blocks/tick), gravity and drag per tick of what the player is holding. */
	record Weapon(double speed, double gravity, double drag) {
	}

	static Weapon weaponOf(LocalPlayer p) {
		ItemStack using = p.isUsingItem() ? p.getUseItem() : ItemStack.EMPTY;
		for (ItemStack s : new ItemStack[] {using, p.getMainHandItem(), p.getOffhandItem()}) {
			if (s.isEmpty()) {
				continue;
			}
			Item item = s.getItem();
			if (item instanceof BowItem) {
				// A half-drawn bow shoots slower; assume a full draw until the player starts pulling.
				double power = s == using ? Math.max(0.35, BowItem.getPowerForTime(p.getTicksUsingItem())) : 1.0;
				return new Weapon(3.0 * power, 0.05, 0.99);
			}
			if (item instanceof CrossbowItem) {
				ChargedProjectiles loaded = s.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
				if (loaded.contains(Items.FIREWORK_ROCKET)) {
					// A rocket fired at an angle keeps its speed and is not pulled down.
					return new Weapon(1.6, 0, 1);
				}
				return new Weapon(3.15, 0.05, 0.99);
			}
			if (item instanceof TridentItem) {
				return new Weapon(2.5, 0.05, 0.99);
			}
		}
		return null;
	}

	public static Aim solve(Minecraft mc, TallyConfig cfg, FlightState flight, Radar.Contact c, float partial) {
		LocalPlayer p = mc.player;
		if (p == null || c == null || c.entity == null) {
			return null;
		}
		Entity e = c.entity;
		Vec3 eye = p.getEyePosition(partial);
		Vec3 target;
		Vec3 targetVel;
		if (c.visible) {
			target = e.getPosition(partial).add(0, e.getBbHeight() * 0.5, 0);
			targetVel = new Vec3(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo);
			if (targetVel.lengthSqr() > 100) {
				targetVel = Vec3.ZERO;
			}
		} else {
			target = c.lastPos;
			targetVel = Vec3.ZERO;
		}
		Vec3 rel = target.subtract(eye);
		double[] tv = {targetVel.x, targetVel.y, targetVel.z};

		Weapon weapon = cfg.leadProjectiles ? weaponOf(p) : null;
		if (weapon != null) {
			// Arrows leave from just below the eye and inherit the shooter's motion.
			Vec3 own = flight.velocity;
			double[] sv = {own.x, p.onGround() ? 0 : own.y, own.z};
			Ballistics.Solution s = Ballistics.solve(new double[] {rel.x, rel.y + 0.1, rel.z}, tv, sv, weapon.speed(), weapon.gravity(), weapon.drag(), 120);
			if (s != null) {
				return new Aim(new Vec3(s.x(), s.y(), s.z()), true, s.ticks());
			}
		}

		// No weapon: fly a collision course rather than chasing the target's tail.
		double ownSpeed = flight.velocity.length();
		if (ownSpeed > 0.25) {
			Ballistics.Solution s = Ballistics.intercept(new double[] {rel.x, rel.y, rel.z}, tv, ownSpeed, 100);
			if (s != null && Angles.between(s.x(), s.y(), s.z(), rel.x, rel.y, rel.z) < 35) {
				return new Aim(new Vec3(s.x(), s.y(), s.z()), false, s.ticks());
			}
		}
		double len = rel.length();
		return len < 1e-6 ? null : new Aim(rel.scale(1 / len), false, 0);
	}
}
