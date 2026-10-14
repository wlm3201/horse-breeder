# Horse Breeder

[简体中文](README.md) | English

A Minecraft Fabric client-side mod that automatically tames, breeds, grows up and culls horses,
iterating until it has horses whose speed, jump height, health, coat and markings all match the
targets you set.

## Usage

1. Prepare a 10×10 pen, two horses, and plenty of carrots, golden carrots and an axe.
2. Set the target count, the three stat targets and the target coats in ModMenu.
3. Press **B** to start breeding.

### General options

| Option                  | Description                                                                  |
| ----------------------- | ---------------------------------------------------------------------------- |
| Enabled                 | Master switch, the same one the keybinding flips                             |
| Speed up growth         | Feed carrots to foals so they grow up faster                                 |
| Population cap          | Horses kept in the pen at once; the worst get culled beyond this             |
| Target count            | How many finished horses are needed; breeding stops once reached             |
| Detection range         | Only horses within this range are counted and handled                        |
| Speed target            | Minimum speed of a finished horse, in blocks per second                      |
| Jump target             | Minimum jump height of a finished horse, in blocks                           |
| Health target           | Minimum health of a finished horse, in half hearts                           |
| Feed to tame            | Feed carrots to speed up taming                                              |
| Target temper           | Feed until this temper value is reached, then start riding to tame           |
| Auto restock            | Automatically take items from containers to refill the inventory             |
| Golden carrot container | Sneak + left click a container while holding a golden carrot; format `x y z` |
| Carrot container        | Sneak + left click a container while holding a carrot; format `x y z`        |
| Appearance filter       | Breed [target count] horses per coat; select none for no coat limit          |

### Advanced options

| Option                  | Description                                          |
| ----------------------- | ---------------------------------------------------- |
| Interaction reach       | Maximum interaction distance                         |
| Pairing caution         | How close the offspring is pushed to the parents     |
| Auto eat                | Eat a golden carrot when the food bar is not full    |
| Walk to targets         | Walk up to targets that are out of interaction reach |
| Protect named horses    | Named horses are never culled                        |
| Critical hits           | Jump before attacking to land a critical hit         |
| Ignore pushing          | Ignore collision pushing from horses                 |
| Drop leather            | Automatically drop leather from the inventory        |
| Drop direction          | Yaw used when dropping leather                       |
| Show HUD                | Whether to draw the HUD                              |
| Glowing markers         | Outline horses according to their state              |
| HUD horizontal position | Pixels from the left edge of the screen              |
| HUD vertical position   | Pixels from the top edge of the screen               |
| Diagnostic logging      | Turn it on when investigating a problem              |

### Glow marker colours

| Colour  | Meaning                     |
| ------- | --------------------------- |
| Yellow  | Being acted on              |
| Magenta | Pair being bred             |
| Gold    | Finished                    |
| Purple  | Candidate for the next pair |
| Orange  | Cull candidate              |
| Cyan    | Foal                        |
| Green   | Ready to breed              |
| Red     | Cooling down                |
| Grey    | Untamed / hurt / mounted    |

## HUD

```
Breeder [On]  Data: Exact
Cap 20  Adults 8  Ready 1  Cooling 4  In love 2  Untamed 1  Foals 2
```

**Exact**: singleplayer, read straight from the integrated server.
**Estimated**: multiplayer, inferred from what we fed and which events we saw.

## Building

```powershell
.\gradlew.bat build

.\gradlew.bat :v26_3:runClient
```
