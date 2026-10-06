"""Private server backup helper called only by publish-release.ps1. Not a release entry."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import uuid


def checked(path):
    path = Path(os.path.abspath(path))
    for parent in [path, *path.parents]:
        if parent.is_symlink():
            raise ValueError("Linked path refused")
    return path


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def attributes(path):
    info = path.stat()
    return {"mode": stat.S_IMODE(info.st_mode), "uid": info.st_uid, "gid": info.st_gid}


def copy_attributes(source, target):
    shutil.copystat(source, target)
    if hasattr(os, "chown"):
        info = source.stat()
        os.chown(target, info.st_uid, info.st_gid)


def in_use(path):
    """Do not reclaim a rollback tree or package being read by another task."""
    proc = Path('/proc')
    if not proc.is_dir():
        return False
    prefix = str(path)
    for process in proc.iterdir():
        if not process.name.isdigit():
            continue
        for reference in [process / 'cwd', process / 'exe', *(process / 'fd').glob('*')]:
            try:
                target = os.readlink(reference)
            except OSError:
                continue
            if target == prefix or target.startswith(prefix + os.sep):
                return True
    return False


def atomic_json(path, value):
    temp = path.with_suffix(path.suffix + ".tmp")
    with temp.open("w", encoding="utf8") as stream:
        json.dump(value, stream, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    temp.chmod(0o600)
    temp.replace(path)
    if os.name != "nt":
        fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def release_names(metadata):
    rows = json.loads(metadata.read_text(encoding="utf8"))
    result = set()
    if not isinstance(rows, dict) or not rows:
        raise ValueError("Missing release references")
    for row in rows.values():
        url = row.get("url", "")
        if not url.startswith("/releases/") or Path(url).name != url[len("/releases/"):]:
            raise ValueError("Invalid release reference")
        result.add(Path(url).name)
    return result


def copy_tree(source, target, immutable=False, base=None):
    base = base or (source if source.is_dir() else source.parent)
    if source.is_symlink():
        link = os.readlink(source)
        if os.path.isabs(link) or not source.resolve().is_relative_to(base.resolve()):
            raise ValueError("External backup link refused")
        target.symlink_to(link, target_is_directory=source.is_dir())
        return
    checked(source)
    if source.is_dir():
        target.mkdir(mode=0o700)
        for entry in source.iterdir():
            copy_tree(entry, target / entry.name, immutable, base)
        copy_attributes(source, target)
    elif source.is_file():
        if immutable:
            os.link(source, target)
        else:
            shutil.copy2(source, target)
        if hasattr(os, "chown"):
            info = source.stat()
            os.chown(target, info.st_uid, info.st_gid)
    else:
        raise ValueError("Special file refused")


def verify(snapshot):
    snapshot = checked(snapshot)
    metadata = json.loads((snapshot / "manifest.json").read_text(encoding="utf8"))
    if metadata.get("format") != 1 or not metadata.get("files"):
        raise ValueError("Invalid backup manifest")
    for name, link in metadata.get("links", {}).items():
        file = snapshot / name
        if not file.is_relative_to(snapshot) or not file.is_symlink() or os.readlink(file) != link or not file.resolve().is_relative_to(snapshot):
            raise ValueError("Backup link verification failed")
    for name, item in metadata["files"].items():
        file = checked(snapshot / name)
        if not file.is_relative_to(snapshot) or not file.is_file() or file.stat().st_size != item["size"] or digest(file) != item["sha256"]:
            raise ValueError("Backup verification failed")
        if attributes(file) != {key: item[key] for key in ["mode", "uid", "gid"]}:
            raise ValueError("Backup file permissions changed")
    for name, expected in metadata["directories"].items():
        directory = checked(snapshot / name)
        if not directory.is_relative_to(snapshot) or not directory.is_dir() or attributes(directory) != expected:
            raise ValueError("Backup directory permissions changed")
    for name in release_names(snapshot / "app/data/releases.json"):
        if not (snapshot / "app/data/releases" / name).is_file():
            raise ValueError("Missing rollback release")
    for required in ["app/dist/index.js", "app/package.json", "app/package-lock.json", "app/node_modules", "app/runtime"]:
        if not (snapshot / required).exists():
            raise ValueError("Missing rollback dependency")
    return metadata


def prepare(root, configs):
    root = checked(root)
    backups = checked(root / "backups")
    backups.mkdir(mode=0o700, exist_ok=True)
    if (backups / "transaction.json").exists():
        raise ValueError("Unfinished release backup exists; preserve it and resolve before another release")
    fd = os.open(backups / "transaction.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(fd)
    name = "snapshot-" + uuid.uuid4().hex
    snapshot = backups / name
    snapshot.mkdir(mode=0o700)
    atomic_json(backups / "transaction.json", {"snapshot": name})
    app = snapshot / "app"
    app.mkdir(mode=0o700)
    keep = release_names(root / "data/releases.json")
    for entry in root.iterdir():
        if entry.name == "backups":
            continue
        if entry.name == "data":
            (app / "data").mkdir(mode=0o700)
            for data in entry.iterdir():
                if data.name == "releases":
                    (app / "data/releases").mkdir(mode=0o700)
                    for asset in keep:
                        copy_tree(data / asset, app / "data/releases" / asset, True)
                else:
                    copy_tree(data, app / "data" / data.name, data.name == "models")
            copy_attributes(entry, app / "data")
        else:
            copy_tree(entry, app / entry.name, entry.name == "runtime")
    copy_attributes(root, app)
    (snapshot / "config").mkdir(mode=0o700)
    mapping = {}
    for index, value in enumerate(configs):
        source = checked(value)
        target = snapshot / "config" / str(index)
        copy_tree(source, target)
        mapping[str(index)] = str(source)
    files = {}
    links = {}
    directories = {}
    for file in snapshot.rglob("*"):
        if file.is_symlink():
            links[str(file.relative_to(snapshot))] = os.readlink(file)
        elif file.is_file():
            files[str(file.relative_to(snapshot))] = {"size": file.stat().st_size, "sha256": digest(file), **attributes(file)}
        elif file.is_dir():
            directories[str(file.relative_to(snapshot))] = attributes(file)
    atomic_json(snapshot / "manifest.json", {"format": 1, "version": json.loads((root / "package.json").read_text())["version"], "configs": mapping, "files": files, "links": links, "directories": directories})
    verify(snapshot)
    return name


def finalize(root):
    backups = checked(Path(root) / "backups")
    transaction = json.loads((backups / "transaction.json").read_text())
    name = transaction["snapshot"]
    if not name.startswith("snapshot-") or len(name) != 41 or not all(c in "0123456789abcdef" for c in name[9:]):
        raise ValueError("Invalid transaction")
    verify(backups / name)
    # Commit the verified rollback pointer before deleting any older backup.
    atomic_json(backups / "previous.json", {"snapshot": name})
    for entry in backups.iterdir():
        if entry.name.startswith("snapshot-") and entry.name != name:
            verify(entry)
            if in_use(entry):
                raise ValueError("Older rollback is in use; preserve both snapshots and resolve the transaction")
            shutil.rmtree(checked(entry))
    root = checked(Path(root))
    keep = release_names(root / "data/releases.json") | release_names(backups / name / "app/data/releases.json")
    import re
    for file in checked(root / "data/releases").iterdir():
        if file.name not in keep and re.fullmatch(r"VisionGuard-[A-Za-z-]*v[0-9]+\.[0-9]+\.[0-9]+\.(apk|zip)", file.name) and file.is_file() and not file.is_symlink() and not in_use(file):
            file.unlink()
    (backups / "transaction.json").unlink()
    return name


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["prepare", "finalize", "verify"])
    parser.add_argument("root")
    parser.add_argument("--config", action="append", default=[])
    args = parser.parse_args()
    print(prepare(args.root, args.config) if args.action == "prepare" else finalize(args.root) if args.action == "finalize" else json.dumps({"version": verify(Path(args.root))["version"]}))
