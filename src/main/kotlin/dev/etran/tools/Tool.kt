package dev.etran.tools

import dev.etran.EasySetupPlugin
import dev.etran.gui.FacingControl
import dev.etran.gui.Items
import dev.etran.model.Element
import dev.etran.model.SetupMode
import dev.etran.model.SetupSession
import org.bukkit.ChatColor
import org.bukkit.inventory.ItemStack

/**
 * The hotbar tools. Each is recognised by a hidden marker in the last lore line, so a tool survives being
 * moved around and is never confused with the player's own items.
 */
enum class Tool(
    @JvmField val id: String,
    @JvmField val element: Element?,
    private val icon: String,
    private val toolName: String
) {
    // page 1: base
    TEAM("team", null, "WHITE_WOOL|WOOL:0", "&e&lTeam"),
    SPAWN("spawn", Element.SPAWN, "ENDER_PEARL", "&b&lTeam Spawn"),
    BED("bed", Element.BED, "RED_BED|BED", "&c&lBed"),
    SHOP("shop", Element.SHOP, "VILLAGER_SPAWN_EGG|MONSTER_EGG:120|EMERALD", "&a&lItem Shop"),
    UPGRADE("upgrade", Element.UPGRADE, "ENCHANTING_TABLE|ENCHANTMENT_TABLE", "&d&lTeam Upgrades"),
    IRON("iron", Element.GEN_IRON, "IRON_INGOT", "&f&lIron Generator"),
    GOLD("gold", Element.GEN_GOLD, "GOLD_INGOT", "&6&lGold Generator"),

    // page 2: extras
    DROPS("drops", Element.KILL_DROPS, "DROPPER", "&7&lDrop Area"),
    DETECT("detect", null, "PAINTING", "&6&lDetect Team Colour"),
    FACING("facing", null, "COMPASS", "&e&lShop Facing"),
    AUTO("auto", null, "ARROW", "&b&lAutomation"),
    GAMEMODE("gamemode", null, "NETHER_STAR", "&6&lGamemode"),
    MENU("menu", null, "BOOK", "&6&lFull Menu"),
    EXIT("exit", null, "BARRIER", "&c&lExit Setup"),

    // page 3: map
    WAIT_SPAWN("waitspawn", Element.WAITING_SPAWN, "BEACON", "&b&lPlayer (Waiting) Spawn"),
    SPEC_SPAWN("specspawn", Element.SPECTATOR_SPAWN, Items.SPECTRAL_ARROW, "&7&lSpectator Spawn"),
    LOBBY1("lobby1", Element.LOBBY_POS1, Items.WOODEN_AXE, "&e&lLobby Corner 1"),
    LOBBY2("lobby2", Element.LOBBY_POS2, "STONE_AXE", "&e&lLobby Corner 2"),
    DIAMOND("diamond", Element.GEN_DIAMOND, "DIAMOND", "&b&lDiamond Generator"),
    EMERALD("emerald", Element.GEN_EMERALD, "EMERALD", "&a&lEmerald Generator"),

    // page switchers (hotbar slots 1 and 9)
    PREV("prev", null, Items.RED_CONCRETE, "&c&l◀ Previous page"),
    NEXT("next", null, Items.LIME_CONCRETE, "&a&lNext page ▶");

    /** The item for this tool, personalised with the session's selected team and settings. */
    fun item(s: SetupSession?, plugin: EasySetupPlugin): ItemStack {
        val team = s?.selected
        var spec = icon
        var title = toolName
        val lore = ArrayList<String>()
        when (this) {
            TEAM -> {
                if (team != null) {
                    spec = team.woolSpec()
                    title = "&e&lTeam: " + team.chat + team.display
                } else {
                    title = "&e&lTeam: &7none selected"
                }
                lore.add("&7Left-click: next team")
                lore.add("&7Right-click: team menu (create teams)")
            }
            PREV, NEXT -> {
                val page = s?.hotbarPage ?: 0
                val n = PAGES.size
                val target = if (this == PREV) (page + n - 1) % n else (page + 1) % n
                lore.add("&7Page " + (page + 1) + " of " + n + ": &f" + PAGE_NAMES[page])
                lore.add("&7Click: go to &f" + PAGE_NAMES[target])
            }
            DETECT -> {
                lore.add("&7Right-click while standing at a base:")
                lore.add("&7selects the team whose wool colour matches.")
            }
            FACING -> if (s != null) lore.addAll(FacingControl.lines(plugin, s))
            AUTO -> {
                val mode = s?.mode
                when (mode) {
                    SetupMode.MANUAL -> {
                        title = "&7&lAutomation: Manual"
                        spec = "BARRIER"
                        lore.add("&7Manual: nothing is copied for you.")
                    }
                    SetupMode.SEMI -> {
                        title = "&b&lApply to next base"
                        lore.add("&7Right-click: copy this base onto the")
                        lore.add("&7nearest base without a bed.")
                    }
                    else -> {
                        title = "&d&lRun automatic placement"
                        spec = Items.ENDER_EYE
                        lore.add("&7Right-click: copy this base onto every")
                        lore.add("&7other bed, plus lobby and generators.")
                    }
                }
                lore.add("&7Left-click: mode (&f" + (mode?.label ?: "Semi-Automatic") + "&7)")
            }
            GAMEMODE -> lore.add("&7Right-click: choose the gamemode.")
            MENU -> {
                lore.add("&7Right-click: open the full menu")
                lore.add("&7(lobby, generators, validation, save).")
            }
            EXIT -> lore.add("&7Right-click: give your own hotbar back.")
            else -> {
                if (this == BED && team != null) spec = team.bedSpec()
                lore.add("&7Right-click a block: place it &fthere&7")
                lore.add("&7(you don't have to stand on it).")
                if (this == SHOP || this == UPGRADE) lore.add("&7It looks back toward you, snapped to 45°.")
                if (this == BED) lore.add("&7Click the bed itself.")
                lore.add("&7Left-click an element to delete it.")
                if (element != null && s != null) {
                    if (element.perTeam) {
                        if (team != null) {
                            lore.add("&7Placing for: " + team.chat + team.display)
                            val n = if (element.isGenerator()) s.team(team).gens.count { it.type == element.genType() } else if (s.teamPlaced(team, element)) 1 else 0
                            lore.add(if (n > 0) "&a✔ Placed" + (if (n > 1) " ($n)" else "") + " for " + team.display else "&c✘ Not placed for " + team.display + " yet")
                        }
                    } else {
                        val n = s.mapCount(element)
                        lore.add(if (n > 0) "&a✔ Placed" + (if (n > 1) " ($n)" else "") else "&c✘ Not placed yet")
                    }
                }
            }
        }
        lore.add("&0$MARKER$id")
        return Items.of(spec, title, lore)
    }

    companion object {
        /** Hotbar pages: slot 0 and 8 are the page buttons, slots 1-7 the tools of the page. */
        @JvmField
        val PAGES: List<Array<Tool?>> = listOf(
            arrayOf(PREV, TEAM, SPAWN, BED, SHOP, UPGRADE, IRON, GOLD, NEXT),
            arrayOf(PREV, DROPS, DETECT, FACING, AUTO, GAMEMODE, MENU, EXIT, NEXT),
            arrayOf(PREV, WAIT_SPAWN, SPEC_SPAWN, LOBBY1, LOBBY2, DIAMOND, EMERALD, MENU, NEXT)
        )

        @JvmField
        val PAGE_NAMES: List<String> = listOf("Base", "Extras", "Map")

        private const val MARKER = "bwauto:"

        /** Which tool an item is, or null for an ordinary item. */
        @JvmStatic
        fun fromItem(item: ItemStack?): Tool? {
            if (item == null || !item.hasItemMeta()) return null
            val m = item.itemMeta ?: return null
            if (!m.hasLore()) return null
            val lore = m.lore
            if (lore.isNullOrEmpty()) return null
            val last = ChatColor.stripColor(lore[lore.size - 1])
            if (last == null || !last.startsWith(MARKER)) return null
            val id = last.substring(MARKER.length)
            return entries.firstOrNull { it.id == id }
        }
    }
}
