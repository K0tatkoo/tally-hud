package dev.kotatko.tally.mixin;

import dev.kotatko.tally.TallyClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two small hooks on the camera: a once-per-frame callback before it is placed (smooth aim assist
 * needs frame rate, not tick rate), and a roll about the view axis so the world banks in a turn.
 * The roll goes into the camera's own rotation, so culling and every renderer see the same view.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	@Final
	private static Vector3fc FORWARDS;
	@Shadow
	@Final
	private static Vector3fc UP;
	@Shadow
	@Final
	private static Vector3fc LEFT;
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	@Final
	private Vector3f forwards;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;
	@Shadow
	private boolean detached;
	@Shadow
	private int matrixPropertiesDirty;

	@Inject(method = "update", at = @At("HEAD"))
	private void tally$beforeUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
		TallyClient.onFrame(deltaTracker);
	}

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void tally$bank(float partialTicks, CallbackInfo ci) {
		float roll = TallyClient.cameraRoll(this.detached);
		if (roll != 0f) {
			this.rotation.rotateZ(roll);
			FORWARDS.rotate(this.rotation, this.forwards);
			UP.rotate(this.rotation, this.up);
			LEFT.rotate(this.rotation, this.left);
			this.matrixPropertiesDirty |= 3;
		}
	}
}
