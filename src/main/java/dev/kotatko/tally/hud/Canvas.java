package dev.kotatko.tally.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

/**
 * Drawing primitives for the HUD, in "HUD units". One unit is a whole number of real pixels, so
 * the game's pixel font and one-unit lines stay crisp at any GUI scale.
 *
 * <p>The game only fills axis-aligned rectangles, so a slanted line is a thin rectangle drawn
 * under a rotated pose.
 */
final class Canvas {
	final GuiGraphicsExtractor g;
	final Font font;
	final Matrix3x2fStack pose;
	/** Canvas size in HUD units. */
	final float w;
	final float h;
	private final boolean shadow;

	Canvas(GuiGraphicsExtractor g, Font font, float unitsPerGuiPixel, boolean shadow) {
		this.g = g;
		this.font = font;
		this.pose = g.pose();
		this.w = g.guiWidth() * unitsPerGuiPixel;
		this.h = g.guiHeight() * unitsPerGuiPixel;
		this.shadow = shadow;
	}

	static int argb(int rgb, float alpha) {
		int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	private static int alphaOf(int color) {
		return color >>> 24;
	}

	private int shadowFor(int color) {
		return ((alphaOf(color) * 0x66 / 255) << 24);
	}

	void line(float x0, float y0, float x1, float y1, int color) {
		line(x0, y0, x1, y1, 1, color);
	}

	void line(float x0, float y0, float x1, float y1, int thickness, int color) {
		if (shadow && alphaOf(color) > 40) {
			rawLine(x0 + 1, y0 + 1, x1 + 1, y1 + 1, thickness, shadowFor(color));
		}
		rawLine(x0, y0, x1, y1, thickness, color);
	}

	private void rawLine(float x0, float y0, float x1, float y1, int thickness, int color) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.5f) {
			return;
		}
		pose.pushMatrix();
		pose.translate(x0, y0);
		pose.rotate((float) Math.atan2(dy, dx));
		pose.translate(0, -thickness / 2f);
		g.fill(0, 0, Math.max(1, Math.round(len)), thickness, color);
		pose.popMatrix();
	}

	void dashed(float x0, float y0, float x1, float y1, float dash, float gap, int color) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.5f) {
			return;
		}
		float ux = dx / len;
		float uy = dy / len;
		for (float d = 0; d < len; d += dash + gap) {
			float e = Math.min(len, d + dash);
			line(x0 + ux * d, y0 + uy * d, x0 + ux * e, y0 + uy * e, color);
		}
	}

	/** Outline of an axis-aligned box. */
	void box(float x0, float y0, float x1, float y1, int color) {
		line(x0, y0, x1, y0, color);
		line(x1, y0, x1, y1, color);
		line(x1, y1, x0, y1, color);
		line(x0, y1, x0, y0, color);
	}

	void fill(float x0, float y0, float x1, float y1, int color) {
		g.fill(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), color);
	}

	/** Arc from angle a0 to a1 in radians, measured clockwise on screen from the +x axis. */
	void arc(float cx, float cy, float r, double a0, double a1, int segments, int color) {
		float px = cx + (float) Math.cos(a0) * r;
		float py = cy + (float) Math.sin(a0) * r;
		for (int i = 1; i <= segments; i++) {
			double a = a0 + (a1 - a0) * i / segments;
			float x = cx + (float) Math.cos(a) * r;
			float y = cy + (float) Math.sin(a) * r;
			line(px, py, x, y, color);
			px = x;
			py = y;
		}
	}

	void circle(float cx, float cy, float r, int segments, int color) {
		arc(cx, cy, r, 0, Math.PI * 2, segments, color);
	}

	int textWidth(String s) {
		return font.width(s);
	}

	/** Text with its left edge at x. */
	void text(String s, float x, float y, int color) {
		g.text(font, s, Math.round(x), Math.round(y), color, shadow);
	}

	void textCentered(String s, float cx, float y, int color) {
		text(s, cx - font.width(s) / 2f, y, color);
	}

	void textRight(String s, float xRight, float y, int color) {
		text(s, xRight - font.width(s), y, color);
	}

	/** Text scaled about its centre point. */
	void textScaled(String s, float cx, float cy, float scale, int color) {
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.scale(scale, scale);
		g.text(font, s, Math.round(-font.width(s) / 2f), -4, color, shadow);
		pose.popMatrix();
	}

	/** Scaled text on a dark plate, for warnings that have to be readable against anything. */
	void plate(String s, float cx, float cy, float scale, int color, int plateColor) {
		float halfW = font.width(s) * scale / 2f + 4;
		float halfH = 4 * scale + 3;
		fill(cx - halfW, cy - halfH, cx + halfW, cy + halfH, plateColor);
		textScaled(s, cx, cy, scale, color);
	}

	/** Text centred on a point and rotated by an angle in radians. */
	void textRotated(String s, float cx, float cy, float angle, int color) {
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.rotate(angle);
		g.text(font, s, Math.round(-font.width(s) / 2f), -4, color, shadow);
		pose.popMatrix();
	}
}
