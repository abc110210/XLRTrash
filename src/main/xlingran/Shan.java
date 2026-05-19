package main.xlingran;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
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

    private final List<ItemStack> globalTrashItems = new ArrayList<>();
    private final Map<Player, Inventory> personalTrashInventories = new HashMap<>();
    private final Map<Player, Inventory> globalTrashInventories = new HashMap<>();
    private final Map<Player, Integer> playerPage = new HashMap<>();
    private final Map<Player, Map<Integer, Integer>> slotGlobalIndexMap = new HashMap<>();
    private final Map<String, Long> clickCooldowns = new HashMap<>(); // 防抖机制
    private final Object trashLock = new Object();
    private int cleanupTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand trashCmd = getCommand("xlrtrash");
        if (trashCmd != null) {
            trashCmd.setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player) {
                    openPersonalTrash((Player) sender);
                }
                return true;
            });
        }

        PluginCommand globalTrashCmd = getCommand("xlrglobaltrash");
        if (globalTrashCmd != null) {
            globalTrashCmd.setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player) {
                    openGlobalTrash((Player) sender, 0);
                }
                return true;
            });
        }

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
        cleanupTaskId = Bukkit.getScheduler().runTaskTimer(this, this::startCountdown, intervalTicks, intervalTicks).getTaskId();
    }

    private void startCountdown() {
        isCountingDown = true;
        if (countdownTips.isEmpty()) {
            performCleanup();
            return;
        }

        List<Integer> sortedSeconds = new ArrayList<>(countdownTips.keySet());
        sortedSeconds.sort(Integer::compareTo);

        int maxSeconds = sortedSeconds.getLast();

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
        synchronized (trashLock) {
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
        ConfigurationSection clearTipSection = getConfig().getConfigurationSection("ClearTip");
        if (clearTipSection != null) {
            for (String key : clearTipSection.getKeys(false)) {
                try {
                    int seconds = Integer.parseInt(key);
                    String tip = clearTipSection.getString(key);
                    if (tip != null) {
                        countdownTips.put(seconds, color(tip));
                    }
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
        // 关键：先关闭旧的 GUI，确保客户端清理缓存
        if (player.getOpenInventory().getTitle().equals(globalTrashTitle)) {
            player.closeInventory();
        }
        
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

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
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
                // 防抖检查：防止同一槽位被快速重复点击
                String clickKey = player.getName() + "_" + slot;
                long currentTime = System.currentTimeMillis();
                Long lastClickTime = clickCooldowns.get(clickKey);
                
                if (lastClickTime != null && (currentTime - lastClickTime) < 500) {
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

                // 记录点击时间
                clickCooldowns.put(clickKey, currentTime);

                // 关键：所有数据操作必须在同步块内原子执行
                ItemStack itemToGive = null;
                boolean shouldRemove;
                
                synchronized (trashLock) {
                    // 验证：索引是否合法
                    if (globalIndex < 0 || globalIndex >= globalTrashItems.size()) {
                        clickCooldowns.remove(clickKey);
                        shouldRemove = false;
                    } else {
                        ItemStack itemInList = globalTrashItems.get(globalIndex);
                        if (itemInList == null || itemInList.getType() == Material.AIR) {
                            // 空物品
                            clickCooldowns.remove(clickKey);
                            shouldRemove = false;
                        } else {
                            // 步骤1：先克隆物品给玩家
                            itemToGive = itemInList.clone();
                            
                            // 步骤2：使用 int 索引删除（关键修复）
                            // 确保调用 remove(int) 而不是 remove(Object)
                            int indexToRemove = globalIndex.intValue();
                            // 注意：remove(int) 返回的是被移除的元素，不是 boolean
                            globalTrashItems.remove(indexToRemove);
                            
                            // 步骤3：清除映射和防抖
                            slotGlobalIndexMap.remove(player);
                            clickCooldowns.remove(clickKey);
                            shouldRemove = true; // 删除操作已执行
                        }
                    }
                }

                // 同步块外执行所有 Bukkit API 操作
                if (shouldRemove) {
                    // 给玩家物品
                    player.getInventory().addItem(itemToGive);
                    
                    // 清除玩家光标物品，防止鼠标自动移动
                    player.setItemOnCursor(null);
                    
                    // 立即刷新 GUI（1 tick 延迟确保客户端同步）
                    Bukkit.getScheduler().runTask(this, () -> {
                        int currentPage = playerPage.getOrDefault(player, 0);
                        openGlobalTrash(player, currentPage);
                    });
                } else {
                    // 物品已被其他操作处理，刷新界面
                    Bukkit.getScheduler().runTask(this, () -> {
                        int currentPage = playerPage.getOrDefault(player, 0);
                        openGlobalTrash(player, currentPage);
                    });
                }
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

        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }

            // 如果当前物品数量已经可以整组存放，直接添加
            int maxStackSize = item.getMaxStackSize();
            int remainingAmount = item.getAmount();

            while (remainingAmount > 0) {
                // 尝试找到可以合并的现有物品
                boolean mergedItem = false;
                for (int i = 0; i < merged.size(); i++) {
                    ItemStack existing = merged.get(i);
                    if (existing.isSimilar(item) && existing.getAmount() < maxStackSize) {
                        int canAdd = Math.min(remainingAmount, maxStackSize - existing.getAmount());
                        existing.setAmount(existing.getAmount() + canAdd);
                        remainingAmount -= canAdd;
                        mergedItem = true;
                        break;
                    }
                }

                // 如果没有找到可以合并的物品，创建新的
                if (!mergedItem) {
                    ItemStack newItem = item.clone();
                    newItem.setAmount(Math.min(remainingAmount, maxStackSize));
                    merged.add(newItem);
                    remainingAmount -= newItem.getAmount();
                }
            }
        }

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
                    List<String> lore = meta.getLore();
                    if (lore != null) {
                        key.append("|lore:").append(String.join(",", lore));
                    }
                }
                if (meta.hasEnchants()) {
                    key.append("|ench:");
                    meta.getEnchants().forEach((ench, level) ->
                        key.append(ench.getKey().getKey()).append(":").append(level).append(";")
                    );
                }
                if (meta.isUnbreakable()) {
                    key.append("|unbreakable");
                }
                if (meta instanceof Damageable damageable) {
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
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        String title = event.getView().getTitle();
        if (!title.equals(personalTrashTitle)) {
            return;
        }

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

            // 先合并本次放入的物品
            List<ItemStack> mergedItems = mergeItemStacks(itemsToTransfer);

            // 再合并到 globalTrashItems 中已有的相同物品
            for (ItemStack newItem : mergedItems) {
                if (newItem != null && newItem.getType() != Material.AIR) {
                    int maxStackSize = newItem.getMaxStackSize();
                    int remainingAmount = newItem.getAmount();

                    // 尝试合并到现有物品
                    for (ItemStack existingItem : globalTrashItems) {
                        if (existingItem != null && existingItem.isSimilar(newItem) && existingItem.getAmount() < maxStackSize) {
                            int canAdd = Math.min(remainingAmount, maxStackSize - existingItem.getAmount());
                            existingItem.setAmount(existingItem.getAmount() + canAdd);
                            remainingAmount -= canAdd;
                            
                            if (remainingAmount == 0) {
                                break; // 完全合并，不需要添加新物品
                            }
                        }
                    }

                    // 如果还有剩余，添加新的 ItemStack
                    if (remainingAmount > 0) {
                        ItemStack remainingItem = newItem.clone();
                        remainingItem.setAmount(remainingAmount);
                        globalTrashItems.add(remainingItem);
                    }
                }
            }
        }

        personalTrashInventories.remove(player);
        player.sendMessage(transferTip);
    }
}
