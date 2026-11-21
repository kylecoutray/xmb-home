# XMB Home

Apply karpathy-guidelines to repository work. Keep the README concise and put setup/build detail in docs/.

- The app package is `local.xmb.home`; the Java source folder keeps its upstream name. Preserve provider authorities and Home handoff behavior.
- Setup is launcher-only by default. Optional cleanup must preserve prior device state and be reversible. Never hardcode a TV address or serial.
- Keep signing keys, ADB keys, `.local/`, device journals, and account data out of commits and releases.
- Preserve GPL code headers and separate asset ownership notices. Do not replace the original art with invented app branding.
- Run `python3 -m unittest discover -s tests -v` and the appropriate build before publishing code changes. Do not operate a live TV as part of automated tests.
- Release APKs use a separate signing key. Development builds must not uninstall a release to bypass a certificate mismatch.
