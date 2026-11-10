package dev.kotatko.tally.edition;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.kotatko.tally.TallyConfig;
import dev.kotatko.tally.flight.FlightState;
import dev.kotatko.tally.radar.Radar;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;

/**
 * The public edition ("Tally HUD"). The HUD, radar, lock and warnings are shared code; this class
 * fills the places where another build of the same source adds things of its own, and here they
 * add nothing. In this edition nothing ever moves the player's view, and the radar only marks
 * what is in line of sight.
 */
public final class Edition {
	private Edition() {
	}

	public static final String MOD_ID = "tally_hud";
	/**
	 * Whether the warning receiver hears nearby players behind blocks too: no. A compile-time constant,
	 * so the shared code's test of it is decided by the compiler and only the line-of-sight path is built in.
	 */
	public static final boolean NEARBY_THROUGH_BLOCKS = false;

	public static void readSettings(JsonObject json, Gson gson) {
	}

	public static void writeSettings(JsonObject json, Gson gson) {
	}

	public static void registerKeys(KeyMapping.Category category) {
	}

	public static void tick(Minecraft mc, TallyConfig cfg, boolean active) {
	}

	public static void frame(Minecraft mc, TallyConfig cfg, FlightState flight, Radar radar, double dt, float partial) {
	}

	/** Always: nothing behind blocks is ever marked. */
	public static boolean lineOfSightOnly() {
		return true;
	}

	/** The last line of the locked target's data block, or null for none. */
	public static String statusLine() {
		return null;
	}

	public static boolean statusLit() {
		return false;
	}

	public static List<OptionInstance<?>> radarOptions() {
		return List.of();
	}
}
