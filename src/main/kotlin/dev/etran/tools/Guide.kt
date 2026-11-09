package dev.etran.tools

import dev.etran.EasySetupPlugin
import dev.etran.Msg
import dev.etran.model.Element
import dev.etran.model.SetupSession
import dev.etran.place.Validator

/** Works out the single most useful next thing to do, so a new user is never left guessing. */
object Guide {
    /** One sentence describing the next step, or null when the arena is complete. */
    @JvmStatic
    fun nextStep(plugin: EasySetupPlugin, s: SetupSession): String? {
        if (s.gamemode == null) return "Pick a gamemode: right-click the nether star on hotbar page 2, or /bwsetup gamemode <mode>."
        val req = s.requiredTeams()
        if (req.any { !s.hasTeam(it) }) {
            return "Create the teams: click the red concrete in the /bwsetup menu, or right-click the Team tool on page 1 and use Auto-Create."
        }
        val sel = s.selected ?: req.first()
        // Template team first, then the rest in order, so the user finishes one base completely before copying it.
        val order = ArrayList(req)
        val tt = s.template.team
        if (tt != null && order.remove(tt)) order.add(0, tt)
        for (c in order) {
            val missing = Validator.missingForTeam(s, c)
            if (missing.isEmpty()) continue
            val first = missing.first()
            val copy = s.mode.name != "MANUAL" && c != (tt ?: sel) && missing.size >= 3
            if (copy) {
                return "${c.display} still needs ${missing.size} elements. Finish your first base, then use the Automation tool (page 2) to copy it."
            }
            val tool = Tool.entries.firstOrNull { it.element == first }
            return "Next: place ${c.display} ${first.label}" + (if (tool != null) " (select ${c.display} with the Team tool, then use the ${first.label} tool on page 1)." else ".")
        }
        val map = Validator.missingMap(s, plugin.settings())
        if (map.isNotEmpty()) {
            val e: Element = map.first()
            return "Next: place the map element \"${e.label}\" (hotbar page 3), or run automation (page 2) to detect it."
        }
        return null
    }

    /** Send the next step (or the "all done" line) to the player. */
    @JvmStatic
    fun hint(plugin: EasySetupPlugin, p: org.bukkit.entity.Player, s: SetupSession) {
        val n = nextStep(plugin, s)
        if (n == null) Msg.ok(p, "Everything is placed. Open /bwsetup and click the green concrete to validate and save.")
        else Msg.info(p, n)
    }
}
