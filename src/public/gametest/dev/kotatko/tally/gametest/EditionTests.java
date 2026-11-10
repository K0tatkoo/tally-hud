package dev.kotatko.tally.gametest;

import static dev.kotatko.tally.gametest.TallyGameTest.check;
import static dev.kotatko.tally.gametest.TallyGameTest.note;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kotatko.tally.TallyClient;
import dev.kotatko.tally.TallyConfig;
import dev.kotatko.tally.edition.Edition;
import dev.kotatko.tally.math.Angles;
import dev.kotatko.tally.radar.Radar;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** The public edition's own checks: nothing moves the view, and nothing behind blocks is given away. */
final class EditionTests {
	private EditionTests() {
	}

	static void configure(TallyConfig c) {
	}

	/** A mob is locked and the bow is put away. */
	static void lockedFlight(ClientGameTestContext context) {
		check(context.computeOnClient(mc -> Arrays.stream(mc.options.keyMappings).noneMatch(k -> k.getName().contains("assist"))),
			"public: there is no aim-assist key");
		long ours = context.computeOnClient(mc -> Arrays.stream(mc.options.keyMappings).filter(k -> k.getName().startsWith("key.tally.")).count());
		check(ours == 3, "public: three keys of our own, lock, settings and HUD mode (" + ours + ")");
		check(Edition.statusLine() == null, "public: no assist line in the target data");

		context.runOnClient(mc -> {
			if (!mc.mouseHandler.isMouseGrabbed()) {
				mc.mouseHandler.grabMouse();
			}
		});
		check(context.computeOnClient(mc -> mc.mouseHandler.isMouseGrabbed()), "mouse is grabbed");
		check(context.computeOnClient(mc -> TallyClient.radar().locked() != null), "still locked on a mob");
		double off = context.computeOnClient(mc -> TallyClient.radar().locked().angleOff);
		note("locked target is " + off + " degrees off the nose");

		// Mouse button 4 is the personal edition's assist key. Here it does nothing at all, and with
		// the target off the nose and the button held for a second the view never moves.
		float yaw = context.computeOnClient(mc -> mc.player.getYRot());
		float pitch = context.computeOnClient(mc -> mc.player.getXRot());
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_4);
		context.waitTicks(20);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_4);
		check(context.computeOnClient(mc -> mc.player.getYRot() == yaw && mc.player.getXRot() == pitch),
			"public: locked, mouse button 4 held, the view is never moved");

		// Point at the target by hand (the test's hand), so the next lock press lands on it again.
		context.runOnClient(mc -> {
			Radar.Contact c = TallyClient.radar().locked();
			var d = c.lastPos.subtract(mc.player.getEyePosition());
			mc.player.setYRot((float) Angles.yawOf(d.x, d.z));
			mc.player.setXRot((float) Angles.pitchOf(d.x, d.y, d.z));
		});
		context.waitTicks(2);
	}

	/** A player is hidden behind a wall, inside the nearby-warning radius. */
	static void playerBehindWall(ClientGameTestContext context) {
		check(context.computeOnClient(mc -> mc.level.players().size()) == 2, "the hidden player is in the client's world");
		check(context.computeOnClient(mc -> TallyClient.radar().bearings().isEmpty()), "public: a player behind the wall gives no bearing and no chirp");
	}
}
