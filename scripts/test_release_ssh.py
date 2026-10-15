import shlex
import unittest
from check_release_target import assert_same_process
from release_ssh import run_remote, open_sftp


class Client:
    def exec_command(self, command, **options):
        self.command, self.options = command, options
        return 'result'

    def open_sftp(self):
        return 'ordinary sftp'


class ReleaseSshTests(unittest.TestCase):
    def test_default_connection_keeps_normal_permissions(self):
        client = Client()
        self.assertEqual(run_remote(client, 'id -u', timeout=20), 'result')
        self.assertEqual(client.command, 'id -u')
        self.assertEqual(client.options, {'timeout': 20})
        self.assertEqual(open_sftp(client), 'ordinary sftp')

    def test_sudo_preserves_the_complete_command_as_one_argument(self):
        client = Client()
        command = "set -euo pipefail\nprintf '%s\\n' 'spaces and quotes' \"$HOME\""
        run_remote(client, command, use_sudo=True, timeout=300)
        self.assertEqual(shlex.split(client.command), ['sudo', '-n', 'bash', '-c', command])
        self.assertEqual(client.options, {'timeout': 300})

    def test_same_public_process_passes(self):
        assert_same_process(*[{'ok': True, 'version': '0.7.6', 'uptime': uptime} for uptime in (100.1, 100.2, 100.3)])

    def test_another_host_with_the_same_version_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'selected SSH process'):
            assert_same_process(*[{'ok': True, 'version': '0.7.5', 'uptime': uptime} for uptime in (125000, 79000, 125001)])

    def test_restart_and_version_drift_are_rejected(self):
        before = {'ok': True, 'version': '0.7.6', 'uptime': 100}
        with self.assertRaises(ValueError):
            assert_same_process(before, {**before, 'uptime': 101}, {**before, 'uptime': 1})
        with self.assertRaises(ValueError):
            assert_same_process(before, {**before, 'version': '0.7.5'}, before)


if __name__ == '__main__':
    unittest.main()
