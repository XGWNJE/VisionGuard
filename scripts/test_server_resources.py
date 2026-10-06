import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import sys

sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("maintenance", Path(__file__).with_name("server-resource-maintenance.py"))
maintenance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(maintenance)


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "server"
        for name in ["dist", "node_modules", "runtime", "data/releases", "data/models"]:
            (self.root / name).mkdir(parents=True, exist_ok=True)
        for name, value in {"package.json": '{"version":"0.6.4"}', "package-lock.json": '{}', "dist/index.js": 'old', "runtime/node": 'node', "data/accounts.json": 'private', "data/models/model.onnx": 'model', "data/releases/old.apk": 'old-package'}.items():
            (self.root / name).write_text(value)
        self.release("old.apk")

    def release(self, name):
        (self.root / "data/releases.json").write_text(json.dumps({"android-notifier": {"url": "/releases/" + name}}))

    def test_private_state_is_copied_immutable_assets_linked_and_one_backup_retained(self):
        name = maintenance.prepare(self.root, [])
        snapshot = self.root / "backups" / name
        self.assertEqual((snapshot / "app/data/models/model.onnx").stat().st_ino, (self.root / "data/models/model.onnx").stat().st_ino)
        (self.root / "data/accounts.json").write_text("new-private")
        self.assertEqual((snapshot / "app/data/accounts.json").read_text(), "private")
        maintenance.finalize(self.root)
        second = maintenance.prepare(self.root, [])
        maintenance.finalize(self.root)
        self.assertFalse(snapshot.exists())
        self.assertTrue((self.root / "backups" / second).exists())

    def test_failed_verification_preserves_previous_and_blocks_another_release(self):
        old = maintenance.prepare(self.root, [])
        maintenance.finalize(self.root)
        pending = maintenance.prepare(self.root, [])
        (self.root / "backups" / pending / "app/dist/index.js").write_text("broken")
        with self.assertRaises(ValueError):
            maintenance.finalize(self.root)
        self.assertTrue((self.root / "backups" / old).exists())
        with self.assertRaises(ValueError):
            maintenance.prepare(self.root, [])

    def test_current_and_rollback_packages_survive_unreferenced_package_pruning(self):
        maintenance.prepare(self.root, [])
        (self.root / "data/releases/new.apk").write_text("new")
        (self.root / "data/releases/VisionGuard-Notifier-v0.1.0.apk").write_text("garbage")
        self.release("new.apk")
        maintenance.finalize(self.root)
        self.assertTrue((self.root / "data/releases/old.apk").exists())
        self.assertTrue((self.root / "data/releases/new.apk").exists())
        self.assertFalse((self.root / "data/releases/VisionGuard-Notifier-v0.1.0.apk").exists())

    def test_restore_root_and_private_directory_keep_access_permissions(self):
        (self.root / 'data').chmod(0o750)
        name = maintenance.prepare(self.root, [])
        snapshot = self.root / 'backups' / name
        self.assertEqual(maintenance.attributes(self.root), maintenance.attributes(snapshot / 'app'))
        self.assertEqual(maintenance.attributes(self.root / 'data'), maintenance.attributes(snapshot / 'app/data'))
        (snapshot / 'app/data/accounts.json').chmod(0o400)
        with self.assertRaises(ValueError):
            maintenance.verify(snapshot)

    @unittest.skipIf(os.name == 'nt', 'Linux process references are verified on Linux CI')
    def test_another_task_using_the_old_snapshot_blocks_reclamation(self):
        import subprocess
        old = maintenance.prepare(self.root, [])
        maintenance.finalize(self.root)
        process = subprocess.Popen([sys.executable, '-c', 'import sys; sys.stdin.read()'], cwd=self.root / 'backups' / old / 'app', stdin=subprocess.PIPE)
        try:
            maintenance.prepare(self.root, [])
            with self.assertRaises(ValueError):
                maintenance.finalize(self.root)
            self.assertTrue((self.root / 'backups' / old).exists())
        finally:
            process.communicate(timeout=10)

    @unittest.skipIf(os.name == "nt", "Windows symlinks require host privileges; exercised on Linux CI")
    def test_internal_runtime_links_preserved_external_links_refused(self):
        (self.root / "runtime/internal").symlink_to("node")
        name = maintenance.prepare(self.root, [])
        maintenance.verify(self.root / "backups" / name)
        self.assertTrue((self.root / "backups" / name / "app/runtime/internal").is_symlink())
        maintenance.finalize(self.root)
        (self.root / "runtime/external").symlink_to("/etc/passwd")
        with self.assertRaises(ValueError):
            maintenance.prepare(self.root, [])


if __name__ == "__main__":
    unittest.main()
