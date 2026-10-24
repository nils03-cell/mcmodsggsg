package dev.lyricscross;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Gelegentliche Effekte ueber dem Bild: Sternschnuppen und kleine Windboeen. */
public final class FxHud {
    private static final Random R = new Random();
    private static long lastNanos = 0;

    private static final class Star {
        float x, y, vx, vy, age, life;
    }

    private static final class Gust {
        float x, y, vx, age, life;
        int lines;
        float[] dy = new float[5];
        float[] len = new float[5];
        float[] off = new float[5];
    }

    private static final List<Star> STARS = new ArrayList<>();
    private static final List<Gust> GUSTS = new ArrayList<>();

    private FxHud() {}

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    public static void render(DrawContext ctx) {
        Config c = Config.get();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        if (!c.fxEnabled) { STARS.clear(); GUSTS.clear(); return; }

        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();
        float rate = clamp(c.fxRate, 1, 10);

        // Spawnen (im Mittel alle 40/rate Sekunden eine Sternschnuppe, alle 70/rate Sekunden eine Boee)
        if (STARS.size() < 3 && R.nextFloat() < dt * rate / 40f) {
            Star s = new Star();
            boolean right = R.nextBoolean();
            s.x = w * (0.15f + 0.7f * R.nextFloat());
            s.y = h * (0.02f + 0.25f * R.nextFloat());
            float ang = (float) Math.toRadians(20 + R.nextInt(25));
            float sp = 420 + R.nextInt(240);
            s.vx = (float) Math.cos(ang) * sp * (right ? 1 : -1);
            s.vy = (float) Math.sin(ang) * sp;
            s.life = 0.8f + R.nextFloat() * 0.5f;
            STARS.add(s);
        }
        if (GUSTS.size() < 2 && R.nextFloat() < dt * rate / 70f) {
            Gust g = new Gust();
            boolean right = R.nextBoolean();
            g.x = right ? -80 : w + 80;
            g.vx = (right ? 1 : -1) * (360 + R.nextInt(200));
            g.y = h * (0.15f + 0.65f * R.nextFloat());
            g.life = 2.2f + R.nextFloat();
            g.lines = 3 + R.nextInt(3);
            for (int i = 0; i < g.lines; i++) {
                g.dy[i] = (i - g.lines / 2f) * (5 + R.nextInt(4));
                g.len[i] = 30 + R.nextInt(80);
                g.off[i] = R.nextFloat() * 40f * (right ? -1 : 1);
            }
            GUSTS.add(g);
        }

        // Sternschnuppen
        for (int i = STARS.size() - 1; i >= 0; i--) {
            Star s = STARS.get(i);
            s.age += dt;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            if (s.age > s.life) { STARS.remove(i); continue; }
            float fade = clamp(Math.min(s.age / 0.12f, (s.life - s.age) / 0.35f), 0f, 1f);
            for (int k = 0; k < 16; k++) {
                float px = s.x - s.vx * k * 0.011f;
                float py = s.y - s.vy * k * 0.011f;
                float a = fade * (1f - k / 16f);
                int alpha = (int) (a * 230);
                if (alpha <= 4) continue;
                int size = k == 0 ? 3 : (k < 5 ? 2 : 1);
                int col = (alpha << 24) | (k < 3 ? 0xFFFFFF : 0xBFD8FF);
                ctx.fill((int) px, (int) py, (int) px + size, (int) py + size, col);
            }
        }

        // Windboeen
        for (int i = GUSTS.size() - 1; i >= 0; i--) {
            Gust g = GUSTS.get(i);
            g.age += dt;
            g.x += g.vx * dt;
            if (g.age > g.life) { GUSTS.remove(i); continue; }
            float fade = clamp(Math.min(g.age / 0.4f, (g.life - g.age) / 0.6f), 0f, 1f);
            for (int k = 0; k < g.lines; k++) {
                float wave = (float) Math.sin(g.age * 3f + k) * 4f;
                int x1 = (int) (g.x + g.off[k]);
                int x2 = (int) (x1 + (g.vx > 0 ? g.len[k] : -g.len[k]));
                int y = (int) (g.y + g.dy[k] + wave);
                int alpha = (int) (fade * 70);
                if (alpha <= 4) continue;
                ctx.fill(Math.min(x1, x2), y, Math.max(x1, x2), y + 1, (alpha << 24) | 0xE8F0FF);
            }
        }
    }
}
