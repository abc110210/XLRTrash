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

    private final List<ItemStack> globalTrashItems = new CopyOnWriteArrayList<>();
    private final Map<Player, Inventory> personalTrashInventories = new HashMap<>();
    private final Map<Player, Inventory> globalTrashInventories = new HashMap<>();
    private final Map<Player, Integer> playerPage = new HashMap<>();
    private final Object trashLock = new Object();

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

        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "欢迎使用寄寄の家 " + ChatColor.AQUA + "全服垃圾桶" + ChatColor.GREEN + " 插件,交流群: 943446220");
    }

    private void loadConfig() {
        personalTrashTitle = color(getConfig().getString("Trash.name", "&a垃圾桶"));
        globalTrashTitle = color(getConfig().getString("GlobalTrash.name", "&a全服垃圾桶"));
        nextPageName = color(getConfig().getString("GlobalTrash.Page1Next.name", "&a下一页"));
        prevPageName = color(getConfig().getString("GlobalTrash2.Back.name", "&a上一页"));
        transferTip = color(getConfig().getString("TrashTip", "&a物品已转移到全服垃圾桶！"));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    @Override
    public void onDisable() {
        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "插件 " + ChatColor.AQUA + "全服垃圾桶" + ChatColor.RED + " 已卸载，感谢使用寄寄の家插件!");
    }

    private void openPersonalTrash(Player player) {
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

        synchronized (trashLock) {
            int displayIndex = 0;
            int maxStoragePerPage = getMaxStoragePerPage();

            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < 9; col++) {
                    int slot = row * 9 + col;

                    if (isBorder(row, col)) {
                        inv.setItem(slot, createBorderItem(Material.BLACK_STAINED_GLASS_PANE));
                    } else if (displayIndex < maxStoragePerPage) {
                        int globalIndex = page * maxStoragePerPage + displayIndex;
                        if (globalIndex < globalTrashItems.size()) {
                            ItemStack item = globalTrashItems.get(globalIndex);
                            if (item != null && item.getType() != Material.AIR) {
                                inv.setItem(slot, item.clone());
                            }
                        }
                        displayIndex++;
                    }
                }
            }

            if (page > 0) {
                inv.setItem(5 * 9 + 2, createNavigationItem(Material.LAPIS_LAZULI, prevPageName));
            }
            inv.setItem(5 * 9 + 6, createNavigationItem(Material.SLIME_BALL, nextPageName));
        }

        globalTrashInventories.put(player, inv);
        player.openInventory(inv);
    }

    private boolean isBorder(int row, int col) {
        return row == 0 || row == 5 || col == 0 || col == 8;
    }

    private boolean isInnerStorage(int row, int col) {
        return row > 0 && row < 5 && col > 0 && col < 8;
    }

    private boolean isNavigationSlot(int row, int col) {
        return row == 5 && (col == 2 || col == 6);
    }

    private int getStorageIndex(int row, int col, int page) {
        int innerRow = row - 1;
        int innerCol = col - 1;
        return page * getMaxStoragePerPage() + innerRow * 7 + innerCol;
    }

    private int getMaxStoragePerPage() {
        return 4 * 7;
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
                } else if (col == 6) {
                    openGlobalTrash(player, currentPage + 1);
                }
                return;
            }

            if (isBorder(row, col)) {
                return;
            }

            if (isInnerStorage(row, col)) {
                synchronized (trashLock) {
                    ItemStack clickedItem = event.getCurrentItem();
                    if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                        int currentPage = playerPage.getOrDefault(player, 0);
                        int slotOnPage = getSlotOnPage(row, col);
                        int globalIndex = currentPage * getMaxStoragePerPage() + slotOnPage;

                        if (globalIndex < globalTrashItems.size()) {
                            ItemStack item = globalTrashItems.get(globalIndex);
                            if (item != null) {
                                globalTrashItems.remove(globalIndex);
                                player.getInventory().addItem(clickedItem.clone());
                                event.getClickedInventory().setItem(slot, new ItemStack(Material.AIR));
                                player.updateInventory();
                            }
                        }
                    }
                }
            }
        }
    }

    private int getSlotOnPage(int row, int col) {
        int innerRow = row - 1;
        int innerCol = col - 1;
        return innerRow * 7 + innerCol;
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
