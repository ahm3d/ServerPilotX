# ServerPilotX — Linux Server Manager

**Version:** 1.0 (spec)
**Status:** Draft
**License:** AGPL-3.0

A self-hosted alternative to commercial server panels (Plesk, cPanel, RunCloud, Ploi). Manage Docker containers, Nginx virtual hosts, SSL certificates, firewalls, SSH access and server metrics from a single web UI and CLI — with no vendor lock-in, no per-server licensing, and no outbound telemetry.

---

## 1. Overview

### 1.1 Problem

Managing a VPS by hand means juggling `docker compose`, Nginx config files, `certbot`, `ufw`, `authorized_keys` and `htop` across SSH sessions. Commercial panels solve this but are closed-source, charge per server, phone home, and often take over the OS in ways that are hard to undo.

### 1.2 Goals

- One binary + one web UI to manage a Linux server end-to-end.
- Zero lock-in: everything ServerPilotX writes is plain files on disk (`/etc/nginx/sites-available`, `docker-compose.yml`, `ufw` rules). Uninstalling the panel leaves a working server.
- Secure by default: 2FA, audit log, least-privilege agent, no root web process.
- Multi-server: one control plane can manage N servers via lightweight agents.
- API-first: every UI action is a documented REST call; a CLI wraps the same API.

### 1.3 Non-goals (v1)

- Shared hosting / reseller model (per-customer isolation, billing).
- Managing Kubernetes clusters.
- Windows servers.
- Built-in email hosting (can be deployed as a Docker app, not managed natively).

### 1.4 Target users

- Self-hosters and homelab operators running 1–10 VPS/bare-metal boxes.
- Small dev teams who want a panel without a SaaS dependency.
- Agencies hosting client sites on their own infrastructure.

---

## 2. Feature Specification

### 2.1 Dashboard

- Server overview cards: hostname, OS, uptime, kernel, agent version, last seen.
- Live metrics (CPU, RAM, disk, load, network I/O) with 1h / 24h / 7d / 30d ranges.
- Health checks: failed systemd units, unhealthy containers, expiring certs (< 14 days), pending security updates, disk > 85%.
- Quick actions: reboot, restart Nginx/Docker, run update check.

### 2.2 Docker Management

| Capability | Detail |
|---|---|
| Container list | Status, image, ports, CPU/RAM usage, restart policy, uptime |
| Container actions | Start / stop / restart / pause / remove / rename |
| Logs | Live tail (WebSocket), search, download, tail N lines |
| Exec | Web terminal into a container (`docker exec -it`) |
| Inspect | Env vars (masked secrets), mounts, networks, labels, healthcheck |
| Images | List, pull, remove, prune dangling, size breakdown |
| Volumes | List, create, remove, browse (read-only file tree), size |
| Networks | List, create, connect/disconnect containers |
| Compose stacks | Create from editor or Git repo; `up` / `down` / `pull` / `restart`; per-stack `.env` editor; drift detection vs. deployed state |
| App catalog | One-click templates (Nextcloud, Gitea, Vaultwarden, Uptime Kuma, n8n, Postgres, Redis, etc.) with form-driven variables |
| Auto-update | Optional per-container watch (Diun-style) with notify or auto-pull+recreate |
| Prune | Scheduled system prune with configurable retention |

Compose stacks are stored at `/opt/serverpilotx/stacks/<name>/docker-compose.yml` and are plain, hand-editable files.

### 2.3 Nginx / Reverse Proxy

- Site list: domain(s), upstream, SSL status, HTTP/2 & HTTP/3, enabled/disabled.
- Site types:
  - **Reverse proxy** → `http://127.0.0.1:PORT` or container name on a shared Docker network.
  - **Static** → serve a directory (with optional SPA fallback).
  - **PHP-FPM** → pool per site, selectable PHP version.
  - **Redirect** → 301/302 to another URL.
- Per-site options: www ↔ non-www redirect, force HTTPS, HSTS, gzip/brotli, client max body size, custom headers, basic auth, IP allow/deny, rate limiting, WebSocket upgrade, custom location blocks.
- Templates rendered to `/etc/nginx/sites-available/<domain>.conf`, symlinked into `sites-enabled`.
- `nginx -t` runs before every reload; failed validation rolls back the previous config automatically.
- Raw config editor with syntax highlighting and "managed block" markers so manual edits survive regeneration.
- Access/error log viewer per site.
- Optional Caddy or Traefik backend behind the same site model (post-v1).

### 2.4 SSL / TLS

- ACME integration (Let's Encrypt, ZeroSSL, Buypass) via built-in ACME client (no external `certbot` dependency).
- Challenge types: HTTP-01 (default), DNS-01 (Cloudflare, Hetzner, Route53, DigitalOcean, deSEC, RFC2136), TLS-ALPN-01.
- Wildcard certs via DNS-01.
- Auto-renew at 30 days remaining; retry with backoff; alert on repeated failure.
- Upload custom certificates (PEM) and self-signed generation for internal services.
- Certificate inventory: issuer, SANs, expiry, key type (RSA-2048/4096, ECDSA P-256/P-384), OCSP stapling status.
- Automatic Nginx reload on renewal.

### 2.5 Firewall

- Backend: `nftables` (preferred) with `ufw` compatibility mode for existing setups.
- Rule editor: port/range, protocol, source CIDR, action (allow/deny/limit), comment, enabled toggle.
- Presets: SSH, HTTP/HTTPS, Docker-safe defaults, WireGuard, mail ports.
- Docker-aware: correctly handles the `DOCKER-USER` chain so published container ports respect panel rules.
- Fail2ban / CrowdSec integration: enable jails for SSH, Nginx, custom; view banned IPs; unban; whitelist.
- Dry-run diff before apply; "lockout protection" keeps the current admin IP allowed on the SSH port for 5 minutes after any change (auto-revert if the agent loses contact with the control plane).
- GeoIP block lists (optional, MaxMind GeoLite2 or DB-IP).

### 2.6 SSH & Users

- Linux user management: create, disable, delete, set shell, groups, sudo (with/without password).
- SSH key management per user: add, remove, label, expiry date, last-used timestamp (parsed from `auth.log`/journal).
- Hardening wizard: disable password auth, disable root login, change port, restrict to `AllowUsers`, enforce `MaxAuthTries`, set `ClientAliveInterval`. Writes to `/etc/ssh/sshd_config.d/serverpilotx.conf`.
- `sshd -t` validation and keep-alive session check before applying.
- Web terminal (xterm.js over WebSocket) to the host, scoped by panel RBAC and fully recorded to the audit log (opt-in session recording).
- SSH login history and active sessions.

### 2.7 Metrics & Monitoring

- Agent collects every 10s: CPU (per core), load, memory, swap, disk usage & I/O, network throughput, temperatures (if available), per-container stats, Nginx requests/sec, Docker events.
- Storage: embedded TimescaleDB (Postgres) on the control plane; configurable retention (default 90 days raw, 1 year downsampled).
- Alert rules: threshold + duration (e.g. CPU > 90% for 5 min), disk free < X GB, container down, cert expiring, service failed, unreachable agent.
- Notification channels: email (SMTP), Telegram, Discord, Slack, Matrix, Gotify, ntfy, generic webhook, PagerDuty.
- Prometheus `/metrics` endpoint on each agent (optional) for existing Grafana stacks.
- Uptime checks for sites (HTTP status/keyword/latency) from the control plane.

### 2.8 Services, Cron & Processes

- systemd unit list: status, enabled/disabled, start/stop/restart, logs (journal).
- Cron editor per user with human-readable schedule preview and run history.
- Process table (top-like) with kill/renice.
- Package updates: list available, apply security-only or all, unattended-upgrades toggle, reboot-required indicator.

### 2.9 Files

- File manager under configurable roots (default `/opt/serverpilotx`, `/var/www`, `/etc/nginx`, `/home/<user>`).
- Edit text files with syntax highlighting, upload/download, chmod/chown, create archives, extract.
- Disk usage analyzer (treemap).

### 2.10 Backups

- Targets: local directory, S3-compatible (AWS, Backblaze B2, Wasabi, MinIO), SFTP, restic repository, Hetzner Storage Box.
- Scopes: Docker volumes (with pre/post hooks, e.g. `pg_dump`), compose stack dirs, Nginx config, SSL certs, arbitrary paths, panel database.
- Schedules with retention (daily/weekly/monthly), encryption (restic/age), integrity verification.
- Restore wizard: browse snapshots, restore to original or alternate path, restore a single volume.

### 2.11 Multi-Server & Teams

- Control plane ↔ agent over mutual TLS (agent-initiated outbound WebSocket, no inbound ports required on managed servers).
- Server groups and tags; bulk actions (update all, restart Nginx on group).
- RBAC: Owner, Admin, Operator (no user/firewall changes), Viewer (read-only); scoped per server or group.
- API tokens with scopes and expiry.
- Audit log: who, what, when, from which IP, before/after diff for config changes; exportable; forwarded to syslog optionally.

### 2.12 Security

- Login: username/password (argon2id), TOTP 2FA, WebAuthn/passkeys, optional OIDC (Authentik, Keycloak, Google, GitHub).
- Session hardening: HttpOnly/SameSite cookies, CSRF tokens, IP pinning (optional), idle timeout.
- Brute-force protection on the panel login.
- Panel itself runs as unprivileged user `serverpilotx`; privileged operations go through a local root helper (`spx-privd`) over a Unix socket with a strict allow-listed command set — no `sudo ALL`.
- Secrets at rest encrypted with a master key (`SPX_MASTER_KEY`), rotatable.
- Signed releases; agent self-update verifies signatures.
- No telemetry. Update check is opt-in and only hits a static JSON file.

---

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Browser (React SPA)  ──HTTPS──▶  Control Plane (spx-server)  │
│                                   ├─ REST API  (/api/v1)      │
│                                   ├─ WebSocket (/ws)          │
│                                   ├─ Postgres + TimescaleDB   │
│                                   └─ Job queue (embedded)     │
│                                          ▲ mTLS WebSocket     │
│                                          │ (agent-initiated)  │
│  ┌───────────────────────────────────────┴──────────────────┐ │
│  │ Managed Server                                           │ │
│  │  spx-agent (user: serverpilotx)                          │ │
│  │   ├─ metrics collector                                   │ │
│  │   ├─ docker client  (socket group access)                │ │
│  │   ├─ nginx/ssl/firewall/ssh modules                      │ │
│  │   └─ spx-privd (root helper, unix socket, allow-list)    │ │
│  └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

- **Single-server mode:** control plane and agent run on the same host as one binary (`spx-server --embedded-agent`).
- **Multi-server mode:** control plane on one host; `spx-agent` installed on each managed server via a one-line installer with a registration token.
- All agent → control-plane communication is outbound; managed servers need no open ports beyond what the user's sites require.
- Control plane dispatches **jobs** (idempotent, versioned, with rollback handlers) to agents; agents report state back; the UI reconciles desired vs. actual state.

---

## 4. Tech Stack

| Layer | Choice | Rationale |
|---|---|---|
| Backend / agent | **Go 1.23** | Single static binary, easy cross-compile, strong stdlib for TLS/net |
| HTTP router | chi | Lightweight, stdlib-compatible |
| ACME | lego library | Mature, many DNS providers |
| Docker | docker/docker client SDK | Official |
| Firewall | nftables via `google/nftables`; ufw fallback via exec | Native netlink, no shelling out for the main path |
| DB | PostgreSQL 16 + TimescaleDB (control plane); SQLite for agent local state | Time-series metrics + relational config |
| Queue | River (Postgres-backed job queue) | No extra infra |
| Frontend | React 19, TypeScript, Vite, TanStack Query/Router, Tailwind, shadcn/ui, xterm.js, Recharts | Fast, standard, embeddable into the Go binary |
| Auth | argon2id, TOTP (RFC 6238), WebAuthn (go-webauthn), OIDC (coreos/go-oidc) | |
| CLI | `spx` (Cobra) | Mirrors API |
| Packaging | `.deb`, `.rpm`, static tarball, Docker image, `install.sh` | |

### 4.1 Supported platforms (agent)

- Debian 11/12, Ubuntu 22.04/24.04, RHEL/Alma/Rocky 8/9, Fedora 39+, Arch (best-effort).
- x86_64 and arm64.
- Requires: systemd, Docker Engine ≥ 24 (optional — Docker features hidden if absent), Nginx ≥ 1.22 (installed by the panel if absent).

---

## 5. Data Model (control plane, simplified)

```
users            (id, username, email, password_hash, totp_secret_enc, role, created_at, disabled)
api_tokens       (id, user_id, name, token_hash, scopes[], expires_at, last_used_at)
servers          (id, name, hostname, os, arch, agent_version, agent_cert_fp, group_id, tags[], last_seen_at, status)
server_groups    (id, name)
server_access    (user_id, server_id|group_id, role)
sites            (id, server_id, type, domains[], upstream, root, php_version, options jsonb, enabled, cert_id)
certificates     (id, server_id, domains[], issuer, challenge, dns_provider_id, key_type, not_after, status, pem_enc)
dns_providers    (id, type, credentials_enc)
firewall_rules   (id, server_id, position, action, proto, port_from, port_to, src_cidr, comment, enabled)
stacks           (id, server_id, name, source{editor|git}, git_url, git_ref, env_enc, compose_hash, deployed_hash)
linux_users      (id, server_id, username, uid, shell, sudo, disabled)
ssh_keys         (id, linux_user_id, label, public_key, fingerprint, expires_at, last_used_at)
cron_jobs        (id, server_id, linux_user_id, schedule, command, enabled)
backup_targets   (id, type, config_enc)
backup_plans     (id, server_id, target_id, scope jsonb, schedule, retention jsonb, encryption)
backup_runs      (id, plan_id, started_at, finished_at, status, size_bytes, snapshot_id, log)
alert_rules      (id, server_id|null, metric, op, threshold, for_seconds, channels[], enabled)
notification_channels (id, type, config_enc)
jobs             (id, server_id, type, payload jsonb, status, result jsonb, created_by, created_at, finished_at)
audit_log        (id, user_id, server_id, action, resource, before jsonb, after jsonb, ip, user_agent, at)
metrics          (time, server_id, container_id|null, name, value)   -- Timescale hypertable
```

---

## 6. API

Base: `https://<panel>/api/v1` — JSON, Bearer token or session cookie. OpenAPI 3.1 spec served at `/api/v1/openapi.json`.

Representative endpoints:

```
POST   /auth/login                 POST /auth/totp/verify       POST /auth/logout
GET    /servers                    POST /servers/register        GET  /servers/{id}/metrics?range=24h
GET    /servers/{id}/containers    POST /servers/{id}/containers/{cid}/restart
GET    /servers/{id}/containers/{cid}/logs   (WS: /ws/servers/{id}/containers/{cid}/logs)
GET    /servers/{id}/stacks        POST /servers/{id}/stacks     POST /servers/{id}/stacks/{name}/up
GET    /servers/{id}/sites         POST /servers/{id}/sites      PUT  /servers/{id}/sites/{sid}   DELETE ...
POST   /servers/{id}/sites/{sid}/ssl/issue    POST /servers/{id}/certificates/{cid}/renew
GET    /servers/{id}/firewall      PUT  /servers/{id}/firewall   (full ruleset, atomic apply)
GET    /servers/{id}/users         POST /servers/{id}/users/{u}/ssh-keys
POST   /servers/{id}/ssh/harden
GET    /servers/{id}/services      POST /servers/{id}/services/{unit}/restart
GET    /servers/{id}/updates       POST /servers/{id}/updates/apply
GET    /backups/plans              POST /backups/plans/{id}/run   POST /backups/runs/{id}/restore
GET    /alerts/rules               GET  /audit?server=&user=&from=&to=
WS     /ws/servers/{id}/terminal   WS   /ws/events
```

Jobs are async: mutating calls return `202 { job_id }`; poll `GET /jobs/{id}` or subscribe on `/ws/events`.

### 6.1 CLI

```
spx login https://panel.example.com
spx servers ls
spx site add --server vps1 --domain app.example.com --proxy 127.0.0.1:3000 --ssl
spx stack deploy --server vps1 --name gitea -f docker-compose.yml
spx fw allow --server vps1 --port 51820/udp --from 0.0.0.0/0 --comment wireguard
spx ssh harden --server vps1 --port 2222 --no-password-auth
spx backup run --plan nightly
spx logs --server vps1 --container nextcloud -f
```

---

## 7. Installation & Deployment

### 7.1 Single server (recommended start)

```bash
curl -fsSL https://get.serverpilotx.io | sudo bash
# → installs spx-server + embedded agent, Postgres (via Docker or native), Nginx if missing
# → prints the one-time setup URL: https://<ip>:8443/setup
```

### 7.2 Docker Compose (control plane)

```yaml
services:
  spx:
    image: ghcr.io/serverpilotx/server:1
    restart: unless-stopped
    ports: ["8443:8443"]
    environment:
      SPX_DB_URL: postgres://spx:${DB_PASS}@db:5432/spx
      SPX_MASTER_KEY: ${SPX_MASTER_KEY}      # openssl rand -hex 32
      SPX_BASE_URL: https://panel.example.com
    volumes:
      - spx_data:/var/lib/serverpilotx
  db:
    image: timescale/timescaledb:latest-pg16
    restart: unless-stopped
    environment:
      POSTGRES_USER: spx
      POSTGRES_PASSWORD: ${DB_PASS}
      POSTGRES_DB: spx
    volumes:
      - spx_db:/var/lib/postgresql/data
volumes:
  spx_data:
  spx_db:
```

### 7.3 Adding a managed server

```bash
curl -fsSL https://panel.example.com/install-agent.sh | sudo bash -s -- --token <registration-token>
```

### 7.4 Filesystem layout (managed server)

```
/opt/serverpilotx/
├── stacks/<name>/docker-compose.yml, .env
├── apps/<name>/            # app catalog deployments
├── backups/                # local backup target (optional)
└── agent/                  # SQLite state, certs, logs
/etc/serverpilotx/agent.yaml
/etc/nginx/sites-available/*.conf        # managed with SPX markers
/etc/ssh/sshd_config.d/serverpilotx.conf
/etc/nftables.d/serverpilotx.nft
/etc/letsencrypt-spx/                    # ACME account + certs
```

### 7.5 Uninstall

`spx uninstall --keep-configs` removes the agent and panel while leaving Nginx, certs, Docker stacks and firewall rules intact and functional.

---

## 8. UI Structure

```
/dashboard
/servers/{id}
  ├── overview      (metrics, health, quick actions)
  ├── docker        (containers | stacks | images | volumes | networks | catalog)
  ├── sites         (list | new | {site}: general, ssl, security, advanced, logs)
  ├── ssl           (certificates | dns providers)
  ├── firewall      (rules | fail2ban | geo)
  ├── users         (linux users | ssh keys | hardening)
  ├── services      (systemd | cron | processes | updates)
  ├── files
  ├── terminal
  └── settings      (agent, retention, prometheus)
/backups            (plans | targets | runs)
/alerts             (rules | channels | history)
/audit
/settings           (panel users & roles | api tokens | auth/OIDC | branding | update)
```

Design: dark/light theme, keyboard command palette (`⌘K`), mobile-responsive, no page reloads for state changes (WebSocket-driven).

---

## 9. Roadmap

| Milestone | Scope |
|---|---|
| **v0.1 – Core** | Single-server mode, dashboard metrics, Docker containers/stacks, Nginx reverse proxy sites, Let's Encrypt HTTP-01, basic firewall, local auth + TOTP |
| **v0.2 – Hardening** | SSH/user management, audit log, RBAC, API tokens, backups (local + S3), alerting with Telegram/email |
| **v0.3 – Multi-server** | Agent/control-plane split, server groups, bulk actions, DNS-01 providers, wildcard certs |
| **v1.0 – Stable** | App catalog, file manager, web terminal recording, OIDC, WebAuthn, Prometheus endpoint, signed auto-update, `.deb`/`.rpm` packages |
| **v1.x** | Caddy/Traefik backends, CrowdSec, GeoIP, PHP-FPM site type, Git-push deploy hooks, plugin API |

---

## 10. Comparison

| | ServerPilotX | Plesk | cPanel | RunCloud | Portainer | Cockpit |
|---|---|---|---|---|---|---|
| Self-hosted control plane | ✅ | ✅ | ✅ | ❌ (SaaS) | ✅ | ✅ |
| Open source | ✅ AGPL | ❌ | ❌ | ❌ | partial (CE) | ✅ |
| Docker + compose stacks | ✅ | partial | ❌ | ❌ | ✅ | partial |
| Nginx sites + SSL | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| Firewall + fail2ban | ✅ | ✅ | ✅ | ✅ | ❌ | partial |
| SSH key/user mgmt | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |
| Multi-server, agent outbound-only | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ |
| Per-server license cost | none | $$ | $$$ | $ | $ (BE) | none |
| Leaves usable server on uninstall | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |

---

## 11. Open Questions

1. Postgres as a hard dependency for single-server mode, or offer SQLite (losing Timescale downsampling)?
2. Should the app catalog be a Git repo of templates the community can PR to, or bundled in releases?
3. Session recording for the web terminal — on by default or opt-in?
4. Caddy as the default web server instead of Nginx (simpler TLS, fewer moving parts) — worth the migration cost for existing Nginx users?
