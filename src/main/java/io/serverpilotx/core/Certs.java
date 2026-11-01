package io.serverpilotx.core;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Finds certificates on a host (Let's Encrypt, ServerPilotX self-signed/uploaded, Nginx references) and parses them. */
public final class Certs {
    public static final String CUSTOM_DIR = "/etc/ssl/serverpilotx";
    public static final String DNS_DIR = "/etc/letsencrypt-spx";

    public record Cert(String name, String source, String certPath, String keyPath, List<String> domains, String issuer,
                       Instant notAfter, String keyType, boolean selfSigned) {
        public long daysLeft() { return ChronoUnit.DAYS.between(Instant.now(), notAfter); }
    }

    private Certs() {}

    private static final String SCAN = String.join("\n",
            "for f in /etc/letsencrypt/live/*/fullchain.pem " + CUSTOM_DIR + "/*/fullchain.pem; do",
            "  [ -f \"$f\" ] || continue; echo \"@@CERT|$f|$(dirname \"$f\")/privkey.pem\"; cat \"$f\"; done",
            "grep -rhoE '^[[:space:]]*ssl_certificate[[:space:]]+[^;]+' /etc/nginx 2>/dev/null | awk '{print $2}' | sort -u | while read f; do",
            "  case \"$f\" in /etc/letsencrypt/live/*|" + CUSTOM_DIR + "/*) continue;; esac",
            "  [ -f \"$f\" ] || continue; echo \"@@CERT|$f|\"; cat \"$f\"; done",
            "true");

    public static List<Cert> scan(Host h) {
        CmdResult r = h.sudoSh(SCAN);
        List<Cert> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String[] blocks = r.out().split("@@CERT\\|");
        CertificateFactory cf;
        try {
            cf = CertificateFactory.getInstance("X.509");
        } catch (Exception e) {
            return out;
        }
        for (String b : blocks) {
            if (b.isBlank()) continue;
            int nl = b.indexOf('\n');
            if (nl < 0) continue;
            String[] head = b.substring(0, nl).split("\\|", -1);
            String pem = b.substring(nl + 1);
            String certPath = head[0];
            if (!seen.add(certPath)) continue;
            try {
                Collection<? extends java.security.cert.Certificate> chain =
                        cf.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
                if (chain.isEmpty()) continue;
                X509Certificate c = (X509Certificate) chain.iterator().next();
                List<String> doms = new ArrayList<>();
                if (c.getSubjectAlternativeNames() != null) {
                    for (List<?> san : c.getSubjectAlternativeNames()) {
                        if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) doms.add(String.valueOf(san.get(1)));
                    }
                }
                String cn = rdn(c.getSubjectX500Principal().getName(), "CN");
                if (doms.isEmpty() && !cn.isEmpty()) doms.add(cn);
                String issuer = rdn(c.getIssuerX500Principal().getName(), "O");
                if (issuer.isEmpty()) issuer = rdn(c.getIssuerX500Principal().getName(), "CN");
                boolean self = c.getSubjectX500Principal().equals(c.getIssuerX500Principal());
                String dir = certPath.substring(0, certPath.lastIndexOf('/'));
                String name = dir.substring(dir.lastIndexOf('/') + 1);
                String source = certPath.startsWith("/etc/letsencrypt/") ? "ACME" : certPath.startsWith(CUSTOM_DIR) ? (self ? "Self-signed" : "Uploaded") : "Nginx";
                String key = head.length > 1 ? head[1] : "";
                out.add(new Cert(source.equals("Nginx") ? certPath.substring(certPath.lastIndexOf('/') + 1) : name, source,
                        certPath, key, doms, issuer, c.getNotAfter().toInstant(), keyType(c.getPublicKey()), self));
            } catch (Exception ignored) {
                // unreadable/invalid PEM
            }
        }
        return out;
    }

    private static String rdn(String dn, String key) {
        for (String part : dn.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String p = part.strip();
            if (p.startsWith(key + "=")) return p.substring(key.length() + 1).replace("\"", "");
        }
        return "";
    }

    private static String keyType(PublicKey k) {
        if (k instanceof RSAPublicKey r) return "RSA-" + r.getModulus().bitLength();
        if (k instanceof ECPublicKey e) {
            int bits = e.getParams().getCurve().getField().getFieldSize();
            return "ECDSA P-" + bits;
        }
        return k.getAlgorithm();
    }

    /** Creates a self-signed certificate in /etc/ssl/serverpilotx/&lt;name&gt;/. */
    public static String selfSigned(Host h, String name, List<String> domains, int days, String keyType) {
        String dir = CUSTOM_DIR + "/" + name;
        StringBuilder san = new StringBuilder();
        for (String d : domains) {
            if (san.length() > 0) san.append(',');
            san.append(d.matches("[0-9.:]+") ? "IP:" : "DNS:").append(d);
        }
        String keyOpt = keyType.startsWith("RSA") ? "-newkey rsa:" + keyType.substring(4)
                : "-newkey ec -pkeyopt ec_paramgen_curve:" + (keyType.endsWith("384") ? "secp384r1" : "prime256v1");
        String script = "set -e; mkdir -p " + Quote.sh(dir) + "; cd " + Quote.sh(dir) + "; openssl req -x509 -nodes " + keyOpt
                + " -days " + days + " -keyout privkey.pem -out fullchain.pem -subj " + Quote.sh("/CN=" + domains.get(0))
                + " -addext " + Quote.sh("subjectAltName=" + san) + " 2>&1; chmod 600 privkey.pem; chmod 644 fullchain.pem";
        return h.sudoSh(script).orThrow();
    }

    public static void upload(Host h, String name, String certPem, String keyPem) {
        String dir = CUSTOM_DIR + "/" + name;
        h.sudo("mkdir", "-p", dir).orThrow();
        h.writeFileRoot(dir + "/fullchain.pem", certPem.strip() + "\n", "644");
        h.writeFileRoot(dir + "/privkey.pem", keyPem.strip() + "\n", "600");
        CmdResult r = h.sudoSh("a=$(openssl x509 -noout -pubkey -in " + Quote.sh(dir + "/fullchain.pem") + " | openssl sha256);"
                + " b=$(openssl pkey -pubout -in " + Quote.sh(dir + "/privkey.pem") + " | openssl sha256); [ \"$a\" = \"$b\" ]");
        if (!r.ok()) {
            h.sudo("rm", "-rf", dir);
            throw new CmdException("The private key does not match the certificate.");
        }
    }
}
