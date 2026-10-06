import importlib.util
import hashlib
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

    def deployed_pending_release(self):
        old = maintenance.prepare(self.root, [])
        maintenance.finalize(self.root)
        (self.root / 'package.json').write_text('{"version":"0.6.5"}')
        pending = maintenance.prepare(self.root, [])
        (self.root / 'package.json').write_text('{"version":"0.6.6"}')
        (self.root / 'dist/index.js').write_text('new-code')
        (self.root / 'data/releases/new.apk').write_bytes(b'new-package')
        (self.root / 'data/releases.json').write_text(json.dumps({'camera': {
            'version': '0.6.6', 'url': '/releases/new.apk', 'size': 11,
            'sha256': hashlib.sha256(b'new-package').hexdigest()
        }}))
        return old, pending, maintenance.digest(self.root / 'dist/index.js'), maintenance.digest(self.root / 'data/releases.json')

    def test_recovery_verification_retains_original_pending_and_previous_snapshots(self):
        old, pending, code, metadata = self.deployed_pending_release()
        self.assertEqual(pending, maintenance.verify_deployed(self.root, '0.6.6', code, metadata))
        self.assertEqual({'snapshot': old}, json.loads((self.root / 'backups/previous.json').read_text()))
        self.assertEqual({'snapshot': pending}, json.loads((self.root / 'backups/transaction.json').read_text()))
        self.assertEqual('0.6.5', maintenance.verify(self.root / 'backups' / pending)['version'])
        self.assertTrue((self.root / 'backups' / old).exists())

    def test_recovery_refuses_a_different_or_not_yet_deployed_version(self):
        old, pending, code, metadata = self.deployed_pending_release()
        for version in ['0.6.7', '0.6.5']:
            with self.subTest(version=version), self.assertRaises(ValueError):
                maintenance.verify_deployed(self.root, version, code, metadata)
        (self.root / 'package.json').write_text('{"version":"0.6.5"}')
        with self.assertRaises(ValueError):
            maintenance.verify_deployed(self.root, '0.6.5', code, metadata)
        self.assertTrue((self.root / 'backups' / old).exists())
        self.assertTrue((self.root / 'backups' / pending).exists())

    def test_recovery_rejects_code_or_metadata_drift_without_finishing_transaction(self):
        _, pending, code, metadata = self.deployed_pending_release()
        for name in ['dist/index.js', 'data/releases.json']:
            file = self.root / name
            original = file.read_bytes()
            file.write_bytes(original + b' ')
            with self.subTest(name=name), self.assertRaises(ValueError):
                maintenance.verify_deployed(self.root, '0.6.6', code, metadata)
            file.write_bytes(original)
        self.assertEqual({'snapshot': pending}, json.loads((self.root / 'backups/transaction.json').read_text()))

    def test_recovery_checks_uploaded_bytes_not_only_package_size(self):
        _, pending, code, metadata = self.deployed_pending_release()
        (self.root / 'data/releases/new.apk').write_bytes(b'bad-package')
        with self.assertRaises(ValueError):
            maintenance.verify_deployed(self.root, '0.6.6', code, metadata)
        self.assertTrue((self.root / 'backups' / pending).exists())

    def test_recovery_preserves_both_backups_when_pending_snapshot_is_corrupt(self):
        old, pending, code, metadata = self.deployed_pending_release()
        (self.root / 'backups' / pending / 'app/dist/index.js').write_text('corrupt')
        with self.assertRaises(ValueError):
            maintenance.verify_deployed(self.root, '0.6.6', code, metadata)
        self.assertTrue((self.root / 'backups' / old).exists())
        self.assertTrue((self.root / 'backups/transaction.json').exists())

    def test_recovery_refuses_transaction_paths_outside_backup_root(self):
        _, pending, code, metadata = self.deployed_pending_release()
        (self.root / 'backups/transaction.json').write_text('{"snapshot":"../outside"}')
        with self.assertRaises(ValueError):
            maintenance.verify_deployed(self.root, '0.6.6', code, metadata)
        self.assertTrue((self.root / 'backups' / pending).exists())

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
