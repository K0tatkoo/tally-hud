package dev.kotatko.tally.ui;

import com.mojang.serialization.Codec;
import dev.kotatko.tally.TallyClient;
import dev.kotatko.tally.TallyConfig;
import dev.kotatko.tally.edition.Edition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** The settings screen, built from the game's own option widgets so it looks and scrolls like vanilla. */
public final class SettingsScreen extends OptionsSubScreen {
	public SettingsScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("tally.settings.title"));
	}

	private static <T> OptionInstance.TooltipSupplier<T> tip(String key) {
		return OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip"));
	}

	public static OptionInstance<Boolean> toggle(String key, boolean tooltip, boolean value, Consumer<Boolean> set) {
		return OptionInstance.createBoolean(key, tooltip ? tip(key) : OptionInstance.noTooltip(), value, set::accept);
	}

	public static OptionInstance<Integer> slider(String key, boolean tooltip, int min, int max, int step, String unit, int value, IntConsumer set) {
		return new OptionInstance<>(
			key,
			tooltip ? tip(key) : OptionInstance.noTooltip(),
			(caption, v) -> Options.genericValueLabel(caption, Component.translatable(unit, v)),
			new OptionInstance.IntRange(min / step, max / step).xmap(v -> v * step, v -> v / step, true),
			Codec.intRange(min, max),
			value,
			set::accept
		);
	}

	public static <E extends Enum<E>> OptionInstance<E> cycle(String key, boolean tooltip, E[] values, E value, Consumer<E> set) {
		return new OptionInstance<>(
			key,
			tooltip ? tip(key) : OptionInstance.noTooltip(),
			(caption, v) -> Component.translatable(key + "." + v.name().toLowerCase(Locale.ROOT)),
			new OptionInstance.Enum<>(Arrays.asList(values), Codec.INT.xmap(i -> values[i], Enum::ordinal)),
			value,
			set::accept
		);
	}

	@Override
	protected void addOptions() {
		TallyConfig c = TallyClient.config();
		String o = "tally.option.";

		this.list.addHeader(Component.translatable("tally.settings.display"));
		this.list.addSmall(
			cycle(o + "hudMode", true, TallyConfig.HudMode.values(), c.hudMode, v -> c.hudMode = v),
			cycle(o + "hudColor", false, TallyConfig.HudColor.values(), c.hudColor, v -> c.hudColor = v),
			slider(o + "hudScale", false, 60, 160, 10, "tally.unit.percent", c.hudScale, v -> c.hudScale = v),
			slider(o + "hudOpacity", false, 30, 100, 5, "tally.unit.percent", c.hudOpacity, v -> c.hudOpacity = v),
			slider(o + "cameraBank", true, 0, 100, 10, "tally.unit.percent", c.cameraBank, v -> c.cameraBank = v),
			cycle(o + "speedUnit", false, TallyConfig.SpeedUnit.values(), c.speedUnit, v -> c.speedUnit = v),
			toggle(o + "hudShadow", true, c.hudShadow, v -> c.hudShadow = v),
			toggle(o + "pitchLadder", false, c.pitchLadder, v -> c.pitchLadder = v),
			toggle(o + "headingTape", false, c.headingTape, v -> c.headingTape = v),
			toggle(o + "tapes", false, c.tapes, v -> c.tapes = v),
			toggle(o + "bankScale", false, c.bankScale, v -> c.bankScale = v),
			toggle(o + "flightPathMarker", true, c.flightPathMarker, v -> c.flightPathMarker = v),
			toggle(o + "radarScope", false, c.radarScope, v -> c.radarScope = v),
			toggle(o + "dataBlock", false, c.dataBlock, v -> c.dataBlock = v)
		);

		this.list.addHeader(Component.translatable("tally.settings.radar"));
		List<OptionInstance<?>> radar = new ArrayList<>(List.of(
			slider(o + "radarRange", true, 32, 512, 16, "tally.unit.blocks", c.radarRange, v -> c.radarRange = v),
			slider(o + "radarCone", true, 20, 90, 5, "tally.unit.degrees", c.radarCone, v -> c.radarCone = v),
			cycle(o + "targets", true, TallyConfig.Targets.values(), c.targets, v -> c.targets = v)
		));
		radar.addAll(Edition.radarOptions());
		radar.add(toggle(o + "leadProjectiles", true, c.leadProjectiles, v -> c.leadProjectiles = v));
		radar.add(toggle(o + "lockTones", false, c.lockTones, v -> c.lockTones = v));
		this.list.addSmall(radar.toArray(new OptionInstance<?>[0]));

		this.list.addHeader(Component.translatable("tally.settings.warnings"));
		this.list.addSmall(
			slider(o + "soundVolume", false, 0, 100, 5, "tally.unit.percent", c.soundVolume, v -> c.soundVolume = v),
			toggle(o + "voice", true, c.voice, v -> c.voice = v),
			toggle(o + "warnTerrain", true, c.warnTerrain, v -> c.warnTerrain = v),
			slider(o + "pullUpTime", true, 8, 30, 1, "tally.unit.tenths", c.pullUpTime, v -> c.pullUpTime = v),
			toggle(o + "warnAltitude", true, c.warnAltitude, v -> c.warnAltitude = v),
			slider(o + "lowAltitude", false, 4, 40, 2, "tally.unit.blocks", c.lowAltitude, v -> c.lowAltitude = v),
			toggle(o + "warnStall", true, c.warnStall, v -> c.warnStall = v),
			toggle(o + "warnElytra", true, c.warnElytra, v -> c.warnElytra = v),
			toggle(o + "warnProximity", true, c.warnProximity, v -> c.warnProximity = v),
			slider(o + "proximityRadius", false, 16, 160, 8, "tally.unit.blocks", c.proximityRadius, v -> c.proximityRadius = v),
			toggle(o + "warnThreat", true, c.warnThreat, v -> c.warnThreat = v),
			toggle(o + "warnBingo", true, c.warnBingo, v -> c.warnBingo = v),
			toggle(o + "systemSounds", false, c.systemSounds, v -> c.systemSounds = v)
		);
	}

	@Override
	public void removed() {
		super.removed();
		TallyClient.config().save();
	}
}
