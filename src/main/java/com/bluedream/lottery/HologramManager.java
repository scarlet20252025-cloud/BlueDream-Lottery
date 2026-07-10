package com.bluedream.lottery;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import java.util.Collection;

public class HologramManager implements org.bukkit.event.Listener {
    private final BlueDreamLottery plugin;
    private final NamespacedKey hologramKey;
    private final Map<Location, Entity> activeItemHolograms = new ConcurrentHashMap<>();
    private final Map<Location, Entity> activeTextHolograms = new ConcurrentHashMap<>();
    private final Map<Location, Integer> itemIndices = new ConcurrentHashMap<>();
    private final boolean isSupported;

    public NamespacedKey getHologramKey() {
        return hologramKey;
    }

    public HologramManager(BlueDreamLottery plugin) {
        this.plugin = plugin;
        this.hologramKey = new NamespacedKey(plugin, "hologram");
        this.isSupported = Adapter.isDisplaySupported();
        if (isSupported) {
            startUpdateTask();
            Bukkit.getScheduler().runTaskLater(plugin, this::cleanupOrphanedHolograms, 20L);
        }
    }

    @org.bukkit.event.EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        if (!isSupported || !plugin.getConfig().getBoolean("hologram.enabled", true)) return;

        org.bukkit.Chunk chunk = event.getChunk();
        Map<Location, String> cachedLocations = plugin.getManager().getCachedLocations();

        for (Map.Entry<Location, String> entry : cachedLocations.entrySet()) {
            Location loc = entry.getKey();
            if (loc.getWorld() == null || !loc.getWorld().equals(chunk.getWorld())) continue;

            int chunkX = loc.getBlockX() >> 4;
            int chunkZ = loc.getBlockZ() >> 4;
            if (chunkX != chunk.getX() || chunkZ != chunk.getZ()) continue;

            Entity itemEntity = activeItemHolograms.get(loc);
            Entity textEntity = activeTextHolograms.get(loc);
            boolean itemValid = itemEntity != null && itemEntity.isValid();
            boolean textValid = textEntity != null && textEntity.isValid();

            if (itemValid && textValid) continue;

            forceRemoveNearbyDisplays(loc);
            activeItemHolograms.remove(loc);
            activeTextHolograms.remove(loc);
            itemIndices.remove(loc);

            createHologram(loc, entry.getValue());
        }
    }

    public void cleanupOrphanedHolograms() {
        if (!isSupported) return;

        int removedCount = 0;

        Map<Location, String> cachedLocations = plugin.getManager().getCachedLocations();

        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity == null || !entity.isValid()) continue;

                if (entity instanceof org.bukkit.entity.ItemDisplay || entity instanceof org.bukkit.entity.TextDisplay) {
                    if (entity.getPersistentDataContainer().has(hologramKey, PersistentDataType.BYTE)) {
                        Location entityLoc = entity.getLocation();
                        boolean nearRegisteredBlock = false;

                        for (Location blockLoc : cachedLocations.keySet()) {
                            if (blockLoc.getWorld() != null && blockLoc.getWorld().equals(entityLoc.getWorld())) {
                                if (blockLoc.distanceSquared(entityLoc) < 16.0) {
                                    nearRegisteredBlock = true;
                                    break;
                                }
                            }
                        }

                        if (!nearRegisteredBlock) {
                            entity.remove();
                            removedCount++;
                        }
                    }
                }
            }
        }

        if (removedCount > 0) {
            plugin.getLogger().info("启动时已清理 " + removedCount + " 个残留全息图实体。");
        }
    }

    private void removeNearbyHolograms(Location loc) {
        if (loc.getWorld() == null) return;
        Location searchCenter = loc.clone().add(0.5, 1.5, 0.5);
        Collection<Entity> entities = loc.getWorld().getNearbyEntities(searchCenter, 2.0, 3.0, 2.0);
        for (Entity entity : entities) {
            if ((entity instanceof org.bukkit.entity.ItemDisplay || entity instanceof org.bukkit.entity.TextDisplay)
                    && entity.getPersistentDataContainer().has(hologramKey, PersistentDataType.BYTE)) {
                entity.remove();
            }
        }
    }

    private void forceRemoveNearbyDisplays(Location loc) {
        if (loc.getWorld() == null) return;
        Location searchCenter = loc.clone().add(0.5, 1.5, 0.5);
        Collection<Entity> entities = loc.getWorld().getNearbyEntities(searchCenter, 2.0, 3.0, 2.0);
        for (Entity entity : entities) {
            if (entity instanceof org.bukkit.entity.ItemDisplay || entity instanceof org.bukkit.entity.TextDisplay) {
                entity.remove();
            }
        }
    }

    public void updateAllHolograms() {
        if (!isSupported) return;

        removeAllHolograms();
        
        Map<Location, String> blocks = plugin.getManager().getCachedLocations();
        for (Map.Entry<Location, String> entry : blocks.entrySet()) {
            createHologram(entry.getKey(), entry.getValue());
        }
    }

    public void createHologram(Location loc, String poolName) {
        if (!plugin.getServer().isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> createHologram(loc, poolName));
            return;
        }
        if (!isSupported || !plugin.getConfig().getBoolean("hologram.enabled", true)) return;
        
        LotteryPool pool = plugin.getManager().getPool(poolName);
        if (pool == null) return;

        double itemHeight = plugin.getConfig().getDouble("hologram.item_height_offset", 1.2);
        double textHeight = plugin.getConfig().getDouble("hologram.text_height_offset", 1.8);

        Location itemLoc = loc.clone().add(0.5, itemHeight, 0.5);
        Location textLoc = loc.clone().add(0.5, textHeight, 0.5);
        
        Bukkit.getScheduler().runTask(plugin, () -> {
            removeHologram(loc);
            removeNearbyHolograms(loc);
            
            if (!pool.getItems().isEmpty()) {
                ItemDisplay display = (ItemDisplay) itemLoc.getWorld().spawnEntity(itemLoc, EntityType.valueOf("ITEM_DISPLAY"));
                display.setItemStack(pool.getItems().get(0).getItem());
                display.setBillboard(ItemDisplay.Billboard.CENTER);
                display.getPersistentDataContainer().set(hologramKey, PersistentDataType.BYTE, (byte) 1);
                
                org.bukkit.util.Transformation transformation = display.getTransformation();
                transformation.getScale().set(0.6f, 0.6f, 0.6f);
                display.setTransformation(transformation);
                
                activeItemHolograms.put(loc, display);
                itemIndices.put(loc, 0);
            }

            if (plugin.getConfig().getBoolean("hologram.show_text", true)) {
                org.bukkit.entity.TextDisplay textDisplay = (org.bukkit.entity.TextDisplay) textLoc.getWorld().spawnEntity(textLoc, EntityType.valueOf("TEXT_DISPLAY"));
                updateHologramText(textDisplay, pool);
                textDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                textDisplay.getPersistentDataContainer().set(hologramKey, PersistentDataType.BYTE, (byte) 1);
                
                String bgColorStr = plugin.getConfig().getString("hologram.text_background_color", "DEFAULT");
                if (!bgColorStr.equalsIgnoreCase("DEFAULT")) {
                    try {
                        if (bgColorStr.startsWith("#")) {
                            long colorLong = Long.parseLong(bgColorStr.substring(1), 16);
                            if (bgColorStr.length() == 7) {
                                colorLong |= 0xFF000000L;
                            }
                            textDisplay.setBackgroundColor(org.bukkit.Color.fromARGB((int) colorLong));
                        }
                    } catch (Exception ignored) {}
                }
                
                textDisplay.setShadowed(plugin.getConfig().getBoolean("hologram.text_shadow", true));
                
                activeTextHolograms.put(loc, textDisplay);
            }
        });
    }

    private void updateHologramText(org.bukkit.entity.TextDisplay display, LotteryPool pool) {
        LanguageManager lm = plugin.getLanguageManager();
        String title = plugin.getConfig().getString("hologram.text_format", "&6&l{name}")
                .replace("{name}", pool.getName());
        
        StringBuilder sb = new StringBuilder();
        sb.append(title);
        
        if (plugin.getConfig().getBoolean("hologram.show_cost", true)) {
            sb.append("\n");
            String costText = "";
            switch (pool.getCostType()) {
                case "VAULT":
                    costText = lm.getMessage("cost_vault")
                        .replace("{value}", String.valueOf(pool.getCostValue()))
                        .replace("{value10}", String.valueOf(pool.getCostValue() * 10));
                    break;
                case "PLAYERPOINTS":
                    costText = lm.getMessage("cost_points")
                        .replace("{value}", String.valueOf((int)pool.getCostValue()))
                        .replace("{value10}", String.valueOf((int)(pool.getCostValue() * 10)));
                    break;
                case "KEY":
                    costText = lm.getMessage("cost_key")
                        .replace("{name}", pool.getKeyName());
                    break;
                default:
                    costText = lm.getMessage("cost_free");
                    break;
            }
            sb.append(costText);
        }
        
        display.setText(Adapter.color(sb.toString()));
    }

    public int globalCleanup(boolean force) {
        if (!isSupported) return 0;

        int removedCount = 0;
        int failedCount = 0;

        for (Entity entity : activeItemHolograms.values()) {
            if (entity != null) {
                try {
                    entity.remove();
                    removedCount++;
                } catch (Exception e) {
                    failedCount++;
                }
            }
        }
        for (Entity entity : activeTextHolograms.values()) {
            if (entity != null) {
                try {
                    entity.remove();
                    removedCount++;
                } catch (Exception e) {
                    failedCount++;
                }
            }
        }
        activeItemHolograms.clear();
        activeTextHolograms.clear();
        itemIndices.clear();

        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                boolean shouldRemove = false;

                if (entity.getPersistentDataContainer().has(hologramKey, PersistentDataType.BYTE)) {
                    shouldRemove = true;
                }

                if (force && (entity instanceof org.bukkit.entity.ItemDisplay || entity instanceof org.bukkit.entity.TextDisplay)) {
                    shouldRemove = true;
                }

                if (shouldRemove) {
                    try {
                        entity.remove();
                        removedCount++;
                    } catch (Exception e) {
                        failedCount++;
                    }
                }
            }
        }

        plugin.getLogger().info("已清理 " + removedCount + " 个全息图实体，失败 " + failedCount + " 个 (强制模式: " + force + ")。");
        return removedCount;
    }

    public void removeHologram(Location loc) {
        if (!plugin.getServer().isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> removeHologram(loc));
            return;
        }
        removeNearbyHolograms(loc);
        Entity itemEntity = activeItemHolograms.remove(loc);
        if (itemEntity != null && itemEntity.isValid()) {
            itemEntity.remove();
        }
        Entity textEntity = activeTextHolograms.remove(loc);
        if (textEntity != null && textEntity.isValid()) {
            textEntity.remove();
        }
        itemIndices.remove(loc);
        forceRemoveNearbyDisplays(loc);
    }

    public void removeAllHolograms() {
        for (Entity entity : activeItemHolograms.values()) {
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
        for (Entity entity : activeTextHolograms.values()) {
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
        activeItemHolograms.clear();
        activeTextHolograms.clear();
        itemIndices.clear();
    }

    public void refreshPoolHolograms(String poolName) {
        if (!plugin.getServer().isPrimaryThread()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> refreshPoolHolograms(poolName));
            return;
        }
        Map<Location, String> cachedLocations = plugin.getManager().getCachedLocations();
        for (Map.Entry<Location, String> entry : cachedLocations.entrySet()) {
            if (entry.getValue().equals(poolName)) {
                removeHologram(entry.getKey());
                createHologram(entry.getKey(), poolName);
            }
        }
    }

    private void startUpdateTask() {
        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    return;
                }

                ticks++;
                float rotationSpeed = (float) plugin.getConfig().getDouble("hologram.rotation_speed", 2.0);
                int cycleInterval = plugin.getConfig().getInt("hologram.cycle_interval", 3) * 20;

                boolean checkPoolChanges = (ticks % 20 == 0);

                for (Map.Entry<Location, Entity> entry : activeItemHolograms.entrySet()) {
                    Location loc = entry.getKey();
                    Entity entity = entry.getValue();

                    if (entity == null || entity.isDead()) {
                        removeHologramFromMaps(loc);
                        continue;
                    }

                    if (!entity.isValid()) {
                        continue;
                    }

                    if (entity instanceof ItemDisplay) {
                        ItemDisplay display = (ItemDisplay) entity;
                        try {
                            org.bukkit.util.Transformation transformation = display.getTransformation();
                            transformation.getLeftRotation().rotationY((float) Math.toRadians(ticks * rotationSpeed));
                            display.setTransformation(transformation);

                            if (ticks % cycleInterval == 0 || checkPoolChanges) {
                                String poolName = plugin.getManager().getCachedLocations().get(loc);
                                if (poolName != null) {
                                    LotteryPool pool = plugin.getManager().getPool(poolName);
                                    if (pool != null) {
                                        if (!pool.getItems().isEmpty() && (ticks % cycleInterval == 0)) {
                                            int nextIndex = (itemIndices.getOrDefault(loc, 0) + 1) % pool.getItems().size();
                                            display.setItemStack(pool.getItems().get(nextIndex).getItem());
                                            itemIndices.put(loc, nextIndex);
                                        }

                                        Entity textEntity = activeTextHolograms.get(loc);
                                        if (textEntity != null && textEntity.isValid() && checkPoolChanges) {
                                            updateHologramText((org.bukkit.entity.TextDisplay) textEntity, pool);
                                        }
                                    } else {
                                        removeHologram(loc);
                                        continue;
                                    }
                                } else {
                                    removeHologram(loc);
                                    continue;
                                }
                            }
                        } catch (Exception e) {
                            removeHologramFromMaps(loc);
                            if (entity.isValid()) entity.remove();
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void removeHologramFromMaps(Location loc) {
        Entity itemEntity = activeItemHolograms.remove(loc);
        if (itemEntity != null && itemEntity.isValid()) {
            itemEntity.remove();
        }
        Entity textEntity = activeTextHolograms.remove(loc);
        if (textEntity != null && textEntity.isValid()) {
            textEntity.remove();
        }
        itemIndices.remove(loc);
    }
}
