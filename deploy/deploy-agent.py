#!/usr/bin/env python3
"""Pull a restricted release manifest; deploy by digest and roll back failed releases."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path('/opt/drivelog')
IMAGE_PREFIX = 'asia-northeast3-docker.pkg.dev/drivelog-prod-20261110/drivelog/backend'
MANIFEST_URL = 'https://storage.googleapis.com/storage/v1/b/drivelog-releases-1079465774150/o/current.json?alt=media'


def read_json(path):
    return json.loads(path.read_text()) if path.exists() else None


def atomic_write(path, content):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(content)
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def validate(release):
    if not isinstance(release, dict):
        raise ValueError('Manifest must be an object')
    if not re.fullmatch(re.escape(IMAGE_PREFIX) + r'@sha256:[0-9a-f]{64}', release.get('image', '')):
        raise ValueError('Only the configured Artifact Registry image digest is accepted')
    if not re.fullmatch(r'[0-9a-f]{40}', release.get('sha', '')):
        raise ValueError('A full Git commit SHA is required')
    return release


def compose(release):
    env = os.environ.copy()
    env.update(BACKEND_IMAGE=release['image'], DRIVELOG_RELEASE=release['sha'])
    subprocess.run(['docker', 'compose', '--env-file', str(ROOT / '.env'),
                    '-f', str(ROOT / 'compose.yaml'), '--profile', 'application',
                    'up', '-d', '--no-deps', 'backend'], env=env, check=True,
                   timeout=120)


def healthy(sha):
    for _ in range(45):
        try:
            with urlopen('http://127.0.0.1:8080/actuator/health', timeout=3) as response:
                health = json.load(response)
            with urlopen('http://127.0.0.1:8080/api/status', timeout=3) as response:
                status = json.load(response)
            if health.get('status') == 'UP' and status.get('release') == sha:
                return True
        except (OSError, ValueError):
            pass
        time.sleep(2)
    return False


def main():
    import fcntl
    with (ROOT / '.deploy.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        request = Request('http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token',
                          headers={'Metadata-Flavor': 'Google'})
        with urlopen(request, timeout=10) as response:
            token = json.load(response)['access_token']
        try:
            with urlopen(Request(MANIFEST_URL, headers={'Authorization': 'Bearer ' + token}), timeout=15) as response:
                release = validate(json.load(response))
        except HTTPError as error:
            if error.code == 404:
                return
            raise
        current_path = ROOT / 'current-release.json'
        current = read_json(current_path)
        if current == release or read_json(ROOT / 'failed-release.json') == release:
            return
        subprocess.run(['docker', 'login', '-u', 'oauth2accesstoken', '--password-stdin',
                        'https://asia-northeast3-docker.pkg.dev'], input=token,
                       text=True, check=True, capture_output=True, timeout=30)
        # Pull before replacing the running container so a registry failure preserves service.
        subprocess.run(['docker', 'pull', release['image']], check=True, timeout=120)
        try:
            compose(release)
            if not healthy(release['sha']):
                raise RuntimeError('New release did not pass health and version checks')
        except Exception:
            atomic_write(ROOT / 'failed-release.json', json.dumps(release))
            if current:
                compose(validate(current))
                if not healthy(current['sha']):
                    raise RuntimeError('Rollback health check failed')
            raise
        if current:
            atomic_write(ROOT / 'previous-release.json', json.dumps(current))
        atomic_write(current_path, json.dumps(release))
        atomic_write(ROOT / 'release.env', f"BACKEND_IMAGE={release['image']}\nDRIVELOG_RELEASE={release['sha']}\n")
        print('DEPLOYED ' + release['sha'])


if __name__ == '__main__':
    main()
