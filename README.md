# IdleRewards
AFK rewards and AFK zones for Paper 1.21+/26.x — pay idle players on a timer, with daily caps and permission multipliers.

Players who stop moving are counted as AFK and earn a randomly picked reward every few minutes. Rewards are plain console commands, so anything works: an economy payout, items, crate keys, ranks. Weighted chances, a daily cap per player to keep AFK farms in check, `idlerewards.multiplier.<n>` so ranks earn more, and an optional zone requirement — rewards only pay inside AFK areas you define in-game with `/ir pos1`, `/ir pos2`, `/ir zone create <name>`. Progress shows in the action bar or a boss bar. MiniMessage and legacy `&` texts, zero dependencies.

Commands: `/ir status` (players) · `/ir reload`, `/ir pos1`, `/ir pos2`, `/ir zone create|remove|list` (admin)

Permissions: `idlerewards.use` (default: true) · `idlerewards.admin` (op) · `idlerewards.multiplier.2` … `.10`

## Tested on
Paper 1.21.11 and Paper 26.2 (runtime-tested, not just "it loads").

## License
MIT — see [LICENSE](LICENSE).

## Development note
This project is **AI-assisted**: the code is written with Claude under the direction, testing and
release approval of the maintainer. Every release is run against a live Paper server before it ships.
