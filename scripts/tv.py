#!/usr/bin/env python3
"""Install XMB Home on a Fire TV and keep a device-specific rollback journal."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

PACKAGE = "local.xmb.home"
SERVICE = PACKAGE + "/" + PACKAGE + ".HomeRedirectService"
SERVICE_NAMES = {SERVICE, PACKAGE + "/.HomeRedirectService"}
OLD_SERVICES = {
    "com.homestead.launcher/com.homestead.launcher.HomeRedirectService",
    "com.homestead.launcher/.HomeRedirectService",
}
READ_LOGS = "android.permission.READ_LOGS"
CLEANUP_PACKAGES = (
    "com.amazon.shoptv.client",
    "com.amazon.shoptv.firetv.client",
    "com.amazon.minitv.android.app",
    "com.amazon.gamehub",
    "com.amazon.tahoe",
    "com.amazon.storm.lightning.tutorial",
    "com.amazon.tmm.tutorial",
    "com.amazon.tv.democontent.provider",
)
AUTOPLAY_SETTINGS = {
    "com.amazon.tv.settings.featured_item_video_enabled": "0",
    "com.amazon.tv.settings.featured_item_video_volume": "0.0",
}
ROOT = Path(__file__).resolve().parent.parent


class SetupError(Exception):
    pass


def apk_package(path):
    """Read only the manifest's package attribute; no APK code is executed."""
    try:
        with zipfile.ZipFile(path) as archive:
            entry = archive.getinfo("AndroidManifest.xml")
            if entry.file_size > 2 * 1024 * 1024:
                raise ValueError("manifest is too large")
            data = archive.read(entry)
        if data.lstrip().startswith(b"<"):
            import xml.etree.ElementTree as ET
            return ET.fromstring(data).get("package")
        strings = []
        offset = 8
        while offset + 8 <= len(data):
            kind, header, size = struct.unpack_from("<HHI", data, offset)
            if size < header or size < 8 or offset + size > len(data):
                raise ValueError("invalid binary manifest chunk")
            if kind == 1:  # Android resource string pool.
                count, _, flags, start = struct.unpack_from("<IIII", data, offset + 8)
                for index in range(count):
                    pos = offset + start + struct.unpack_from("<I", data, offset + header + index * 4)[0]
                    if flags & 0x100:
                        # UTF-8 strings carry UTF-16 length, then byte length.
                        pos += 2 if data[pos] & 0x80 else 1
                        length = data[pos]
                        pos += 1
                        if length & 0x80:
                            length = ((length & 0x7f) << 8) | data[pos]
                            pos += 1
                        strings.append(data[pos:pos + length].decode("utf-8"))
                    else:
                        length = struct.unpack_from("<H", data, pos)[0]
                        pos += 2
                        if length & 0x8000:
                            length = ((length & 0x7fff) << 16) | struct.unpack_from("<H", data, pos)[0]
                            pos += 2
                        strings.append(data[pos:pos + length * 2].decode("utf-16le"))
            elif kind == 0x102 and strings:  # Start-element attribute extension.
                name = struct.unpack_from("<I", data, offset + 20)[0]
                if strings[name] == "manifest":
                    start, width, count = struct.unpack_from("<HHH", data, offset + 24)
                    for index in range(count):
                        attr = offset + 16 + start + index * width
                        name, raw = struct.unpack_from("<II", data, attr + 4)
                        if strings[name] == "package":
                            value = raw if raw != 0xffffffff else struct.unpack_from("<I", data, attr + 16)[0]
                            return strings[value]
            offset += size
    except (OSError, ValueError, KeyError, IndexError, struct.error, zipfile.BadZipFile) as error:
        raise SetupError("Cannot read APK manifest: " + str(error)) from error
    raise SetupError("APK has no manifest package attribute.")


class Adb:
    def __init__(self, executable, address, dry_run=False):
        self.executable = executable
        self.address = address
        self.dry_run = dry_run

    def run(self, args, write=False, targeted=True):
        command = [self.executable] + (["-s", self.address] if targeted else []) + list(args)
        if write:
            print(("Would run: " if self.dry_run else "Running: ") + shlex.join(command), flush=True)
            if self.dry_run:
                return ""
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=90 if "install" in args else 30)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise SetupError("ADB command failed: " + str(error)) from error
        output = (result.stdout + result.stderr).strip()
        if result.returncode or "Failure [" in output or "Error:" in output or "Security exception:" in output:
            if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output:
                raise SetupError("The installed XMB Home uses a different signing key. It was not removed. Use an APK signed by the same key, or deliberately remove the existing app after saving anything you need.")
            raise SetupError(output or "ADB command failed.")
        return result.stdout.strip()

    def connect(self):
        self.run(["connect", self.address], targeted=False)
        if self.run(["get-state"]) != "device":
            raise SetupError("TV is not authorized. Accept its debugging prompt, then try again.")

    def read(self, *args):
        # adb shell joins arguments; quote the remote command independently.
        return self.run(["shell", shlex.join([str(arg) for arg in args])])

    def write(self, *args):
        return self.run(["shell", shlex.join([str(arg) for arg in args])], write=True)

    def install(self, path):
        return self.run(["install", "-r", str(path)], write=True)

    def uninstall(self):
        return self.run(["uninstall", PACKAGE], write=True)


def services(value):
    return [item for item in (value or "").split(":") if item and item != "null"]


class Setup:
    def __init__(self, adb, state_path=None):
        self.adb = adb
        serial = adb.read("getprop", "ro.serialno").strip()
        if not serial or serial.lower() in ("unknown", "null"):
            raise SetupError("Cannot identify this TV: ro.serialno is empty. Nothing was changed.")
        self.device_hash = hashlib.sha256(serial.encode()).hexdigest()
        self.path = Path(state_path) if state_path else ROOT / ".local" / "state" / (self.device_hash + ".json")
        self.state = None
        if self.path.exists():
            try:
                self.state = json.loads(self.path.read_text(encoding="utf-8"))
            except (OSError, ValueError) as error:
                raise SetupError("Cannot read rollback journal: " + str(error)) from error
            if self.state.get("schema") != 1 or self.state.get("device_serial_sha256") != self.device_hash:
                raise SetupError("Rollback journal does not match this TV. Nothing was changed.")

    def save(self):
        if self.adb.dry_run:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=".state-", suffix=".tmp", dir=self.path.parent)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(self.state, stream, indent=2)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temporary, 0o600)
            os.replace(temporary, self.path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def setting(self, namespace, key):
        value = self.adb.read("settings", "get", namespace, key)
        return None if value == "null" else value

    def put_setting(self, namespace, key, value):
        if value is None:
            self.adb.write("settings", "delete", namespace, key)
        else:
            self.adb.write("settings", "put", namespace, key, value)

    def package(self, name):
        present = "package:" + name in self.adb.read("pm", "list", "packages", "--user", "0", name).splitlines()
        if not present:
            return None
        dump = self.adb.read("dumpsys", "package", name)
        enabled = re.search(r"User 0:.*?\benabled=(\d+)", dump)
        return {"enabled": int(enabled.group(1)) if enabled else None,
                "read_logs": bool(re.search(re.escape(READ_LOGS) + r": granted=true\b", dump))}

    def apply(self, key, kind, before, after, action):
        changes = self.state["changes"]
        if key not in changes:
            changes[key] = {"kind": kind, "before": before, "after": after, "status": "prepared"}
        else:
            changes[key].update(after=after, status="prepared")
        self.save()  # Journal before any command that may partially succeed.
        action()
        changes[key]["status"] = "applied"
        self.save()

    def install(self, apk, cleanup=False):
        apk = Path(apk).resolve()
        if apk_package(apk) != PACKAGE:
            raise SetupError("This APK is not XMB Home (" + PACKAGE + "). Nothing was changed.")
        try:
            sdk = int(self.adb.read("getprop", "ro.build.version.sdk"))
        except ValueError as error:
            raise SetupError("Cannot read this TV's Android version.") from error
        if sdk < 22:
            raise SetupError("This launcher requires Android API 22 or later.")
        current_services = services(self.setting("secure", "enabled_accessibility_services"))
        if OLD_SERVICES.intersection(current_services):
            raise SetupError("The original Homestead Home redirect is enabled. Turn off that accessibility service first; this installer will not run two competing Home redirects.")
        current_app = self.package(PACKAGE)
        if not self.state or self.state.get("status") == "restored":
            self.state = {"schema": 1, "device_serial_sha256": self.device_hash,
                          "package_before": current_app, "service_present_before": bool(SERVICE_NAMES.intersection(current_services)),
                          "status": "installing", "changes": {}}
        self.state["status"] = "installing"
        self.state["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        self.apply("app", "app", bool(current_app), True, lambda: self.adb.install(apk))
        if current_app and current_app["enabled"] in (2, 3, 4):
            self.apply("package:" + PACKAGE, "package", current_app["enabled"], 1,
                       lambda: self.adb.write("pm", "enable", "--user", "0", PACKAGE))
        grant_before = self.state["package_before"] or {"read_logs": False}
        if not current_app or not current_app["read_logs"]:
            self.apply("read_logs", "permission", grant_before["read_logs"], True,
                       lambda: self.adb.write("pm", "grant", PACKAGE, READ_LOGS))
        wanted = current_services if SERVICE_NAMES.intersection(current_services) else current_services + [SERVICE]
        self.apply("services", "setting", self.setting("secure", "enabled_accessibility_services"), ":".join(wanted),
                   lambda: self.put_setting("secure", "enabled_accessibility_services", ":".join(wanted)))
        self.apply("accessibility", "setting", self.setting("secure", "accessibility_enabled"), "1",
                   lambda: self.put_setting("secure", "accessibility_enabled", "1"))
        if cleanup:
            for name in CLEANUP_PACKAGES:
                info = self.package(name)
                if info and info["enabled"] in (0, 1):
                    self.apply("package:" + name, "package", info["enabled"], 3,
                               lambda name=name: self.adb.write("pm", "disable-user", "--user", "0", name))
            for key, value in AUTOPLAY_SETTINGS.items():
                self.apply("global:" + key, "setting", self.setting("global", key), value,
                           lambda key=key, value=value: self.put_setting("global", key, value))
        self.adb.write("am", "start", "-n", PACKAGE + "/.MainActivity")
        self.state["status"] = "installed"
        self.save()
        print("Dry run complete; no changes made." if self.adb.dry_run else "XMB Home installed. Check Home, then turn ADB Debugging off on the TV.")
        print("Rollback journal: " + str(self.path))

    def restore(self):
        if not self.state:
            raise SetupError("No rollback journal for this TV. Use --state to select a saved journal; no changes made.")
        if self.state.get("status") == "restored":
            print("This journal is already restored; nothing to do.")
            return
        self.state["status"] = "restoring"
        self.save()
        changes = self.state["changes"]
        # Remove only our newly added service; keep unrelated or later services.
        if "services" in changes and not self.state["service_present_before"]:
            current = services(self.setting("secure", "enabled_accessibility_services"))
            remaining = [item for item in current if item not in SERVICE_NAMES]
            if remaining != current:
                self.put_setting("secure", "enabled_accessibility_services", ":".join(remaining) or None)
            changes["services"]["status"] = "restored"
            self.save()
        if "accessibility" in changes:
            change = changes["accessibility"]
            remaining = services(self.setting("secure", "enabled_accessibility_services"))
            if not remaining and self.setting("secure", "accessibility_enabled") == change["after"]:
                self.put_setting("secure", "accessibility_enabled", change["before"])
            change["status"] = "restored"
            self.save()
        for key, change in reversed(list(changes.items())):
            if change.get("status") == "restored":
                continue
            if key.startswith("global:"):
                name = key.split(":", 1)[1]
                if self.setting("global", name) == change["after"]:
                    self.put_setting("global", name, change["before"])
                else:
                    print("Keeping a later change to " + name)
            elif key.startswith("package:"):
                name = key.split(":", 1)[1]
                info = self.package(name)
                if info and info["enabled"] == change["after"]:
                    command = {0: "default-state", 1: "enable", 2: "disable", 3: "disable-user", 4: "disable-until-used"}[change["before"]]
                    self.adb.write("pm", command, "--user", "0", name)
                elif info:
                    print("Keeping a later package-state change to " + name)
            elif key == "read_logs" and self.state["package_before"] and not change["before"]:
                info = self.package(PACKAGE)
                if info and info["read_logs"]:
                    self.adb.write("pm", "revoke", PACKAGE, READ_LOGS)
            elif key == "app" and not self.state["package_before"] and self.package(PACKAGE):
                self.adb.uninstall()
            change["status"] = "restored"
            self.save()
        self.adb.write("am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME", "-p", "com.amazon.tv.launcher")
        self.state["status"] = "restored"
        self.save()
        if self.state["package_before"]:
            print("Pre-existing XMB Home was kept installed. This restores settings, not its previous APK version or app data.")
        print("Dry run complete; no changes made." if self.adb.dry_run else "Saved changes restored. Turn ADB Debugging off on the TV.")

    def status(self):
        info = self.package(PACKAGE)
        selected = services(self.setting("secure", "enabled_accessibility_services"))
        print("XMB Home: " + ("installed" if info else "not installed"))
        print("Home redirect: " + ("enabled" if SERVICE_NAMES.intersection(selected) else "off"))
        print("READ_LOGS: " + ("granted" if info and info["read_logs"] else "not granted"))
        print("Rollback journal: " + (str(self.path) if self.state else "none for this TV"))
        if self.state:
            print("Journal status: " + self.state["status"])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("install", "restore", "status"):
        sub = commands.add_parser(name)
        sub.add_argument("--address", required=True, help="TV network address, for example TV_IP:5555")
        sub.add_argument("--adb", help="Path to adb; otherwise use adb on PATH")
        sub.add_argument("--state", type=Path, help="Override the device-specific rollback-journal path")
        if name != "status":
            sub.add_argument("--dry-run", action="store_true", help="Read the TV and print planned commands without changing it or saving state")
        if name == "install":
            sub.add_argument("--apk", type=Path, required=True)
            sub.add_argument("--cleanup", action="store_true", help="Also disable eight optional Amazon packages and home autoplay; save their previous state")
    args = parser.parse_args(argv)
    if args.address.startswith("-") or any(char.isspace() for char in args.address):
        parser.error("--address must be a TV hostname/IP and optional port, without spaces")
    executable = args.adb or shutil.which("adb")
    if not executable:
        parser.error("ADB not found. Install Android SDK Platform-Tools and add adb to PATH, or pass --adb.")
    try:
        adb = Adb(str(executable), args.address, getattr(args, "dry_run", False))
        adb.connect()
        setup = Setup(adb, args.state)
        if args.command == "install":
            setup.install(args.apk, args.cleanup)
        elif args.command == "restore":
            setup.restore()
        else:
            setup.status()
    except (SetupError, OSError) as error:
        print("Error: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
