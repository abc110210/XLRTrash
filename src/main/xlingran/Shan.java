package main.xlingran;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Shan extends JavaPlugin implements Listener {

    private static final String PERSONAL_TRASH_TITLE = "§a垃圾桶";
    private static final String GLOBAL_TRASH_TITLE = "§a全服垃圾桶";
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private final List<ItemStack> globalTrashItems = new ArrayList<>();
    private final Map<Player, Inventory> personalTrashInventories = new HashMap<>();
    private final Map<Player, Integer> playerPage = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        getCommand("trash").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player) {
                openPersonalTrash((Player) sender);
            }
            return true;
        });

        getCommand("globaltrash").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player) {
                openGlobalTrash((Player) sender, 0);
            }
            return true;
        });

        getLogger().info(ChatColor.GREEN + "[XLRTrash] 全服垃圾桶插件已启用！");
        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "====================================");
        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[XLRTrash] 插件作者: Shan");
        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[XLRTrash] 版本: 1.0");
        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[XLRTrash] 功能: 全服垃圾桶系统");
        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "====================================");
    }

    @Override
    public void onDisable() {
        getLogger().info(ChatColor.RED + "[XLRTrash] 全服垃圾桶插件已卸载！");
        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "====================================");
        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[XLRTrash] 感谢使用！");
        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "====================================");
    }

    private void openPersonalTrash(Player player) {
        Inventory inv = Bukkit.createInventory(null, SIZE, PERSONAL_TRASH_TITLE);

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
        Inventory inv = Bukkit.createInventory(null, SIZE, GLOBAL_TRASH_TITLE);

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                if (isBorder(row, col)) {
                    inv.setItem(row * 9 + col, createBorderItem(Material.GLASS_PANE));
                } else if (isInnerStorage(row, col)) {
                    int index = getStorageIndex(row, col, page);
                    if (index < globalTrashItems.size()) {
                        ItemStack item = globalTrashItems.get(index);
                        if (item != null) {
                            inv.setItem(row * 9 + col, item.clone());
                        }
                    }
                }
            }
        }

        int lastRow = 5;
        if (page > 0) {
            inv.setItem(lastRow * 9 + 3, createNavigationItem(Material.LAPIS_LAZULI, "§a上一页"));
        }
        if ((page + 1) * getMaxStoragePerPage() < globalTrashItems.size() || shouldShowNextPage(page)) {
            inv.setItem(lastRow * 9 + 5, createNavigationItem(Material.SLIME_BALL, "§a下一页"));
        } else if (page == 0) {
            inv.setItem(lastRow * 9 + 5, createNavigationItem(Material.SLIME_BALL, "§a下一页"));
        }

        player.openInventory(inv);
    }

    private boolean isBorder(int row, int col) {
        return row == 0 || row == 5 || col == 0 || col == 8;
    }

    private boolean isInnerStorage(int row, int col) {
        return row > 0 && row < 5 && col > 0 && col < 8;
    }

    private int getStorageIndex(int row, int col, int page) {
        int innerRow = row - 1;
        int innerCol = col - 1;
        return page * getMaxStoragePerPage() + innerRow * 7 + innerCol;
    }

    private int getMaxStoragePerPage() {
        return 4 * 7;
    }

    private boolean shouldShowNextPage(int page) {
        int startIndex = page * getMaxStoragePerPage();
        for (int i = startIndex; i < startIndex + getMaxStoragePerPage(); i++) {
            if (i < globalTrashItems.size() && globalTrashItems.get(i) != null) {
                return true;
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
        if (!title.equals(PERSONAL_TRASH_TITLE) && !title.equals(GLOBAL_TRASH_TITLE)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();
        int row = slot / 9;
        int col = slot % 9;

        if (isBorder(row, col)) {
            return;
        }

        if (title.equals(PERSONAL_TRASH_TITLE)) {
            handlePersonalTrashClick(event, player, slot);
        } else if (title.equals(GLOBAL_TRASH_TITLE)) {
            handleGlobalTrashClick(event, player, slot, row, col);
        }
    }

    private void handlePersonalTrashClick(InventoryClickEvent event, Player player, int slot) {
        Inventory inv = event.getClickedInventory();
        if (inv == null) return;

        switch (event.getAction()) {
            case PLACE_ONE:
            case PLACE_SOME:
            case PLACE_ALL:
            case SWAP_WITH_CURSOR:
                ItemStack cursorItem = event.getCursor();
                if (cursorItem != null && cursorItem.getType() != Material.AIR) {
                    ItemStack itemToPlace = cursorItem.clone();
                    itemToPlace.setAmount(Math.min(itemToPlace.getAmount(), Math.min(
                        event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_ONE ? 1 :
                        event.getAction() == org.bukkit.event.inventory.InventoryAction.PLACE_SOME ? cursorItem.getAmount() :
                        cursorItem.getAmount(), cursorItem.getAmount())));
                    inv.setItem(slot, itemToPlace);
                    player.setItemOnCursor(new ItemStack(Material.AIR));
                }
                break;

            case PICKUP_ONE:
            case PICKUP_SOME:
            case PICKUP_ALL:
            case MOVE_TO_OTHER_INVENTORY:
            case HOTBAR_SWAP:
            case HOTBAR_MOVE_AND_READD:
            case COLLECT_TO_CURSOR:
                ItemStack clickedItem = inv.getItem(slot);
                if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                    player.getInventory().addItem(clickedItem.clone());
                    inv.setItem(slot, new ItemStack(Material.AIR));
                }
                break;

            default:
                break;
        }

        player.updateInventory();
    }

    private void handleGlobalTrashClick(InventoryClickEvent event, Player player, int slot, int row, int col) {
        int lastRow = 5;
        int currentPage = playerPage.getOrDefault(player, 0);

        if (row == lastRow) {
            if (col == 3 && currentPage > 0) {
                openGlobalTrash(player, currentPage - 1);
            } else if (col == 5) {
                openGlobalTrash(player, currentPage + 1);
            }
        } else if (isInnerStorage(row, col)) {
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                int index = getStorageIndex(row, col, currentPage);
                if (index < globalTrashItems.size()) {
                    globalTrashItems.remove(index);
                    player.getInventory().addItem(clickedItem.clone());
                    event.getClickedInventory().setItem(slot, new ItemStack(Material.AIR));
                    player.updateInventory();
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        String title = event.getView().getTitle();
        if (!title.equals(PERSONAL_TRASH_TITLE)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        Inventory inv = event.getInventory();

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                if (!isBorder(row, col)) {
                    ItemStack item = inv.getItem(row * 9 + col);
                    if (item != null && item.getType() != Material.AIR) {
                        globalTrashItems.add(item.clone());
                    }
                }
            }
        }

        personalTrashInventories.remove(player);
        player.sendMessage(ChatColor.GREEN + "物品已转移到全服垃圾桶！");
    }
}
