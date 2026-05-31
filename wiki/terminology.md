# Robocode Terminology — Quick Reference

## Angles & Geometry

| Term | Definition |
|------|-----------|
| **Bearing Offset** | Angle difference between head-on and actual firing direction (radians) |
| **GuessFactor (GF)** | Bearing offset normalized by MEA × lateral direction → [-1, +1] |
| **Maximum Escape Angle (MEA)** | `arcsin(8.0 / bulletSpeed)` — largest possible angular offset |
| **Lateral Velocity** | Component of velocity perpendicular to the bearing line to opponent |
| **Advancing Velocity** | Component of velocity along the bearing line (positive = approaching) |
| **Angular Velocity** | Rate of angular change of opponent relative to us (radians/tick) |

## Waves & Bullets

| Term | Definition |
|------|-----------|
| **Wave** | Expanding circle from a bullet's origin representing all possible bullet positions |
| **Wave Break** | Moment wave radius reaches target → records where target actually was (GF observation) |
| **Bullet Flight Time** | `ceil(distance / bulletSpeed)` ticks from fire to hit |
| **Bullet Speed** | `20 - 3 × power` px/tick |
| **Bullet Damage** | `4 × power` (power ≤ 1) or `6 × power - 2` (power > 1) |
| **Energy Drop** | Decrease in opponent energy between scans → indicates fire event |
| **Gun Heat** | Cooldown timer: `1 + power/5` added on fire, decreases by cooling rate (0.1/tick) |
| **Bullet Shadow** | GF range on a wave guaranteed safe because your bullet intercepts it |
| **Bullet Shielding** | Deliberately intercepting enemy bullets with your own bullets |

## Targeting Terms

| Term | Definition |
|------|-----------|
| **Visit Count Stats (VCS)** | Histogram of GF observations → fire at peak bin |
| **Segmentation** | Partitioning firing situations by features (distance, velocity, etc.) |
| **Dynamic Clustering** | KNN on logged wave observations (not real clustering) |
| **Pattern Matching** | Find similar past movement sequences → play forward to predict position |
| **Play It Forward (PIF)** | Simulate matched pattern forward in time → get predicted (x,y) |
| **Displacement Vector** | Predict position as offset from current position (alternative to GF) |
| **Virtual Guns** | Run multiple targeting algorithms in parallel, select best performer |
| **Anti-Surfer Gun** | Predicts where a wave-surfer will dodge TO, fires there |
| **Bin Smoothing** | Apply Gaussian kernel to VCS observations for better interpolation |
| **Multiple Choice** | Use top-K bins weighted by probability instead of single peak |

## Movement Terms

| Term | Definition |
|------|-----------|
| **Wave Surfing** | Move to minimize danger on incoming opponent waves |
| **True Surfing** | Per-tick forward/stop/reverse decision |
| **GoTo Surfing** | Calculate safest destination, navigate there directly |
| **Precise Prediction** | Tick-by-tick physics simulation to determine reachable positions |
| **Movement Profile** | Distribution of GFs where you actually end up on opponent waves |
| **Flattener** | Deliberate movement to create uniform (flat) GF profile |
| **Adaptive Movement** | Switch strategies based on opponent gun type detection |
| **Stop-and-Go** | Stop when opponent fires → dodge simple targeters |
| **Wall Smoothing** | Turn parallel to wall before hitting it |
| **Distancing** | Maintaining optimal distance for your gun/dodge tradeoff |
| **Dive Protection** | Counter aggressive closing by opponent |
| **Mirror Movement** | Move symmetrically to opponent (rarely effective) |
| **Minimum Risk** | Move to position with lowest aggregate danger from all threats |

## Strategy Terms

| Term | Definition |
|------|-----------|
| **Energy Management** | Choosing bullet power based on game state and expected hit rate |
| **Musashi Trick** | Fire power 0.1 bullets to flush Stop-and-Go bots |
| **Gun Heat Lock** | Lock radar on target just before gun cools → guaranteed scan at fire time |
| **Multi-Mode** | Switch between distinct operating modes based on opponent class |
| **Survivalist** | Prioritize survival points over bullet damage |
| **Escape Envelope** | Full set of reachable positions before a wave breaks |

## Radar Terms

| Term | Definition |
|------|-----------|
| **Infinity Lock** | `setTurnRadarRight(Double.POSITIVE_INFINITY)` — infinite spin |
| **Width Lock** | Narrow oscillation just wide enough to catch opponent |
| **Narrow Lock** | Minimal overshoot (1-2°) past opponent bearing |
| **Scan Arc** | Angular sweep between previous and current radar heading |
| **Oldest Scanned** | Melee radar: always scan the bot with most stale data |

## Competition Terms

| Term | Definition |
|------|-----------|
| **RoboRumble** | Distributed ranking system for 1v1 Robocode bots |
| **LiteRumble** | Lightweight variant of RoboRumble |
| **MeleeRumble** | Same for melee (multi-bot) battles |
| **Weight Class** | CodeSize categories: Nano (<250), Micro (<750), Mini (<1500), Mega (unlimited) |
| **APS** | Average Percentage Score — primary ranking metric |
| **Premier League** | Top ~30 bots in RoboRumble |
| **Survival Rate** | % of rounds bot survives (not just win rate) |

## Physics Constants

| Constant | Value |
|----------|-------|
| Max velocity | 8.0 px/tick |
| Acceleration | +1.0 px/tick² |
| Deceleration | -2.0 px/tick² |
| Max body turn | `10 - 0.75 × |velocity|` deg/tick |
| Max gun turn | 20 deg/tick |
| Max radar turn | 45 deg/tick |
| Robot size | 36×36 px (axis-aligned) |
| Battlefield default | 800×600 px |
| Radar range | 1200 px |
| Initial energy | 100 |
| Gun cooling rate | 0.1 per tick (default) |
| Initial gun heat | 3.0 |
| Wall collision damage | `max(0, abs(velocity) × 0.5 - 1)` |
| Ram damage (dealt) | 0.6 per tick of contact |
| Ram bonus (score only, not energy) | 1.2 × ram_damage |
