package dev.lyricscross;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Holt synchronisierte Songtexte (LRC) von lrclib.net (kostenlos, ohne API-Key). */
public final class LrcLib {
    public record Line(long timeMs, String text) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private static final Pattern TS = Pattern.compile("\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    private LrcLib() {}

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "SpotifyLyricsCrosshair/1.0 (Minecraft Fabric mod)")
                .GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 200) throw new RuntimeException("LRCLIB HTTP " + r.statusCode());
        return r.body();
    }

    public static List<Line> fetch(String title, String artist, String album, int durationSec) throws Exception {
        String body = get("https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                + (album == null || album.isBlank() ? "" : "&album_name=" + enc(album))
                + (durationSec > 0 ? "&duration=" + durationSec : ""));
        if (body != null) {
            List<Line> l = fromObject(JsonParser.parseString(body).getAsJsonObject());
            if (!l.isEmpty()) return l;
        }
        // Fallback: Suche
        String sb = get("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist));
        if (sb == null) return List.of();
        JsonArray arr = JsonParser.parseString(sb).getAsJsonArray();
        JsonObject best = null;
        double bestDiff = Double.MAX_VALUE;
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            if (!hasSynced(o)) continue;
            double d = o.has("duration") && !o.get("duration").isJsonNull()
                    ? Math.abs(o.get("duration").getAsDouble() - durationSec) : 999;
            if (d < bestDiff) { bestDiff = d; best = o; }
        }
        return best == null ? List.of() : fromObject(best);
    }

    private static boolean hasSynced(JsonObject o) {
        return o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull()
                && !o.get("syncedLyrics").getAsString().isBlank();
    }

    private static List<Line> fromObject(JsonObject o) {
        if (!hasSynced(o)) return List.of();
        return parse(o.get("syncedLyrics").getAsString());
    }

    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = TS.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = 0;
                String f = m.group(3);
                if (f != null) {
                    long v = Long.parseLong(f);
                    ms = f.length() == 1 ? v * 100 : f.length() == 2 ? v * 10 : v;
                }
                times.add(min * 60_000 + sec * 1000 + ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : times) out.add(new Line(t, text));
        }
        out.sort(Comparator.comparingLong(Line::timeMs));
        return out;
    }
}
