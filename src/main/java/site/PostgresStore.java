package site;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Form messages and daily page-view counters in PostgreSQL (DATABASE_URL). Nothing about visitors is kept. */
public final class PostgresStore implements Store {
    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS form_messages (id BIGSERIAL PRIMARY KEY, form_id VARCHAR(200) NOT NULL, page VARCHAR(300) NOT NULL, fields TEXT NOT NULL, created_at VARCHAR(30) NOT NULL)",
        "CREATE TABLE IF NOT EXISTS daily_counts (day CHAR(10) NOT NULL, kind VARCHAR(20) NOT NULL, \"key\" VARCHAR(255) NOT NULL, count BIGINT NOT NULL, PRIMARY KEY (day, kind, \"key\"))",
        "CREATE TABLE IF NOT EXISTS rate_hits (bucket VARCHAR(100) NOT NULL, at DOUBLE PRECISION NOT NULL)",
        "CREATE INDEX IF NOT EXISTS rate_hits_bucket ON rate_hits (bucket, at)",
        "CREATE TABLE IF NOT EXISTS settings (name VARCHAR(50) PRIMARY KEY, value VARCHAR(200) NOT NULL)",
        "CREATE TABLE IF NOT EXISTS members (id BIGSERIAL PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE, name VARCHAR(100) NULL, password_hash VARCHAR(200) NOT NULL, status VARCHAR(10) NOT NULL, created_at VARCHAR(30) NOT NULL)",
        "CREATE TABLE IF NOT EXISTS member_sessions (token_hash CHAR(64) PRIMARY KEY, member_id BIGINT NOT NULL, expires_at VARCHAR(30) NOT NULL)",
        "CREATE INDEX IF NOT EXISTS member_sessions_member ON member_sessions (member_id)",
        "CREATE TABLE IF NOT EXISTS records (id BIGSERIAL PRIMARY KEY, collection VARCHAR(40) NOT NULL, member_id BIGINT NULL, record_values TEXT NOT NULL, created_at VARCHAR(30) NOT NULL, updated_at VARCHAR(30) NOT NULL)",
        "CREATE INDEX IF NOT EXISTS records_list ON records (collection, id)",
        "CREATE INDEX IF NOT EXISTS records_member ON records (member_id)",
    };
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern LOCAL = Pattern.compile("^(localhost|127\\.\\d+\\.\\d+\\.\\d+|::1|\\[::1\\])$");

    private final String jdbcUrl;
    private final Properties login = new Properties();
    private final int keepMessages;
    private final JsonMapper json;
    private Connection db;

    /**
     * postgres://user:password@host:5432/site. Hosted PostgreSQL (Neon, Supabase, Render…) is reached
     * over TLS, checked against the certificate; it is off when the server is on this machine, or
     * with ?sslmode=disable (or ?ssl=false).
     */
    public PostgresStore(String url, int keepMessages, JsonMapper json) throws Exception {
        URI uri = URI.create(url);
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        boolean off = query.contains("sslmode=disable") || query.contains("ssl=false");
        boolean tls = !off && (query.contains("ssl=true") || query.contains("sslmode=") || !LOCAL.matcher(uri.getHost()).matches());
        String[] user = (uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo()).split(":", 2);
        login.setProperty("user", URLDecoder.decode(user[0], StandardCharsets.UTF_8));
        if (user.length > 1) login.setProperty("password", URLDecoder.decode(user[1], StandardCharsets.UTF_8));
        login.setProperty("sslmode", tls ? "verify-full" : "disable");
        // The JVM's own trusted certificates, as hosted servers' are publicly signed.
        if (tls) login.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
        jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 5432) + uri.getRawPath();
        this.keepMessages = keepMessages;
        this.json = json;
        try (Statement s = connection().createStatement()) {
            for (String sql : SCHEMA) s.execute(sql);
        }
    }

    /** One connection, opened again when the server has closed it. */
    private Connection connection() throws SQLException {
        if (db == null || !db.isValid(2)) db = DriverManager.getConnection(jdbcUrl, login);
        return db;
    }

    private static String today(int offset) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(offset).toString();
    }

    @Override
    public synchronized void addMessage(Rules.Message m, String createdAt) throws Exception {
        try (PreparedStatement add = connection().prepareStatement("INSERT INTO form_messages (form_id, page, fields, created_at) VALUES (?, ?, ?, ?)");
             PreparedStatement trim = connection().prepareStatement("DELETE FROM form_messages WHERE id <= (SELECT id FROM form_messages ORDER BY id DESC LIMIT 1 OFFSET ?)")) {
            add.setString(1, m.formId());
            add.setString(2, m.page());
            add.setString(3, json.writeValueAsString(m.fields()));
            add.setString(4, createdAt);
            add.executeUpdate();
            trim.setInt(1, keepMessages);
            trim.executeUpdate();
        }
    }

    @Override
    public synchronized List<Map<String, Object>> messages(int limit) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement q = connection().prepareStatement("SELECT id, form_id, page, fields, created_at FROM form_messages ORDER BY id DESC LIMIT ?")) {
            q.setInt(1, limit);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getLong("id"));
                    m.put("formId", r.getString("form_id"));
                    m.put("page", r.getString("page"));
                    m.put("fields", json.readTree(r.getString("fields")));
                    m.put("createdAt", r.getString("created_at"));
                    out.add(m);
                }
            }
        }
        return out;
    }

    @Override
    public synchronized boolean deleteMessage(long id) throws Exception {
        try (PreparedStatement q = connection().prepareStatement("DELETE FROM form_messages WHERE id = ?")) {
            q.setLong(1, id);
            return q.executeUpdate() > 0;
        }
    }

    @Override
    public synchronized void bump(String day, List<Rules.Bump> rows) throws Exception {
        try (PreparedStatement q = connection().prepareStatement("INSERT INTO daily_counts (day, kind, \"key\", count) VALUES (?, ?, ?, ?) ON CONFLICT (day, kind, \"key\") DO UPDATE SET count = daily_counts.count + EXCLUDED.count")) {
            for (Rules.Bump b : rows) {
                q.setString(1, day);
                q.setString(2, b.kind());
                q.setString(3, Rules.key(b.key()));
                q.setLong(4, b.count());
                q.executeUpdate();
            }
        }
    }

    @Override
    public synchronized void once(String day, List<String[]> rows) throws Exception {
        try (PreparedStatement q = connection().prepareStatement("INSERT INTO daily_counts (day, kind, \"key\", count) VALUES (?, ?, ?, 1) ON CONFLICT DO NOTHING")) {
            for (String[] r : rows) {
                q.setString(1, day);
                q.setString(2, r[0]);
                q.setString(3, Rules.key(r[1]));
                q.executeUpdate();
            }
        }
    }

    @Override
    public synchronized List<Rules.Count> counts(String since) throws Exception {
        List<Rules.Count> out = new ArrayList<>();
        try (PreparedStatement q = connection().prepareStatement("SELECT day, kind, \"key\", count FROM daily_counts WHERE day >= ?")) {
            q.setString(1, since);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) out.add(new Rules.Count(r.getString("day"), r.getString("kind"), r.getString("key"), r.getLong("count")));
            }
        }
        return out;
    }

    @Override
    public synchronized String secret() throws Exception {
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        try (PreparedStatement put = connection().prepareStatement("INSERT INTO settings (name, value) VALUES (?, ?) ON CONFLICT DO NOTHING");
             PreparedStatement get = connection().prepareStatement("SELECT value FROM settings WHERE name = ?")) {
            put.setString(1, "visitor-secret");
            put.setString(2, java.util.HexFormat.of().formatHex(random));
            put.executeUpdate();
            get.setString(1, "visitor-secret");
            try (ResultSet r = get.executeQuery()) {
                r.next();
                return r.getString(1);
            }
        }
    }

    private List<Map<String, Object>> totals(String kind, String since) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement q = connection().prepareStatement("SELECT \"key\", SUM(count) AS count FROM daily_counts WHERE kind = ? AND day >= ? GROUP BY \"key\" ORDER BY count DESC, \"key\"")) {
            q.setString(1, kind);
            q.setString(2, since);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("key", r.getString("key"));
                    t.put("count", r.getLong("count"));
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static Members.Member member(ResultSet r) throws SQLException {
        return new Members.Member(r.getLong("id"), r.getString("email"), r.getString("name"), r.getString("password_hash"), r.getString("status"), r.getString("created_at"));
    }

    @Override
    public synchronized Long addMember(String email, String name, String hash, String status) throws Exception {
        try (PreparedStatement q = connection().prepareStatement("INSERT INTO members (email, name, password_hash, status, created_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (email) DO NOTHING")) {
            q.setString(1, email);
            q.setString(2, name);
            q.setString(3, hash);
            q.setString(4, status);
            q.setString(5, Members.time(0));
            if (q.executeUpdate() == 0) return null;
        }
        return memberByEmail(email).id();
    }

    @Override
    public synchronized Members.Member memberByEmail(String email) throws Exception {
        try (PreparedStatement q = connection().prepareStatement("SELECT * FROM members WHERE email = ?")) {
            q.setString(1, email);
            try (ResultSet r = q.executeQuery()) {
                return r.next() ? member(r) : null;
            }
        }
    }

    @Override
    public synchronized void addSession(String tokenHash, long memberId, String expiresAt) throws Exception {
        try (PreparedStatement q = connection().prepareStatement("INSERT INTO member_sessions (token_hash, member_id, expires_at) VALUES (?, ?, ?)")) {
            q.setString(1, tokenHash);
            q.setLong(2, memberId);
            q.setString(3, expiresAt);
            q.executeUpdate();
        }
    }

    @Override
    public synchronized Members.Member sessionMember(String tokenHash, String now) throws Exception {
        try (PreparedStatement q = connection().prepareStatement("SELECT m.* FROM member_sessions s JOIN members m ON m.id = s.member_id WHERE s.token_hash = ? AND s.expires_at > ? AND m.status = 'active'")) {
            q.setString(1, tokenHash);
            q.setString(2, now);
            try (ResultSet r = q.executeQuery()) {
                return r.next() ? member(r) : null;
            }
        }
    }

    @Override
    public synchronized void endSession(String tokenHash) throws Exception {
        try (PreparedStatement q = connection().prepareStatement("DELETE FROM member_sessions WHERE token_hash = ?")) {
            q.setString(1, tokenHash);
            q.executeUpdate();
        }
    }

    @Override
    public synchronized List<Members.Member> members() throws Exception {
        List<Members.Member> out = new ArrayList<>();
        try (PreparedStatement q = connection().prepareStatement("SELECT * FROM members ORDER BY id"); ResultSet r = q.executeQuery()) {
            while (r.next()) out.add(member(r));
        }
        return out;
    }

    @Override
    public synchronized boolean approveMember(long id) throws Exception {
        try (PreparedStatement q = connection().prepareStatement("UPDATE members SET status = 'active' WHERE id = ?")) {
            q.setLong(1, id);
            return q.executeUpdate() > 0;
        }
    }

    @Override
    public synchronized boolean removeMember(long id) throws Exception {
        try (PreparedStatement sessions = connection().prepareStatement("DELETE FROM member_sessions WHERE member_id = ?");
             PreparedStatement q = connection().prepareStatement("DELETE FROM members WHERE id = ?")) {
            sessions.setLong(1, id);
            sessions.executeUpdate();
            SqlRecords.unown(connection(), id);
            q.setLong(1, id);
            return q.executeUpdate() > 0;
        }
    }

    @Override
    public synchronized List<Store.RecordRow> records(String collection, Long memberId, int limit) throws Exception {
        return SqlRecords.list(connection(), collection, memberId, limit);
    }

    @Override
    public synchronized long recordCount(String collection) throws Exception {
        return SqlRecords.count(connection(), collection);
    }

    @Override
    public synchronized Store.RecordRow addRecord(String collection, Long memberId, String values, String now) throws Exception {
        return SqlRecords.add(connection(), collection, memberId, values, now);
    }

    @Override
    public synchronized Store.RecordRow record(String collection, long id) throws Exception {
        return SqlRecords.get(connection(), collection, id);
    }

    @Override
    public synchronized void updateRecord(long id, String values, String now) throws Exception {
        SqlRecords.update(connection(), id, values, now);
    }

    @Override
    public synchronized boolean deleteRecord(String collection, long id) throws Exception {
        return SqlRecords.delete(connection(), collection, id);
    }

    @Override
    public synchronized Map<String, Object> stats(int days) throws Exception {
        String since = today(-(days - 1));
        List<Map<String, Object>> daily = new ArrayList<>();
        try (PreparedStatement q = connection().prepareStatement("SELECT day, SUM(count) AS views FROM daily_counts WHERE kind = 'view' AND day >= ? GROUP BY day ORDER BY day")) {
            q.setString(1, since);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("day", r.getString("day"));
                    d.put("views", r.getLong("views"));
                    daily.add(d);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("daily", daily);
        out.put("pages", totals("view", since));
        out.put("referrers", totals("referrer", since));
        return out;
    }

    @Override
    public synchronized Boolean allow(String bucket, int perMinute) throws Exception {
        double now = System.currentTimeMillis() / 1000.0;
        try (PreparedStatement clear = connection().prepareStatement("DELETE FROM rate_hits WHERE at <= ?");
             PreparedStatement count = connection().prepareStatement("SELECT COUNT(*) FROM rate_hits WHERE bucket = ?");
             PreparedStatement hit = connection().prepareStatement("INSERT INTO rate_hits (bucket, at) VALUES (?, ?)")) {
            clear.setDouble(1, now - 60);
            clear.executeUpdate();
            count.setString(1, bucket);
            try (ResultSet r = count.executeQuery()) {
                r.next();
                if (r.getLong(1) >= perMinute) return false;
            }
            hit.setString(1, bucket);
            hit.setDouble(2, now);
            hit.executeUpdate();
            return true;
        }
    }
}
