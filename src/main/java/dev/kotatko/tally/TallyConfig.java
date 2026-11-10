package dev.kotatko.tally;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kotatko.tally.edition.Edition;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Everything the player can change, saved as config/tally.json (tally_hud.json in the public edition)
 * together with the edition's own settings.
 */
public final class TallyConfig {
	public enum HudMode {
		/** Only while gliding. */
		AUTO,
		ALWAYS,
		OFF
	}

	public enum SpeedUnit {
		MS("M/S", 1.0),
		KMH("KM/H", 3.6),
		KT("KT", 1.943844);

		public final String label;
		public final double perMs;

		SpeedUnit(String label, double perMs) {
			this.label = label;
			this.perMs = perMs;
		}
	}

	public enum HudColor {
		GREEN(0x35FF6B),
		AMBER(0xFFB627),
		CYAN(0x4FE3FF),
		WHITE(0xF2F2F2);

		public final int rgb;

		HudColor(int rgb) {
			this.rgb = rgb;
		}
	}

	public enum Targets {
		PLAYERS,
		/** Players and hostile mobs. */
		HOSTILES,
		/** Every living thing. */
		ALL
	}



	// --- display ---
	public HudMode hudMode = HudMode.AUTO;
	public int hudScale = 100;
	public HudColor hudColor = HudColor.GREEN;
	public int hudOpacity = 90;
	public boolean hudShadow = true;
	public SpeedUnit speedUnit = SpeedUnit.MS;
	/** Camera roll into turns, percent of the coordinated-turn bank. 0 = off. */
	public int cameraBank = 70;
	public boolean pitchLadder = true;
	public boolean headingTape = true;
	public boolean tapes = true;
	public boolean bankScale = true;
	public boolean flightPathMarker = true;
	public boolean radarScope = true;
	public boolean dataBlock = true;

	// --- radar and lock ---
	public int radarRange = 192;
	/** Half-angle of the radar cone in degrees. */
	public int radarCone = 60;
	public Targets targets = Targets.PLAYERS;
	public boolean leadProjectiles = true;

	// --- warnings ---
	public int soundVolume = 100;
	public boolean voice = true;
	public boolean warnTerrain = true;
	/** Pull-up warning lead time in tenths of a second. */
	public int pullUpTime = 15;
	public boolean warnAltitude = true;
	public int lowAltitude = 10;
	public boolean warnStall = true;
	public boolean warnProximity = true;
	public int proximityRadius = 64;
	public boolean warnThreat = true;
	public boolean warnElytra = true;
	public boolean warnBingo = true;
	public boolean lockTones = true;
	public boolean systemSounds = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(Edition.MOD_ID + ".json");
	}

	public static TallyConfig load() {
		Path p = path();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				JsonElement json = JsonParser.parseReader(r);
				if (json.isJsonObject()) {
					TallyConfig c = GSON.fromJson(json, TallyConfig.class);
					c.sanitise();
					// The edition's own settings live in the same file, as more top-level fields.
					Edition.readSettings(json.getAsJsonObject(), GSON);
					return c;
				}
			} catch (IOException | RuntimeException e) {
				TallyClient.LOG.warn("Could not read {}, using defaults", p, e);
			}
		}
		return new TallyConfig();
	}

	public void save() {
		Path p = path();
		JsonObject json = GSON.toJsonTree(this).getAsJsonObject();
		Edition.writeSettings(json, GSON);
		try {
			Files.createDirectories(p.getParent());
			try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				GSON.toJson(json, w);
			}
		} catch (IOException e) {
			TallyClient.LOG.warn("Could not write {}", p, e);
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** A hand-edited file can hold anything; bring it back into range. */
	private void sanitise() {
		TallyConfig d = new TallyConfig();
		if (hudMode == null) {
			hudMode = d.hudMode;
		}
		if (hudColor == null) {
			hudColor = d.hudColor;
		}
		if (speedUnit == null) {
			speedUnit = d.speedUnit;
		}
		if (targets == null) {
			targets = d.targets;
		}
		hudScale = clamp(hudScale, 60, 160);
		hudOpacity = clamp(hudOpacity, 30, 100);
		cameraBank = clamp(cameraBank, 0, 100);
		radarRange = clamp(radarRange, 32, 512);
		radarCone = clamp(radarCone, 20, 90);
		soundVolume = clamp(soundVolume, 0, 100);
		pullUpTime = clamp(pullUpTime, 8, 30);
		lowAltitude = clamp(lowAltitude, 4, 40);
		proximityRadius = clamp(proximityRadius, 16, 160);
	}
}
