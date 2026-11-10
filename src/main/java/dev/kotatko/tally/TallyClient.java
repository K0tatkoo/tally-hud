package dev.kotatko.tally;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kotatko.tally.audio.Sounds;
import dev.kotatko.tally.audio.Warnings;
import dev.kotatko.tally.edition.Edition;
import dev.kotatko.tally.flight.FlightState;
import dev.kotatko.tally.hud.HudRenderer;
import dev.kotatko.tally.math.Angles;
import dev.kotatko.tally.radar.Radar;
import dev.kotatko.tally.ui.SettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TallyClient implements ClientModInitializer {
	public static final String MOD_ID = Edition.MOD_ID;
	public static final Logger LOG = LoggerFactory.getLogger("Tally");

	private static TallyConfig config = new TallyConfig();
	private static final FlightState FLIGHT = new FlightState();
	private static final Radar RADAR = new Radar();
	private static final Warnings WARNINGS = new Warnings();
	private static final HudRenderer HUD = new HudRenderer();

	private static KeyMapping lockKey;
	private static KeyMapping settingsKey;
	private static KeyMapping hudKey;

	private static boolean active;
	private static float fade;
	private static long lastFrameNanos;

	public static TallyConfig config() {
		return config;
	}

	public static FlightState flight() {
		return FLIGHT;
	}

	public static Radar radar() {
		return RADAR;
	}

	public static Warnings warnings() {
		return WARNINGS;
	}

	/** Whether the HUD and its systems are running this tick. */
	public static boolean active() {
		return active;
	}

	/** 0..1, eased, so the HUD fades in and out instead of popping. */
	public static float hudFade() {
		return fade;
	}

	public static KeyMapping lockKey() {
		return lockKey;
	}

	@Override
	public void onInitializeClient() {
		config = TallyConfig.load();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		lockKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.tally.lock", InputConstants.KEY_LALT, category));
		settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.tally.settings", InputConstants.KEY_J, category));
		hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.tally.hud", InputConstants.UNKNOWN.getValue(), category));
		Edition.registerKeys(category);

		// Under the crosshair, hotbar and chat, over the vignette.
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath(MOD_ID, "hud"), HUD::render);
		ClientTickEvents.END_CLIENT_TICK.register(TallyClient::tick);
	}

	private static boolean shouldBeActive(LocalPlayer p) {
		if (config.hudMode == TallyConfig.HudMode.OFF || p.isSpectator() || !p.isAlive()) {
			return false;
		}
		return config.hudMode == TallyConfig.HudMode.ALWAYS || p.isFallFlying();
	}

	private static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			active = false;
			FLIGHT.reset();
			RADAR.clear();
			WARNINGS.reset();
			return;
		}

		while (settingsKey.consumeClick()) {
			mc.gui.setScreen(new SettingsScreen(null));
		}
		while (hudKey.consumeClick()) {
			TallyConfig.HudMode[] modes = TallyConfig.HudMode.values();
			config.hudMode = modes[(config.hudMode.ordinal() + 1) % modes.length];
			config.save();
			mc.gui.hud.setOverlayMessage(Component.translatable("tally.message.hudMode", Component.translatable("tally.option.hudMode." + config.hudMode.name().toLowerCase(java.util.Locale.ROOT))), false);
		}
		if (mc.isPaused()) {
			return;
		}

		boolean was = active;
		active = shouldBeActive(p);
		if (active != was && config.systemSounds) {
			(active ? Sounds.HUD_ON : Sounds.HUD_OFF).play();
		}

		FLIGHT.tick(mc, config, active);
		RADAR.tick(mc, config, active);
		while (lockKey.consumeClick()) {
			if (active) {
				RADAR.toggleLock(config);
			}
		}
		Edition.tick(mc, config, active);
		WARNINGS.tick(config, FLIGHT, RADAR, active);
	}

	/** Called by the camera mixin once per rendered frame, before the camera is placed. */
	public static void onFrame(DeltaTracker delta) {
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
		lastFrameNanos = now;

		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			fade = 0;
			return;
		}
		fade += ((active ? 1f : 0f) - fade) * (float) (1 - Math.exp(-dt / 0.12));
		if (mc.isPaused()) {
			return;
		}
		FLIGHT.frame(dt, config);
		if (active) {
			Edition.frame(mc, config, FLIGHT, RADAR, dt, delta.getGameTimeDeltaPartialTick(true));
		}
	}

	/**
	 * Roll to apply to the first-person camera, in radians about its own forward axis.
	 * Zero when banking is off, when wings-level, or in third person.
	 */
	public static float cameraRoll(boolean detached) {
		if (detached || FLIGHT.bank == 0) {
			return 0;
		}
		// A positive rotation about the camera's own z axis tips its top to the left, so a right bank is negative.
		return (float) (-FLIGHT.bank * Angles.DEG);
	}
}
