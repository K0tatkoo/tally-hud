package dev.kotatko.tally.gametest;

import com.mojang.authlib.GameProfile;
import dev.kotatko.tally.TallyClient;
import dev.kotatko.tally.TallyConfig;
import dev.kotatko.tally.math.Angles;
import dev.kotatko.tally.math.Gpws;
import dev.kotatko.tally.radar.AimPoint;
import dev.kotatko.tally.radar.Radar;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Scripted sorties in a real client. Each one takes off, sets up a situation, asserts on what the
 * mod made of it, and saves a screenshot for a human to look at:
 *
 * <ol>
 *   <li>another player ahead, aiming at us: contact, nearby chirp, SPIKE, lock, lock lost when they leave;</li>
 *   <li>three mobs: lock, the view is left alone, bow gives a firing solution, then the edition's own
 *       checks ({@link EditionTests});</li>
 *   <li>a wall goes up between us and a target: the contact is masked and the terrain warning fires; the
 *       edition decides what a player hidden behind it may give away;</li>
 *   <li>a hard turn banks the camera the right way; a dive at the ground gets "pull up".</li>
 * </ol>
 */
public class TallyGameTest implements FabricClientGameTest {
	static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
		System.out.println("[tally-test] ok: " + what);
	}

	static void note(String what) {
		System.out.println("[tally-test] " + what);
	}

	static void shot(ClientGameTestContext context, String name) {
		// No advancement toasts or chat lines over the HUD in the pictures.
		context.runOnClient(mc -> {
			mc.gui.toastManager().clear();
			mc.gui.hud.getChat().clearMessages(false);
		});
		Path p = context.takeScreenshot(name);
		note("screenshot: " + p);
	}

	static ServerPlayer me(MinecraftServer s) {
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (!(p instanceof FakePlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no real player");
	}

	/** A second player, without a second client: a fake player the server tells the client about. */
	static FakePlayer spawnPlayer(MinecraftServer s, String name, Vec3 offset) {
		ServerPlayer p = me(s);
		ServerLevel level = (ServerLevel) p.level();
		FakePlayer b = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), name));
		Vec3 pos = p.position().add(offset);
		b.snapTo(pos.x, pos.y, pos.z, 180f, 0f);
		b.setYHeadRot(180f);
		s.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(b)));
		level.addNewPlayer(b);
		return b;
	}

	static void removePlayer(MinecraftServer s, FakePlayer b) {
		((ServerLevel) b.level()).removePlayerImmediately(b, Entity.RemovalReason.DISCARDED);
		s.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(b.getUUID())));
	}

	/** Back up to altitude, heading south, gliding. */
	private static void launch(ClientGameTestContext context, TestSingleplayerContext sp) {
		TestServerContext server = sp.getServer();
		server.runCommand("kill @e[type=minecraft:husk]");
		server.runCommand("execute as @a at @s run tp @s ~ 230 ~ 0 4");
		context.waitTicks(4);
		server.runOnServer(s -> {
			ServerPlayer p = me(s);
			p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
			p.getAbilities().flying = false;
			p.onUpdateAbilities();
			p.startFallFlying();
		});
		context.waitTicks(25);
		check(context.computeOnClient(mc -> mc.player.isFallFlying() && TallyClient.active()), "airborne, HUD on");
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1280, 720);
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			sp.getConnection().waitForChunksRender();

			context.runOnClient(mc -> {
				TallyConfig c = TallyClient.config();
				c.targets = TallyConfig.Targets.PLAYERS;
				EditionTests.configure(c);
				c.soundVolume = 25;
				c.hudMode = TallyConfig.HudMode.AUTO;
			});
			server.runCommand("gamemode creative @a");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runOnServer(s -> me(s).getInventory().add(new ItemStack(Items.FIREWORK_ROCKET, 12)));
			context.waitTicks(20);
			check(!context.computeOnClient(mc -> TallyClient.active()), "HUD is off on the ground");
			note("fps on the ground, HUD off: " + context.computeOnClient(mc -> mc.getFps()));

			// ================= sortie 1: another player =================
			launch(context, sp);
			check(context.computeOnClient(mc -> TallyClient.hudFade() > 0.9f), "HUD has faded in");
			check(context.computeOnClient(mc -> TallyClient.radar().contacts().isEmpty()), "empty sky, no contacts");

			FakePlayer[] bandit = new FakePlayer[1];
			server.runOnServer(s -> bandit[0] = spawnPlayer(s, "Bandit", new Vec3(-4, -14, 42)));
			// Keep the bandit's crosshair on us for a second.
			for (int i = 0; i < 24; i++) {
				server.runOnServer(s -> {
					ServerPlayer p = me(s);
					FakePlayer b = bandit[0];
					Vec3 d = p.getEyePosition().subtract(b.getEyePosition());
					float yaw = (float) Angles.yawOf(d.x, d.z);
					float pitch = (float) Angles.pitchOf(d.x, d.y, d.z);
					b.setYRot(yaw);
					b.setXRot(pitch);
					b.setYHeadRot(yaw);
				});
				context.waitTick();
			}
			int players = context.computeOnClient(mc -> mc.level.players().size());
			check(players == 2, "client sees the other player (players in level: " + players + ")");
			Boolean isPlayerContact = context.computeOnClient(mc -> {
				for (Radar.Contact c : TallyClient.radar().contacts()) {
					if (c.visible && c.player) {
						return true;
					}
				}
				return false;
			});
			check(isPlayerContact, "radar marks the player");
			check(context.computeOnClient(mc -> !TallyClient.radar().bearings().isEmpty()), "warning receiver has the nearby player's bearing");
			check(context.computeOnClient(mc -> TallyClient.radar().threat && TallyClient.warnings().threat), "being aimed at raises SPIKE");
			note("spike clock position: " + context.computeOnClient(mc -> TallyClient.warnings().threatClock));
			context.getInput().pressKey(TallyClient.lockKey());
			context.waitTicks(8);
			check(context.computeOnClient(mc -> {
				Radar.Contact c = TallyClient.radar().locked();
				return c != null && c.player && c.name().equals("Bandit");
			}), "lock key locked the player");
			shot(context, "1-player-locked-spike");
			note("fps gliding, HUD on: " + context.computeOnClient(mc -> mc.getFps()));

			server.runOnServer(s -> removePlayer(s, bandit[0]));
			context.waitTicks(10);
			check(context.computeOnClient(mc -> TallyClient.radar().locked() == null), "lock is dropped when the player leaves");
			check(context.computeOnClient(mc -> !TallyClient.warnings().threat), "SPIKE clears");

			// ================= sortie 2: mobs, lock, bow, and the edition's own checks =================
			context.runOnClient(mc -> TallyClient.config().targets = TallyConfig.Targets.HOSTILES);
			launch(context, sp);
			server.runCommand("execute as @a at @s rotated 0 0 run summon minecraft:husk ^8 ^-10 ^45 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b}");
			server.runCommand("execute as @a at @s rotated 0 0 run summon minecraft:husk ^-14 ^-2 ^58 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b}");
			server.runCommand("execute as @a at @s rotated 0 0 run summon minecraft:husk ^-3 ^-18 ^70 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b}");
			context.waitTicks(12);
			int contacts = context.computeOnClient(mc -> (int) TallyClient.radar().contacts().stream().filter(c -> c.visible).count());
			check(contacts == 3, "radar tracks the three mobs (saw " + contacts + ")");
			double speed = context.computeOnClient(mc -> TallyClient.flight().speed);
			check(speed > 5, "airspeed reads (" + speed + " m/s)");
			double ralt = context.computeOnClient(mc -> TallyClient.flight().radarAltitude);
			check(ralt > 50 && ralt < 320, "radar altitude reads (" + ralt + ")");
			shot(context, "2-three-contacts");

			context.getInput().pressKey(TallyClient.lockKey());
			context.waitTicks(3);
			check(context.computeOnClient(mc -> TallyClient.radar().locked() != null), "lock key locked a mob");
			float yawBefore = context.computeOnClient(mc -> mc.player.getYRot());
			float pitchBefore = context.computeOnClient(mc -> mc.player.getXRot());
			context.waitTicks(8);
			check(context.computeOnClient(mc -> mc.player.getYRot() == yawBefore && mc.player.getXRot() == pitchBefore), "locked: the view is left alone");

			server.runOnServer(s -> me(s).setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BOW)));
			context.waitTicks(5);
			String lead = context.computeOnClient(mc -> {
				Radar.Contact c = TallyClient.radar().locked();
				AimPoint.Aim aim = AimPoint.solve(mc, TallyClient.config(), TallyClient.flight(), c, 1f);
				Vec3 direct = c.lastPos.subtract(mc.player.getEyePosition()).normalize();
				double off = Angles.between(aim.direction().x, aim.direction().y, aim.direction().z, direct.x, direct.y, direct.z);
				return aim.ballistic() + " " + (aim.direction().y > direct.y) + " " + String.format(java.util.Locale.ROOT, "%.1f deg off the target, %.1f ticks of flight", off, aim.ticks());
			});
			note("bow solution: " + lead);
			check(lead.startsWith("true true"), "bow in hand gives a firing solution aimed above the target");
			shot(context, "3-locked-bow-lead");
			server.runOnServer(s -> me(s).setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY));
			context.waitTicks(3);

			EditionTests.lockedFlight(context);

			context.getInput().pressKey(TallyClient.lockKey());
			context.waitTicks(3);
			check(context.computeOnClient(mc -> TallyClient.radar().locked() == null), "lock key on the same target lets go");

			// ================= sortie 3: terrain masking and a wall ahead =================
			launch(context, sp);
			server.runCommand("execute as @a at @s rotated 0 0 run summon minecraft:husk ^0 ^-8 ^60 {NoAI:1b,NoGravity:1b,PersistenceRequired:1b}");
			context.waitTicks(10);
			check(context.computeOnClient(mc -> TallyClient.radar().contacts().stream().anyMatch(c -> c.visible)), "target in the open is tracked");
			context.getInput().pressKey(TallyClient.lockKey());
			context.waitTicks(3);
			List<BlockPos> wall = new ArrayList<>();
			FakePlayer[] hidden = new FakePlayer[1];
			server.runOnServer(s -> {
				ServerPlayer p = me(s);
				ServerLevel level = (ServerLevel) p.level();
				BlockPos base = BlockPos.containing(p.getX(), p.getY(), p.getZ() + 34);
				for (int x = -14; x <= 14; x++) {
					for (int y = -24; y <= 10; y++) {
						BlockPos pos = base.offset(x, y, 0);
						level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
						wall.add(pos);
					}
				}
				// Another player behind the wall, inside the nearby-warning radius.
				hidden[0] = spawnPlayer(s, "Hidden", new Vec3(6, -6, 50));
			});
			context.waitTicks(6);
			check(context.computeOnClient(mc -> TallyClient.radar().contacts().stream().noneMatch(c -> c.visible)), "a wall in between masks the target");
			check(context.computeOnClient(mc -> TallyClient.radar().locked() != null), "lock is remembered for a moment");
			Gpws.Surface surface = context.computeOnClient(mc -> TallyClient.flight().hitSurface);
			Gpws.Level wallLevel = context.computeOnClient(mc -> TallyClient.warnings().terrain);
			note("wall ahead: surface " + surface + ", alert " + wallLevel + ", distance " + context.computeOnClient(mc -> TallyClient.flight().hitDistance));
			check(surface == Gpws.Surface.WALL && wallLevel != Gpws.Level.NONE, "flying level at a wall raises the terrain alert");
			shot(context, "5-wall-masked-target");
			EditionTests.playerBehindWall(context);
			server.runOnServer(s -> {
				removePlayer(s, hidden[0]);
				ServerLevel level = (ServerLevel) me(s).level();
				for (BlockPos pos : wall) {
					level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
				}
			});

			// ================= sortie 4: banking, then a dive =================
			launch(context, sp);
			for (int i = 0; i < 16; i++) {
				context.runOnClient(mc -> mc.player.turn(5.0 / 0.15, 0));
				context.waitTick();
			}
			double bank = context.computeOnClient(mc -> TallyClient.flight().bank);
			check(bank > 15, "turning right banks right (" + bank + " degrees)");
			// The camera's "up" must lean to the right-hand side of the direction of flight.
			double lean = context.computeOnClient(mc -> {
				var cam = mc.gameRenderer.mainCamera();
				double yaw = cam.yRot() * Angles.DEG;
				double rightX = -Math.cos(yaw);
				double rightZ = -Math.sin(yaw);
				return cam.upVector().x() * rightX + cam.upVector().z() * rightZ;
			});
			check(lean > 0.2, "camera rolled to the right, not the left (" + lean + ")");
			shot(context, "6-right-turn-bank");
			context.waitTicks(30);
			double level = context.computeOnClient(mc -> TallyClient.flight().bank);
			check(Math.abs(level) < 3, "wings level again after the turn (" + level + ")");

			context.runOnClient(mc -> {
				LocalPlayer p = mc.player;
				p.setXRot(70);
				p.xRotO = 70;
			});
			int waited = context.waitFor(mc -> TallyClient.warnings().terrain == Gpws.Level.TERRAIN, 400);
			note("TERRAIN after " + waited + " ticks of diving");
			context.waitFor(mc -> TallyClient.warnings().terrain == Gpws.Level.PULL_UP, 200);
			double alt = context.computeOnClient(mc -> TallyClient.flight().radarAltitude);
			double vs = context.computeOnClient(mc -> TallyClient.flight().verticalSpeed);
			note("PULL UP at radar altitude " + alt + ", vertical speed " + vs + " (" + (alt / -vs) + " s from the ground)");
			// The break X flashes; three frames a tenth of a second apart catch it lit at least once.
			for (int i = 0; i < 3; i++) {
				context.waitTicks(2);
				shot(context, "7-pull-up-" + i);
			}
			context.runOnClient(mc -> {
				LocalPlayer p = mc.player;
				p.setXRot(-25);
				p.xRotO = -25;
			});
			context.waitFor(mc -> TallyClient.warnings().terrain == Gpws.Level.NONE, 200);
			check(true, "warning clears after pulling up");
			check(context.computeOnClient(mc -> mc.player.isAlive()), "survived the pull-up");

			// ================= settings =================
			context.setScreen(() -> new dev.kotatko.tally.ui.SettingsScreen(null));
			context.waitTicks(5);
			shot(context, "8-settings");
			context.setScreen(() -> null);
		}
	}
}
