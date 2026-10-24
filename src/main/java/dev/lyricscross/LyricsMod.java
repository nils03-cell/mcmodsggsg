package dev.lyricscross;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;


public class LyricsMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Config.load(FabricLoader.getInstance().getConfigDir());
        SpotifyClient.get().start(FabricLoader.getInstance().getConfigDir());

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CROSSHAIR,
                Identifier.of("spotifylyrics", "lyrics"),
                LyricsHud::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = ClientCommandManager.literal("lyrics");

            root.then(ClientCommandManager.literal("on").executes(c -> {
                Config.get().enabled = true; Config.save(); return reply(c, "Lyrics an."); }));
            root.then(ClientCommandManager.literal("off").executes(c -> {
                Config.get().enabled = false; Config.save(); return reply(c, "Lyrics aus."); }));
            root.then(ClientCommandManager.literal("toggle").executes(c -> {
                Config.get().enabled = !Config.get().enabled; Config.save();
                return reply(c, Config.get().enabled ? "Lyrics an." : "Lyrics aus."); }));

            root.then(ClientCommandManager.literal("status").executes(c -> {
                Config cfg = Config.get();
                reply(c, "Status: " + SpotifyClient.get().status());
                return reply(c, "Einstellungen: scale=" + cfg.scale + " y=" + cfg.yOffset + " width=" + cfg.maxWidth
                        + " lines=" + cfg.linesBefore + "/" + cfg.linesAfter + " offset=" + cfg.syncOffsetMs
                        + "ms speed=" + cfg.animationSpeed);
            }));
            root.then(ClientCommandManager.literal("reload").executes(c -> {
                SpotifyClient.get().reloadLyrics(); return reply(c, "Songtext wird neu geladen."); }));

            root.then(ClientCommandManager.literal("scale")
                    .then(ClientCommandManager.argument("v", FloatArgumentType.floatArg(0.3f, 4f)).executes(c -> {
                        Config.get().scale = FloatArgumentType.getFloat(c, "v"); Config.save();
                        return reply(c, "Größe: " + Config.get().scale); })));
            root.then(ClientCommandManager.literal("y")
                    .then(ClientCommandManager.argument("px", IntegerArgumentType.integer(-200, 400)).executes(c -> {
                        Config.get().yOffset = IntegerArgumentType.getInteger(c, "px"); Config.save();
                        return reply(c, "Abstand unter Fadenkreuz: " + Config.get().yOffset); })));
            root.then(ClientCommandManager.literal("width")
                    .then(ClientCommandManager.argument("px", IntegerArgumentType.integer(80, 1000)).executes(c -> {
                        Config.get().maxWidth = IntegerArgumentType.getInteger(c, "px"); Config.save();
                        return reply(c, "Maximale Breite: " + Config.get().maxWidth); })));
            root.then(ClientCommandManager.literal("lines")
                    .then(ClientCommandManager.argument("davor", IntegerArgumentType.integer(0, 5))
                    .then(ClientCommandManager.argument("danach", IntegerArgumentType.integer(0, 8)).executes(c -> {
                        Config.get().linesBefore = IntegerArgumentType.getInteger(c, "davor");
                        Config.get().linesAfter = IntegerArgumentType.getInteger(c, "danach"); Config.save();
                        return reply(c, "Zeilen davor/danach: " + Config.get().linesBefore + "/" + Config.get().linesAfter); }))));
            root.then(ClientCommandManager.literal("offset")
                    .then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(-5000, 5000)).executes(c -> {
                        Config.get().syncOffsetMs = IntegerArgumentType.getInteger(c, "ms"); Config.save();
                        return reply(c, "Sync-Offset: " + Config.get().syncOffsetMs + " ms (positiv = Text früher)"); })));
            root.then(ClientCommandManager.literal("speed")
                    .then(ClientCommandManager.argument("v", FloatArgumentType.floatArg(1f, 40f)).executes(c -> {
                        Config.get().animationSpeed = FloatArgumentType.getFloat(c, "v"); Config.save();
                        return reply(c, "Animationsgeschwindigkeit: " + Config.get().animationSpeed); })));
            root.then(ClientCommandManager.literal("shadow")
                    .then(ClientCommandManager.argument("an", BoolArgumentType.bool()).executes(c -> {
                        Config.get().shadow = BoolArgumentType.getBool(c, "an"); Config.save();
                        return reply(c, "Textschatten: " + Config.get().shadow); })));
            root.then(ClientCommandManager.literal("color")
                    .then(ClientCommandManager.literal("highlight")
                        .then(ClientCommandManager.argument("hex", StringArgumentType.word()).executes(c -> {
                            Config.get().highlightColor = StringArgumentType.getString(c, "hex"); Config.save();
                            return reply(c, "Farbe aktuelle Zeile: " + Config.get().highlightColor); })))
                    .then(ClientCommandManager.literal("normal")
                        .then(ClientCommandManager.argument("hex", StringArgumentType.word()).executes(c -> {
                            Config.get().normalColor = StringArgumentType.getString(c, "hex"); Config.save();
                            return reply(c, "Farbe andere Zeilen: " + Config.get().normalColor); }))));

            root.then(ClientCommandManager.literal("fx")
                    .then(ClientCommandManager.literal("on").executes(c -> {
                        Config.get().fxEnabled = true; Config.save(); return reply(c, "Effekte an."); }))
                    .then(ClientCommandManager.literal("off").executes(c -> {
                        Config.get().fxEnabled = false; Config.save(); return reply(c, "Effekte aus."); }))
                    .then(ClientCommandManager.literal("rate")
                        .then(ClientCommandManager.argument("v", IntegerArgumentType.integer(1, 10)).executes(c -> {
                            Config.get().fxRate = IntegerArgumentType.getInteger(c, "v"); Config.save();
                            return reply(c, "Effekt-Haeufigkeit: " + Config.get().fxRate + " (1 selten, 10 oft)"); }))));

            dispatcher.register(root);
        });
    }

    private static int reply(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendFeedback(Text.literal("[Lyrics] " + msg));
        return 1;
    }
}
