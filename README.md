# EasyVip (Paper Edition)

Modern, enterprise-grade VIP, Key, Reward, and Package management plugin built for **Paper / Purpur / Folia 26.2** (Java 25).

Fully backported from the EasyVip mod with 100% feature parity, zero mod dependencies, high performance, atomic persistence, and robust integrations.

---

## 🌟 Key Features

- **VIP Tier Management:**
  - Configurable VIP tiers with display names, colors, priorities, default durations, and stacking modes (`extend`, `replace`, `keep`).
  - Maximum duration caps (`max_stack_duration_seconds`).
  - Active VIP evaluation (highest priority tier or manual player choice).
  - Multi-tier lifecycle hooks (`actions_on_activate`, `actions_on_expire`, `actions_on_remove`, `actions_on_set_active`, `actions_on_unset_active`).
  - Activation items with chance rolls and rare item server-wide broadcasts.
- **Key & Physical Item System:**
  - Secure cryptographic code generator (`UniqueCodeGenerator`) with customizable prefix, length, and charset.
  - Multi-use, single-use, player-bound, and expiring keys.
  - Key types: `vip`, `reward`, `command`, `item`, `itemstack`, and `custom` (raw JSON actions).
  - Physical in-game keys (tripwire hooks or custom items) powered by Paper's `PersistentDataContainer` (PDC) with anti-duplication instance tracking. Right-click to redeem!
  - Confirmation prompts before redeeming (`/easyvip confirm`).
  - Rate limiting & cooldown protection per player.
- **Packages & Variants:**
  - Non-repeatable or cooldown-based reward packages.
  - Interactive variant choices (`/easyvip variant choose <package> <variant>`) with configurable expiration timeouts.
  - Login reminders for pending variant choices.
- **Action Scripting & Random Pools:**
  - Actions: `give_item`, `give_item_stack`, `give_experience`, `give_level`, `give_effect`, `send_message`, `broadcast_message`, `run_server_command`, `run_player_command`, `give_package`, `set_scoreboard_tag`, `remove_scoreboard_tag`, `add_to_team`, `remove_from_team`, `add_luckperms_group`, `remove_luckperms_group`, `economy_deposit`, `economy_withdraw`.
  - Scripting variables (`$var = %random(pool)%`) and placeholder resolution (`{player}`, `{tier_display}`, `{duration}`, `%player%`, etc.).
  - Weighted and uniform random pools.
  - Security command allowlist preventing unauthorized console command injections.
- **Persistence:**
  - Dual-mode persistence:
    - **JSON Mode**: Atomic file operations (`.tmp` -> target file with automatic `.bak` backup fallbacks) and background thread flushing.
    - **SQL Mode**: High-performance HikariCP connection pool supporting MySQL, MariaDB, SQLite, and H2 databases.
- **WebStore Integration:**
  - **Player Sync**: Automatic player profile & IP synchronization to Rails WebStore API.
  - **Account Linking**: `/link` challenge codes with expiration.
  - **Asynchronous Fulfillment**: Polling daemon with HMAC-SHA256 request and response signature verification, replay protection, and transactional claim/complete/fail staging.
- **Platform & Server Integrations:**
  - Kyori Adventure native component support with legacy color codes (`&a`, `&6`, etc.) and hex colors (`&#RRGGBB`).
  - **LuckPerms** API (v5.4) hooked for permission checks and automatic group inheritance.
  - **Vault** Economy API hooked for economy deposits and withdrawals.
  - **Folia** multi-threading and region scheduler support.

---

## 📋 Commands & Permissions

### Player Commands (`easyvip.use`)
| Command | Alias | Description |
|---|---|---|
| `/easyvip help` | `/vip` | Displays available commands |
| `/easyvip use <key>` | `/usekey <key>`, `/activate <key>`, `/vip <key>` | Redeems a VIP, reward, or custom key |
| `/easyvip confirm` | - | Confirms a pending key redemption |
| `/easyvip info [player]` | `/viptime [player]` | Shows remaining time for active and registered VIPs |
| `/easyvip select <tier>` | - | Selects the active VIP tier (when player selection is enabled) |
| `/easyvip variant choose <package> <variant>` | - | Selects a variant for a pending package |
| `/easyvip variant pending` | - | Lists player's pending variant choices |
| `/link` | - | Generates an account linking challenge code for the WebStore |

### Administrator Commands (`easyvip.admin`)
| Command | Description |
|---|---|
| `/easyvip admin addvip <player> <tier> <duration>` | Grants a VIP tier to an online or offline player |
| `/easyvip admin addfakevip <fake_player> <tier> <duration>` | Grants a VIP tier to a fake/virtual player identity |
| `/easyvip admin removevip <player> <tier>` | Removes a VIP tier from a player |
| `/easyvip admin generate vip <tier> <duration> [uses] [player]` | Generates a new VIP key |
| `/easyvip admin generate reward <reward_id> [uses] [player]` | Generates a reward key |
| `/easyvip admin generate command <cmd>` | Generates a key that executes a server command |
| `/easyvip admin generate item <item_id> <amount> [uses] [player]` | Generates a key that gives specific items |
| `/easyvip admin generate itemstack [uses] [player]` | Generates a key containing the item in the admin's main hand |
| `/easyvip admin generate custom <json>` | Generates a key from custom action JSON |
| `/easyvip admin givepackage <player> <package_id>` | Grants a package directly to a player |
| `/easyvip admin giveitemkey <player> <code>` | Gives a physical redeemable key item to an online player |
| `/easyvip admin audit [page]` | Displays persistent audit logs |
| `/easyvip admin webstore status` | Shows WebStore fulfillment polling status |
| `/easyvip active set <player> <tier>` | Manually overrides a player's active VIP tier |
| `/easyvip createvip <id> <display_name> [color]` | Dynamically defines a new VIP tier and updates `tiers.toml` |
| `/easyvip savevipactivation <tier>` | Saves the admin's current inventory as the activation kit for a VIP tier |
| `/easyvip key list` | Lists all registered keys |
| `/easyvip key info <code> [reveal]` | Inspects details and usages of a key |
| `/easyvip key delete <code>` | Deletes a key from storage |
| `/easyvip key cleanup` | Deletes all unused keys |
| `/easyvip package list` | Lists configured packages |
| `/easyvip package info <id>` | Shows details of a package |
| `/easyvip reload` | Hot-reloads all TOML configuration files, persistence, and services |
| `/easyvip config reload` | Reloads configurations |
| `/easyvip config validate` | Validates all configuration files for syntax or semantic errors |

---

## ⚙️ Configuration Files (TOML)

All configuration files are located under `plugins/EasyVip/`:
- `common.toml` - Core settings (keys, cooldowns, dimension restrictions, logging, allowlist).
- `messages.toml` - All player and administrative messages (supports `en-us` and `pt-br`).
- `tiers.toml` - VIP tier definitions, stacking rules, priorities, and action hooks.
- `activation_items/<tier>.toml` - Items and enchanted equipment awarded upon VIP activation.
- `packages.toml` - Package definitions, repeatability, cooldowns, and variant options.
- `reward_keys.toml` - Pre-configured reward keys and bundle definitions.
- `pools.toml` - Weighted and unweighted random pools for script actions.
- `integrations.toml` - Database (MySQL/SQLite/H2) and LuckPerms configuration.
- `webstore.toml` - WebStore API endpoint, tokens, and sync behavior.
- `fulfillment.toml` - WebStore asynchronous claim and HMAC-SHA256 validation rules.

---

## 🔨 Building

Requirements:
- Java 25 JDK
- Gradle 8+ or bundled Gradle wrapper

```bash
# Build the shaded plugin jar
./gradlew build
```

The compiled, shaded jar ready for production will be generated in:
```
build/libs/EasyVip-Paper-1.2.0.jar
```

---

## 🧪 Testing

Run the comprehensive unit and integration test suite:
```bash
./gradlew test
```

Includes 95+ automated tests covering duration parsing, TOML serialization, SQL persistence staging, HMAC fulfillment, key security, cooldowns, variant selections, and command handling.
