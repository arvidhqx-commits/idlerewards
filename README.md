# IdleRewards
AFK rewards and AFK zones for Paper 1.21+/26.x — pay idle players on a timer, with daily caps and permission multipliers.

Players who stop moving are counted as AFK and earn a randomly picked reward every few minutes. Rewards are plain console commands, so anything works: an economy payout, items, crate keys, ranks. Weighted chances, a daily cap per player to keep AFK farms in check, `idlerewards.multiplier.<n>` so ranks earn more, and an optional zone requirement — rewards only pay inside AFK areas you define in-game with `/ir pos1`, `/ir pos2`, `/ir zone create <name>`. Progress shows in the action bar or a boss bar. MiniMessage and legacy `&` texts, zero dependencies.

Commands: `/ir status` (players) · `/ir reload`, `/ir pos1`, `/ir pos2`, `/ir zone create|remove|list` (admin)

Permissions: `idlerewards.use` (default: true) · `idlerewards.admin` (op) · `idlerewards.multiplier.2` … `.10`

## Tested on
Paper 1.21.11, 26.2 and 26.3 — a runtime test suite of 72 assertions (AFK detection, reward timing, daily cap, zones, boss bar, config names with dots) runs on all three before every release, not just "it loads".

## Changelog
**0.1.2** — three fixes found by the new test suite:
- The daily cap survives relogging and restarts (`daily.yml`). Before, `max-rewards-per-day` was reset by every rejoin, so an AFK farm could just reconnect.
- Reward names with a dot (`small.coins`) no longer turn into an empty ghost reward that paid nothing.
- Zone names with a dot (`spawn.afk`, also via `/ir zone create`) are no longer dropped on load.
No config changes needed.

## License
MIT — see [LICENSE](LICENSE).

## Development note
This project is **AI-assisted**: the code is written with Claude under the direction, testing and
release approval of the maintainer. Every release is run against a live Paper server before it ships.
