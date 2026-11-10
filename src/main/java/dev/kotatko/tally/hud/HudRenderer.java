package dev.kotatko.tally.hud;

import com.mojang.blaze3d.platform.Window;
import dev.kotatko.tally.TallyClient;
import dev.kotatko.tally.TallyConfig;
import dev.kotatko.tally.audio.Warnings;
import dev.kotatko.tally.edition.Edition;
import dev.kotatko.tally.flight.FlightState;
import dev.kotatko.tally.math.Angles;
import dev.kotatko.tally.math.Gpws;
import dev.kotatko.tally.radar.AimPoint;
import dev.kotatko.tally.radar.Radar;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws the head-up display. Everything that sits "in the world" (pitch ladder, horizon, flight
 * path marker, target boxes) is placed by projecting real directions through the game's own camera
 * matrix, so it stays glued to the scenery at any field of view and any bank angle.
 */
public final class HudRenderer {
	private static final int RED = 0xFF4A3A;
	private static final int AMBER = 0xFFB000;

	private final Matrix4f viewProj = new Matrix4f();
	private final Matrix4f view = new Matrix4f();
	private final Vector4f clip = new Vector4f();
	private final Vector3f dirTmp = new Vector3f();
	private final float[] pt = new float[2];
	private final float[] pt2 = new float[2];

	private Canvas c;
	private float w;
	private float h;
	private float cx;
	private float cy;
	private Vec3 camPos = Vec3.ZERO;
	private int main;
	private int dim;
	private int red;
	private int amber;
	private int boxBg;

	public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		float fade = TallyClient.hudFade();
		LocalPlayer p = mc.player;
		if (fade < 0.02f || p == null || mc.level == null) {
			return;
		}
		TallyConfig cfg = TallyClient.config();
		FlightState f = TallyClient.flight();
		Radar radar = TallyClient.radar();
		Warnings warn = TallyClient.warnings();
		float partial = delta.getGameTimeDeltaPartialTick(true);

		Window win = mc.getWindow();
		int unitPx = Math.max(1, Math.round(win.getHeight() / 540f * cfg.hudScale / 100f));
		float k = unitPx / (float) win.getGuiScale();
		c = new Canvas(g, mc.font, 1f / k, cfg.hudShadow);
		w = c.w;
		h = c.h;
		cx = w / 2f;
		cy = h / 2f;

		float alpha = cfg.hudOpacity / 100f * fade;
		main = Canvas.argb(cfg.hudColor.rgb, alpha);
		dim = Canvas.argb(cfg.hudColor.rgb, alpha * 0.5f);
		red = Canvas.argb(RED, fade);
		amber = Canvas.argb(AMBER, fade);
		boxBg = Canvas.argb(0x000000, 0.35f * fade);

		Camera cam = mc.gameRenderer.mainCamera();
		cam.getViewRotationProjectionMatrix(viewProj);
		cam.getViewRotationMatrix(view);
		camPos = cam.position();
		float yaw = cam.yRot();
		float pitch = cam.xRot();

		float tapeX = Mth.clamp(w * 0.25f, 140f, 250f);
		float ladderRadius = Math.min(tapeX - 34f, h * 0.33f);

		g.pose().pushMatrix();
		g.pose().scale(k, k);

		float phi = horizonAngle(yaw, pitch);
		if (cfg.pitchLadder) {
			pitchLadder(yaw, pitch, phi, cam.getFov(), ladderRadius);
		}
		if (cfg.flightPathMarker) {
			flightPathMarker(f, partial);
		}
		if (cfg.headingTape) {
			headingTape(yaw, ladderRadius, radar, p, partial);
		}
		if (cfg.tapes) {
			speedTape(cfg, f, tapeX);
			altitudeTape(f, p, partial, tapeX);
		}
		if (cfg.dataBlock) {
			dataBlocks(cfg, f, radar, p, partial, tapeX);
		}
		if (cfg.bankScale) {
			bankScale(phi, ladderRadius + 6f);
		}
		targets(mc, cfg, f, radar, partial);
		if (cfg.radarScope) {
			scope(cfg, radar, p, partial, yaw);
		}
		warnings(warn, ladderRadius);

		g.pose().popMatrix();
	}

	// ------------------------------------------------------------------ projection

	/** Projects a direction from the camera onto the canvas. False when it is behind the camera. */
	private boolean projectDir(double dx, double dy, double dz, float[] out) {
		clip.set((float) dx, (float) dy, (float) dz, 1f);
		viewProj.transform(clip);
		if (clip.w <= 1e-4f) {
			return false;
		}
		out[0] = (clip.x / clip.w * 0.5f + 0.5f) * w;
		out[1] = (0.5f - clip.y / clip.w * 0.5f) * h;
		return true;
	}

	private boolean projectPoint(double x, double y, double z, float[] out) {
		return projectDir(x - camPos.x, y - camPos.y, z - camPos.z, out);
	}

	/** Angle of the horizon on screen in radians; 0 when wings-level. */
	private float horizonAngle(float yaw, float pitch) {
		double[] fwd = Angles.direction(yaw, pitch);
		double rx = -Math.cos(yaw * Angles.DEG);
		double rz = -Math.sin(yaw * Angles.DEG);
		if (!projectDir(fwd[0], fwd[1], fwd[2], pt) || !projectDir(fwd[0] + rx * 0.05, fwd[1], fwd[2] + rz * 0.05, pt2)) {
			return 0;
		}
		return (float) Math.atan2(pt2[1] - pt[1], pt2[0] - pt[0]);
	}

	// ------------------------------------------------------------------ pitch ladder

	private void pitchLadder(float yaw, float pitch, float phi, float fov, float radius) {
		float focal = (h / 2f) / (float) Math.tan(fov * Angles.DEG / 2);
		int step = focal * Math.tan(5 * Angles.DEG) >= 22 ? 5 : 10;
		int base = Math.round(-pitch / step) * step;
		float ux = (float) Math.cos(phi);
		float uy = (float) Math.sin(phi);
		// Screen direction that points at the ground.
		float nx = -uy;
		float ny = ux;
		double sy = Math.sin(yaw * Angles.DEG);
		double cyaw = Math.cos(yaw * Angles.DEG);
		for (int th = base - 5 * step; th <= base + 5 * step; th += step) {
			if (th > 85 || th < -85) {
				continue;
			}
			double ct = Math.cos(th * Angles.DEG);
			if (!projectDir(-sy * ct, Math.sin(th * Angles.DEG), cyaw * ct, pt)) {
				continue;
			}
			float x = pt[0];
			float y = pt[1];
			float dist = (float) Math.hypot(x - cx, y - cy);
			if (th == 0) {
				if (dist > h) {
					continue;
				}
				c.line(x - ux * 150, y - uy * 150, x - ux * 34, y - uy * 34, main);
				c.line(x + ux * 34, y + uy * 34, x + ux * 150, y + uy * 150, main);
				continue;
			}
			if (dist > radius) {
				continue;
			}
			float half = 56;
			float gap = 20;
			float side = th > 0 ? 1 : -1;
			for (int s = -1; s <= 1; s += 2) {
				float ix = x + ux * gap * s;
				float iy = y + uy * gap * s;
				float ox = x + ux * half * s;
				float oy = y + uy * half * s;
				if (th > 0) {
					c.line(ix, iy, ox, oy, main);
				} else {
					c.dashed(ix, iy, ox, oy, 7, 4, main);
				}
				// The end ticks point toward the horizon.
				c.line(ox, oy, ox + nx * 5 * side, oy + ny * 5 * side, main);
				c.textRotated(Integer.toString(Math.abs(th)), x + ux * (half + 10) * s, y + uy * (half + 10) * s, phi, main);
			}
		}
	}

	// ------------------------------------------------------------------ flight path marker

	private void flightPathMarker(FlightState f, float partial) {
		Vec3 v = f.prevVelocity.lerp(f.velocity, partial);
		if (v.lengthSqr() < 0.0025 || !projectDir(v.x, v.y, v.z, pt)) {
			return;
		}
		float x = pt[0];
		float y = pt[1];
		if (x < 12 || y < 12 || x > w - 12 || y > h - 12) {
			return;
		}
		c.circle(x, y, 4, 12, main);
		c.line(x - 4, y, x - 12, y, main);
		c.line(x + 4, y, x + 12, y, main);
		c.line(x, y - 4, x, y - 9, main);
	}

	// ------------------------------------------------------------------ heading

	private void headingTape(float yaw, float ladderRadius, Radar radar, LocalPlayer p, float partial) {
		double heading = Angles.heading(yaw);
		float y0 = Math.max(22f, cy - ladderRadius - 34f);
		float ppd = 4.5f;
		int span = 20;
		int first = (int) Math.ceil((heading - span) / 5.0) * 5;
		for (int d = first; d <= heading + span; d += 5) {
			float x = cx + (float) (d - heading) * ppd;
			boolean major = Math.floorMod(d, 10) == 0;
			c.line(x, y0 + 11, x, y0 + 11 + (major ? 6 : 3), main);
			if (major) {
				int v = Math.floorMod(d, 360);
				String label = switch (v) {
					case 0 -> "N";
					case 90 -> "E";
					case 180 -> "S";
					case 270 -> "W";
					default -> String.format(Locale.ROOT, "%02d", v / 10);
				};
				c.textCentered(label, x, y0, main);
			}
		}
		// Own heading: caret under the scale, digits boxed above it.
		c.line(cx - 4, y0 + 24, cx, y0 + 19, main);
		c.line(cx, y0 + 19, cx + 4, y0 + 24, main);
		String digits = String.format(Locale.ROOT, "%03d", (int) Math.round(heading) % 360);
		c.fill(cx - 13, y0 - 15, cx + 13, y0 - 3, boxBg);
		c.box(cx - 13, y0 - 15, cx + 13, y0 - 3, main);
		c.textCentered(digits, cx, y0 - 13, main);

		Radar.Contact locked = radar.locked();
		if (locked != null) {
			Vec3 eye = p.getEyePosition(partial);
			double rel = Angles.wrap(Angles.yawOf(locked.lastPos.x - eye.x, locked.lastPos.z - eye.z) - yaw);
			float x = cx + (float) Mth.clamp(rel, -span, span) * ppd;
			c.line(x - 3, y0 + 18, x - 3, y0 + 11, main);
			c.line(x - 3, y0 + 11, x + 3, y0 + 11, main);
			c.line(x + 3, y0 + 11, x + 3, y0 + 18, main);
		}
	}

	// ------------------------------------------------------------------ speed and altitude

	/** Rounds up to 1, 2 or 5 times a power of ten. */
	private static int nice(double v) {
		double mag = Math.pow(10, Math.floor(Math.log10(Math.max(v, 1e-6))));
		double n = v / mag;
		double r = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
		return Math.max(1, (int) Math.round(r * mag));
	}

	private void speedTape(TallyConfig cfg, FlightState f, float tapeX) {
		float x = cx - tapeX;
		float half = 62;
		double value = f.shownSpeed * cfg.speedUnit.perMs;
		double ppu = 2.4 / cfg.speedUnit.perMs;
		int minor = nice(5 * cfg.speedUnit.perMs);
		int major = minor * 2;
		c.line(x, cy - half, x, cy + half, main);
		int first = (int) Math.floor((value - half / ppu) / minor) * minor;
		for (int v = Math.max(0, first); v <= value + half / ppu; v += minor) {
			float y = cy - (float) ((v - value) * ppu);
			if (Math.abs(y - cy) > half) {
				continue;
			}
			boolean isMajor = v % major == 0;
			c.line(x - (isMajor ? 6 : 3), y, x, y, main);
			if (isMajor && Math.abs(y - cy) > 13) {
				c.textRight(Integer.toString(v), x - 9, y - 4, main);
			}
		}
		c.fill(x - 46, cy - 8, x - 6, cy + 8, boxBg);
		c.box(x - 46, cy - 8, x - 6, cy + 8, main);
		c.line(x - 6, cy - 4, x - 1, cy, main);
		c.line(x - 1, cy, x - 6, cy + 4, main);
		c.textRight(Integer.toString((int) Math.round(value)), x - 10, cy - 4, main);
		c.textRight(cfg.speedUnit.label, x - 2, cy - half - 12, main);

		c.text(String.format(Locale.ROOT, "G %.1f", f.loadFactor), x - 46, cy + half + 6, main);
		c.text(String.format(Locale.ROOT, "α %.1f", f.aoa), x - 46, cy + half + 17, main);
	}

	private void altitudeTape(FlightState f, LocalPlayer p, float partial, float tapeX) {
		float x = cx + tapeX;
		float half = 62;
		double value = Mth.lerp(partial, p.yo, p.getY());
		double ppu = 1.0;
		int minor = 10;
		int major = 20;
		c.line(x, cy - half, x, cy + half, main);
		int first = (int) Math.floor((value - half / ppu) / minor) * minor;
		for (int v = first; v <= value + half / ppu; v += minor) {
			float y = cy - (float) ((v - value) * ppu);
			if (Math.abs(y - cy) > half) {
				continue;
			}
			boolean isMajor = Math.floorMod(v, major) == 0;
			c.line(x, y, x + (isMajor ? 6 : 3), y, main);
			if (isMajor && Math.abs(y - cy) > 13) {
				c.text(Integer.toString(v), x + 9, y - 4, main);
			}
		}
		c.fill(x + 6, cy - 8, x + 46, cy + 8, boxBg);
		c.box(x + 6, cy - 8, x + 46, cy + 8, main);
		c.line(x + 6, cy - 4, x + 1, cy, main);
		c.line(x + 1, cy, x + 6, cy + 4, main);
		c.text(Integer.toString((int) Math.round(value)), x + 11, cy - 4, main);
		c.text("ALT", x + 2, cy - half - 12, main);

		if (!Double.isNaN(f.radarAltitude) && f.radarAltitude < 500) {
			c.text("R " + (int) Math.round(f.radarAltitude), x + 6, cy + half + 6, main);
		} else {
			c.text("R ---", x + 6, cy + half + 6, dim);
		}
		c.text(String.format(Locale.ROOT, "VS %+.1f", f.shownVerticalSpeed), x + 6, cy + half + 17, main);
	}

	// ------------------------------------------------------------------ data blocks

	private void dataBlocks(TallyConfig cfg, FlightState f, Radar radar, LocalPlayer p, float partial, float tapeX) {
		float lx = cx - tapeX - 46;
		float y = cy + 62 + 32;
		c.text("RKT " + f.rockets, lx, y, f.rockets <= 3 && f.gliding ? amber : main);
		if (f.elytraSeconds >= 0) {
			String t = f.elytraSeconds == Integer.MAX_VALUE
				? "ELY --:--"
				: String.format(Locale.ROOT, "ELY %d:%02d", f.elytraSeconds / 60, f.elytraSeconds % 60);
			c.text(t, lx, y + 11, f.elytraSeconds <= 60 ? amber : main);
		}

		float rx = cx + tapeX + 6;
		Radar.Contact locked = radar.locked();
		if (locked != null && locked.entity != null) {
			String name = locked.name();
			if (name.length() > 14) {
				name = name.substring(0, 14);
			}
			c.text("TGT " + name, rx, y, main);
			c.text("RNG " + (int) Math.round(locked.range), rx, y + 11, main);
			c.text(String.format(Locale.ROOT, "VC %+d", (int) Math.round(locked.closure)), rx, y + 22, main);
			c.text(String.format(Locale.ROOT, "ΔALT %+d", (int) Math.round(locked.lastPos.y - p.getEyePosition(partial).y)), rx, y + 33, main);
			String status = Edition.statusLine();
			if (status != null) {
				c.text(status, rx, y + 44, Edition.statusLit() ? main : dim);
			}
		} else {
			int n = 0;
			for (Radar.Contact ct : radar.contacts()) {
				if (ct.visible) {
					n++;
				}
			}
			c.text("CNT " + n, rx, y, n > 0 ? main : dim);
		}
	}

	// ------------------------------------------------------------------ bank scale

	private void bankScale(float phi, float radius) {
		int[] marks = {-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60};
		for (int a : marks) {
			float s = (float) Math.sin(a * Angles.DEG);
			float co = (float) Math.cos(a * Angles.DEG);
			float len = a % 30 == 0 ? 7 : 4;
			c.line(cx + s * radius, cy + co * radius, cx + s * (radius + len), cy + co * (radius + len), main);
		}
		// The pointer always points at the ground, so it slides along the scale as the aircraft banks.
		float gx = -(float) Math.sin(phi);
		float gy = (float) Math.cos(phi);
		if (gy < 0.2f) {
			return;
		}
		float tipX = cx + gx * (radius - 1);
		float tipY = cy + gy * (radius - 1);
		float baseX = cx + gx * (radius - 8);
		float baseY = cy + gy * (radius - 8);
		float px = gy * 4;
		float py = -gx * 4;
		c.line(tipX, tipY, baseX + px, baseY + py, main);
		c.line(tipX, tipY, baseX - px, baseY - py, main);
		c.line(baseX + px, baseY + py, baseX - px, baseY - py, main);
	}

	// ------------------------------------------------------------------ targets

	private void targets(Minecraft mc, TallyConfig cfg, FlightState f, Radar radar, float partial) {
		Radar.Contact locked = radar.locked();
		for (Radar.Contact ct : radar.contacts()) {
			if (ct.entity == null) {
				continue;
			}
			boolean isLocked = ct == locked;
			float[] b = boxOf(ct, partial);
			boolean onScreen = b != null && b[2] > 0 && b[0] < w && b[3] > 0 && b[1] < h;
			if (!onScreen) {
				if (isLocked) {
					locator(ct);
				}
				continue;
			}
			if (isLocked) {
				// The box closes in on the target for the first few ticks of a lock.
				float grow = Math.max(0f, 1.4f - (radar.lockAge() + partial) / 5f) * 14f;
				float x0 = b[0] - grow;
				float y0 = b[1] - grow;
				float x1 = b[2] + grow;
				float y1 = b[3] + grow;
				int col = ct.visible ? main : dim;
				c.box(x0, y0, x1, y1, col);
				float mx = (x0 + x1) / 2;
				float my = (y0 + y1) / 2;
				c.line(mx, y0 - 5, mx, y0, col);
				c.line(mx, y1, mx, y1 + 5, col);
				c.line(x0 - 5, my, x0, my, col);
				c.line(x1, my, x1 + 5, my, col);
				c.textCentered(ct.visible ? "LOCK" : "MEM", mx, y0 - 15, col);
				c.textCentered(Integer.toString((int) Math.round(ct.range)), mx, y1 + 7, col);
			} else if (ct.visible && ct.inCone) {
				brackets(b[0], b[1], b[2], b[3], main);
				c.textCentered(Integer.toString((int) Math.round(ct.range)), (b[0] + b[2]) / 2, b[3] + 3, main);
			} else {
				brackets(b[0], b[1], b[2], b[3], dim);
			}
		}

		if (locked != null) {
			AimPoint.Aim aim = AimPoint.solve(mc, cfg, f, locked, partial);
			if (aim != null && projectDir(aim.direction().x, aim.direction().y, aim.direction().z, pt)) {
				float x = pt[0];
				float y = pt[1];
				if (x > 0 && y > 0 && x < w && y < h) {
					if (aim.ballistic()) {
						// Put the crosshair on this and the shot arrives where the target will be.
						c.circle(x, y, 6, 14, main);
						c.fill(x - 1, y - 1, x + 1, y + 1, main);
						c.line(x, y - 6, x, y - 10, main);
					} else if (aim.ticks() > 0) {
						c.circle(x, y, 3, 8, main);
					}
				}
			}
		}
	}

	/** Screen rectangle {x0, y0, x1, y1} of a contact's hitbox, or null when any of it is behind the camera. */
	private float[] boxOf(Radar.Contact ct, float partial) {
		Entity e = ct.entity;
		double hw = e.getBbWidth() / 2.0;
		double eh = e.getBbHeight();
		double bx;
		double by;
		double bz;
		if (ct.visible) {
			Vec3 pos = e.getPosition(partial);
			bx = pos.x;
			by = pos.y;
			bz = pos.z;
		} else {
			bx = ct.lastPos.x;
			by = ct.lastPos.y - eh / 2;
			bz = ct.lastPos.z;
		}
		float x0 = Float.MAX_VALUE;
		float y0 = Float.MAX_VALUE;
		float x1 = -Float.MAX_VALUE;
		float y1 = -Float.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			double x = bx + ((i & 1) == 0 ? -hw : hw);
			double y = by + ((i & 2) == 0 ? 0 : eh);
			double z = bz + ((i & 4) == 0 ? -hw : hw);
			if (!projectPoint(x, y, z, pt)) {
				return null;
			}
			x0 = Math.min(x0, pt[0]);
			y0 = Math.min(y0, pt[1]);
			x1 = Math.max(x1, pt[0]);
			y1 = Math.max(y1, pt[1]);
		}
		// Far targets would be a speck; keep the box big enough to see.
		float minHalf = 6;
		float mx = (x0 + x1) / 2;
		float my = (y0 + y1) / 2;
		if (x1 - x0 < minHalf * 2) {
			x0 = mx - minHalf;
			x1 = mx + minHalf;
		}
		if (y1 - y0 < minHalf * 2) {
			y0 = my - minHalf;
			y1 = my + minHalf;
		}
		return new float[] {x0 - 2, y0 - 2, x1 + 2, y1 + 2};
	}

	private void brackets(float x0, float y0, float x1, float y1, int col) {
		float lx = Math.max(3, (x1 - x0) * 0.28f);
		float ly = Math.max(3, (y1 - y0) * 0.28f);
		c.line(x0, y0, x0 + lx, y0, col);
		c.line(x0, y0, x0, y0 + ly, col);
		c.line(x1, y0, x1 - lx, y0, col);
		c.line(x1, y0, x1, y0 + ly, col);
		c.line(x0, y1, x0 + lx, y1, col);
		c.line(x0, y1, x0, y1 - ly, col);
		c.line(x1, y1, x1 - lx, y1, col);
		c.line(x1, y1, x1, y1 - ly, col);
	}

	/** The locked target is off the glass: a line from the centre points the shortest way back to it. */
	private void locator(Radar.Contact ct) {
		dirTmp.set((float) (ct.lastPos.x - camPos.x), (float) (ct.lastPos.y - camPos.y), (float) (ct.lastPos.z - camPos.z));
		view.transformDirection(dirTmp);
		float sx = dirTmp.x;
		float sy = -dirTmp.y;
		float len = (float) Math.sqrt(sx * sx + sy * sy);
		if (len < 1e-5f) {
			return;
		}
		sx /= len;
		sy /= len;
		float x0 = cx + sx * 26;
		float y0 = cy + sy * 26;
		float x1 = cx + sx * 64;
		float y1 = cy + sy * 64;
		c.line(x0, y0, x1, y1, main);
		float px = -sy;
		float py = sx;
		c.line(x1, y1, x1 - sx * 7 + px * 4, y1 - sy * 7 + py * 4, main);
		c.line(x1, y1, x1 - sx * 7 - px * 4, y1 - sy * 7 - py * 4, main);
		c.textCentered(Integer.toString((int) Math.round(ct.angleOff)) + "°", cx + sx * 80, cy + sy * 80 - 4, main);
	}

	// ------------------------------------------------------------------ radar scope

	private void scope(TallyConfig cfg, Radar radar, LocalPlayer p, float partial, float yaw) {
		float r = 44;
		float sx = w - 62;
		float sy = h - 66;
		c.fill(sx - r - 4, sy - r - 4, sx + r + 4, sy + r + 4, Canvas.argb(0x000000, 0.22f * TallyClient.hudFade()));
		c.circle(sx, sy, r, 28, main);
		c.circle(sx, sy, r / 2, 18, dim);
		double cone = cfg.radarCone * Angles.DEG;
		float ex = (float) Math.sin(cone) * r;
		float ey = (float) Math.cos(cone) * r;
		c.line(sx, sy, sx - ex, sy - ey, dim);
		c.line(sx, sy, sx + ex, sy - ey, dim);
		c.line(sx - 3, sy + 3, sx, sy - 3, main);
		c.line(sx, sy - 3, sx + 3, sy + 3, main);
		c.text("RDR", sx - r - 2, sy - r - 14, main);
		c.textRight(Integer.toString(cfg.radarRange), sx + r + 2, sy - r - 14, main);

		Vec3 eye = p.getEyePosition(partial);
		Radar.Contact locked = radar.locked();
		for (Radar.Contact ct : radar.contacts()) {
			double dx = ct.lastPos.x - eye.x;
			double dz = ct.lastPos.z - eye.z;
			double rel = Angles.wrap(Angles.yawOf(dx, dz) - yaw) * Angles.DEG;
			float rr = (float) Math.min(1.0, Math.sqrt(dx * dx + dz * dz) / cfg.radarRange) * r;
			float x = sx + (float) Math.sin(rel) * rr;
			float y = sy - (float) Math.cos(rel) * rr;
			if (ct == locked) {
				c.line(x, y - 5, x + 5, y, main);
				c.line(x + 5, y, x, y + 5, main);
				c.line(x, y + 5, x - 5, y, main);
				c.line(x - 5, y, x, y - 5, main);
				c.fill(x - 1, y - 1, x + 1, y + 1, main);
			} else if (ct.visible) {
				c.fill(x - 2, y - 2, x + 2, y + 2, main);
			} else {
				c.box(x - 2, y - 2, x + 2, y + 2, dim);
			}
		}
		// The warning receiver only knows a direction: ticks on the rim.
		boolean blink = (System.currentTimeMillis() / 150) % 2 == 0;
		for (Radar.Bearing b : radar.bearings()) {
			double rel = b.relativeYaw() * Angles.DEG;
			float s = (float) Math.sin(rel);
			float co = (float) Math.cos(rel);
			int col = b.threat() ? (blink ? red : 0) : main;
			if (col != 0) {
				c.line(sx + s * (r + 1), sy - co * (r + 1), sx + s * (r + (b.threat() ? 8 : 5)), sy - co * (r + (b.threat() ? 8 : 5)), 2, col);
			}
		}
	}

	// ------------------------------------------------------------------ warnings

	private void warnings(Warnings warn, float ladderRadius) {
		boolean blink = (System.currentTimeMillis() / 125) % 2 == 0;
		int plate = Canvas.argb(0x000000, 0.55f * TallyClient.hudFade());
		List<String> lines = new ArrayList<>(4);
		List<Integer> colors = new ArrayList<>(4);
		float y = cy + 46;
		if (warn.terrain == Gpws.Level.PULL_UP) {
			if (blink) {
				float d = Math.min(ladderRadius, 90);
				c.line(cx - d, cy - d, cx + d, cy + d, 2, red);
				c.line(cx - d, cy + d, cx + d, cy - d, 2, red);
			}
			c.plate("PULL UP", cx, y + 6, 2f, blink ? red : Canvas.argb(RED, 0.55f * TallyClient.hudFade()), plate);
			y += 30;
		} else if (warn.terrain == Gpws.Level.TERRAIN) {
			lines.add("TERRAIN");
			colors.add(amber);
		}
		if (warn.altitude) {
			lines.add("ALTITUDE");
			colors.add(amber);
		}
		if (warn.stall) {
			lines.add("STALL");
			colors.add(blink ? amber : Canvas.argb(AMBER, 0.55f * TallyClient.hudFade()));
		}
		if (warn.threat) {
			lines.add("SPIKE " + warn.threatClock + " O'CLOCK");
			colors.add(red);
		}
		if (warn.elytraCritical) {
			lines.add("ELYTRA");
			colors.add(blink ? red : Canvas.argb(RED, 0.55f * TallyClient.hudFade()));
		} else if (warn.elytraLow) {
			lines.add("ELYTRA LOW");
			colors.add(amber);
		}
		if (warn.bingo) {
			lines.add("BINGO");
			colors.add(amber);
		}
		for (int i = 0; i < lines.size(); i++) {
			c.plate(lines.get(i), cx, y + 4, 1.5f, colors.get(i), plate);
			y += 20;
		}
	}
}
