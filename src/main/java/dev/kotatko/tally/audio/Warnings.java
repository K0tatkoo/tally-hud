package dev.kotatko.tally.audio;

import dev.kotatko.tally.TallyConfig;
import dev.kotatko.tally.flight.FlightState;
import dev.kotatko.tally.math.Angles;
import dev.kotatko.tally.math.Gpws;
import dev.kotatko.tally.radar.Radar;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;

/**
 * The warning computer. Decides once per tick which cautions are up, and speaks them.
 *
 * <p>Voice callouts share one channel with priorities, so "pull up" cuts off anything lesser and
 * two voices never talk over each other. Tones (stall horn, contact chirp, spike) are separate and
 * only rate-limited.
 */
public final class Warnings {
	private static final double STALL_SPEED = 7.0;
	private static final int ELYTRA_LOW_SECONDS = 60;
	private static final int ELYTRA_CRITICAL_SECONDS = 15;
	private static final int BINGO_ROCKETS = 3;

	/** Current terrain alert, after hold-over. */
	public Gpws.Level terrain = Gpws.Level.NONE;
	public boolean stall;
	public boolean altitude;
	public boolean threat;
	public int threatClock;
	public boolean elytraLow;
	public boolean elytraCritical;
	public boolean bingo;

	private long tick;
	private long terrainHoldUntil;
	private Gpws.Level heldTerrain = Gpws.Level.NONE;
	private long altitudeShownUntil;
	private boolean altitudeArmed = true;
	private boolean elytraLowArmed = true;
	private boolean bingoArmed;
	private long bingoShownUntil;

	private SoundInstance voice;
	private int voicePriority;
	private long voiceUntil;
	private final Map<Sounds, Long> nextAllowed = new EnumMap<>(Sounds.class);

	public void reset() {
		terrain = heldTerrain = Gpws.Level.NONE;
		stall = altitude = threat = elytraLow = elytraCritical = bingo = false;
		altitudeArmed = true;
	}

	/** Speaks on the voice channel unless something at least as important is already talking. */
	private boolean say(Sounds sound, int priority, int lengthTicks) {
		if (voice != null && tick < voiceUntil) {
			if (priority <= voicePriority) {
				return false;
			}
			Minecraft.getInstance().getSoundManager().stop(voice);
		}
		voice = sound.play();
		voicePriority = priority;
		voiceUntil = tick + lengthTicks;
		return true;
	}

	/** Plays a tone at most once per {@code everyTicks}. */
	private void tone(Sounds sound, int everyTicks) {
		Long next = nextAllowed.get(sound);
		if (next == null || tick >= next) {
			sound.play();
			nextAllowed.put(sound, tick + everyTicks);
		}
	}

	/** The master-caution chime, used in place of a callout when the voice is switched off. */
	private boolean chime() {
		tone(Sounds.CAUTION, 20);
		return true;
	}

	private boolean due(Sounds sound, int everyTicks) {
		Long next = nextAllowed.get(sound);
		if (next == null || tick >= next) {
			nextAllowed.put(sound, tick + everyTicks);
			return true;
		}
		return false;
	}

	public void tick(TallyConfig cfg, FlightState f, Radar radar, boolean active) {
		tick++;
		if (!active) {
			reset();
			return;
		}

		// --- terrain: caution, then warning -------------------------------------
		Gpws.Level now = cfg.warnTerrain && f.gliding
			? Gpws.assess(f.hitDistance, f.hitSurface, f.speed, f.horizontalSpeed, f.verticalSpeed, cfg.pullUpTime / 10.0)
			: Gpws.Level.NONE;
		if (now.ordinal() >= heldTerrain.ordinal() && now != Gpws.Level.NONE) {
			heldTerrain = now;
			terrainHoldUntil = tick + 8;
		} else if (tick >= terrainHoldUntil) {
			heldTerrain = now;
			terrainHoldUntil = tick + 8;
		}
		terrain = heldTerrain;
		if (terrain == Gpws.Level.PULL_UP) {
			if (cfg.voice) {
				if (due(Sounds.PULL_UP, 21)) {
					say(Sounds.PULL_UP, 100, 19);
				}
			} else {
				tone(Sounds.WHOOP, 15);
			}
		} else if (terrain == Gpws.Level.TERRAIN) {
			if (cfg.voice) {
				if (due(Sounds.TERRAIN, 50)) {
					say(Sounds.TERRAIN, 60, 23);
				}
			} else {
				tone(Sounds.CAUTION, 40);
			}
		}

		// --- low altitude: once per descent --------------------------------------
		double low = cfg.lowAltitude;
		if (!f.gliding || Double.isNaN(f.radarAltitude) || f.radarAltitude > low * 1.5 + 4) {
			altitudeArmed = true;
		}
		if (cfg.warnAltitude && f.gliding && altitudeArmed && !Double.isNaN(f.radarAltitude) && f.radarAltitude < low
			&& f.verticalSpeed < -1.5 && f.speed > 8 && !f.overWater) {
			boolean spoken = cfg.voice ? say(Sounds.ALTITUDE, 50, 31) : chime();
			if (spoken || terrain != Gpws.Level.NONE) {
				altitudeArmed = false;
				altitudeShownUntil = tick + 40;
			}
		}
		altitude = tick < altitudeShownUntil;

		// --- stall ------------------------------------------------------------------
		stall = cfg.warnStall && f.gliding && f.glideTicks > 30 && f.speed < STALL_SPEED
			&& (Double.isNaN(f.radarAltitude) || f.radarAltitude > 4);
		if (stall) {
			tone(Sounds.STALL, 10);
		}

		// --- other players ---------------------------------------------------------
		if (cfg.warnProximity && radar.newContact) {
			tone(Sounds.CONTACT, 20);
		}
		threat = cfg.warnThreat && radar.threat;
		if (threat) {
			threatClock = Angles.clock(radar.threatRelativeYaw);
			tone(Sounds.SPIKE, 30);
		}

		// --- elytra wear ------------------------------------------------------------
		int left = f.elytraSeconds;
		boolean wearing = cfg.warnElytra && f.gliding && left >= 0 && left != Integer.MAX_VALUE;
		elytraCritical = wearing && left <= ELYTRA_CRITICAL_SECONDS;
		elytraLow = wearing && left <= ELYTRA_LOW_SECONDS;
		if (elytraCritical) {
			if (due(Sounds.WARNING_VOICE, 100)) {
				if (cfg.voice) {
					say(Sounds.WARNING_VOICE, 70, 23);
				} else {
					Sounds.CAUTION.play();
				}
			}
		} else if (elytraLow && elytraLowArmed) {
			boolean spoken = cfg.voice ? say(Sounds.CAUTION_VOICE, 40, 24) : chime();
			if (spoken) {
				elytraLowArmed = false;
			}
		}
		if (!wearing || left > ELYTRA_LOW_SECONDS + 30) {
			elytraLowArmed = true;
		}

		// --- rockets: "bingo" as the last few go -------------------------------------
		if (f.rockets > BINGO_ROCKETS) {
			bingoArmed = true;
		} else if (cfg.warnBingo && f.gliding && bingoArmed) {
			boolean spoken = cfg.voice ? say(Sounds.BINGO, 30, 22) : chime();
			if (spoken) {
				bingoArmed = false;
				bingoShownUntil = tick + 60;
			}
		}
		bingo = tick < bingoShownUntil;
	}
}
