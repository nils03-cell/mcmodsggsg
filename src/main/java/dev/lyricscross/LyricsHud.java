package dev.lyricscross;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.List;

/** Zeichnet die Songtexte direkt unter dem Fadenkreuz. */
public final class LyricsHud {
    private static float scrollPos = 0f;
    private static float visible = 0f;
    private static long lastNanos = 0;
    private static String lastKey = "";

    private LyricsHud() {}

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static int mix(int a, int b, float t) {
        int r = (int) lerp((a >> 16) & 0xFF, (b >> 16) & 0xFF, t);
        int g = (int) lerp((a >> 8) & 0xFF, (b >> 8) & 0xFF, t);
        int bl = (int) lerp(a & 0xFF, b & 0xFF, t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int currentIndex(List<LrcLib.Line> lines, long pos) {
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs() <= pos) idx = i; else break;
        }
        return idx;
    }

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        FxHud.render(ctx);
        Config c = Config.get();
        if (!c.enabled) return;

        long nowN = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (nowN - lastNanos) / 1_000_000_000f);
        lastNanos = nowN;

        SpotifyClient sp = SpotifyClient.get();
        List<LrcLib.Line> lines = sp.lyrics();
        long pos = sp.positionMs();
        SpotifyClient.NowPlaying np = sp.nowPlaying();
        boolean show = lines != null && !lines.isEmpty() && pos >= 0 && sp.isPlaying();

        visible = clamp(visible + (show ? 1 : -1) * dt * 4f, 0f, 1f);
        if (visible <= 0.01f || lines == null || lines.isEmpty() || np == null) return;

        int idx = currentIndex(lines, pos);
        float target = idx;
        if (!np.key().equals(lastKey) || Math.abs(target - scrollPos) > 4f) {
            scrollPos = target;
            lastKey = np.key();
        } else {
            scrollPos += (target - scrollPos) * (1f - (float) Math.exp(-c.animationSpeed * dt));
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        int cx = mc.getWindow().getScaledWidth() / 2;
        int baseY = mc.getWindow().getScaledHeight() / 2 + c.yOffset;
        float lineH = (tr.fontHeight + 3) * c.scale;

        int hi = Config.parseColor(c.highlightColor, 0x55FF55);
        int lo = Config.parseColor(c.normalColor, 0xBBBBBB);

        int first = (int) Math.floor(scrollPos) - c.linesBefore - 1;
        int last = (int) Math.ceil(scrollPos) + c.linesAfter + 1;

        for (int i = Math.max(0, first); i <= Math.min(lines.size() - 1, last); i++) {
            float d = i - scrollPos;
            float p = clamp(1f - Math.abs(d), 0f, 1f);
            float edge = d < 0 ? clamp(c.linesBefore + 1 + d, 0f, 1f) : clamp(c.linesAfter + 1 - d, 0f, 1f);
            float alphaF = visible * edge * lerp(0.45f, 1f, p);
            int alpha = (int) (alphaF * 255f);
            if (alpha <= 4) continue;

            float s = c.scale * lerp(c.inactiveScale, 1f, p);
            String text = lines.get(i).text();
            if (text.isEmpty()) text = "\u266A";
            float limit = c.maxWidth / s;
            if (tr.getWidth(text) > limit) {
                text = tr.trimToWidth(text, (int) limit - tr.getWidth("\u2026")) + "\u2026";
            }
            int w = tr.getWidth(text);
            int color = (alpha << 24) | mix(lo, hi, p);

            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(cx, baseY + d * lineH);
            ctx.getMatrices().scale(s, s);
            ctx.drawText(tr, text, -(w / 2), 0, color, c.shadow);
            ctx.getMatrices().popMatrix();
        }
    }
}
