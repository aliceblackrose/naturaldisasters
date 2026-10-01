# Natural Disasters

A Paper 26.1 survival-round plugin inspired by the disaster loop in Roblox's
Natural Disaster Survival. It is an original implementation for Minecraft and
does not include Roblox code or assets.

## Gameplay

The plugin runs repeating rounds:

1. Intermission while players prepare.
2. A random disaster is announced with a survival hint.
3. Players get a short warning countdown.
4. The disaster runs for the configured duration.
5. Players who die during the round are eliminated and survivors are counted.

Included disasters:

- **Earthquake** - repeated horizontal knockback and chip damage.
- **Acid Rain** - exposed players take damage; solid overhead cover protects them.
- **Lightning Storm** - exposed survivors are targeted by lightning.
- **Meteor Shower** - explosions land near randomly selected survivors.
- **Flash Flood** - a virtual flood line rises throughout the round; climb upward.
- **Tornado** - moving funnels pull and lift nearby survivors.

Terrain destruction is off by default. Set `disasters.block-damage: true` if you
want meteor explosions and lightning to affect the map.

## Commands

- `/disaster status` - show the current phase, timer, and survivor count.
- `/disaster list` - show disasters enabled for random selection.
- `/disaster start <type>` - immediately start a specific disaster (op/admin).
- `/disaster stop` - stop the current warning or round (op/admin).
- `/disaster reload` - reload `config.yml` (op/admin).

The admin permission is `naturaldisasters.admin` and defaults to operators.

## Configuration

Edit `plugins/NaturalDisasters/config.yml` to change round timers, minimum
players, eligible worlds, disaster damage, intervals, radius/strength, and block
damage behavior. Leaving `rounds.worlds` empty enables the plugin in every world.

## Development

```bash
./gradlew build
./gradlew spotlessApply
./gradlew runServer
```

The project targets Java 25 and Paper 26.1.

## License

GPL-3.0. See `LICENSE`.
