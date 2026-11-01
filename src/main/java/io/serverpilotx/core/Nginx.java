package io.serverpilotx.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nginx site model, config generator and safe apply (write → nginx -t → reload, rollback on failure).
 * Generated files are plain Nginx config; the managed part sits between BEGIN/END markers so manual
 * edits outside it survive regeneration.
 */
public final class Nginx {
    public static final String BEGIN = "# BEGIN SPX MANAGED";
    public static final String END = "# END SPX MANAGED";
    public static final String META = "# SPX-META ";
    public static final String ACME_ROOT = "/var/www/letsencrypt";

    private Nginx() {}

    /** Site settings; serialised into the SPX-META comment so the wizard can re-open a site. */
    public static final class Site {
        public String type = "proxy";          // proxy | static | php | redirect
        public String domains = "";
        public String upstream = "http://127.0.0.1:3000";
        public String root = "/var/www/html";
        public boolean spa;
        public String php = "8.3";
        public String redirectTo = "";
        public int redirectCode = 301;
        public boolean keepPath = true;
        public String canonical = "none";      // none | www | non-www
        public String cert = "";
        public String key = "";
        public boolean forceHttps = true;
        public boolean hsts;
        public boolean gzip = true;
        public boolean websocket = true;
        public String maxBody = "64m";
        public String headers = "";
        public String allow = "";
        public String authUser = "";
        public int rateLimit;
        public String custom = "";

        public List<String> domainList() {
            return parseDomains(domains);
        }

        public String primary() {
            List<String> l = domainList();
            return l.isEmpty() ? "site" : l.get(0);
        }

        public boolean ssl() { return !cert.isBlank() && !key.isBlank(); }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type); m.put("domains", domains); m.put("upstream", upstream); m.put("root", root);
            m.put("spa", spa); m.put("php", php); m.put("redirectTo", redirectTo); m.put("redirectCode", redirectCode);
            m.put("keepPath", keepPath); m.put("canonical", canonical); m.put("cert", cert); m.put("key", key);
            m.put("forceHttps", forceHttps); m.put("hsts", hsts); m.put("gzip", gzip); m.put("websocket", websocket);
            m.put("maxBody", maxBody); m.put("headers", headers); m.put("allow", allow); m.put("authUser", authUser);
            m.put("rateLimit", rateLimit); m.put("custom", custom);
            return m;
        }

        public static Site fromMap(Map<String, Object> m) {
            Site s = new Site();
            s.type = str(m, "type", s.type); s.domains = str(m, "domains", ""); s.upstream = str(m, "upstream", s.upstream);
            s.root = str(m, "root", s.root); s.spa = bool(m, "spa"); s.php = str(m, "php", s.php);
            s.redirectTo = str(m, "redirectTo", ""); s.redirectCode = num(m, "redirectCode", 301); s.keepPath = bool(m, "keepPath");
            s.canonical = str(m, "canonical", "none"); s.cert = str(m, "cert", ""); s.key = str(m, "key", "");
            s.forceHttps = bool(m, "forceHttps"); s.hsts = bool(m, "hsts"); s.gzip = bool(m, "gzip"); s.websocket = bool(m, "websocket");
            s.maxBody = str(m, "maxBody", "64m"); s.headers = str(m, "headers", ""); s.allow = str(m, "allow", "");
            s.authUser = str(m, "authUser", ""); s.rateLimit = num(m, "rateLimit", 0); s.custom = str(m, "custom", "");
            return s;
        }

        private static String str(Map<String, Object> m, String k, String d) { Object v = m.get(k); return v == null ? d : String.valueOf(v); }
        private static boolean bool(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }
        private static int num(Map<String, Object> m, String k, int d) { Object v = m.get(k); return v instanceof Number n ? n.intValue() : d; }
    }

    public static List<String> parseDomains(String text) {
        List<String> l = new ArrayList<>();
        if (text == null) return l;
        for (String d : text.split("[\\s,]+")) {
            String x = d.strip().toLowerCase();
            if (!x.isBlank() && !l.contains(x)) l.add(x);
        }
        return l;
    }

    // ------------------------------------------------------------ layout

    public record Layout(boolean debian, String availableDir, String enabledDir) {
        public String pathFor(String name) { return availableDir + "/" + name + ".conf"; }
    }

    public static Layout layout(Host h) {
        if (h.run("test", "-d", "/etc/nginx/sites-available").ok()) {
            return new Layout(true, "/etc/nginx/sites-available", "/etc/nginx/sites-enabled");
        }
        return new Layout(false, "/etc/nginx/conf.d", "/etc/nginx/conf.d");
    }

    /** True if nginx ≥ 1.25.1 (uses the "http2 on;" directive). */
    public static boolean modernHttp2(Host h) {
        String v = h.run("nginx", "-v").text();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("nginx/(\\d+)\\.(\\d+)\\.(\\d+)").matcher(v);
        if (!m.find()) return true;
        int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2)), c = Integer.parseInt(m.group(3));
        return a > 1 || (a == 1 && (b > 25 || (b == 25 && c >= 1)));
    }

    // ------------------------------------------------------------ generator

    public static String id(Site s) {
        return s.primary().replaceAll("[^a-z0-9._-]", "_");
    }

    public static String generate(Site s, boolean modernH2) {
        String id = id(s);
        List<String> names = s.domainList();
        String serverNames = String.join(" ", names);
        StringBuilder o = new StringBuilder();
        o.append(META).append(Json.write(s.toMap())).append('\n');
        o.append(BEGIN).append('\n');
        o.append("# Generated by ServerPilotX — changes inside this block are overwritten by the panel.\n");
        if (s.rateLimit > 0) {
            o.append("limit_req_zone $binary_remote_addr zone=spx_").append(id.replace('.', '_').replace('-', '_'))
                    .append(":10m rate=").append(s.rateLimit).append("r/s;\n");
        }
        if (s.websocket && s.type.equals("proxy")) {
            o.append("map $http_upgrade $spx_conn_").append(id.replace('.', '_').replace('-', '_')).append(" { default upgrade; '' close; }\n");
        }
        o.append('\n');

        // canonical host redirect blocks (www <-> non-www)
        if (!s.canonical.equals("none") && !names.isEmpty()) {
            String apex = names.get(0).startsWith("www.") ? names.get(0).substring(4) : names.get(0);
            String from = s.canonical.equals("www") ? apex : "www." + apex;
            String to = s.canonical.equals("www") ? "www." + apex : apex;
            String scheme = s.ssl() ? "https" : "http";
            o.append("server {\n    listen 80;\n    listen [::]:80;\n");
            if (s.ssl()) {
                o.append("    listen 443 ssl;\n    listen [::]:443 ssl;\n");
                o.append("    ssl_certificate ").append(s.cert).append(";\n    ssl_certificate_key ").append(s.key).append(";\n");
            }
            o.append("    server_name ").append(from).append(";\n");
            o.append("    location ^~ /.well-known/acme-challenge/ { root ").append(ACME_ROOT).append("; }\n");
            o.append("    location / { return 301 ").append(scheme).append("://").append(to).append("$request_uri; }\n}\n\n");
            names = new ArrayList<>(names);
            names.remove(from);
            serverNames = String.join(" ", names);
            if (serverNames.isBlank()) serverNames = to;
        }

        if (s.ssl()) {
            o.append("server {\n    listen 80;\n    listen [::]:80;\n    server_name ").append(serverNames).append(";\n");
            o.append("    location ^~ /.well-known/acme-challenge/ { root ").append(ACME_ROOT).append("; default_type text/plain; }\n");
            if (s.forceHttps) {
                o.append("    location / { return 301 https://$host$request_uri; }\n}\n\n");
            } else {
                body(o, s, id);
                o.append("}\n\n");
            }
            o.append("server {\n");
            o.append("    listen 443 ssl").append(modernH2 ? "" : " http2").append(";\n");
            o.append("    listen [::]:443 ssl").append(modernH2 ? "" : " http2").append(";\n");
            if (modernH2) o.append("    http2 on;\n");
            o.append("    server_name ").append(serverNames).append(";\n\n");
            o.append("    ssl_certificate ").append(s.cert).append(";\n");
            o.append("    ssl_certificate_key ").append(s.key).append(";\n");
            o.append("    ssl_protocols TLSv1.2 TLSv1.3;\n");
            o.append("    ssl_prefer_server_ciphers off;\n");
            o.append("    ssl_session_cache shared:SPXSSL:10m;\n");
            o.append("    ssl_session_timeout 1d;\n");
            if (s.hsts) o.append("    add_header Strict-Transport-Security \"max-age=63072000; includeSubDomains\" always;\n");
            o.append('\n');
            body(o, s, id);
            o.append("}\n");
        } else {
            o.append("server {\n    listen 80;\n    listen [::]:80;\n    server_name ").append(serverNames).append(";\n");
            o.append("    location ^~ /.well-known/acme-challenge/ { root ").append(ACME_ROOT).append("; default_type text/plain; }\n\n");
            body(o, s, id);
            o.append("}\n");
        }
        o.append(END).append('\n');
        return o.toString();
    }

    private static void body(StringBuilder o, Site s, String id) {
        String zid = id.replace('.', '_').replace('-', '_');
        o.append("    access_log /var/log/nginx/").append(id).append(".access.log;\n");
        o.append("    error_log /var/log/nginx/").append(id).append(".error.log;\n");
        if (!s.maxBody.isBlank()) o.append("    client_max_body_size ").append(s.maxBody.strip()).append(";\n");
        if (s.gzip) {
            o.append("    gzip on;\n    gzip_vary on;\n    gzip_proxied any;\n");
            o.append("    gzip_types text/plain text/css application/json application/javascript text/xml application/xml image/svg+xml;\n");
        }
        for (String line : s.headers.split("\\R")) {
            int c = line.indexOf(':');
            if (c > 0) o.append("    add_header ").append(line.substring(0, c).strip()).append(" \"")
                    .append(line.substring(c + 1).strip().replace("\"", "\\\"")).append("\" always;\n");
        }
        List<String> allow = Arrays.stream(s.allow.split("[\\s,]+")).filter(x -> !x.isBlank()).toList();
        for (String a : allow) o.append("    allow ").append(a).append(";\n");
        if (!allow.isEmpty()) o.append("    deny all;\n");
        if (!s.authUser.isBlank()) {
            o.append("    auth_basic \"Restricted\";\n    auth_basic_user_file /etc/nginx/spx-htpasswd/").append(id).append(";\n");
        }
        if (s.rateLimit > 0) o.append("    limit_req zone=spx_").append(zid).append(" burst=").append(s.rateLimit * 2).append(" nodelay;\n");
        o.append('\n');
        switch (s.type) {
            case "proxy" -> {
                o.append("    location / {\n");
                o.append("        proxy_pass ").append(s.upstream.strip()).append(";\n");
                o.append("        proxy_http_version 1.1;\n");
                o.append("        proxy_set_header Host $host;\n");
                o.append("        proxy_set_header X-Real-IP $remote_addr;\n");
                o.append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n");
                o.append("        proxy_set_header X-Forwarded-Proto $scheme;\n");
                if (s.websocket) {
                    o.append("        proxy_set_header Upgrade $http_upgrade;\n");
                    o.append("        proxy_set_header Connection $spx_conn_").append(zid).append(";\n");
                    o.append("        proxy_read_timeout 3600s;\n");
                }
                o.append("    }\n");
            }
            case "static" -> {
                o.append("    root ").append(s.root.strip()).append(";\n    index index.html index.htm;\n");
                o.append("    location / {\n        try_files $uri $uri/ ").append(s.spa ? "/index.html" : "=404").append(";\n    }\n");
            }
            case "php" -> {
                o.append("    root ").append(s.root.strip()).append(";\n    index index.php index.html;\n");
                o.append("    location / {\n        try_files $uri $uri/ /index.php?$query_string;\n    }\n");
                o.append("    location ~ \\.php$ {\n        include fastcgi_params;\n");
                o.append("        fastcgi_param SCRIPT_FILENAME $realpath_root$fastcgi_script_name;\n");
                o.append("        fastcgi_pass unix:/run/php/php").append(s.php.strip()).append("-fpm.sock;\n    }\n");
                o.append("    location ~ /\\.(?!well-known) { deny all; }\n");
            }
            case "redirect" -> {
                String target = s.redirectTo.strip().replaceAll("/+$", "");
                o.append("    location / {\n        return ").append(s.redirectCode).append(' ').append(target)
                        .append(s.keepPath ? "$request_uri" : "").append(";\n    }\n");
            }
            default -> { }
        }
        if (!s.custom.isBlank()) {
            o.append("\n    # custom directives\n");
            for (String l : s.custom.split("\\R")) o.append("    ").append(l).append('\n');
        }
    }

    /** Replaces the managed block of an existing file, keeping anything outside it. */
    public static String merge(String existing, String generated) {
        if (existing == null || !existing.contains(BEGIN) || !existing.contains(END)) return generated;
        String withoutMeta = existing.replaceAll("(?m)^" + java.util.regex.Pattern.quote(META) + ".*\\R?", "");
        int a = withoutMeta.indexOf(BEGIN);
        int b = withoutMeta.indexOf(END) + END.length();
        String before = withoutMeta.substring(0, a);
        String after = withoutMeta.substring(Math.min(withoutMeta.length(), b)).replaceFirst("^\\R", "");
        return before + generated + after;
    }

    public static Site parseMeta(String content) {
        for (String line : content.split("\\R")) {
            if (line.startsWith(META)) {
                try { return Site.fromMap(Json.obj(line.substring(META.length()))); } catch (RuntimeException e) { return null; }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ apply

    /**
     * Writes a site config, validates with nginx -t and reloads. On validation failure the previous
     * file (or no file) is restored and the nginx error is thrown.
     */
    public static String apply(Host h, Layout l, String path, String content, boolean enable) {
        boolean existed = h.exists(path);
        String backup = existed ? h.readFile(path) : null;
        String name = path.substring(path.lastIndexOf('/') + 1);
        String link = l.enabledDir() + "/" + name;
        boolean linkExisted = l.debian() && h.run("test", "-L", link).ok();
        h.writeFileRoot(path, content, "644");
        if (l.debian() && enable && !linkExisted) h.sudo("ln", "-sf", path, link).orThrow();
        CmdResult t = h.sudo("nginx", "-t");
        if (!t.ok()) {
            if (existed) h.writeFileRoot(path, backup, "644");
            else h.sudo("rm", "-f", path);
            if (l.debian() && !linkExisted) h.sudo("rm", "-f", link);
            throw new CmdException("nginx -t failed — previous configuration restored.\n\n" + t.text());
        }
        reload(h);
        return t.text();
    }

    public static void reload(Host h) {
        CmdResult r = h.sudo("systemctl", "reload", "nginx");
        if (!r.ok()) {
            CmdResult s = h.sudo("systemctl", "start", "nginx");
            if (!s.ok()) h.sudo("nginx", "-s", "reload").orThrow();
        }
    }

    public static void setEnabled(Host h, Layout l, String path, boolean enable) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (l.debian()) {
            String link = l.enabledDir() + "/" + name;
            if (enable) h.sudo("ln", "-sf", path, link).orThrow();
            else h.sudo("rm", "-f", link).orThrow();
        } else {
            String target = enable ? path.replaceAll("\\.disabled$", "") : path + ".disabled";
            if (!target.equals(path)) h.sudo("mv", path, target).orThrow();
        }
        CmdResult t = h.sudo("nginx", "-t");
        if (!t.ok()) {
            // undo
            if (l.debian()) {
                String link = l.enabledDir() + "/" + name;
                if (enable) h.sudo("rm", "-f", link); else h.sudo("ln", "-sf", path, link);
            } else {
                String target = enable ? path.replaceAll("\\.disabled$", "") : path + ".disabled";
                if (!target.equals(path)) h.sudo("mv", target, path);
            }
            throw new CmdException("nginx -t failed — change reverted.\n\n" + t.text());
        }
        reload(h);
    }

    public static void setBasicAuth(Host h, String id, String user, String password) {
        String hash = h.runIn(password + "\n", "openssl", "passwd", "-apr1", "-stdin").orThrow().strip();
        h.sudo("mkdir", "-p", "/etc/nginx/spx-htpasswd").orThrow();
        h.writeFileRoot("/etc/nginx/spx-htpasswd/" + id, user + ":" + hash + "\n", "640");
        String f = Quote.sh("/etc/nginx/spx-htpasswd/" + id);
        h.sudoSh("G=$(ps -o group= -C nginx | grep -v root | head -n 1 | tr -d ' '); if [ -n \"$G\" ]; then chgrp \"$G\" " + f + "; else chmod 644 " + f + "; fi");
    }
}
