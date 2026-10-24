package dev.lyricscross;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Liest den laufenden Song aus der Windows-Mediensteuerung (kein Spotify-Login, kein Premium nötig)
 * und lädt dazu die Songtexte.
 */
public final class SpotifyClient {
    public record NowPlaying(String key, String title, String artist, String album,
                             int durationMs, boolean playing, long progressMs, long sampleNanos) {}

    private static final SpotifyClient INSTANCE = new SpotifyClient();

    private static final String SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
            function Await($op, $type) {
              $t = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
              $t.Wait(-1) | Out-Null
              $t.Result
            }
            $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
            $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
            $mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
            $propType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
            $mgr = Await ($mgrType::RequestAsync()) $mgrType
            $filter = $args[0]
            while ($true) {
              $out = @{ ok = $false }
              try {
                $sel = $null
                foreach ($s in $mgr.GetSessions()) {
                  if ($s.SourceAppUserModelId -like ('*' + $filter + '*')) { $sel = $s; break }
                }
                if ($sel) {
                  $p = Await ($sel.TryGetMediaPropertiesAsync()) $propType
                  $tl = $sel.GetTimelineProperties()
                  $pb = $sel.GetPlaybackInfo()
                  $playing = ($pb.PlaybackStatus -eq 'Playing')
                  $dur = [long]($tl.EndTime.TotalMilliseconds - $tl.StartTime.TotalMilliseconds)
                  $pos = $tl.Position.TotalMilliseconds
                  if ($playing) { $pos += ([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds }
                  if ($pos -lt 0 -or ($dur -gt 0 -and $pos -gt $dur)) { $pos = $tl.Position.TotalMilliseconds }
                  $out = @{ ok = $true; title = $p.Title; artist = $p.Artist; album = $p.AlbumTitle; pos = [long]$pos; dur = $dur; playing = $playing }
                }
              } catch { }
              try {
                [Console]::Out.WriteLine(($out | ConvertTo-Json -Compress))
                [Console]::Out.Flush()
              } catch { exit }
              Start-Sleep -Milliseconds 400
            }
            """;

    private volatile NowPlaying now;
    private volatile List<LrcLib.Line> lyrics;
    private volatile String lyricsKey;
    private volatile String status = "Nicht gestartet";
    private volatile Process process;
    private Path scriptFile;
    private final ExecutorService lyricsExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SpotifyLyrics-Fetch");
        t.setDaemon(true);
        return t;
    });

    public static SpotifyClient get() { return INSTANCE; }

    public void start(Path configDir) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            status = "Nur unter Windows unterstützt";
            return;
        }
        try {
            scriptFile = configDir.resolve("spotifylyrics-media.ps1");
            Files.writeString(scriptFile, SCRIPT, StandardCharsets.UTF_8);
        } catch (Exception e) {
            status = "Skript konnte nicht geschrieben werden: " + e.getMessage();
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = process;
            if (p != null) p.destroyForcibly();
        }));
        Thread t = new Thread(this::loop, "SpotifyLyrics-Media");
        t.setDaemon(true);
        t.start();
    }

    public String status() { return status; }
    public NowPlaying nowPlaying() { return now; }

    public List<LrcLib.Line> lyrics() {
        NowPlaying n = now;
        return (n != null && n.key().equals(lyricsKey)) ? lyrics : null;
    }

    public boolean isPlaying() {
        NowPlaying n = now;
        return n != null && n.playing();
    }

    /** Geschätzte aktuelle Position in ms (inkl. Offset), -1 wenn nichts läuft. */
    public long positionMs() {
        NowPlaying n = now;
        if (n == null) return -1;
        long p = n.progressMs();
        if (n.playing()) p += (System.nanoTime() - n.sampleNanos()) / 1_000_000L;
        if (n.durationMs() > 0) p = Math.min(p, n.durationMs());
        return p + Config.get().syncOffsetMs;
    }

    public void reloadLyrics() {
        NowPlaying n = now;
        if (n != null) fetchLyrics(n);
    }

    private void loop() {
        while (true) {
            try {
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString(), "Spotify");
                pb.redirectErrorStream(false);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                process = p;
                status = "Warte auf Spotify…";
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!Config.get().enabled) { now = null; status = "Deaktiviert"; continue; }
                        handle(line);
                    }
                }
                now = null;
                status = "PowerShell wurde beendet, starte neu…";
            } catch (Exception e) {
                now = null;
                status = "PowerShell-Fehler: " + e.getMessage();
            }
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    private void handle(String line) {
        long sample = System.nanoTime();
        JsonObject o;
        try {
            o = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) {
            now = null;
            status = "Spotify nicht gefunden (Desktop-App starten und Song abspielen)";
            return;
        }
        String title = str(o, "title");
        String artist = str(o, "artist");
        if (title.isBlank()) {
            now = null;
            status = "Kein Song (Werbung/Pause?)";
            return;
        }
        String album = str(o, "album");
        int dur = (int) (o.has("dur") && !o.get("dur").isJsonNull() ? o.get("dur").getAsLong() : 0);
        long pos = o.has("pos") && !o.get("pos").isJsonNull() ? o.get("pos").getAsLong() : 0;
        boolean playing = o.has("playing") && o.get("playing").getAsBoolean();
        String key = title + "|" + artist;

        NowPlaying n = new NowPlaying(key, title, artist, album, dur, playing, pos, sample);
        boolean changed = !key.equals(lyricsKey);
        now = n;
        status = (playing ? "Spielt: " : "Pausiert: ") + title + " – " + artist;
        if (changed) fetchLyrics(n);
    }

    private void fetchLyrics(NowPlaying n) {
        lyricsKey = n.key();
        lyrics = null;
        lyricsExec.submit(() -> {
            List<LrcLib.Line> result;
            try {
                result = LrcLib.fetch(n.title(), n.artist(), n.album(), Math.round(n.durationMs() / 1000f));
            } catch (Exception e) {
                System.err.println("[SpotifyLyrics] Lyrics-Abruf fehlgeschlagen: " + e);
                result = List.of();
            }
            if (n.key().equals(lyricsKey)) lyrics = result;
        });
    }
}
