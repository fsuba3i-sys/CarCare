# CarCare

Android app to track maintenance and repairs for your cars (Arabic / English, dark theme).

- Car list → car page with odometer, engine-oil card, upcoming maintenance and one log (maintenance + parts) with a running total.
- Reminders by distance **or** time, whichever comes first (500 km / 30 days ahead), plus a nudge when the odometer has not been updated for 14 days.
- Photo / invoice on every record (camera or gallery).
- Backup / restore (one .zip with data and photos) and export of the log to Excel (.xlsx).
- Maintenance library: `reference/reference.json`. The app downloads it from this repository when its `version` number increases, so new brands and models do not need a new APK.

## Library format (`reference/reference.json`)

`items` (maintenance item names) · `sources` (title + URL) · `schedules` (list of `{item, km, months, conf, src}`) · `brands → models → gens → engines → schedule`.
`conf` is `official`, `secondary` or `estimated`. To publish a change, edit the file and **increase `version`**.

## Build

GitHub Actions builds the debug APK on every push and publishes it to the `apk` branch (`CarCare.apk`).

## Backlog (agreed, not yet built)

- **Auto-apply library updates to existing cars:** after a library update, replace every schedule item whose `confidence` is not `"user"` with the library value for the same item, add new library items, keep user-edited items. Bump `versionCode` (currently 2) / `versionName` (currently 1.1).
