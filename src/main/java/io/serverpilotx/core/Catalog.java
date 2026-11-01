package io.serverpilotx.core;

import java.security.SecureRandom;
import java.util.List;

/** One-click app templates. Variables go to the stack's .env file; compose reads them as ${VAR}. */
public final class Catalog {
    public record Var(String key, String label, String def, boolean secret) {}

    public record App(String id, String name, String description, String icon, String upstreamVar, List<Var> vars, String compose) {}

    private Catalog() {}

    public static String randomSecret() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 24; i++) b.append(chars.charAt(r.nextInt(chars.length())));
        return b.toString();
    }

    private static final Var BIND = new Var("BIND", "Bind address", "127.0.0.1", false);

    public static final List<App> APPS = List.of(
            new App("nextcloud", "Nextcloud", "Files, calendar and contacts in your own cloud", "folder", "PORT",
                    List.of(BIND, new Var("PORT", "Web port", "8080", false), new Var("DB_PASSWORD", "Database password", "", true)),
                    """
                    services:
                      db:
                        image: mariadb:11
                        restart: unless-stopped
                        command: --transaction-isolation=READ-COMMITTED
                        environment:
                          MARIADB_ROOT_PASSWORD: ${DB_PASSWORD}
                          MARIADB_DATABASE: nextcloud
                          MARIADB_USER: nextcloud
                          MARIADB_PASSWORD: ${DB_PASSWORD}
                        volumes:
                          - db:/var/lib/mysql
                      app:
                        image: nextcloud:stable
                        restart: unless-stopped
                        depends_on: [db]
                        ports:
                          - "${BIND}:${PORT}:80"
                        environment:
                          MYSQL_HOST: db
                          MYSQL_DATABASE: nextcloud
                          MYSQL_USER: nextcloud
                          MYSQL_PASSWORD: ${DB_PASSWORD}
                        volumes:
                          - data:/var/www/html
                    volumes:
                      db:
                      data:
                    """),
            new App("gitea", "Gitea", "Lightweight self-hosted Git service", "link", "PORT",
                    List.of(BIND, new Var("PORT", "Web port", "3000", false), new Var("SSH_PORT", "Git SSH port", "2222", false)),
                    """
                    services:
                      gitea:
                        image: gitea/gitea:latest
                        restart: unless-stopped
                        environment:
                          USER_UID: "1000"
                          USER_GID: "1000"
                        ports:
                          - "${BIND}:${PORT}:3000"
                          - "${SSH_PORT}:22"
                        volumes:
                          - data:/data
                    volumes:
                      data:
                    """),
            new App("vaultwarden", "Vaultwarden", "Bitwarden-compatible password manager", "lock", "PORT",
                    List.of(BIND, new Var("PORT", "Web port", "8081", false), new Var("ADMIN_TOKEN", "Admin token", "", true),
                            new Var("SIGNUPS_ALLOWED", "Allow sign-ups", "true", false)),
                    """
                    services:
                      vaultwarden:
                        image: vaultwarden/server:latest
                        restart: unless-stopped
                        environment:
                          ADMIN_TOKEN: ${ADMIN_TOKEN}
                          SIGNUPS_ALLOWED: ${SIGNUPS_ALLOWED}
                        ports:
                          - "${BIND}:${PORT}:80"
                        volumes:
                          - data:/data
                    volumes:
                      data:
                    """),
            new App("uptime-kuma", "Uptime Kuma", "Uptime monitoring with a nice status page", "activity", "PORT",
                    List.of(BIND, new Var("PORT", "Web port", "3001", false)),
                    """
                    services:
                      uptime-kuma:
                        image: louislam/uptime-kuma:1
                        restart: unless-stopped
                        ports:
                          - "${BIND}:${PORT}:3001"
                        volumes:
                          - data:/app/data
                    volumes:
                      data:
                    """),
            new App("n8n", "n8n", "Workflow automation", "network", "PORT",
                    List.of(BIND, new Var("PORT", "Web port", "5678", false), new Var("TZ", "Time zone", "UTC", false)),
                    """
                    services:
                      n8n:
                        image: n8nio/n8n:latest
                        restart: unless-stopped
                        environment:
                          GENERIC_TIMEZONE: ${TZ}
                          TZ: ${TZ}
                        ports:
                          - "${BIND}:${PORT}:5678"
                        volumes:
                          - data:/home/node/.n8n
                    volumes:
                      data:
                    """),
            new App("grafana", "Grafana", "Dashboards and visualisation", "chart", "PORT",
                    List.of(BIND, new Var("PORT", "Web port", "3002", false), new Var("ADMIN_PASSWORD", "Admin password", "", true)),
                    """
                    services:
                      grafana:
                        image: grafana/grafana-oss:latest
                        restart: unless-stopped
                        environment:
                          GF_SECURITY_ADMIN_PASSWORD: ${ADMIN_PASSWORD}
                        ports:
                          - "${BIND}:${PORT}:3000"
                        volumes:
                          - data:/var/lib/grafana
                    volumes:
                      data:
                    """),
            new App("postgres", "PostgreSQL", "Relational database (bound to localhost)", "database", null,
                    List.of(BIND, new Var("PORT", "Port", "5432", false), new Var("POSTGRES_DB", "Database", "app", false),
                            new Var("POSTGRES_USER", "User", "app", false), new Var("POSTGRES_PASSWORD", "Password", "", true)),
                    """
                    services:
                      postgres:
                        image: postgres:16
                        restart: unless-stopped
                        environment:
                          POSTGRES_DB: ${POSTGRES_DB}
                          POSTGRES_USER: ${POSTGRES_USER}
                          POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
                        ports:
                          - "${BIND}:${PORT}:5432"
                        volumes:
                          - data:/var/lib/postgresql/data
                        healthcheck:
                          test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER}"]
                          interval: 10s
                    volumes:
                      data:
                    """),
            new App("mariadb", "MariaDB", "MySQL-compatible database (bound to localhost)", "database", null,
                    List.of(BIND, new Var("PORT", "Port", "3306", false), new Var("DB_NAME", "Database", "app", false),
                            new Var("DB_USER", "User", "app", false), new Var("DB_PASSWORD", "Password", "", true)),
                    """
                    services:
                      mariadb:
                        image: mariadb:11
                        restart: unless-stopped
                        environment:
                          MARIADB_RANDOM_ROOT_PASSWORD: "yes"
                          MARIADB_DATABASE: ${DB_NAME}
                          MARIADB_USER: ${DB_USER}
                          MARIADB_PASSWORD: ${DB_PASSWORD}
                        ports:
                          - "${BIND}:${PORT}:3306"
                        volumes:
                          - data:/var/lib/mysql
                    volumes:
                      data:
                    """),
            new App("redis", "Redis", "In-memory key/value store (bound to localhost)", "layers", null,
                    List.of(BIND, new Var("PORT", "Port", "6379", false), new Var("REDIS_PASSWORD", "Password", "", true)),
                    """
                    services:
                      redis:
                        image: redis:7-alpine
                        restart: unless-stopped
                        command: ["redis-server", "--requirepass", "${REDIS_PASSWORD}", "--appendonly", "yes"]
                        ports:
                          - "${BIND}:${PORT}:6379"
                        volumes:
                          - data:/data
                    volumes:
                      data:
                    """));
}
