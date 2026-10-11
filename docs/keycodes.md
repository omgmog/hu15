# Key codes seen on the unit

The steering-menu service delivers wheel buttons as key events with large negative codes. Known so far:

| Code | Button | Confidence |
|---|---|---|
| -65535 | Wheel + (volume up) | Confirmed |
| -65534 | Wheel - (volume down) | Confirmed |
| -65533 | Wheel right (next track) | Confirmed |
| -65532 | Wheel left (previous track) | Confirmed |
| -65531 | Source (play/pause); holding repeats | Confirmed |
| -65526 | Unknown | Unconfirmed |
| -65522 | Voice / talk | Probable |

The first five are OpenDroidAuto's keymap and match what the unit sends. The other two come from logs. Some buttons never reach the app because the unit keeps them (the voice button seems to be one): the key tester shows nothing for those.

To map the rest: Advanced > Key tester, press one button, wait about four seconds, press the next, and note the order.
