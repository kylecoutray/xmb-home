# Security and permissions

The default setup grants only the privileges needed for Home redirection. Optional cleanup is separate and records reversible changes for the specific TV.

| Capability | Use |
| --- | --- |
| Accessibility | Observe foreground window changes for Home redirection. The service cannot retrieve window contents or filter keystrokes. |
| `READ_LOGS` | Watch system activity-start messages for Home presses. The watcher filters the system log and does not store or upload it. |
| `RECEIVE_BOOT_COMPLETED` | Open the home interface after boot. |
| Usage statistics | Optional discovery of past app launches; ordinary recent-use ordering is recorded locally. |
| Network / overlay / package-install permissions | Retained from the Homestead host. The normal XMB flow uses an accessibility overlay. Legacy ADB tunnel and updater features are disabled by the XMB defaults. |

`WRITE_SECURE_SETTINGS` is declared by the retained host but is not granted by the setup tool. The installer does not enable the app's on-device ADB client, tunnel, automatic updates, or boot cleanup. Normal XMB settings expose theme, wave, sounds, app order, and native TV settings.

The application has no telemetry or analytics. The retained, dormant updater names the original Homestead repository; automatic checks are disabled and it is not exposed in XMB's menu. Release updates are manual. The legacy tunnel connects to the TV's own ADB service only if explicitly enabled through retained advanced paths.

## Local setup state

The setup tool connects only to the address you provide. Rollback state is written under `.local/state/` and uses a hash of the TV serial to reject restoration on another device. It contains device configuration and should remain local. A dry run reads the TV but changes neither TV state nor the journal.

Enable ADB Debugging only while setting up or restoring a TV, accept authorization only for a trusted computer, then turn debugging off. The launcher works without an ongoing computer connection.

Release signing keys, ADB authorization keys, device backups, and local setup journals do not belong in this repository. Development signing keys are generated under ignored `.local/`.

## Reporting

For a security issue, use [GitHub's private reporting flow](https://github.com/kylecoutray/xmb-home/security/advisories/new). For ordinary bugs, include the model, Fire OS version, app version, and reproduction steps. Omit account information, sign-in codes, local journals, and credentials.
