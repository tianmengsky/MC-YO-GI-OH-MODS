package dev.codex.yugiohprojecte;

import com.google.gson.*;
import com.kiki.yugioh.item.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

public final class EmcValues {
    private static final Set<String> NAMESPACES = Set.of("yugioh", "yugioh_interaction_fix", "yugioh_mdpro_addon", "ygostar");
    private static final Map<String, JsonObject> PRODUCTS = new HashMap<>();
    private static JsonObject config;
    public static boolean target(ResourceLocation id) { return id != null && NAMESPACES.contains(id.getNamespace()); }
    public static boolean target(Item item) { return target(ForgeRegistries.ITEMS.getKey(item)); }
    public static boolean glove(Item item) {
        var id = ForgeRegistries.ITEMS.getKey(item);
        return id != null && id.getNamespace().equals("ygostar") && id.getPath().startsWith("gloves");
    }
    public static void load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("yugioh-projecte.json");
        try {
            if (!Files.exists(path)) {
                config = JsonParser.parseString("""
                    {"card":256,"memorial":256,"deck_box":256,"duel_disk":8192,
                    "duel_table":4096,"gold_card":2048,"star":1024,"glove_shell":4096,
                    "fallback":256,"overrides":{}}
                    """).getAsJsonObject();
                Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(config), StandardCharsets.UTF_8);
            } else config = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : List.of("card","memorial","deck_box","duel_disk","duel_table","gold_card","star","glove_shell","fallback")) value(key);
            for (var entry : config.getAsJsonObject("overrides").entrySet()) positive(entry.getValue());
            try (InputStream stream = EmcValues.class.getResourceAsStream("/data/yugioh_mdpro_addon/historical-content.json")) {
                if (stream != null) {
                    JsonObject data = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (String section : List.of("products", "retired_products")) {
                        if (!data.has(section)) continue;
                        for (var row : data.getAsJsonArray(section)) {
                            var product = row.getAsJsonObject();
                            PRODUCTS.put("yugioh_mdpro_addon:" + product.get("id").getAsString(), product);
                            if (product.has("legacy_item")) PRODUCTS.put(product.get("legacy_item").getAsString(), product);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Invalid Yu-Gi-Oh EMC configuration: " + path, e);
        }
    }
    private static long positive(JsonElement element) {
        long value = element.getAsBigDecimal().longValueExact();
        if (value < 1 || value > 1_000_000_000L) throw new IllegalArgumentException("EMC must be an integer in [1, 1000000000]");
        return value;
    }
    public static long value(String key) { return positive(config.get(key)); }
    public static long base(Item item) {
        var id = ForgeRegistries.ITEMS.getKey(item);
        var overrides = config.getAsJsonObject("overrides");
        if (overrides.has(id.toString())) return positive(overrides.get(id.toString()));
        if (item instanceof CardItem) return value("card");
        if (item instanceof DeckBoxItem) return value("deck_box");
        if (item instanceof DuelDiskItem) return value("duel_disk");
        var product = PRODUCTS.get(id.toString());
        if (product != null) {
            boolean booster = product.has("opening_kind") ? product.get("opening_kind").getAsString().equals("booster") :
                Set.of("booster_pack","core_booster_pack","special_booster_pack","duelist_pack","duel_terminal").contains(product.get("product_kind").getAsString());
            if (booster) return Math.multiplyExact(5, value("card"));
            if (product.has("deck_recipe")) {
                long total = value("deck_box");
                var recipe = product.getAsJsonObject("deck_recipe");
                for (String section : List.of("main", "extra")) for (var row : recipe.getAsJsonArray(section)) {
                    var entry = row.getAsJsonObject();
                    var card = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("yugioh:" + entry.get("id").getAsString()));
                    total = Math.addExact(total, Math.multiplyExact(base(card), entry.get("count").getAsLong()));
                }
                return total;
            }
        }
        if (item instanceof BoosterItem) return Math.multiplyExact(5, value("card"));
        if (item instanceof StructureDeckItem deck && deck.deck() != null) {
            long total = value("deck_box");
            var inventory = DeckContents.handlerFor(StructureDeckItem.build(deck.deck()));
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                var child = inventory.getStackInSlot(slot);
                if (!child.isEmpty()) total = Math.addExact(total, Math.multiplyExact(base(child.getItem()), child.getCount()));
            }
            return total;
        }
        if (glove(item)) return value("glove_shell");
        return switch (id.getPath()) {
            case "duel_table" -> value("duel_table");
            case "gold_card" -> value("gold_card");
            case "star" -> value("star");
            case "duel_disk_selector", "initial_duel_disk_selector" -> value("duel_disk");
            case "duelist_glove_selector" -> value("glove_shell") + 2 * value("star");
            // Two gold cards, two selection vouchers, 128 cooked porkchops (128 * 64 EMC).
            case "starter_pack" -> Math.addExact(8192 + 2 * value("gold_card") + value("duel_disk"),
                value("glove_shell") + 2 * value("star"));
            default -> id.getNamespace().equals("yugioh_mdpro_addon") ? value("memorial") : value("fallback");
        };
    }
}
