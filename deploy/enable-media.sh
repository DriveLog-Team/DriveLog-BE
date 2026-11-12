#!/usr/bin/env bash
# Run as root with this directory and an approved synthetic trip + model in STAGE.
set -euo pipefail
umask 077
stage=${1:?staging directory required}
cd /opt/drivelog
install -d -m 755 edge acme /etc/letsencrypt media/trips/demo media/model
install -d -m 700 -o 10001 -g 10001 media/library
install -m 644 "$stage"/{trip.json,front-long.mp4,rear.mp4} media/trips/demo/
echo "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21  $stage/ggml-tiny.bin" | sha256sum -c -
install -m 644 "$stage/ggml-tiny.bin" media/model/
test -s media.env
test -e compose.pre-media.yaml || cp -p compose.yaml compose.pre-media.yaml
test -e nginx.pre-media.conf || cp -p nginx.conf nginx.pre-media.conf
install -m 644 "$stage/compose.yaml" compose.yaml
install -m 644 "$stage/nginx.conf" nginx.conf
docker compose --env-file .env up -d --no-deps --force-recreate nginx
command -v /snap/bin/certbot >/dev/null || snap install certbot --classic
/snap/bin/certbot certonly --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring \
    --webroot -w /opt/drivelog/acme --preferred-profile shortlived --ip-address 35.216.33.209
install -m 644 "$stage/https.conf" edge/https.conf
docker compose exec -T nginx nginx -t
docker compose exec -T nginx nginx -s reload
install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
printf '%s\n' '#!/bin/sh' 'cd /opt/drivelog && /usr/bin/docker compose exec -T nginx nginx -s reload' > /etc/letsencrypt/renewal-hooks/deploy/drivelog
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/drivelog
systemctl is-active snap.certbot.renew.timer
