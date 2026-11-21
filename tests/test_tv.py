"""Device-free checks for installation, journal recovery, and scoped rollback."""

import copy
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from contextlib import redirect_stdout

SPEC = importlib.util.spec_from_file_location("tv_setup", Path(__file__).resolve().parents[1] / "scripts" / "tv.py")
tv = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tv)


class FakeAdb:
    def __init__(self, dry_run=False):
        self.dry_run = dry_run
        self.serial = "fixture-device"
        self.settings = {("secure", "enabled_accessibility_services"): "other.app/.Reader",
                         ("secure", "accessibility_enabled"): "1"}
        self.packages = {}
        self.commands = []
        self.fail_after = None

    def read(self, *args):
        if args == ("getprop", "ro.serialno"):
            return self.serial
        if args == ("getprop", "ro.build.version.sdk"):
            return "28"
        if args[:2] == ("settings", "get"):
            return self.settings.get(tuple(args[2:]), "null")
        if args[:3] == ("pm", "list", "packages"):
            name = args[-1]
            return "package:" + name if name in self.packages else ""
        if args[:2] == ("dumpsys", "package"):
            info = self.packages[args[2]]
            return "User 0: installed=true enabled={}\n{}: granted={}\n".format(info["enabled"], tv.READ_LOGS, str(info["read_logs"]).lower())
        raise AssertionError("Unexpected read: " + repr(args))

    def write(self, *args):
        self.commands.append(args)
        if self.dry_run:
            return ""
        if args[:2] == ("settings", "put"):
            self.settings[tuple(args[2:4])] = args[4]
        elif args[:2] == ("settings", "delete"):
            self.settings.pop(tuple(args[2:]), None)
        elif args[:2] in (("pm", "grant"), ("pm", "revoke")):
            self.packages[args[2]]["read_logs"] = args[1] == "grant"
        elif args[0] == "pm":
            states = {"default-state": 0, "enable": 1, "disable": 2, "disable-user": 3, "disable-until-used": 4}
            self.packages[args[-1]]["enabled"] = states[args[1]]
        elif args[:2] != ("am", "start"):
            raise AssertionError("Unexpected write: " + repr(args))
        if args == self.fail_after:
            raise tv.SetupError("simulated command failure after mutation")
        return ""

    def install(self, path):
        self.commands.append(("install", str(path)))
        if not self.dry_run:
            self.packages.setdefault(tv.PACKAGE, {"enabled": 0, "read_logs": False})

    def uninstall(self):
        self.commands.append(("uninstall", tv.PACKAGE))
        if not self.dry_run:
            self.packages.pop(tv.PACKAGE, None)


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.state = self.root / "state.json"
        self.apk = self.root / "xmb.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", '<manifest package="local.xmb.home"/>')
        self.adb = FakeAdb()
        self.output = io.StringIO()
        self.quiet = redirect_stdout(self.output)
        self.quiet.__enter__()
        self.addCleanup(self.quiet.__exit__, None, None, None)

    def setup(self):
        return tv.Setup(self.adb, self.state)

    def install(self, cleanup=False):
        setup = self.setup()
        setup.install(self.apk, cleanup)
        return setup

    def test_default_install_is_launcher_only_and_restores_owned_app(self):
        self.install()
        self.assertTrue(self.adb.packages[tv.PACKAGE]["read_logs"])
        self.assertEqual(self.adb.settings[("secure", "enabled_accessibility_services")], "other.app/.Reader:" + tv.SERVICE)
        self.assertFalse(any(command[:2] == ("pm", "disable-user") for command in self.adb.commands))
        self.assertFalse(any(command[:3] == ("settings", "put", "global") for command in self.adb.commands))
        self.setup().restore()
        self.assertNotIn(tv.PACKAGE, self.adb.packages)
        self.assertEqual(self.adb.settings[("secure", "enabled_accessibility_services")], "other.app/.Reader")
        self.assertEqual(json.loads(self.state.read_text())["status"], "restored")

    def test_restore_preserves_accessibility_service_added_after_install(self):
        self.adb.settings[("secure", "enabled_accessibility_services")] = "null"
        self.adb.settings[("secure", "accessibility_enabled")] = "0"
        self.install()
        self.adb.settings[("secure", "enabled_accessibility_services")] += ":later.app/.Reader"
        self.setup().restore()
        self.assertEqual(self.adb.settings[("secure", "enabled_accessibility_services")], "later.app/.Reader")
        self.assertEqual(self.adb.settings[("secure", "accessibility_enabled")], "1")

    def test_repeated_install_keeps_original_app_and_setting_baselines(self):
        self.adb.settings[("secure", "accessibility_enabled")] = "0"
        self.install()
        self.install()
        record = json.loads(self.state.read_text())
        self.assertIsNone(record["package_before"])
        self.assertFalse(record["changes"]["app"]["before"])
        self.assertEqual(record["changes"]["accessibility"]["before"], "0")
        self.setup().restore()
        self.assertNotIn(tv.PACKAGE, self.adb.packages)

    def test_cleanup_only_touches_enabled_allowlisted_packages(self):
        first, disabled = tv.CLEANUP_PACKAGES[:2]
        self.adb.packages[first] = {"enabled": 0, "read_logs": False}
        self.adb.packages[disabled] = {"enabled": 3, "read_logs": False}
        self.adb.packages["unrelated.app"] = {"enabled": 1, "read_logs": False}
        self.install(cleanup=True)
        self.assertEqual(self.adb.packages[first]["enabled"], 3)
        record = json.loads(self.state.read_text())
        self.assertNotIn("package:" + disabled, record["changes"])
        self.setup().restore()
        self.assertEqual(self.adb.packages[first]["enabled"], 0)
        self.assertEqual(self.adb.packages[disabled]["enabled"], 3)
        self.assertEqual(self.adb.packages["unrelated.app"]["enabled"], 1)

    def test_cleanup_rollback_preserves_later_setting_and_package_changes(self):
        first = tv.CLEANUP_PACKAGES[0]
        video, volume = list(tv.AUTOPLAY_SETTINGS)
        self.adb.packages[first] = {"enabled": 1, "read_logs": False}
        self.adb.settings[("global", volume)] = "0.7"
        self.install(cleanup=True)
        self.adb.packages[first]["enabled"] = 2
        self.adb.settings[("global", video)] = "later-choice"
        self.setup().restore()
        self.assertEqual(self.adb.packages[first]["enabled"], 2)
        self.assertEqual(self.adb.settings[("global", video)], "later-choice")
        self.assertEqual(self.adb.settings[("global", volume)], "0.7")

    def test_cleanup_added_on_later_install_remembers_its_first_baseline(self):
        first = tv.CLEANUP_PACKAGES[0]
        self.adb.packages[first] = {"enabled": 1, "read_logs": False}
        self.install()
        self.install(cleanup=True)
        self.install(cleanup=True)
        record = json.loads(self.state.read_text())
        self.assertEqual(record["changes"]["package:" + first]["before"], 1)
        self.setup().restore()
        self.assertEqual(self.adb.packages[first]["enabled"], 1)

    def test_preexisting_app_is_kept_and_its_permission_is_restored(self):
        self.adb.packages[tv.PACKAGE] = {"enabled": 3, "read_logs": False}
        self.install()
        self.assertEqual(self.adb.packages[tv.PACKAGE]["enabled"], 1)
        self.setup().restore()
        self.assertEqual(self.adb.packages[tv.PACKAGE], {"enabled": 3, "read_logs": False})
        self.assertNotIn(("uninstall", tv.PACKAGE), self.adb.commands)

    def test_preexisting_permission_and_redirect_are_kept(self):
        self.adb.packages[tv.PACKAGE] = {"enabled": 0, "read_logs": True}
        self.adb.settings[("secure", "enabled_accessibility_services")] = tv.SERVICE
        self.install()
        self.setup().restore()
        self.assertTrue(self.adb.packages[tv.PACKAGE]["read_logs"])
        self.assertEqual(self.adb.settings[("secure", "enabled_accessibility_services")], tv.SERVICE)

    def test_mismatched_device_rejected_before_mutation(self):
        self.install()
        self.adb.commands.clear()
        self.adb.serial = "different-fixture"
        with self.assertRaisesRegex(tv.SetupError, "does not match"):
            self.setup()
        self.assertEqual(self.adb.commands, [])

    def test_old_homestead_redirect_blocks_install_before_writes(self):
        self.adb.settings[("secure", "enabled_accessibility_services")] = next(iter(tv.OLD_SERVICES))
        with self.assertRaisesRegex(tv.SetupError, "competing"):
            self.install()
        self.assertEqual(self.adb.commands, [])
        self.assertFalse(self.state.exists())

    def test_failed_command_has_recoverable_prepared_journal(self):
        self.adb.fail_after = ("settings", "put", "secure", "enabled_accessibility_services", "other.app/.Reader:" + tv.SERVICE)
        with self.assertRaisesRegex(tv.SetupError, "simulated"):
            self.install()
        record = json.loads(self.state.read_text())
        self.assertEqual(record["changes"]["services"]["status"], "prepared")
        self.assertEqual(record["status"], "installing")
        self.adb.fail_after = None
        self.setup().restore()
        self.assertNotIn(tv.PACKAGE, self.adb.packages)
        self.assertEqual(self.adb.settings[("secure", "enabled_accessibility_services")], "other.app/.Reader")

    def test_dry_runs_never_change_device_or_journal(self):
        self.adb.dry_run = True
        before = copy.deepcopy(self.adb.settings)
        self.install(cleanup=True)
        self.assertEqual(self.adb.settings, before)
        self.assertEqual(self.adb.packages, {})
        self.assertFalse(self.state.exists())
        self.adb.dry_run = False
        self.install()
        saved = self.state.read_bytes()
        before = copy.deepcopy((self.adb.settings, self.adb.packages))
        self.adb.dry_run = True
        self.setup().restore()
        self.assertEqual((self.adb.settings, self.adb.packages), before)
        self.assertEqual(self.state.read_bytes(), saved)

    def test_status_is_read_only(self):
        self.setup().status()
        self.assertEqual(self.adb.commands, [])
        self.assertFalse(self.state.exists())

    def test_wrong_apk_is_rejected_before_writes(self):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", '<manifest package="another.app"/>')
        with self.assertRaisesRegex(tv.SetupError, "not XMB Home"):
            self.install()
        self.assertEqual(self.adb.commands, [])
        self.assertFalse(self.state.exists())


class TransportTests(unittest.TestCase):
    def test_certificate_mismatch_reports_without_uninstalling(self):
        adb = tv.Adb("adb", "fixture:5555")
        result = type("Result", (), {"returncode": 1, "stdout": "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]", "stderr": ""})()
        with patch.object(tv.subprocess, "run", return_value=result) as run:
            with self.assertRaisesRegex(tv.SetupError, "was not removed"):
                adb.install("release.apk")
        self.assertEqual(run.call_count, 1)
        self.assertNotIn("uninstall", run.call_args.args[0])

    def test_dry_write_does_not_call_subprocess(self):
        adb = tv.Adb("adb", "fixture:5555", dry_run=True)
        with patch.object(tv.subprocess, "run") as run, redirect_stdout(io.StringIO()):
            adb.write("settings", "put", "secure", "key", "value")
        run.assert_not_called()

    def test_remote_setting_value_is_shell_quoted(self):
        adb = tv.Adb("adb", "fixture:5555")
        with patch.object(adb, "run", return_value="") as run:
            adb.write("settings", "put", "secure", "key", "value; not-a-command")
        self.assertEqual(run.call_args.args[0], ["shell", "settings put secure key 'value; not-a-command'"])

    def test_binary_manifest_package_parser(self):
        strings = ["manifest", "package", tv.PACKAGE]
        raw = b"".join(bytes([len(s), len(s)]) + s.encode() + b"\0" for s in strings)
        offsets = [0, len(strings[0]) + 3, len(strings[0]) + len(strings[1]) + 6]
        pool = struct.pack("<HHIIIIII", 1, 28, 28 + 12 + len(raw), 3, 0, 0x100, 40, 0) + struct.pack("<III", *offsets) + raw
        attribute = struct.pack("<IIIHBBI", 0xffffffff, 1, 2, 8, 0, 3, 2)
        element = struct.pack("<HHIII", 0x102, 16, 56, 1, 0xffffffff) + struct.pack("<IIHHHHHH", 0xffffffff, 0, 20, 20, 1, 0, 0, 0) + attribute
        manifest = struct.pack("<HHI", 3, 8, 8 + len(pool) + len(element)) + pool + element
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("AndroidManifest.xml", manifest)
            self.assertEqual(tv.apk_package(path), tv.PACKAGE)


if __name__ == "__main__":
    unittest.main()
