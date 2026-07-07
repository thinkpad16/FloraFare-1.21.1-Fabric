# FloraFare

A datapack-driven food overhaul for **Minecraft 1.21.1 (Fabric)**. Eating food grants timed **buffs** (extra max health, attributes, status effects), and eating the right combinations at the same time unlocks hidden **synergies**. Everything — nutrition, saturation, buffs, synergies — is defined in JSON datapacks, so modpack makers have full control without touching code.

- **Author:** tend1tnuy
- **License:** MIT
- **Requires:** Minecraft `1.21.1`, Fabric Loader, Fabric API, Java 21
- **Optional:** [AppleSkin](https://modrinth.com/mod/appleskin) (hunger/saturation preview integration)

---

## How it works

1. When a player eats any food, FloraFare looks up a config for that item (see [lookup order](#how-a-food-is-matched-to-a-config)) and applies the **configured** nutrition and saturation instead of the vanilla values.
2. The food also grants a **buff** — a timed entry shown on the HUD that can add max health, attribute modifiers, and status effects for its duration.
3. A player can hold at most **3 buffs** at once. Eating the same food again refreshes its buff; eating a 4th different food does nothing until a slot frees up.
4. While buffs are active, the mod checks all **synergies**. If the active buffs cover every requirement of a synergy, it activates as a bonus buff (its duration is the *shortest remaining* duration among the foods that satisfy it).
5. Buffs and synergies expire over time, are saved with the player, and survive relogging and dimension changes.

> **Intentional design:** vanilla food status effects are *not* applied — effects come only from datapack configs. Potions are untouched and stay fully vanilla.

### Items

| Item | How you get it | What it does |
|---|---|---|
| **Food Journal** | Given automatically on first join | Right-click to open the journal: browse every configured food and its buff, plus discovered synergies. Undiscovered synergies stay hidden. Tag entries cycle through the items in the tag. |
| **Forgotten Mead** | Crafting / creative | Drink to remove your **most recently gained** buff (returns a glass bottle). Useful to free a buff slot. |

### HUD

Active buffs and synergies are rendered on screen with item icons and remaining-time bars. Press **H** (rebindable) to open the HUD config screen:

- **Layout:** `COMPACT` / expanded
- **Icon size:** `SMALL` / `LARGE`
- **Position:** e.g. `BOTTOM_LEFT`

Settings persist in the main config file.

---

## Datapacks

FloraFare loads two kinds of data files. They work in **any datapack** — the mod's built-in pack, a world datapack (`<world>/datapacks/...`), or a server datapack. A datapack file at the **same path** as a built-in file overrides it completely.

```
data/
└── <any_namespace>/
    ├── food_buffs/          ← food buff definitions (+ optional config_generation.json)
    └── food_synergies/      ← synergy definitions
```

Configs and synergies are synced to clients automatically on join and after `/reload`, so everything works on dedicated servers.

---

## `food_buffs` syntax

Each file is a JSON object with an optional file-wide `priority` and an `entries` array:

```json
{
  "priority": 2,
  "entries": [
    {
      "id": "minecraft:golden_carrot",
      "duration": 6000,
      "nutrition": 6,
      "saturation": 1.2,
      "health_bonus": 4,
      "effects": [
        { "id": "minecraft:night_vision", "duration": 1200, "amplifier": 0 }
      ],
      "attributes": [
        { "attribute": "minecraft:generic.movement_speed", "amount": 0.1, "operation": "add_multiplied_total" }
      ]
    },
    {
      "id": "#c:foods/berry",
      "duration": 1200,
      "nutrition": 2,
      "saturation": 0.4,
      "health_bonus": 0
    }
  ]
}
```

### Entry fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | **required** | What this entry targets — see [target types](#target-types) below. |
| `duration` | int | `6000` | Buff duration in **ticks** (20 ticks = 1 second, 6000 = 5 minutes). |
| `nutrition` | int | `0` | Hunger points restored. **Whole numbers only** — 1 point = half a drumstick; the hunger bar holds 20 points. Fractional values like `0.5` are truncated to `0` and do nothing. Negative values drain hunger. |
| `saturation` | float | `0.0` | Saturation **modifier**, vanilla semantics: added saturation = `nutrition × saturation × 2`. (So it does nothing if `nutrition` is 0.) |
| `health_bonus` | double | `0.0` | Extra **max health** for the buff's duration. `2.0` = 1 heart. Negative values reduce max health (current health is clamped down immediately; max health can never drop below half a heart). Removed when the buff expires. |
| `priority` | int | file's `priority` | Per-entry override of the file-wide priority. See [priority rules](#priority-rules). |
| `effects` | array | `[]` | Status effects applied when the food is eaten (see below). |
| `attributes` | array | `[]` | Attribute modifiers active while the buff lasts (see below). |

#### `effects` entries

```json
{ "id": "minecraft:regeneration", "duration": 200, "amplifier": 1 }
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | required | Status effect id. Must exist, or the effect is skipped with an error in the log. |
| `duration` | int | required | Effect duration in ticks. |
| `amplifier` | int | `0` | Effect level minus 1 (`0` = level I). |

#### `attributes` entries

```json
{ "attribute": "minecraft:generic.attack_damage", "amount": 2.0, "operation": "add_value" }
```

| Field | Type | Meaning |
|---|---|---|
| `attribute` | string | Any registered entity attribute id. |
| `amount` | double | Modifier amount (negative allowed). |
| `operation` | string | `add_value`, `add_multiplied_base`, or `add_multiplied_total`. Anything else falls back to `add_value`. |

Attribute modifiers are applied for the buff's duration and removed on expiry; they survive relogging and returning from the End.

### Target types

The `id` field accepts several forms:

| Form | Example | Matches |
|---|---|---|
| Item id | `minecraft:bread` | Exactly that item. Ids without a namespace get `minecraft:` prepended. |
| Tag | `#c:foods/vegetable` | Every item in that item tag. |
| Namespace | `namespace:farmersdelight` | Any food from that mod that has no more specific entry. |
| Default template | `template:default` | Fallback for any food nothing else matched. |
| Potion display | `potion:minecraft:strength` | **Journal display only** — potions are never intercepted, so these entries can't trigger. |

If a plain item target doesn't exist, the loader logs a warning (or stays quiet when the whole mod is absent, so cross-mod packs don't spam).

### How a food is matched to a config

When a player eats something, the first match wins:

1. **Runtime stack config** — item NBT from `/florafare setbuff`.
2. **Exact item id** entry.
3. **Tag** entries the item belongs to (best one picked by [priority rules](#priority-rules)).
4. **`namespace:`** entry for the item's mod.
5. **`template:default`** entry.
6. **Auto-generated** from the item's vanilla food component (see below).

So an item entry always beats a tag entry, which beats a namespace entry, and so on.

### Priority rules

`priority` resolves two kinds of conflicts (higher number wins in both):

1. **Same target defined twice** (e.g. two datapacks both define `minecraft:bread`): the entry with the higher priority is kept.
2. **An item in several configured tags** (e.g. carrot is in both `#c:foods` and `#c:foods/vegetable`): the entry with the higher priority is used. On a tie, the **more specific tag path** wins (`#c:foods/vegetable` beats `#c:foods`), so broad tags naturally act as fallbacks; remaining ties resolve alphabetically for deterministic results.

This makes the "broad rule + specific overrides" pattern work in one file:

```json
{
  "priority": 1,
  "entries": [
    { "id": "#c:foods",           "duration": 1200, "nutrition": -20, "saturation": 0.5 },
    { "id": "#c:foods/vegetable", "duration": 1200, "nutrition": 20,  "saturation": 0.2 },
    { "id": "farmersdelight:cabbage", "duration": 1200, "nutrition": 20, "saturation": 0.2 }
  ]
}
```

Everything in `#c:foods` is penalized, vegetables are exempt (deeper tag), and cabbage is exempt regardless of tags (item entry).

### Auto-generation (`config_generation.json`)

Foods with no matching entry get a buff generated from their vanilla stats. Tune it with an optional `data/<namespace>/food_buffs/config_generation.json`:

```json
{
  "duration_multiplier": 1200,
  "health_multiplier": 0.5
}
```

- buff duration = `vanilla nutrition × duration_multiplier` ticks (default `1200`)
- health bonus = `vanilla nutrition × health_multiplier` (default `0.5`)

---

## `food_synergies` syntax

Files live in `data/<namespace>/food_synergies/`. A file is either a single synergy object or an object with an `entries` array:

```json
{
  "entries": [
    {
      "id": "hearty_lunch",
      "requirements": ["minecraft:baked_potato", "minecraft:cooked_beef", "minecraft:carrot"],
      "duration": 2400,
      "health_bonus": 6,
      "effects": [
        { "id": "minecraft:resistance", "duration": 2400, "amplifier": 0 }
      ],
      "attributes": []
    },
    {
      "id": "ocean_bounty",
      "requirements": ["#minecraft:fishes", "minecraft:dried_kelp"],
      "health_bonus": 4
    }
  ]
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | required | Unique synergy name (shown in logs and the journal). |
| `requirements` | array of strings | required | Item ids and/or `#` tags. The synergy activates while the player's **active buffs** cover *every* requirement at once. A tag requirement is satisfied by any buff whose **eaten item** is in that tag — even if that item got its buff from its own item entry. |
| `duration` | int | `2400` | Maximum duration in ticks — the actual duration is capped at the shortest remaining duration among the buffs that satisfy the requirements. |
| `health_bonus` | double | `0.0` | Extra max health while the synergy is active (negative allowed). |
| `effects` | array | `[]` | Same format as food buffs; effect durations are stretched to the synergy's duration. |
| `attributes` | array | `[]` | Same format as food buffs. |

Notes:

- With **3 buff slots**, keep requirements to 3 or fewer or the synergy can never activate.
- Synergies are **hidden** in the journal until first activated (a toast announces the discovery). Discoveries are saved per player.
- If a required buff expires or is removed, the synergy is removed too.
- Synergies can be disabled globally in the config.

---

## Commands

All commands require permission level 2 (op).

| Command | What it does |
|---|---|
| `/florafare buff give <player> <targetId>` | Applies the configured buff for `targetId` (item id or `#tag`, quoted) to a player and unlocks its journal entry. |
| `/florafare clear <player>` | Removes all active buffs and synergies from a player. |
| `/florafare setbuff <duration> <nutrition> <saturation> <health> [<attr_id> <attr_amount> <attr_op>]` | Attaches a **custom buff to the item stack in your main hand** (via NBT). Anyone who eats that exact stack gets this buff instead of the normal config. Persists across restarts (`config/florafare_runtime.dat`). |
| `/florafare dumpfoods` | Exports every edible item in the game (with recipe trees) to `florafare_edible_items_dump.txt` in the game directory — handy for building datapacks. |

`/reload` re-reads all `food_buffs` and `food_synergies` files and re-syncs connected clients.

---

## Configuration (`config/florafare.json`)

```json
{
  "enableSynergies": true,
  "consumptionLogging": "ALL",
  "hudLayout": "COMPACT",
  "hudIconSize": "LARGE",
  "hudPosition": "BOTTOM_LEFT"
}
```

| Key | Values | Meaning |
|---|---|---|
| `enableSynergies` | `true` / `false` | Master switch for the synergy system. |
| `consumptionLogging` | `NONE` / `REDUCED` / `ALL` | Server-log detail: `ALL` logs every consumption and synergy with full stats, `REDUCED` only logs health-restoring foods (exploit tracking) and synergy activations, `NONE` disables logging. |
| `hudLayout`, `hudIconSize`, `hudPosition` | see [HUD](#hud) | Client HUD appearance (also editable in-game with **H**). |

---

## AppleSkin integration

With AppleSkin installed, hovering a food shows the **configured** nutrition/saturation values (not vanilla ones), so the preview matches what FloraFare will actually apply. The icons only appear once the player has **discovered** the food (eaten it at least once) — before that, its values stay hidden, matching the food journal.

---

## Building from source

```bash
sh gradlew build
```

The built jar lands in `build/libs/`. Developed against Yarn mappings with Fabric Loom; see `gradle.properties` for exact versions.
