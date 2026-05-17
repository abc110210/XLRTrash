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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Shan extends JavaPlugin implements Listener {

    private static final String PERSONAL_TRASH_TITLE = "§a垃圾桶";
    private static final String GLOBAL_TRASH_TITLE = "§a全服垃圾桶";
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private final List<ItemStack> globalTrashItems = new CopyOnWriteArrayList<>();
    private final Map<Player, Inventory> personalTrashInventories = new HashMap<>();
    private final Map<Player, Integer> playerPage = new HashMap<>();
    private final Object trashLock = new Object();

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

        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "欢迎使用寄寄の家" + ChatColor.AQUA + "全服垃圾桶" + ChatColor.GREEN + "插件,交流群: 943446220");
    }

    @Override
    public void onDisable() {
        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "插件" + ChatColor.AQUA + "全服垃圾桶" + ChatColor.RED + "已卸载，感谢使用寄寄の家插件!");
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

        synchronized (trashLock) {
            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < 9; col++) {
                    if (isBorder(row, col)) {
                        inv.setItem(row * 9 + col, createBorderItem(Material.BLACK_STAINED_GLASS_PANE));
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
        }

        int lastRow = 5;
        if (page > 0) {
            inv.setItem(lastRow * 9 + 3, createNavigationItem(Material.LAPIS_LAZULI, "§a上一页"));
        }
        if ((page + 1) * getMaxStoragePerPage() < globalTrashItems.size() || shouldShowNextPage(page)) {
            inv.setItem(lastRow * 9 + 4, createNavigationItem(Material.SLIME_BALL, "§a下一页"));
        } else if (page == 0) {
            inv.setItem(lastRow * 9 + 4, createNavigationItem(Material.SLIME_BALL, "§a下一页"));
        }

        player.openInventory(inv);
    }

    private boolean isBorder(int row, int col) {
        return row == 0 || row == 5 || col == 0 || col == 8;
    }

    private boolean isInnerStorage(int row, int col) {
        return row > 0 && row < 5 && col > 0 && col < 8;
    }

    private boolean isNavigationSlot(int row, int col) {
        return row == 5 && (col == 3 || col == 4);
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

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();
        int row = slot / 9;
        int col = slot % 9;

        if (title.equals(PERSONAL_TRASH_TITLE)) {
            if (isBorder(row, col)) {
                event.setCancelled(true);
            }
            return;
        }

        if (title.equals(GLOBAL_TRASH_TITLE)) {
            event.setCancelled(true);

            if (isBorder(row, col)) {
                return;
            }

            if (isNavigationSlot(row, col)) {
                int currentPage = playerPage.getOrDefault(player, 0);
                if (col == 3 && currentPage > 0) {
                    openGlobalTrash(player, currentPage - 1);
                } else if (col == 4) {
                    openGlobalTrash(player, currentPage + 1);
                }
            } else if (isInnerStorage(row, col)) {
                synchronized (trashLock) {
                    ItemStack clickedItem = event.getCurrentItem();
                    if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                        int index = getStorageIndex(row, col, playerPage.getOrDefault(player, 0));
                        if (index < globalTrashItems.size()) {
                            ItemStack item = globalTrashItems.get(index);
                            if (item != null) {
                                globalTrashItems.remove(index);
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

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title.equals(GLOBAL_TRASH_TITLE)) {
            event.setCancelled(true);
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

        synchronized (trashLock) {
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
        }

        personalTrashInventories.remove(player);
        player.sendMessage(ChatColor.GREEN + "物品已转移到全服垃圾桶！");
    }
}
