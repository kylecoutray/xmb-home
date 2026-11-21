# Setup

Use the release setup ZIP to install from another computer. It contains the signed APK and the Python setup tool; source code is available in this repository. Python uses only its standard library.

## Before you start

- An Android-based Fire TV, awake on the same network as the computer.
- Python 3.9 or later: `python3` on macOS/Linux, or `py -3` on Windows.
- [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools), which supplies `adb`. Android Studio is not required for installation.

On the TV, open **Settings → My Fire TV / Device & Software → About**. If Developer Options is hidden, highlight the device name and press Select seven times. Open Developer Options and enable **ADB Debugging**. Find the TV's address under About → Network.

## Install

Unpack the release ZIP and open a terminal in that folder. Replace `TV_IP` with the TV's address:

```sh
python3 scripts/tv.py install --address TV_IP:5555 --apk xmb-home-1.0.7.apk
```

Windows PowerShell:

```powershell
py -3 scripts/tv.py install --address TV_IP:5555 --apk xmb-home-1.0.7.apk --adb "C:\path\to\platform-tools\adb.exe"
```

macOS/Linux with an unpacked copy of Platform Tools:

```sh
python3 scripts/tv.py install --address TV_IP:5555 --apk xmb-home-1.0.7.apk --adb /path/to/platform-tools/adb
```

Accept **Allow USB debugging?** on the TV for this computer. If the command reports an unauthorized connection, accept the prompt and run it again. Do not share a debugging authorization with an unfamiliar computer.

The tool installs `local.xmb.home`, grants `READ_LOGS` for the Home-button watcher, and enables its accessibility service while retaining unrelated services. It records prior settings before changing them. It does not grant `WRITE_SECURE_SETTINGS` or enable the launcher's legacy ADB tunnel/updater.

The journal lives in `.local/state/<device-hash>.json`. The raw TV serial is not stored. Keep the unpacked folder, including `.local`, for restoration; copy it locally if changing setup computers. This state is device-specific and should stay out of Git.

After installation, press Home, launch an app, and return Home. Use Menu to choose Dark or Classic Blue. Turn ADB Debugging off after checking the result; normal use does not need the computer or debugging enabled.

## Optional cleanup

Default installation changes the launcher only. Add `--cleanup` to also disable these packages when present and currently enabled:

```sh
python3 scripts/tv.py install --address TV_IP:5555 --apk xmb-home-1.0.7.apk --cleanup
```

| Package | Category |
| --- | --- |
| `com.amazon.shoptv.client` | Shopping |
| `com.amazon.shoptv.firetv.client` | Shopping |
| `com.amazon.minitv.android.app` | MiniTV |
| `com.amazon.gamehub` | Games hub |
| `com.amazon.tahoe` | Optional Amazon content |
| `com.amazon.storm.lightning.tutorial` | Tutorial |
| `com.amazon.tmm.tutorial` | Tutorial |
| `com.amazon.tv.democontent.provider` | Demo content |

Cleanup also turns off Amazon Home's featured-content autoplay video and audio. These changes were used on the verified Insignia; package availability varies. Original disabled states are retained, and rollback only restores values that still match what this tool set. Firmware updates, Amazon's launcher, Appstore, audio processing, screensaver settings, and streaming app data are outside this cleanup.

## Inspect first

```sh
python3 scripts/tv.py status --address TV_IP:5555
python3 scripts/tv.py install --address TV_IP:5555 --apk xmb-home-1.0.7.apk --cleanup --dry-run
```

Dry runs connect to and read the TV to show an accurate plan. They do not write TV settings or local journals.

## Restore

Enable ADB Debugging again, use the same local state folder, and run:

```sh
python3 scripts/tv.py restore --address TV_IP:5555 --dry-run
python3 scripts/tv.py restore --address TV_IP:5555
```

Use `--state /path/to/saved.json` to select a copied journal. The TV's serial hash must match. Unrelated accessibility services and later setting changes are preserved. The tool uninstalls XMB only when its journal recorded that it installed the app on a TV where it was absent. An already-installed XMB is left installed; restoring an older APK or its private data is outside this rollback.

Streaming applications and their accounts remain. Turn debugging off afterward.

## Updates and signing

Use release APKs to update a release installation; rerunning install keeps the original rollback baseline. Source builds use a different development certificate. Android rejects an update signed by another key, and the setup tool does not uninstall an existing app to work around that rejection.

Automatic upstream update checks are disabled in this fork. Download updates from this repository's Releases.

## Apps and limits

Install Netflix, Prime Video, Hulu, Disney+, HBO Max, Spotify, and YouTube through the TV's Amazon Appstore as available in your region. The launcher discovers installed apps. They require their own accounts or subscriptions. YouTube Music is a shortcut into YouTube; choose Music from YouTube's left menu.

The launcher was verified on Insignia NS-40F401NA26 / Fire OS 7.7.1.5. Other Android-based Fire TVs are experimental. Vega OS devices cannot run this Android build. Friends currently has an informational placeholder. The extra-Up folded view is a custom XMB adaptation.

| Symptom | Check |
| --- | --- |
| Cannot connect | Confirm the address, same network, awake TV, and ADB Debugging. Guest-network isolation can block local connections. |
| Unauthorized connection | Accept the TV's prompt for this computer, then rerun. |
| Home does not redirect | Check the XMB accessibility service, `READ_LOGS` grant, and firmware compatibility. Status reports the configuration. |
| Several seconds before an app opens after Home | Fire OS restricts activity starts briefly. The overlay covers this interval. |
| Stock screensaver while an app is idle | Home's stay-awake request applies only to Home. Other apps retain system idle behavior; setup does not change it. |
| Bluetooth audio becomes distorted | Check the native speaker connection; disconnect/reconnect it before changing EQ. Setup does not configure audio processing. |
| Another Homestead Home service is enabled | Turn that redirect off first so two launchers do not compete. Other accessibility services can stay enabled. |
