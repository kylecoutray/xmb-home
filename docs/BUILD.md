# Build

The Android launcher builds with command-line SDK tools. No Gradle, private registry, or signing account is required.

## Requirements

- JDK 11 or later; CI uses JDK 17.
- [Android SDK command-line tools](https://developer.android.com/tools).
- Android platform 34 and Build Tools 36.1.0.

Install the pinned SDK components with `sdkmanager`:

```sh
sdkmanager "platforms;android-34" "build-tools;36.1.0" "platform-tools"
```

Set `ANDROID_SDK_ROOT` to your SDK folder. `ANDROID_HOME` is also accepted. Put Java on PATH or set `JAVA_HOME`.

## Compile

macOS/Linux:

```sh
bash build.sh
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

Output: **xmb-home.apk**. The first build creates `.local/signing/development.jks`, which later builds reuse. Its alias is `xmbhome` and its development-only password is `android`. Keep `.local` out of Git.

A development build cannot replace the published release APK because its signing certificate is different. Use the release APK for normal installs and upgrades. The setup tool reports a certificate mismatch instead of uninstalling an existing app.

For an externally managed signing key, set `HOMESTEAD_KEYSTORE`, `HOMESTEAD_KEYPASS`, and optionally `HOMESTEAD_KEYALIAS` (default `homestead`). These inherited variable names are retained for build compatibility. Passwords are passed to `apksigner` through the environment. Never commit signing material.

## Check

```sh
python3 -m unittest discover -s tests -v
```

The setup tests use mocked ADB responses and do not connect to a television. The GitHub build workflow runs tests and builds on Ubuntu and Windows, then uploads development-signed artifacts. CI does not publish releases or use the release signing key.

## Find your way

| Path | Purpose |
| --- | --- |
| `src/com/homestead/launcher/` | Native Java UI and retained Homestead host; package is `local.xmb.home` |
| `assets/xmb/` | Waves, font, monochrome app marks, sounds, and notices |
| `res/` | Android resources and strings |
| `scripts/tv.py` | Install, inspect, and restore from a computer |
| `tests/` | Setup-tool regression tests |
| `tools/` | Build-time artwork generation and original HBO vector |

The XMB-specific entry points are `XmbView`, `XmbOptions`, and the thin `HomeGrid` host. `HomeRedirectService` handles the early overlay and selection handoff to `MainActivity`. The legacy Java folder name preserves upstream layout; package declarations and manifest authorities use `local.xmb.home`.

The UI uses native 1920×1080 coordinates. Background videos/stills are 1280×720; loops are 24 fps, video-only H.264, and are stored uncompressed in the APK for `AssetFileDescriptor` access. Normal navigation uses a finite 180 ms animator; folding uses 200 ms. The focused Home window alone owns the wave decoder.

Build tools regenerate `res/drawable-nodpi/` and `assets/landscape_drawn_*`. Asset provenance is in [XMB-ASSETS.md](../XMB-ASSETS.md); retained landscape sources are in [LANDSCAPES.md](../LANDSCAPES.md).
