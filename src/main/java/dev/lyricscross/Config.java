package dev.lyricscross;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance = new Config();
    private static Path file;

    public boolean enabled = true;
    public String clientId = "";
    public int yOffset = 14;
    public float scale = 1.0f;
    public float inactiveScale = 0.8f;
    public int maxWidth = 320;
    public int linesBefore = 1;
    public int linesAfter = 2;
    public String highlightColor = "55FF55";
    public String normalColor = "BBBBBB";
    public boolean shadow = true;
    public int syncOffsetMs = 0;
    public float animationSpeed = 9.0f;
    public boolean fxEnabled = true;
    public int fxRate = 5;

    public static Config get() {
        return instance;
    }

    public static void load(Path configDir) {
        file = configDir.resolve("spotifylyrics.json");
        try {
            if (Files.exists(file)) {
                Config c = GSON.fromJson(Files.readString(file), Config.class);
                if (c != null) instance = c;
            }
        } catch (Exception e) {
            System.err.println("[SpotifyLyrics] Config konnte nicht gelesen werden: " + e);
        }
        save();
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.writeString(file, GSON.toJson(instance));
        } catch (Exception e) {
            System.err.println("[SpotifyLyrics] Config konnte nicht gespeichert werden: " + e);
        }
    }

    public static int parseColor(String hex, int fallback) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return Integer.parseInt(h, 16) & 0xFFFFFF;
        } catch (Exception e) {
            return fallback;
        }
    }
}
