<div align="center">

# GoidaJail

[![Latest Release](https://img.shields.io/github/v/release/Yukovsky/GoidaJail?style=flat-square&label=latest&color=brightgreen)](https://github.com/Yukovsky/GoidaJail/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-blue?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.228+-orange?style=flat-square)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-Apache--2.0-lightgrey?style=flat-square)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/Yukovsky/GoidaJail/build.yml?style=flat-square)](https://github.com/Yukovsky/GoidaJail/actions)

**A server-side jail for griefers, PvP-abusers and rule-breakers — no client mod required.**

One hit with the jail baton teleports the offender to an isolated bedrock cell for a
scaling timer; when it runs out they're released with every item restored.

</div>

---

## Why GoidaJail

Most punishment mods make you choose between "ban and lose the player" and "warn and get
ignored." GoidaJail sits in between: a timed, escalating, reversible lockup that a moderator
can hand out with a single tool swing, mid-fight, without opening a menu.

- **Server-only.** The mod ships nothing to the client. The baton item itself is registered by
  KubeJS — already required on your clients for a modpack — so GoidaJail never has to touch them.
- **Escalating, not static.** Repeat offenders get longer sentences automatically; the counter
  forgets after a configurable cooldown so it isn't a permanent scarlet letter.
- **Crash-safe.** The confiscated inventory lives in the same player data-attachment as the live
  inventory, written to disk atomically. A server crash mid-sentence never eats someone's items.
- **Escape-proof.** Adventure mode, cancelled dimension changes, blocked teleport commands, a
  per-tick position leash, and a bedrock shell — all four backstops run at once.
- **Power lives with the moderator, not the item.** A stolen or dropped baton is inert in
  anyone else's hands; see [Baton safety](#baton-safety) below.

---

## How it works

1. A moderator hits a player with the jail baton → the target is teleported to a private
   bedrock dimension (`goidajail:jail`).
2. Their inventory — vanilla slots, plus Curios and cosmetic armor if installed — is captured
   and stored; the player is switched to adventure mode with damage disabled (no deaths, no
   Gravestone-style grave spawning).
3. The sentence clock only runs while the player is **online**. First offense: 15 minutes.
   Every additional offense within the retention window (default 3 days) adds another 15,
   up to a configurable cap.
4. On release: teleport back to the configured spawn point, items and prior game mode
   restored, spawn point reset to what it was before arrest.

---

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228 or later (`[21.1.228,)`) |
| Java | 21 |
| Side | **Server only** — do not install on clients |
| KubeJS | Required — registers the baton item (already on both sides in a modpack) |
| Curios / CosmeticArmorReworked | Optional — their slots are captured and restored too |
| LuckPerms / FTB Ranks | Optional — grants permissions to non-op players; falls back to op-level otherwise |
| GoidaChat | Optional — mutes chat and voice for the duration of a sentence |

---

## Installation

1. Drop the built jar into the server's `mods/` folder. **Do not** install it on clients.
2. Copy `kubejs-integration/startup_scripts/goidajail_baton.js` (bundled with this repo) into
   your modpack at:
   ```
   <modpack>/kubejs/startup_scripts/goidajail_baton.js
   ```
   This script must reach every client and the server via the modpack itself — it registers
   `kubejs:jail_baton` (the id must match `batonItemId` in the config). Since KubeJS creates the
   item without a crafting recipe, it can't be crafted into anything, and `/goidajail baton`
   refuses to hand out a second one if you already have one.
3. Start the server. Config is generated at `config/goidajail-server.toml` on first launch.
4. Give yourself or a moderator the baton with `/goidajail baton`, or grant permissions — see
   [Permissions](#permissions) below.

---

## Configuration

`config/goidajail-server.toml`:

```toml
baseSentenceMinutes = 15    # sentence length for a first offense
incrementMinutes    = 15    # extra minutes added per prior offense in the retention window
maxSentenceMinutes  = 180   # hard cap regardless of offense count
offenseExpiryHours  = 72    # offense memory window; resets to 0 after this with no new offense
releaseX = 0                # overworld coordinates a freed player returns to
releaseY = 63
releaseZ = 0
visitX = 0                  # jail-dimension coordinates /goidajail visit teleports an admin to
visitY = 65
visitZ = 0
batonItemId = "kubejs:jail_baton"  # must match the id in the bundled KubeJS script
batonPermissionLevel = 2    # fallback op level for goidajail.use when no permission mod is present
radioIntervalSeconds = 30   # how often the in-cell reminder message repeats
```

---

## Commands

All commands live under `/goidajail` and require a permission node — see
[Permissions](#permissions).

| Command | Description |
|---|---|
| `help` | List all commands |
| `baton` | Give yourself a baton (skipped if you already have one) |
| `jail <player> [minutes]` | Manually jail a player |
| `release <player>` | Release and restore items (offense count kept) |
| `pardon <player>` | Release, restore items, **and clear the offense** |
| `time <player> <minutes>` | Set the remaining sentence |
| `addtime <player> <minutes>` | Add or subtract time from the remaining sentence |
| `info <player>` | Status, remaining time, offense count, backup availability |
| `list` | List all prisoners (● online / ○ offline) |
| `releaseoffline <name>` | Queue release for an offline player, applied on next join |
| `restoreinv <player>` | **Recovery:** force-restore items from the saved backup |
| `clearstate <player>` | **Emergency:** clear jail status without touching the item backup |
| `clearoffenses <player>` | Wipe a player's offense history |
| `confiscate <name> [notify\|silent]` | Open a prisoner's inventory to confiscate items |
| `confiscatesilent <on\|off>` | Toggle your default confiscation notification behaviour |
| `confiscationlog [page]` | Paginated audit log of every confiscation |
| `visit` / `back` | Teleport into the jail dimension and back, for setup or inspection |
| `setpos1` / `setpos2` | Mark corners of a region (region-editing helpers) |
| `applyregion` / `clearregion` | Apply or clear a marked region in the jail dimension |
| `setjailspawn` | Set the spawn point used inside the jail dimension |

---

## Confiscation

While a player is jailed, a moderator with `goidajail.confiscate` can open a **separate**
container view of their impounded inventory — vanilla, Curios, and cosmetic slots included —
and pull out whatever shouldn't be given back.

- `/goidajail confiscate <name>` opens a chest-style window over the stored inventory. Works
  for **offline** players too, since the data lives server-side.
- Take or place items exactly like a normal chest; closing with **Esc** saves the result.
- **Confiscated items do not return** to the player on release — everything else does.
- By default the prisoner is notified which item(s) were taken, and how many, when they're
  released.

**Silent mode** (`goidajail.confiscate.silent`): flip your default with
`/goidajail confiscatesilent on`, or override per-action with the `silent` / `notify` flag on
`confiscate`. Without this permission, confiscations always notify.

**Audit log** (`goidajail.confiscate.log`): `/goidajail confiscationlog [page]` records who
took what from whom, and when — **every** confiscation, silent ones included. There is no way
to hide a confiscation from the log.

### `restoreinv` and `clearstate` — when things go wrong

These exist for corrupted-state recovery. A normal release (`release`/`pardon`, or the timer
running out) already restores everything — you should rarely need either command.

- **`restoreinv`** force-reapplies the backed-up inventory without touching jail status. Use it
  if items weren't returned on release but the backup is still intact (check `info` for
  "backup: yes").
- **`clearstate`** forcibly clears a stuck jail flag — restoring game mode and spawn point and
  teleporting the player out — but **deliberately does not touch the inventory backup**. That's
  intentional: if state is already corrupted, get the player out first, then restore items as a
  separate, deliberate step so a partial state can't clobber a good backup.

  Typical recovery: `clearstate <player>` → check `info` → `restoreinv <player>`.

---

## Baton safety

The baton is inert in the hands of anyone without `goidajail.use`:

- It **never deals damage**, even used as a plain weapon — the attack event is cancelled outright.
- Without permission, a hit is simply swallowed: nobody gets jailed, and the attacker sees
  "This item does not obey you."
- It only affects players. Mobs and other entities are ignored entirely.

So stealing or finding the baton buys an attacker nothing — the power lives in the permission
check on the wielder, not in the item. On top of that, the baton has no crafting recipe (it
can't be turned into anything), and `/goidajail baton` won't hand out a duplicate if you already
have one.

**Jailed players lose the entire `/goidajail` command tree** — even with `goidajail.use` — the
moment they're arrested, and it comes back on release. A jailed moderator can't free themselves
or anyone else. Separately, **you can never target yourself**: `jail`, `release`, `pardon`,
`time`, `addtime`, `clearstate`, `restoreinv`, `clearoffenses`, and `releaseoffline` all reject
self-application. Console and command blocks are exempt from both restrictions.

---

## Permissions

Each right is an independent NeoForge permission node — none implies another:

| Node | Grants |
|---|---|
| `goidajail.use` | The baton, and the core commands (`jail`, `release`, `pardon`, `info`, ...) |
| `goidajail.confiscate` | Opening a prisoner's confiscation inventory |
| `goidajail.confiscate.silent` | Confiscating without notifying + toggling silent mode |
| `goidajail.confiscate.log` | Viewing the confiscation audit log |

`goidajail.use` does **not** imply confiscation rights; `goidajail.confiscate` does **not**
imply silent mode or log access. Grant exactly what each role needs.

- **No permission mod installed:** nodes fall back to the vanilla op-level check — op level 2
  by default (`/op <name>`, or tune `batonPermissionLevel`).
- **With LuckPerms or another permission mod:** an operator can grant a node to any player,
  opped or not:

  ```
  /lp user <name> permission set goidajail.use true
  /lp user <name> permission set goidajail.confiscate true
  /lp user <name> permission set goidajail.confiscate.silent true
  /lp user <name> permission set goidajail.confiscate.log true
  ```

  Or to a whole group:

  ```
  /lp group moderator permission set goidajail.use true
  ```

Console and command blocks are always checked against their own permission level directly.

### Hybrid cores (Mohist / Arclight / Banner) with a LuckPerms plugin

NeoForge's `PermissionAPI` and the Bukkit permission system are separate worlds. If LuckPerms
is installed as a **Bukkit plugin** rather than a mod, grants made there never reach
`PermissionAPI`. GoidaJail bridges this gap on hybrid cores by also checking Bukkit permissions
directly:

| Setup | Works? |
|---|---|
| LuckPerms as a **mod** (NeoForge) | Yes — via `PermissionAPI` |
| LuckPerms as a **plugin** (Bukkit, hybrid core) | Yes — via the Bukkit bridge |
| No permission mod at all | Yes — falls back to op level |

On a plain NeoForge server (no Bukkit layer present) the bridge is simply inactive.

---

## Building from source

```bash
git clone https://github.com/Yukovsky/GoidaJail.git
cd GoidaJail
./gradlew build
```

Output: `build/libs/goidajail-<version>.jar`. Requires Java 21.

---

## License

[Apache License 2.0](LICENSE)
