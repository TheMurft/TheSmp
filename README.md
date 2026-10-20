# TheSmp 🌍

<div align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.x-62B47A?style=for-the-badge&logo=minecraft&logoColor=white" alt="Minecraft 1.21.x">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Platform-Paper%20%7C%20Spigot-5865F2?style=for-the-badge" alt="Paper and Spigot">
  <img src="https://img.shields.io/badge/Developer-TheMurft-F1C40F?style=for-the-badge" alt="Developer: TheMurft">
</div>

---

TheSmp is an all-in-one SMP plugin for homes, teleports, player interaction, moderation, clans, and server utilities. Messages and menus can be customized, with English, Spanish, French, and Portuguese language files included.

## ✨ Features

- 🏠 **Homes and warps:** Save homes, set public warps, and configure home limits with permissions.
- 🧭 **Teleport tools:** TPA requests, random teleport, spawn, and return-to-previous-location commands.
- 🤝 **Player features:** Player trades, friends, private messages, reply, and ignore controls.
- 🛡️ **Moderation:** Reports, warnings, mutes, and player history.
- 🏰 **Clans:** Clan management, clan homes, bank, levels, and leaderboards.
- 🎁 **Progression:** Daily rewards, quests, and player profiles.
- 🪟 **Menus:** Configurable inventory menus and warp menu.
- 🌐 **Localization:** English, Spanish, French, and Portuguese messages.

## 🚀 Quick start

1. Build the plugin with `mvn clean package`, or use a compatible TheSmp JAR.
2. Put the JAR in the server's `plugins/` folder.
3. Start the server and edit the generated files in `plugins/TheSmp/`.

### Requirements

- Paper or Spigot 1.21.x
- Java 21

## 🎮 Commands

| Command | Description |
| :--- | :--- |
| `/home`, `/sethome`, `/homes`, `/delhome` | Manage player homes. |
| `/tpa`, `/tphere`, `/tpallow`, `/tpdeny`, `/rtp` | Request or perform teleports. |
| `/spawn`, `/setspawn`, `/back`, `/sit` | Spawn, return, and utility actions. |
| `/trade <player>` | Start a player trade. |
| `/report`, `/reports` | Submit and review reports. |
| `/clan`, `/clanhome`, `/clanbank`, `/clanlevel`, `/clantop` | Clan features. |
| `/friend`, `/friends`, `/msg`, `/reply`, `/ignore` | Social and chat tools. |
| `/warp`, `/warps`, `/setwarp`, `/delwarp` | Manage and use warps. |
| `/warn`, `/mute`, `/history` | Moderation tools. |
| `/profile`, `/daily`, `/rewards`, `/quests` | Player progression. |
| `/menu`, `/lang`, `/langg`, `/thesmp` | Menus, language, and plugin settings. |

Permissions can be adjusted in the server's permission manager. The plugin descriptor includes granular nodes such as `thesmp.homes.*`, `thesmp.moderation.*`, `thesmp.clan.*`, and `thesmp.admin`.

## ⚙️ Configuration

- `config.yml` — General settings and feature options.
- `lang/` — Localized messages.
- `menus.yml` — Menu contents and actions.
- `warps-menu.yml` — Warp menu appearance and layout.

## 🧰 Build from source

Requires Java 21 and Maven.

```bash
mvn clean package
```

The plugin JAR is created in `target/`.

## 💬 Support

For questions or help, join the [Discord support server](https://discord.gg/6C5t995jC6).

---

Made with ❤️ by [TheMurft](https://github.com/TheMurft).
