#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl python3
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu %s stable\n' "$(dpkg --print-architecture)" "$VERSION_CODENAME" > /etc/apt/sources.list.d/docker.list
apt-get update -qq
apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
install -d -m 700 /opt/drivelog

metadata_file() {
    curl -fsS -H 'Metadata-Flavor: Google' "http://metadata.google.internal/computeMetadata/v1/instance/attributes/$1" -o "$2"
}
metadata_file drivelog-compose /opt/drivelog/compose.yaml
metadata_file drivelog-nginx /opt/drivelog/nginx.conf
metadata_file drivelog-agent /opt/drivelog/deploy-agent.py
metadata_file drivelog-unit /etc/systemd/system/drivelog-deploy.service
metadata_file drivelog-timer /etc/systemd/system/drivelog-deploy.timer
if [ ! -f /opt/drivelog/.env ]; then
    umask 077
    python3 -c 'import secrets; print("POSTGRES_PASSWORD=" + secrets.token_urlsafe(40))' > /opt/drivelog/.env
fi
chmod 600 /opt/drivelog/.env /opt/drivelog/deploy-agent.py
cd /opt/drivelog
docker compose --env-file .env -f compose.yaml up -d postgres redis nginx
if [ -f release.env ]; then
    set -a
    . ./release.env
    set +a
    docker compose --env-file .env -f compose.yaml --profile application up -d --no-deps backend
fi
systemctl daemon-reload
systemctl enable --now drivelog-deploy.timer
echo DRIVELOG_BOOTSTRAP_READY
