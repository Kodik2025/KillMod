package lumi.kiss;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class KissClient implements ClientModInitializer {

    private static boolean enabled = true;
    private static String[] messages = {"{killer} зацеловал до смерти {victim}"};
    private static String prefix = "";
    private static long windowMs = 30000;
    private static boolean countDisappear = true;

    private static class Hit {
        long time;
        String name;
        Vec3d lastPos;
    }

    private static final Map<UUID, Hit> hits = new HashMap<>();

    @Override
    public void onInitializeClient() {
        loadConfig();

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (enabled && world.isClient && entity instanceof PlayerEntity p && p != player) {
                Hit h = new Hit();
                h.time = System.currentTimeMillis();
                h.name = p.getName().getString();
                h.lastPos = p.getPos();
                hits.put(p.getUuid(), h);
            }
            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!enabled || client.player == null || client.world == null) {
                hits.clear();
                return;
            }

            // Если умер ты сам - ничего не пишем
            if (client.player.getHealth() <= 0 || client.player.isDead()) {
                hits.clear();
                return;
            }

            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, Hit>> it = hits.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Hit> e = it.next();
                Hit h = e.getValue();

                if (now - h.time > windowMs) {
                    it.remove();
                    continue;
                }

                PlayerEntity victim = client.world.getPlayerByUuid(e.getKey());

                if (victim == null) {
                    // Игрок пропал из мира: если был рядом (до 12 блоков), считаем убийством
                    if (countDisappear && h.lastPos != null
                            && h.lastPos.squaredDistanceTo(client.player.getPos()) < 144) {
                        send(client, h.name);
                    }
                    it.remove();
                    continue;
                }

                h.lastPos = victim.getPos();

                if (victim.getHealth() <= 0 || victim.isDead()) {
                    send(client, victim.getName().getString());
                    it.remove();
                }
            }
        });
    }

    private static void send(MinecraftClient client, String victimName) {
        String template = messages[ThreadLocalRandom.current().nextInt(messages.length)].trim();
        String msg = prefix + template
                .replace("{killer}", client.player.getName().getString())
                .replace("{victim}", victimName);
        client.player.networkHandler.sendChatMessage(msg);
    }

    private static void loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("kissmod.properties");
        try {
            if (!Files.exists(file)) {
                String def =
                        "# Включить мод: true или false\n" +
                        "enabled=true\n" +
                        "# Текст сообщения. {killer} - твой ник, {victim} - ник убитого.\n" +
                        "# Можно несколько вариантов через | , тогда выберется случайный.\n" +
                        "message={killer} зацеловал до смерти {victim}\n" +
                        "# Префикс перед сообщением (например ! для глобального чата). Если не нужен - оставь пустым.\n" +
                        "prefix=\n" +
                        "# Сколько секунд после удара убийство засчитывается тебе\n" +
                        "window_seconds=30\n" +
                        "# Считать убийством, если игрок внезапно пропал рядом после удара (true/false)\n" +
                        "count_disappear=true\n";
                Files.writeString(file, def, StandardCharsets.UTF_8);
            }
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
            messages = p.getProperty("message", messages[0]).split("\\|");
            prefix = p.getProperty("prefix", "");
            windowMs = Long.parseLong(p.getProperty("window_seconds", "30").trim()) * 1000L;
            countDisappear = Boolean.parseBoolean(p.getProperty("count_disappear", "true").trim());
        } catch (IOException | NumberFormatException ex) {
            // оставляем значения по умолчанию
        }
    }
}
