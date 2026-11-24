# FilTarPro + FilTarView (by filmtarget)

Two apps in one repo.

- **FilTarPro** (`app/`): pro Camera2 filmmaking camera. Flat/Log, focus assist, LUT looks, aspect-ratio frame with bars, level meter, rack focus, gamepad, monitor extender.
- **FilTarView** (`view/`): install on a second phone/tablet. Shows FilTarPro's live view and controls it remotely (touch or PS4/PS5 controller).

## Build
Push to GitHub; Actions builds both. Download artifacts `FilTarPro-apk` and `FilTarView-apk`.

## Connect
1. In FilTarPro tap **MON** (top centre): note the address and PIN. Optionally switch Bluetooth link on.
2. Open FilTarView, pick the discovered device (or type address + PIN), Connect.
3. Wi-Fi/hotspot: both devices on the same network. Bluetooth: pair in Android settings first (slower, smaller picture).
