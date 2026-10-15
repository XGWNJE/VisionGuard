"""Read-only check that the public service reaches the selected SSH target."""
import argparse
import json
import urllib.request


def assert_same_process(before, public, after):
    if not all(row.get('ok') is True for row in (before, public, after)):
        raise ValueError('Release target health is not ok')
    if not before.get('version') or not before['version'] == public.get('version') == after.get('version'):
        raise ValueError('Public service and selected SSH target versions differ')
    first, middle, last = [float(row['uptime']) for row in (before, public, after)]
    if not first <= middle <= last:
        raise ValueError('Public service does not reach the selected SSH process; check current infrastructure and ServerEnvPath')


def main():
    import paramiko
    from release_ssh import run_remote
    parser = argparse.ArgumentParser()
    parser.add_argument('--server-env', required=True)
    parser.add_argument('--base-url', required=True)
    args = parser.parse_args()
    values = {}
    with open(args.server_env, encoding='utf-8-sig') as stream:
        for line in stream:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    def pick(*names, default=None):
        return next((values[name] for name in names if values.get(name)), default)
    client = paramiko.SSHClient()
    client.load_system_host_keys()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(hostname=pick('VPS_IP', 'SSH_HOST', 'VPS_HOST'), port=int(pick('SSH_PORT', 'VPS_PORT', default='22')), username=pick('SSH_USER', 'VPS_USER', default='root'), password=pick('SSH_PASSWORD', 'VPS_PASSWORD'), key_filename=pick('SSH_KEY', 'SSH_KEY_PATH'), timeout=20)
    def remote_health():
        _, stdout, stderr = run_remote(client, 'curl -fsS --max-time 15 http://127.0.0.1:3000/health', use_sudo=pick('SSH_SUDO') == '1', timeout=20)
        result = stdout.read()
        if stdout.channel.recv_exit_status():
            raise RuntimeError('Selected SSH target health request failed')
        return json.loads(result)
    try:
        before = remote_health()
        request = urllib.request.Request(args.base_url.rstrip('/') + '/health', headers={'Cache-Control': 'no-cache'})
        with urllib.request.urlopen(request, timeout=20) as response:
            public = json.load(response)
        after = remote_health()
        assert_same_process(before, public, after)
        print('Release target verified: public health reaches the selected SSH process (version ' + public['version'] + ')')
    finally:
        client.close()


if __name__ == '__main__':
    main()
