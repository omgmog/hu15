# Honda Permissions entry for hu15

The unit only runs apps listed in its process-control whitelist; the community "Honda Permissions" app edits it. The entry that works for `hu.hu15`:

| Field | Value | Why |
|---|---|---|
| Auth type | THIRD_PARTY | |
| App type | **NAVI** | With OTHER the unit blanks the screen under its driving restriction. NAVI is exempt. |
| Sound out | ON | Exclusive audio: the unit's other sources (radio, USB) shut off while the app is open, which is what Android Auto wants. |
| Last mode | ON | The unit relaunches the app after a restart; hu15 then reconnects by itself. |
| Sound interrupt / interrupt mute | OFF | Interrupt plays over the current source from the driver's speaker only (like the stock satnav voice); interrupt mute silences that source meanwhile. |

Flag meanings are from a community install guide for this family of units.
