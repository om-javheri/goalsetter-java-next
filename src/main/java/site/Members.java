package site;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Visitors' accounts (docs/62 in UniversalIDE): sign-ups and sign-ins as the page sends them, passwords and sessions. */
public final class Members {
    private Members() {}

    /** A member as their account is kept. */
    public record Member(long id, String email, String name, String hash, String status, String createdAt) {}

    /** A sign-up or sign-in as the page sends it. */
    public record Input(String email, String password, String name) {}

    /** The members-only page a request is for: its path, its file, and whether the address ends in /. */
    public record Page(String page, String file, boolean slash) {}

    public static final String COOKIE = "uide_member";
    public static final int SESSION_DAYS = 30;
    private static final int ROUNDS = 100000;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", Pattern.UNICODE_CHARACTER_CLASS);
    public static final Pattern TOKEN = Pattern.compile("^[\\w-]{20,100}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    /** Compared against when the email is unknown, so it takes as long as a wrong password. */
    public static final String DUMMY_HASH = hash(HexFormat.of().formatHex(random(12)));

    /** The time in seconds from now, as kept (ISO 8601, UTC). */
    public static String time(long seconds) {
        return TIME.format(Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(seconds));
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** The input, or a Rules.Refusal. */
    public static Object read(ServerConfig config, JsonMapper json, String body, boolean signup) {
        JsonNode data;
        try {
            data = json.readTree(body);
        } catch (Exception e) {
            data = null;
        }
        if (data == null || !data.isObject()) return new Rules.Refusal(400, config.message("unreadable"));
        JsonNode e = data.get("email");
        String email = e != null && e.isString() ? Rules.trim(e.stringValue()).toLowerCase(Locale.ROOT) : "";
        if (!EMAIL.matcher(email).matches() || email.length() > 254) return new Rules.Refusal(400, config.message("memberEmail"));
        JsonNode p = data.get("password");
        String password = p != null && p.isString() ? p.stringValue() : "";
        if (password.length() < 8 || password.length() > 200) return new Rules.Refusal(400, config.message("memberPassword"));
        JsonNode n = data.get("name");
        String name = signup && n != null && n.isString() && !Rules.trim(n.stringValue()).isEmpty() ? Rules.trim(n.stringValue()) : null;
        if (name != null && name.length() > 100) name = name.substring(0, 100);
        return new Input(email, password, name);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int rounds) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, rounds, 256)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A password as kept: PBKDF2-SHA256, the same on every server ("pbkdf2-sha256$rounds$salt$hash", hex). */
    public static String hash(String password) {
        byte[] salt = random(16);
        return "pbkdf2-sha256$" + ROUNDS + "$" + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(pbkdf2(password, salt, ROUNDS));
    }

    public static boolean check(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
        try {
            return MessageDigest.isEqual(pbkdf2(password, HexFormat.of().parseHex(parts[2]), Integer.parseInt(parts[1])), HexFormat.of().parseHex(parts[3]));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String newToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
    }

    public static String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Page page(ServerConfig config, String path) {
        if (config.members() == null || config.members().pages() == null) return null;
        for (Map.Entry<String, String> p : config.members().pages().entrySet()) {
            if (path.equals(p.getKey() + "/") || path.equals(p.getKey() + "/index.html")) return new Page(p.getKey(), p.getValue(), true);
            if (path.equals(p.getKey())) return new Page(p.getKey(), p.getValue(), false);
        }
        return null;
    }
}
