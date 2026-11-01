# ServerPilotX — Linux Server Manager

A native Linux desktop app (Java 21 + JavaFX 23) for managing your servers: Docker containers and compose stacks, Nginx sites, SSL certificates, firewall, SSH users and keys, services, files, and live metrics. It has a dark and a light theme.

It manages **this computer** directly and **remote servers over SSH**. Nothing needs to be installed on the servers. Everything it writes is plain config on disk, such as `/etc/nginx/sites-available/*.conf`, `docker-compose.yml`, UFW rules and `sshd_config.d`, so uninstalling ServerPilotX leaves a working server.

![poster](Images/poster.png)

## Install

```bash
# Debian / Ubuntu (system-wide, adds a menu entry)
sudo apt install ./target/dist/serverpilotx_1.0.0_amd64.deb

# Any distro, current user only (no root): ~/.local/opt + menu entry + `serverpilotx` command
./install-local.sh
```

Both builds bundle their own Java runtime, so you don't need to install Java separately.

## Build

Requirements: JDK 21+ (with `jpackage`) and Maven 3.9+. To build a `.deb` you also need `dpkg-deb` and `fakeroot`.

```bash
./package.sh          # app-image + .deb   → target/dist/
./package.sh image    # app-image only     → target/dist/ServerPilotX/bin/ServerPilotX
./package.sh rpm      # app-image + .rpm   (needs rpmbuild)
mvn javafx:run        # run from source
```

## Features

| Page | What it does |
|---|---|
| **Dashboard** | Live CPU, RAM, disk and network gauges. History chart for 1h/24h/7d/30d (stored as one point per minute). Server info. Health checks: failed units, unhealthy containers, pending and security updates, disk over 85%, certificates expiring within 14 days, reboot required. Common services. Quick actions. |
| **Servers** | Add remote hosts (SSH key auth, multiplexed connections), test them, switch between them, open an SSH terminal. |
| **Docker** | Containers: start, stop, restart, pause, rename, remove, live logs (follow, filter, download), inspect with secrets masked, exec shell, scheduled prune. Compose stacks in `/opt/serverpilotx/stacks`: create from the editor or a Git repo, up, down, pull, restart, `.env` editor, validation with `docker compose config`, drift detection. Images, volumes (with file browser) and networks. App catalog: Nextcloud, Gitea, Vaultwarden, Uptime Kuma, n8n, Grafana, PostgreSQL, MariaDB, Redis. |
| **Sites** | Nginx wizard for reverse proxy, static/SPA, PHP-FPM and redirect sites. Options: www handling, HTTPS redirect, HSTS, gzip, max body size, headers, IP allow list, basic auth, rate limit, WebSocket and custom directives. Every save runs `nginx -t` and rolls back automatically if it fails. Raw editor whose `SPX MANAGED` markers keep manual edits. Enable/disable, access and error logs. |
| **SSL / TLS** | Certificate inventory with issuer, SANs, expiry, days left and key type. Let's Encrypt, LE staging or Buypass via certbot, using HTTP-01 (webroot or standalone) or DNS-01 (Cloudflare, DigitalOcean, RFC2136, including wildcards). Renew, auto-renew setup, self-signed certificates, PEM upload with key-match check, and one-click install on a site. |
| **Firewall** | UFW rules with a dry-run preview, presets (SSH, web, HTTP/3, WireGuard, mail, DNS) and a "Docker respects UFW" option (DOCKER-USER chain). **Lockout protection:** on remote servers every change auto-reverts after 5 minutes unless you confirm it, and is reverted immediately if a new SSH connection fails. Fail2ban jails, ban/unban and whitelist. Raw nftables ruleset viewer and editor. |
| **Users & SSH** | Create, lock, delete users, and set passwords, shell, groups and sudo. Authorized keys per user with fingerprint, `expiry-time` and last-used time (from the journal). SSH hardening wizard (`sshd -t` validation, rollback if a new connection fails, socket-activated sshd handled). Active sessions and login history. |
| **Services** | systemd units (start, stop, restart, enable, journal), a cron editor with plain-English schedules, run-now and history, a live process table (kill, renice), and package updates for apt, dnf or pacman (security-only or all, plus automatic updates). |
| **Files** | Browse the configured roots. Edit, upload, download, chmod, chown, tar/zip archive and extract, and a disk-usage treemap. Uses root automatically when a folder needs it. |
| **Terminal** | Built-in persistent shell on the active server, with history, Ctrl+C and quick commands. Full-screen programs open in your desktop terminal. |
| **Audit log** | Every change with who, what, server, result and detail. Filter it and export to CSV. |
| **Settings** | Theme (dark, light or follow system), sudo or pkexec, metrics interval and retention, paths, terminal emulator, ACME email. |

**Keyboard:** Ctrl+K opens the command palette, Ctrl+1…9 jump to pages, F5 refreshes, and Ctrl+Shift+L switches between dark and light.

## Privileges

ServerPilotX runs as your user. When a change needs root, it uses `sudo`: you enter the password once per session per server, and it is kept in memory only. Locally you can switch to `pkexec` in Settings. Docker commands use your `docker` group membership when it's available.

## Data locations

- Settings: Java preferences (`~/.java/.userPrefs/io/serverpilotx`)
- Audit log: `~/.local/share/serverpilotx/audit.jsonl`
- Metrics history: `~/.local/share/serverpilotx/metrics/<server>.csv`
- SSH multiplexing sockets: `~/.cache/serverpilotx/`

## Differences from the web-panel spec

The original spec describes a Go + React web panel with agents. This app is a native desktop version of the same idea, so some things differ:

- It has no agents and no control plane. Remote servers are managed over SSH (outbound only, nothing to install on them).
- ACME certificates are issued through `certbot`, not a built-in ACME client.
- Not implemented yet: backups, alert rules and notification channels, multi-user RBAC, API tokens and CLI, Prometheus endpoint, GeoIP blocking, container auto-update, uptime checks, and Caddy/Traefik backends.

## License

AGPL-3.0. See [LICENSE](LICENSE).
