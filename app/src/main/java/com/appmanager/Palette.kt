// Generated from ProjectForge/palettes.toml by tools/gen-palettes.py — do not edit here.
package com.appmanager

/**
 * The house palettes, in the picker's order. Saved by [name], so adding one never shifts a
 * saved choice. [followsMode] palettes honour the light/dark setting; the rest are one look.
 */
enum class Palette(
    val label: String,
    val style: Int,
    val followsMode: Boolean = false,
    val mono: Boolean = false,
) {
    MaterialYou("Material You (wallpaper colours)", R.style.Palette_MaterialYou, followsMode = true),
    Ocean("Ocean", R.style.Palette_Ocean, followsMode = true),
    Terminal("Terminal", R.style.Palette_Terminal, followsMode = true, mono = true),
    Trellis("Trellis", R.style.Palette_Trellis),
    Graphite("Graphite", R.style.Palette_Graphite),
    Blueprint("Blueprint", R.style.Palette_Blueprint),
    Futuristic("Futuristic", R.style.Palette_Futuristic),
    Neon("Neon", R.style.Palette_Neon),
    SynthWave("SynthWave", R.style.Palette_SynthWave),
    WhiteGlow("White Glow", R.style.Palette_WhiteGlow),
    Silkscreen("Silkscreen", R.style.Palette_Silkscreen),
    StickyNotes("Sticky Notes", R.style.Palette_StickyNotes),
    AmberTerminal("Amber Terminal", R.style.Palette_AmberTerminal, mono = true),
    PhosphorGreen("Phosphor Green", R.style.Palette_PhosphorGreen, mono = true),
    PhosphorP31("Phosphor P31", R.style.Palette_PhosphorP31, mono = true),
    Zoro("Zoro", R.style.Palette_Zoro),
    Nebula("Nebula", R.style.Palette_Nebula),
    ;

    companion object {
        /** The house default for a fresh install. */
        val DEFAULT = MaterialYou

        /** By key (`WhiteGlow`), label (`White Glow`), `white-glow`, any casing; null if unknown. */
        fun find(value: String?): Palette? {
            val v = value?.let(::squash) ?: return null
            if (v.isEmpty()) return null
            return entries.firstOrNull { squash(it.name) == v || squash(it.label) == v }
        }

        private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
