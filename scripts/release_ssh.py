"""SSH operations shared by the release entrypoint; sudo is explicitly opt-in."""
import shlex


def run_remote(client, command, use_sudo=False, **options):
    if use_sudo:
        command = 'sudo -n bash -c ' + shlex.quote(command)
    return client.exec_command(command, **options)


def open_sftp(client, use_sudo=False):
    if not use_sudo:
        return client.open_sftp()
    import paramiko
    channel = client.get_transport().open_session()
    channel.exec_command('sudo -n /usr/lib/openssh/sftp-server')
    return paramiko.SFTPClient(channel)
