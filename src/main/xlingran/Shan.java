package main.xlingran;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Shan extends JavaPlugin implements Listener {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private String personalTrashTitle;
    private String globalTrashTitle;
    private String nextPageName;
    private String prevPageName;
    private String transferTip;
    private Map<Integer, String> countdownTips;
    private String clearTrashDisabledTip;
    private boolean clearTrashDisabled;
    private int clearInterval;
    private volatile boolean isCountingDown = false;

    private final List<ItemStack> globalTrashItems = new CopyOnWriteArrayList<>();
    private final Map<Player, Inventory> personalTrashInventories = new HashMap<>();
    private final Map<Player, Inventory> globalTrashInventories = new HashMap<>();
    private final Map<Player, Integer> playerPage = new HashMap<>();
    private final Map<Player, Map<Integer, Integer>> slotGlobalIndexMap = new HashMap<>();
    private final Object trashLock = new Object();
    private int cleanupTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        getServer().getPluginManager().registerEvents(this, this);

        getCommand("xlrtrash").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player) {
                openPersonalTrash((Player) sender);
            }
            return true;
        });

        getCommand("xlrglobaltrash").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player) {
                openGlobalTrash((Player) sender, 0);
            }
            return true;
        });

        startCleanupTask();
        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "欢迎使用寄の家 " + ChatColor.AQUA + "全服垃圾桶" + ChatColor.GREEN + " 插件,交流群: 943446220");
    }

    @Override
    public void onDisable() {
        if (cleanupTaskId != -1) {
            getServer().getScheduler().cancelTask(cleanupTaskId);
        }
        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "插件 " + ChatColor.AQUA + "全服垃圾桶" + ChatColor.RED + " 已卸载，感谢使用寄の家插件!");
    }

    private void startCleanupTask() {
        long intervalTicks = clearInterval * 60L * 20L;
        cleanupTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            startCountdown();
        }, intervalTicks, intervalTicks).getTaskId();
    }

    private void startCountdown() {
        isCountingDown = true;
        if (countdownTips.isEmpty()) {
            performCleanup();
            return;
        }

        List<Integer> sortedSeconds = new ArrayList<>(countdownTips.keySet());
        sortedSeconds.sort(Integer::compareTo);

        int maxSeconds = sortedSeconds.get(sortedSeconds.size() - 1);

        for (int seconds : sortedSeconds) {
            int finalSeconds = seconds;
            long delayTicks = (maxSeconds - seconds) * 20L;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                broadcastCountdown(finalSeconds);
                if (finalSeconds == 0) {
                    performCleanup();
                }
            }, delayTicks);
        }
    }

    private void broadcastCountdown(int seconds) {
        String message = countdownTips.get(seconds);
        if (message != null && !message.isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(message);
            }
        }
    }

    private void performCleanup() {
        int clearedItems = 0;
        synchronized (trashLock) {
            clearedItems = globalTrashItems.size();
            globalTrashItems.clear();
        }

        isCountingDown = false;
        closeAllGlobalTrashInventories();
    }

    private void closeAllGlobalTrashInventories() {
        for (Player player : new HashSet<>(globalTrashInventories.keySet())) {
            if (player != null && player.isOnline()) {
                Inventory inv = globalTrashInventories.get(player);
                if (inv != null && player.getOpenInventory().getTopInventory().equals(inv)) {
                    player.closeInventory();
                }
            }
        }
        globalTrashInventories.clear();
        playerPage.clear();
    }

    private void loadConfig() {
        personalTrashTitle = color(getConfig().getString("Trash.name", "&a垃圾桶"));
        globalTrashTitle = color(getConfig().getString("GlobalTrash.name", "&a全服垃圾桶"));
        nextPageName = color(getConfig().getString("GlobalTrash.Page1Next.name", "&a下一页"));
        prevPageName = color(getConfig().getString("GlobalTrash2.Back.name", "&a上一页"));
        transferTip = color(getConfig().getString("TrashTip", "&a物品已转移到全服垃圾桶！"));
        clearTrashDisabledTip = color(getConfig().getString("ClearTrashDisabledTip", "&a倒计时结束前无法打开垃圾桶"));
        clearTrashDisabled = getConfig().getBoolean("ClearTrashDisabled", true);
        clearInterval = getConfig().getInt("ClearInterval", 5);

        countdownTips = new HashMap<>();
        if (getConfig().getConfigurationSection("ClearTip") != null) {
            for (String key : getConfig().getConfigurationSection("ClearTip").getKeys(false)) {
                try {
                    int seconds = Integer.parseInt(key);
                    countdownTips.put(seconds, color(getConfig().getString("ClearTip." + key)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void openPersonalTrash(Player player) {
        if (clearTrashDisabled && isCountingDown) {
            player.sendMessage(clearTrashDisabledTip);
            return;
        }

        Inventory inv = Bukkit.createInventory(null, SIZE, personalTrashTitle);

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                if (isBorder(row, col)) {
                    inv.setItem(row * 9 + col, createBorderItem(Material.BLACK_STAINED_GLASS_PANE));
                }
            }
        }

        personalTrashInventories.put(player, inv);
        player.openInventory(inv);
    }

    private void openGlobalTrash(Player player, int page) {
        playerPage.put(player, page);
        Inventory inv = Bukkit.createInventory(null, SIZE, globalTrashTitle);
        Map<Integer, Integer> slotToGlobalIndex = new HashMap<>();

        synchronized (trashLock) {
            // Collect all valid items first
            List<ItemStack> validItems = new ArrayList<>();
            List<Integer> validIndices = new ArrayList<>();

            for (int i = 0; i < globalTrashItems.size(); i++) {
                ItemStack item = globalTrashItems.get(i);
                if (item != null && item.getType() != Material.AIR) {
                    validItems.add(item);
                    validIndices.add(i);
                }
            }

            int maxStoragePerPage = getMaxStoragePerPage();
            int startIndex = page * maxStoragePerPage;

            // Fill all slots with borders first
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < 9; col++) {
                    int slot = row * 9 + col;
                    if (isBorder(row, col)) {
                        inv.setItem(slot, createBorderItem(Material.BLACK_STAINED_GLASS_PANE));
                    }
                }
            }

            // Fill items in inner storage
            int displaySlot = 0;

            for (int row = 1; row <= 4 && displaySlot < maxStoragePerPage; row++) {
                for (int col = 1; col <= 7 && displaySlot < maxStoragePerPage; col++) {
                    int itemIndex = startIndex + displaySlot;
                    if (itemIndex < validItems.size()) {
                        int slot = row * 9 + col;
                        ItemStack item = validItems.get(itemIndex);
                        int globalIndex = validIndices.get(itemIndex);

                        inv.setItem(slot, item.clone());
                        slotToGlobalIndex.put(slot, globalIndex);
                        displaySlot++;
                    }
                }
            }

            // Add navigation buttons
            boolean hasMoreItems = validItems.size() > (page + 1) * maxStoragePerPage;

            if (page == 0) {
                if (hasMoreItems) {
                    inv.setItem(5 * 9 + 4, createNavigationItem(Material.SLIME_BALL, nextPageName));
                }
            } else {
                inv.setItem(5 * 9 + 2, createNavigationItem(Material.LAPIS_LAZULI, prevPageName));
                if (hasMoreItems) {
                    inv.setItem(5 * 9 + 6, createNavigationItem(Material.SLIME_BALL, nextPageName));
                }
            }
        }

        globalTrashInventories.put(player, inv);
        slotGlobalIndexMap.put(player, slotToGlobalIndex);
        player.openInventory(inv);
    }

    private boolean isBorder(int row, int col) {
        return row == 0 || row == 5 || col == 0 || col == 8;
    }

    private boolean isInnerStorage(int row, int col) {
        return row > 0 && row < 5 && col > 0 && col < 8;
    }

    private boolean isNavigationSlot(int row, int col) {
        return row == 5 && (col == 2 || col == 4 || col == 6);
    }

    private int getStorageIndex(int row, int col, int page) {
        int innerRow = row - 1;
        int innerCol = col - 1;
        return page * getMaxStoragePerPage() + innerRow * 7 + innerCol;
    }

    private int getMaxStoragePerPage() {
        return 4 * 7;
    }

    private boolean hasMoreItemsOnPage(int page) {
        int maxStoragePerPage = getMaxStoragePerPage();
        int logicalEndIndex = (page + 1) * maxStoragePerPage;
        int logicalCount = 0;

        for (ItemStack item : globalTrashItems) {
            if (item != null && item.getType() != Material.AIR) {
                if (logicalCount >= logicalEndIndex) {
                    return true;
                }
                logicalCount++;
            }
        }
        return false;
    }

    private ItemStack createBorderItem(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNavigationItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
        }
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.equals(personalTrashTitle) && !title.equals(globalTrashTitle)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();

        if (title.equals(personalTrashTitle)) {
            Inventory clickedInv = event.getClickedInventory();
            if (clickedInv == null) return;

            boolean isTrashInv = personalTrashInventories.get(player) == clickedInv;
            int row = slot / 9;
            int col = slot % 9;

            if (isTrashInv && isBorder(row, col)) {
                event.setCancelled(true);
                return;
            }

            if (!isTrashInv) {
                return;
            }
        }

        if (title.equals(globalTrashTitle)) {
            event.setCancelled(true);

            Inventory clickedInv = event.getClickedInventory();
            if (clickedInv == null || globalTrashInventories.get(player) != clickedInv) {
                return;
            }

            int row = slot / 9;
            int col = slot % 9;

            if (isNavigationSlot(row, col)) {
                int currentPage = playerPage.getOrDefault(player, 0);
                if (col == 2 && currentPage > 0) {
                    openGlobalTrash(player, currentPage - 1);
                } else if (col == 4 && currentPage == 0) {
                    if (hasMoreItemsOnPage(currentPage)) {
                        openGlobalTrash(player, currentPage + 1);
                    }
                } else if (col == 6) {
                    if (hasMoreItemsOnPage(currentPage)) {
                        openGlobalTrash(player, currentPage + 1);
                    }
                }
                return;
            }

            if (isBorder(row, col)) {
                return;
            }

            if (isInnerStorage(row, col)) {
                event.setCancelled(true);

                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                    return;
                }

                Map<Integer, Integer> slotMap = slotGlobalIndexMap.get(player);
                if (slotMap == null) {
                    return;
                }

                Integer globalIndex = slotMap.get(slot);
                if (globalIndex == null) {
                    return;
                }

                synchronized (trashLock) {
                    // Validate index still exists
                    if (globalIndex >= 0 && globalIndex < globalTrashItems.size()) {
                        ItemStack item = globalTrashItems.get(globalIndex);
                        if (item != null && item.getType() != Material.AIR) {
                            // Double-check it's the same item
                            if (item.isSimilar(clickedItem)) {
                                // Remove from list
                                globalTrashItems.remove(globalIndex);

                                // Clear mapping immediately
                                slotGlobalIndexMap.remove(player);

                                // Give to player
                                player.getInventory().addItem(clickedItem.clone());

                                // Refresh GUI
                                int currentPage = playerPage.getOrDefault(player, 0);
                                openGlobalTrash(player, currentPage);
                                return;
                            }
                        }
                    }
                }

                // If we get here, the item was already removed or doesn't exist
                player.sendMessage("§c物品不存在，正在刷新界面...");
                slotGlobalIndexMap.remove(player);
                int currentPage = playerPage.getOrDefault(player, 0);
                openGlobalTrash(player, currentPage);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title.equals(globalTrashTitle)) {
            event.setCancelled(true);
        }
    }

    private List<ItemStack> mergeItemStacks(List<ItemStack> items) {
        List<ItemStack> merged = new ArrayList<>();
        Map<String, ItemStack> typeMap = new HashMap<>();

        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }

            String key = getItemKey(item);
            ItemStack existing = typeMap.get(key);

            if (existing != null) {
                int newAmount = existing.getAmount() + item.getAmount();
                int maxStackSize = item.getMaxStackSize();

                if (newAmount <= maxStackSize) {
                    existing.setAmount(newAmount);
                } else {
                    existing.setAmount(maxStackSize);
                    ItemStack remaining = item.clone();
                    remaining.setAmount(newAmount - maxStackSize);
                    String remainingKey = getItemKey(remaining);
                    ItemStack existingRemaining = typeMap.get(remainingKey);
                    if (existingRemaining != null) {
                        existingRemaining.setAmount(existingRemaining.getAmount() + remaining.getAmount());
                    } else {
                        typeMap.put(remainingKey, remaining);
                    }
                }
            } else {
                typeMap.put(key, item.clone());
            }
        }

        merged.addAll(typeMap.values());
        return merged;
    }

    private String getItemKey(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "";
        }

        StringBuilder key = new StringBuilder();
        key.append(item.getType().name());

        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (meta.hasDisplayName()) {
                    key.append("|name:").append(meta.getDisplayName());
                }
                if (meta.hasLore()) {
                    key.append("|lore:").append(String.join(",", meta.getLore()));
                }
                if (meta.hasEnchants()) {
                    key.append("|ench:");
                    meta.getEnchants().forEach((ench, level) -> {
                        key.append(ench.getKey().getKey()).append(":").append(level).append(";");
                    });
                }
                if (meta.isUnbreakable()) {
                    key.append("|unbreakable");
                }
                if (meta instanceof Damageable) {
                    Damageable damageable = (Damageable) meta;
                    if (damageable.hasDamage()) {
                        key.append("|damage:").append(damageable.getDamage());
                    }
                }
                if (meta.hasCustomModelData()) {
                    key.append("|cmd:").append(meta.getCustomModelData());
                }
            }
        }

        return key.toString();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        String title = event.getView().getTitle();
        if (!title.equals(personalTrashTitle)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        Inventory inv = event.getInventory();

        synchronized (trashLock) {
            List<ItemStack> itemsToTransfer = new ArrayList<>();

            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < 9; col++) {
                    if (!isBorder(row, col)) {
                        ItemStack item = inv.getItem(row * 9 + col);
                        if (item != null && item.getType() != Material.AIR) {
                            itemsToTransfer.add(item.clone());
                        }
                    }
                }
            }

            List<ItemStack> mergedItems = mergeItemStacks(itemsToTransfer);

            for (ItemStack item : mergedItems) {
                if (item != null && item.getType() != Material.AIR) {
                    globalTrashItems.add(item);
                }
            }
        }

        personalTrashInventories.remove(player);
        player.sendMessage(transferTip);
    }
}
