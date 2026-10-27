#!/usr/bin/env python3
"""Generate App Manager's copy of the house palettes from ProjectForge/palettes.toml.

App Manager is a View-system app, so `pf palette apply` has no generated file to
rewrite here (its Kotlin output is Compose). This script is the hand-carried
equivalent: it writes the XML theme styles and the Kotlin enum that names them.

    python3 tools/gen-palettes.py [path/to/palettes.toml]
    pf palette apply . --done        # then record the update

Outputs (do not edit by hand):
    app/src/main/res/values/palettes.xml
    app/src/main/res/values-night/palettes.xml
    app/src/main/java/com/appmanager/Palette.kt
"""
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TOML = Path("/media/veracrypt1/Rust/ProjectForge/palettes.toml")
HEADER = "Generated from ProjectForge/palettes.toml by tools/gen-palettes.py — do not edit here."

# Material roles from a palette's `light` table → the theme attribute that holds them.
LIGHT_ATTRS = {
    "background": "android:colorBackground",
    "onBackground": "colorOnBackground",
    "primary": "colorPrimary",
    "onPrimary": "colorOnPrimary",
    "primaryContainer": "colorPrimaryContainer",
    "onPrimaryContainer": "colorOnPrimaryContainer",
    "secondary": "colorSecondary",
    "onSecondary": "colorOnSecondary",
    "secondaryContainer": "colorSecondaryContainer",
    "onSecondaryContainer": "colorOnSecondaryContainer",
    "surface": "colorSurface",
    "onSurface": "colorOnSurface",
    "surfaceVariant": "colorSurfaceVariant",
    "onSurfaceVariant": "colorOnSurfaceVariant",
    "surfaceContainer": "colorSurfaceContainer",
    "surfaceContainerHigh": "colorSurfaceContainerHigh",
    "surfaceContainerHighest": "colorSurfaceContainerHighest",
    "outline": "colorOutline",
}


def key(name: str) -> str:
    return name.replace(" ", "")


def luminance(hex_colour: str) -> float:
    r, g, b = (int(hex_colour[i:i + 2], 16) / 255 for i in (1, 3, 5))
    lin = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in (r, g, b)]
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]


def roles_to_attrs(r: dict, overrides: dict) -> dict:
    """The KeyX roles as Material attributes — the same mapping the Compose copies use."""
    face = r["surface"]
    attrs = {
        "colorPrimary": r["accent"], "colorOnPrimary": r["on_accent"],
        "colorPrimaryContainer": r["pressed"], "colorOnPrimaryContainer": r["text"],
        "colorSecondary": r["accent_secondary"], "colorOnSecondary": r["background"],
        "colorSecondaryContainer": r["pressed"], "colorOnSecondaryContainer": r["text"],
        "colorTertiary": r["highlight"], "colorOnTertiary": r["background"],
        "colorTertiaryContainer": r["pressed"], "colorOnTertiaryContainer": r["text"],
        "android:colorBackground": r["background"], "colorOnBackground": r["text"],
        "colorSurface": r["background"], "colorOnSurface": r["text"],
        "colorSurfaceVariant": face, "colorOnSurfaceVariant": r["text_secondary"],
        "colorSurfaceContainerLowest": r["background"], "colorSurfaceContainerLow": r["bars"],
        "colorSurfaceContainer": r["bars"], "colorSurfaceContainerHigh": r["raised"],
        "colorSurfaceContainerHighest": face, "colorSurfaceBright": r["raised"],
        "colorSurfaceDim": r["background"],
        "colorOutline": r["border"], "colorOutlineVariant": r["border"],
        "colorSurfaceInverse": r["text"], "colorOnSurfaceInverse": r["background"],
        "colorPrimaryInverse": r["pressed"],
    }
    for role, colour in overrides.items():
        attrs[LIGHT_ATTRS.get(role, "color" + role[0].upper() + role[1:])] = colour
    return attrs


def style(name: str, parent: str, attrs: dict, light: bool, mono: bool) -> str:
    lines = [f'    <style name="Palette.{name}" parent="{parent}">']
    for attr, colour in attrs.items():
        lines.append(f'        <item name="{attr}">{colour}</item>')
    flag = "true" if light else "false"
    lines += [
        '        <item name="android:statusBarColor">@android:color/transparent</item>',
        '        <item name="android:navigationBarColor">?android:colorBackground</item>',
        f'        <item name="android:windowLightStatusBar">{flag}</item>',
        f'        <item name="android:windowLightNavigationBar" tools:targetApi="o_mr1">{flag}</item>',
    ]
    if mono:
        lines += [
            '        <item name="fontFamily">monospace</item>',
            '        <item name="android:fontFamily">monospace</item>',
        ]
    lines.append("    </style>")
    return "\n".join(lines)


def resources(styles: list) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {HEADER} -->\n"
        '<resources xmlns:tools="http://schemas.android.com/tools">\n'
        + "\n\n".join(styles)
        + "\n</resources>\n"
    )


def main() -> None:
    toml = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_TOML
    palettes = tomllib.loads(toml.read_text())["palette"]

    day, night, entries = [], [], []
    for p in palettes:
        name, mono = key(p["name"]), bool(p.get("mono"))
        label = p.get("label", p["name"])
        if p.get("dynamic"):
            # Material You: stock Material 3; the activity layers the wallpaper colours on top.
            day.append(style(name, "Theme.Material3.DayNight.NoActionBar", {}, True, mono))
            night.append(style(name, "Theme.Material3.DayNight.NoActionBar", {}, False, mono))
            follows = True
        else:
            dark = roles_to_attrs(p["roles"], p.get("overrides", {}))
            if "light" in p:
                light = {LIGHT_ATTRS[k]: v for k, v in p["light"].items()}
                day.append(style(name, "Theme.Material3.DayNight.NoActionBar", light, True, mono))
                night.append(style(name, "Theme.Material3.DayNight.NoActionBar", dark, False, mono))
                follows = True
            else:
                is_light = luminance(p["roles"]["background"]) > 0.5
                parent = "Theme.Material3.Light.NoActionBar" if is_light else "Theme.Material3.Dark.NoActionBar"
                day.append(style(name, parent, dark, is_light, mono))
                follows = False
        entries.append((name, label, follows, mono))

    res = ROOT / "app/src/main/res"
    (res / "values/palettes.xml").write_text(resources(day))
    (res / "values-night/palettes.xml").write_text(resources(night))

    rows = []
    for name, label, follows, mono in entries:
        flags = "".join([
            ", followsMode = true" if follows else "",
            ", mono = true" if mono else "",
        ])
        rows.append(f'    {name}("{label}", R.style.Palette_{name}{flags}),')
    kotlin = f"""// {HEADER}
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
) {{
{chr(10).join(rows)}
    ;

    companion object {{
        /** The house default for a fresh install. */
        val DEFAULT = MaterialYou

        /** By key (`WhiteGlow`), label (`White Glow`), `white-glow`, any casing; null if unknown. */
        fun find(value: String?): Palette? {{
            val v = value?.let(::squash) ?: return null
            if (v.isEmpty()) return null
            return entries.firstOrNull {{ squash(it.name) == v || squash(it.label) == v }}
        }}

        private fun squash(s: String) = s.lowercase().filter {{ it.isLetterOrDigit() }}
    }}
}}
"""
    (ROOT / "app/src/main/java/com/appmanager/Palette.kt").write_text(kotlin)
    print(f"{len(entries)} palettes written")


if __name__ == "__main__":
    main()
