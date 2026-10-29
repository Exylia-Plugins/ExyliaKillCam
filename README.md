# ExyliaKillCam

Free, open-source killcam for Paper and Folia. After you die, you watch the last seconds of the fight again through your killer's eyes: the last moment slows down, the camera cuts to your body as it falls, and the killer's kill effect plays once more, for you alone. Then you are back where you respawned, with a line saying who killed you, with what, and how many hearts they had left.

No resource pack, no client mod: the killcam is built from what the server already knows.

## Requirements

- Paper or Folia 1.21.4 or newer, Java 21
- [ExyliaLib](https://modrinth.com/plugin/exylialib) ([GitHub releases](https://github.com/DiGround-s/ExyliaLib/releases)). Install it first; without it the plugin stays off and says where to get it.
- [PacketEvents](https://modrinth.com/plugin/packetevents): the bodies in a killcam are packets.
- Optional: [ExyliaKillEffect](https://builtbybit.com/resources/exyliakilleffect) 1.9.0 or newer, to see the killer's kill effect again at the end.

## Install

1. Download `ExyliaKillCam.jar` from the [latest release](https://github.com/Exylia-Plugins/ExyliaKillCam/releases/latest).
2. Put it in `plugins/` and restart the server.

## How it works

ExyliaLib keeps the last seconds around every player in memory. When a player is killed, the seconds before the death are cut out of it, laid down on ExyliaLib's temporary world with the ground they were fought on, and played back to the victim once they respawn. The real world is never touched, and the fight goes on for everybody else.

A death another plugin is in charge of (a duel, an event, a match, an FFA arena, anything that holds the player or takes over what they drop) waits until that plugin lets the player go, and plays then. Deaths those plugins cancel count too. While somebody watches, they are held themselves, so nothing else picks them up halfway.

## Updates

ExyliaLib checks this plugin's GitHub releases and announces a new version in the console and to admins. Nothing is downloaded on its own: run `/exylialib plugins` to download the update, which is applied on the next restart. Tune it under `plugin-updates` in `plugins/ExyliaLib/config.yml`.

## Configuration

`config.yml`:

- `killcam.default-mode`: what players get until they choose: `AUTO` (starts by itself), `ASK` (a clickable line in chat) or `OFF`.
- `killcam.seconds-before`: how much of the fight is shown before the death.
- `killcam.slow-motion-seconds` and `killcam.slow-motion-speed`: the slow motion before the death.
- `killcam.first-person`: through the killer's eyes, or from behind the victim.
- `killcam.hold-seconds`: how long the camera stays on the body.
- `killcam.auto-respawn`: players in `AUTO` skip the death screen.
- `killcam.rewatch-minutes`: how long `/killcam` can still show the last death.
- `deaths.mobs`, `deaths.disabled-worlds`, `deaths.skip-held-players`: which deaths get one.
- `recording.radius`, `recording.hide-vanished`: what the killcam shows around the victim.
- `kill-effects.replay`: the killer's ExyliaKillEffect effect at the end.

Messages and the hotbar live in `lang/<code>/` (English, Spanish, Portuguese, French, Russian and Turkish); `language` in `config.yml` picks one, or `default` follows ExyliaLib.

## Commands

| Command | Permission | |
| --- | --- | --- |
| `/killcam` | `exyliakillcam.use` | Watches your last death again |
| `/killcam skip` | `exyliakillcam.use` | Leaves the killcam (also the button on the hotbar) |
| `/killcam mode <auto\|ask\|off>` | `exyliakillcam.use` | What you get after dying; also on ExyliaLib's `/settings` |
| `/killcam reload` | `exyliakillcam.admin` | Reloads the config, messages and hotbar |

## Building

```
./gradlew build
```

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[GNU General Public License v3.0](LICENSE).
