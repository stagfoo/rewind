# Rewind

Instant replay for Android handhelds, like the Switch's and Steam Deck's: Rewind keeps the last
15 seconds to 2 minutes of the screen (and the game's sound) in memory, and saves them as an MP4
when you ask. If you don't, they're recorded over. Made for the AYN Thor; works on any Android 11+
device.

Nothing is written to storage until you save, and the hardware video encoder does the recording,
so it's light on battery.

## Using it

1. Open Rewind and tap **Start recording**. Android asks to allow screen recording (it asks each
   time Rewind starts, e.g. after a reboot). Rewind then goes to the background.
2. Play. When something happens, **save** with any of:
   - **Save clip** in Rewind's notification
   - the **Save clip** quick settings tile (add it when editing your tiles)
   - the **Save clip** app icon: launch it from your frontend, or map it to a button
3. Clips land in `Movies/Rewind`, named by date, or after the game if you allow **usage access**
   (or over adb: `adb shell appops set com.rewind.app GET_USAGE_STATS allow`).

**Clip length:** 15 s, 30 s, 1 min or 2 min, at about 75 MB of memory per minute (1080p, 60 fps,
10 Mb/s). Changing it applies straight away.

**Game sound:** recorded from what apps play, not the microphone. Apps can opt out of being
recorded; then the clip has no sound.

Only the main (big) screen is recorded.

## Install

With [Obtainium](https://github.com/ImranR98/Obtainium): add `https://github.com/stagfoo/rewind`.
Or download the APK from [Releases](https://github.com/stagfoo/rewind/releases).

## Build and release

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease
scripts/release.sh   # bumps the version, tests, builds, checks the APK, tags and publishes
```

Releases are signed with the committed `app/debug.keystore`, so every build installs as an update
over the last one.
