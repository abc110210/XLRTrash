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
import org.bukkit.event.player.PlayerQuitEvent;
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
    private static final int TICKS_PER_SECOND = 20;
    private static final long CLICK_DEBOUNCE_MS = 500;
    private static final int MAX_STORAGE_PER_PAGE = 28; // 4行 * 7列
    
    // 导航按钮位置常量
    private static final int NAV_PREV_COL = 2;
    private static final int NAV_CENTER_COL = 4;
    private static final int NAV_NEXT_COL = 6;
    private static final int NAV_ROW = 5;
    
    // 存储区域边界
    private static final int STORAGE_START_ROW = 1;
    private static final int STORAGE_END_ROW = 4;
    private static final int STORAGE_START_COL = 1;
    private static final int STORAGE_END_COL = 7;

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

    // 使用 LinkedList 优化频繁删除中间元素的性能
    private final List<ItemStack> globalTrashItems = new java.util.LinkedList<>();
    private volatile int validItemCount = 0; // 缓存有效物品数量，优化分页查询
    private final Map<Player, Inventory> personalTrashInventories = new HashMap<>();
    private final Map<Player, Inventory> globalTrashInventories = new HashMap<>();
    private final Map<Player, Integer> playerPage = new HashMap<>();
    private final Map<Player, Map<Integer, Integer>> slotGlobalIndexMap = new HashMap<>();
    private final Map<Player, Map<Integer, Long>> clickCooldowns = new HashMap<>(); // 防抖机制：Player -> (Slot -> Timestamp)
    private final Object trashLock = new Object();
    private int cleanupTaskId = -1;
    private int countdownTaskId = -1; // 倒计时任务ID
    private int cooldownCleanupTaskId = -1; // 防抖记录清理任务ID

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
        if (countdownTaskId != -1) {
            getServer().getScheduler().cancelTask(countdownTaskId);
        }
        if (cooldownCleanupTaskId != -1) {
            getServer().getScheduler().cancelTask(cooldownCleanupTaskId);
        }
        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "插件 " + ChatColor.AQUA + "全服垃圾桶" + ChatColor.RED + " 已卸载，感谢使用寄の家插件!");
    }

    private void startCleanupTask() {
        long intervalTicks = clearInterval * 60L * TICKS_PER_SECOND;
        cleanupTaskId = Bukkit.getScheduler().runTaskTimer(this, this::startCountdown, intervalTicks, intervalTicks).getTaskId();
        
        // 启动防抖记录定期清理任务（每分钟清理一次过期记录）
        startCooldownCleanupTask();
    }
    
    private void startCooldownCleanupTask() {
        cooldownCleanupTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            synchronized (trashLock) {
                // 清理所有玩家的过期防抖记录
                clickCooldowns.forEach((player, cooldownMap) -> {
                    cooldownMap.entrySet().removeIf(entry -> now - entry.getValue() > CLICK_DEBOUNCE_MS);
                });
                // 移除空的防抖 Map
                clickCooldowns.values().removeIf(Map::isEmpty);
            }
        }, 20L * 60, 20L * 60).getTaskId(); // 1分钟后开始，每分钟执行一次
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
        final int[] currentIndex = {0}; // 使用数组包装以在 Lambda 中修改
        
        // 使用单个循环任务替代多个 runTaskLater，减少任务数
        countdownTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (currentIndex[0] >= sortedSeconds.size()) {
                Bukkit.getScheduler().cancelTask(countdownTaskId);
                countdownTaskId = -1;
                return;
            }
            
            int seconds = sortedSeconds.get(currentIndex[0]);
            broadcastCountdown(seconds);
            
            if (seconds == 0) {
                performCleanup();
                Bukkit.getScheduler().cancelTask(countdownTaskId);
                countdownTaskId = -1;
            }
            
            currentIndex[0]++;
        }, 0, TICKS_PER_SECOND).getTaskId();
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
            validItemCount = 0; // 重置计数器
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
        
        // 参数校验：清理间隔必须 > 0
        clearInterval = getConfig().getInt("ClearInterval", 5);
        if (clearInterval <= 0) {
            clearInterval = 5; // 使用默认值
            Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[XLRTrash] ClearInterval 配置无效，已使用默认值 5 分钟");
        }

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
            
            // 更新缓存的有效物品数
            validItemCount = validItems.size();

            int startIndex = page * MAX_STORAGE_PER_PAGE;

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

            for (int row = STORAGE_START_ROW; row <= STORAGE_END_ROW && displaySlot < MAX_STORAGE_PER_PAGE; row++) {
                for (int col = STORAGE_START_COL; col <= STORAGE_END_COL && displaySlot < MAX_STORAGE_PER_PAGE; col++) {
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
            boolean hasMoreItems = hasMoreItemsOnPage(page);

            if (page == 0) {
                if (hasMoreItems) {
                    inv.setItem(NAV_ROW * 9 + NAV_CENTER_COL, createNavigationItem(Material.SLIME_BALL, nextPageName));
                }
            } else {
                inv.setItem(NAV_ROW * 9 + NAV_PREV_COL, createNavigationItem(Material.LAPIS_LAZULI, prevPageName));
                if (hasMoreItems) {
                    inv.setItem(NAV_ROW * 9 + NAV_NEXT_COL, createNavigationItem(Material.SLIME_BALL, nextPageName));
                }
            }
        }

        globalTrashInventories.put(player, inv);
        slotGlobalIndexMap.put(player, slotToGlobalIndex);
        player.openInventory(inv);
    }

    private boolean isBorder(int row, int col) {
        return !isInnerStorage(row, col) && !isNavigationSlot(row, col);
    }

    private boolean isInnerStorage(int row, int col) {
        return row >= STORAGE_START_ROW && row <= STORAGE_END_ROW 
            && col >= STORAGE_START_COL && col <= STORAGE_END_COL;
    }

    private boolean isNavigationSlot(int row, int col) {
        return row == NAV_ROW && (col == NAV_PREV_COL || col == NAV_CENTER_COL || col == NAV_NEXT_COL);
    }

    private int getStorageIndex(int row, int col, int page) {
        int innerRow = row - 1;
        int innerCol = col - 1;
        return page * getMaxStoragePerPage() + innerRow * 7 + innerCol;
    }

    private int getMaxStoragePerPage() {
        return MAX_STORAGE_PER_PAGE;
    }

    private boolean hasMoreItemsOnPage(int page) {
        // 使用缓存的有效物品数，避免遍历全量列表
        synchronized (trashLock) {
            return validItemCount > (page + 1) * MAX_STORAGE_PER_PAGE;
        }
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
                if (col == NAV_PREV_COL && currentPage > 0) {
                    openGlobalTrash(player, currentPage - 1);
                } else if (col == NAV_CENTER_COL && currentPage == 0) {
                    if (hasMoreItemsOnPage(currentPage)) {
                        openGlobalTrash(player, currentPage + 1);
                    }
                } else if (col == NAV_NEXT_COL) {
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
                Map<Integer, Long> playerCooldowns = clickCooldowns.get(player);
                if (playerCooldowns != null) {
                    Long lastClickTime = playerCooldowns.get(slot);
                    if (lastClickTime != null && (System.currentTimeMillis() - lastClickTime) < CLICK_DEBOUNCE_MS) {
                        return;
                    }
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
                clickCooldowns.computeIfAbsent(player, k -> new HashMap<>()).put(slot, System.currentTimeMillis());

                // 关键：所有数据操作必须在同步块内原子执行
                ItemStack itemToGive = null;
                boolean shouldRemove;
                
                synchronized (trashLock) {
                    // 验证：索引是否合法
                    if (globalIndex < 0 || globalIndex >= globalTrashItems.size()) {
                        if (playerCooldowns != null) {
                            playerCooldowns.remove(slot);
                        }
                        shouldRemove = false;
                    } else {
                        ItemStack itemInList = globalTrashItems.get(globalIndex);
                        if (itemInList == null || itemInList.getType() == Material.AIR) {
                            // 空物品
                            if (playerCooldowns != null) {
                                playerCooldowns.remove(slot);
                            }
                            shouldRemove = false;
                        } else {
                            // 步骤1：先克隆物品给玩家
                            itemToGive = itemInList.clone();
                            
                            // 步骤2：使用 int 索引删除（关键修复）
                            // 显式拆箱为 int 变量，确保调用 remove(int) 而不是 remove(Object)
                            int index = globalIndex;
                            globalTrashItems.remove(index);
                            
                            // 更新缓存计数器
                            synchronized (trashLock) {
                                validItemCount = Math.max(0, validItemCount - 1);
                            }
                            
                            // 步骤3：清除映射和防抖
                            slotGlobalIndexMap.remove(player);
                            if (playerCooldowns != null) {
                                playerCooldowns.remove(slot);
                            }
                            shouldRemove = true; // 删除操作已执行
                        }
                    }
                }

                // 同步块外执行所有 Bukkit API 操作
                if (shouldRemove) {
                    // 给玩家物品
                    player.getInventory().addItem(itemToGive);
                    
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

    private void mergeIntoGlobalItems(ItemStack newItem) {
        if (newItem == null || newItem.getType() == Material.AIR) {
            return;
        }

        int maxStackSize = newItem.getMaxStackSize();
        int remainingAmount = newItem.getAmount();

        // 尝试合并到现有物品
        for (ItemStack existingItem : globalTrashItems) {
            if (existingItem != null && existingItem.isSimilar(newItem) && existingItem.getAmount() < maxStackSize) {
                int canAdd = Math.min(remainingAmount, maxStackSize - existingItem.getAmount());
                existingItem.setAmount(existingItem.getAmount() + canAdd);
                remainingAmount -= canAdd;
                
                if (remainingAmount == 0) {
                    return; // 完全合并，不需要添加新物品
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
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cleanupPlayerData(player);
    }
    
    private void cleanupPlayerData(Player player) {
        personalTrashInventories.remove(player);
        globalTrashInventories.remove(player);
        playerPage.remove(player);
        slotGlobalIndexMap.remove(player);
        clickCooldowns.remove(player); // 清理该玩家的防抖记录
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
                mergeIntoGlobalItems(newItem);
            }
            
            // 更新缓存计数器
            synchronized (trashLock) {
                validItemCount = 0;
                for (ItemStack item : globalTrashItems) {
                    if (item != null && item.getType() != Material.AIR) {
                        validItemCount++;
                    }
                }
            }
        }

        cleanupPlayerData(player);
        player.sendMessage(transferTip);
    }
}
