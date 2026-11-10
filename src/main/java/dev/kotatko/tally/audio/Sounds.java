package dev.kotatko.tally.audio;

import dev.kotatko.tally.TallyClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * The cockpit sounds. They are played straight from this mod's own sounds.json and are never put in
 * the sound-event registry: a client-only mod that registered them would not match a vanilla server's registry.
 */
public enum Sounds {
	LOCK("lock"),
	UNLOCK("unlock"),
	NO_TARGET("no_target"),
	CONTACT("contact"),
	SPIKE("spike"),
	STALL("stall"),
	CAUTION("caution"),
	WHOOP("whoop"),
	HUD_ON("hud_on"),
	HUD_OFF("hud_off"),
	PULL_UP("pull_up"),
	ALTITUDE("altitude"),
	TERRAIN("terrain"),
	CAUTION_VOICE("caution_voice"),
	WARNING_VOICE("warning_voice"),
	BINGO("bingo");

	private final SoundEvent event;

	Sounds(String name) {
		this.event = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(TallyClient.MOD_ID, name));
	}

	/** Plays the sound in the UI channel at the configured volume and returns the instance, or null if muted. */
	public SoundInstance play() {
		float volume = TallyClient.config().soundVolume / 100f;
		if (volume <= 0) {
			return null;
		}
		SoundInstance instance = SimpleSoundInstance.forUI(event, 1.0f, volume);
		Minecraft.getInstance().getSoundManager().play(instance);
		return instance;
	}
}
