package site;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The site's own server (docs/39 in UniversalIDE): the site from site/, its forms' messages and its
 * page views. Run with PORT, DATA_DIR and ADMIN_KEY (see README.md).
 */
@RestController
public class SiteController {
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "application/xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("pdf", "application/pdf"));

    private final JsonMapper json = JsonMapper.builder().build();
    private final ServerConfig config;
    private final Path site;
    private final String notFoundPage;
    private final Path dataDir;
    private Store store;
    private final Pattern bots;
    private final Limiter messageLimit;
    private final Limiter viewLimit;
    private final Limiter interactionLimit;
    private final Limiter memberLimit;
    private final Limiter recordLimit;
    private final Path privateDir;
    private String secret;
    private final String adminKey = env("ADMIN_KEY", "");
    private final boolean trustProxy = "1".equals(env("TRUST_PROXY", ""));

    public SiteController() throws Exception {
        config = ServerConfig.load(json);
        Path root = ServerConfig.root();
        site = root.resolve("site").toRealPath();
        notFoundPage = Files.readString(root.resolve("not-found.html"));
        dataDir = Path.of(env("DATA_DIR", root.resolve("data").toString()));
        bots = Pattern.compile(config.botPattern(), Pattern.CASE_INSENSITIVE);
        messageLimit = new Limiter(config.rules().messagesPerMinute());
        viewLimit = new Limiter(config.rules().viewsPerMinute());
        interactionLimit = new Limiter(config.rules().interactionsPerMinute());
        memberLimit = new Limiter(config.rules().membersPerMinute());
        recordLimit = new Limiter(config.rules().recordWritesPerMinute());
        privateDir = root.resolve("private");
    }

    private static String today(int offset) {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(offset).toString();
    }

    /** The day's visitor hash (the secret read once). */
    private String visitorOf(Store store, HttpServletRequest req, String day) throws Exception {
        synchronized (this) {
            if (secret == null) secret = store.secret();
        }
        String agent = req.getHeader("User-Agent") == null ? "" : req.getHeader("User-Agent");
        return Rules.visitorHash(secret, day, visitor(req), agent);
    }

    /** Opened on the first request that needs it (null: the database server isn't set up); tried again after a failure. */
    private synchronized Store store() throws Exception {
        if (store == null) store = Stores.open(dataDir, config.rules().keepMessages(), json, config);
        return store;
    }

    private ResponseEntity<Object> noDatabase() {
        return answer(503, Map.of("message", "This site can’t take messages yet: its database isn’t set up (DATABASE_URL)."));
    }

    /** The sending limits: kept by a database server when there is one, else in memory. */
    private boolean allow(Store store, String kind, String visitor) throws Exception {
        int perMinute = switch (kind) {
            case "message" -> config.rules().messagesPerMinute();
            case "view" -> config.rules().viewsPerMinute();
            case "member" -> config.rules().membersPerMinute();
            case "record" -> config.rules().recordWritesPerMinute();
            default -> config.rules().interactionsPerMinute();
        };
        Limiter limiter = switch (kind) {
            case "message" -> messageLimit;
            case "view" -> viewLimit;
            case "member" -> memberLimit;
            case "record" -> recordLimit;
            default -> interactionLimit;
        };
        String bucket = kind + ":" + java.util.HexFormat.of().formatHex(sha256(visitor)).substring(0, 32);
        Boolean kept = store.allow(bucket, perMinute);
        return kept != null ? kept : limiter.allow(visitor);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static ResponseEntity<Object> answer(int status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private ResponseEntity<Object> refuse(int status, String key) {
        return answer(status, Map.of("message", config.message(key)));
    }

    /** The visitor's address, for the limits only (never kept). */
    private String visitor(HttpServletRequest req) {
        String forwarded = trustProxy && req.getHeader("X-Forwarded-For") != null ? req.getHeader("X-Forwarded-For").split(",")[0].trim() : "";
        return forwarded.isEmpty() ? req.getRemoteAddr() : forwarded;
    }

    /** The request body as text, or null when larger than the rules allow. */
    private String body(HttpServletRequest req) throws IOException {
        int max = config.rules().maxBody();
        if (req.getContentLengthLong() > max) return null;
        try (InputStream in = req.getInputStream()) {
            byte[] bytes = in.readNBytes(max + 1);
            return bytes.length > max ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    @PostMapping("/api/forms/{id}")
    public ResponseEntity<Object> takeMessage(@PathVariable String id, HttpServletRequest req, HttpServletResponse res) throws Exception {
        // The answer may be read by the page wherever it is shown.
        res.setHeader("Access-Control-Allow-Origin", "*");
        String body = body(req);
        if (body == null) return refuse(413, "tooLong");
        Rules.Result result = Rules.checkMessage(config, json, id, body);
        if (result instanceof Rules.Refusal r) return answer(r.status(), Map.of("message", r.message()));
        Store store = store();
        if (store == null) return noDatabase();
        if (!allow(store, "message", visitor(req))) return refuse(429, "tooMany");
        String createdAt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        store.addMessage((Rules.Message) result, createdAt);
        sendHook((Rules.Message) result, createdAt);
        return answer(200, Map.of("ok", true));
    }

    private final String webhookUrl = env("FORM_WEBHOOK_URL", "");
    private final java.net.http.HttpClient hookClient = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofMillis(4000)).build();

    /** What is posted to FORM_WEBHOOK_URL for a message (docs/64): the same on every server and the platform. */
    static Map<String, Object> webhookBody(Rules.Message m, String createdAt) {
        StringBuilder full = new StringBuilder("New message from the " + m.formId() + " form on " + m.page());
        for (Rules.Field f : m.fields()) full.append('\n').append(f.label()).append(": ").append(f.value());
        String text = full.length() > 1900 ? full.substring(0, 1899) + "…" : full.toString();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("event", "form.message");
        out.put("form", m.formId());
        out.put("page", m.page());
        out.put("fields", m.fields());
        out.put("createdAt", createdAt);
        out.put("text", text);
        out.put("content", text);
        return out;
    }

    /** Posts a message to FORM_WEBHOOK_URL (docs/64), waiting at most a few seconds; a failure is only logged. */
    private void sendHook(Rules.Message m, String createdAt) {
        if (!webhookUrl.matches("(?i)^https?://.*")) return;
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(webhookUrl))
                    .timeout(java.time.Duration.ofMillis(4000))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(webhookBody(m, createdAt))))
                    .build();
            int status = hookClient.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) System.err.println("Form webhook answered HTTP " + status);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Form webhook: " + e.getMessage());
        }
    }

    @PostMapping("/api/views")
    public ResponseEntity<Void> countView(HttpServletRequest req) throws Exception {
        String agent = req.getHeader("User-Agent") == null ? "" : req.getHeader("User-Agent");
        String body = body(req);
        try {
            Store store = !agent.isEmpty() && !bots.matcher(agent).find() && body != null ? store() : null;
            if (store != null && allow(store, "view", visitor(req))) {
                Rules.View view = Rules.readView(config, json, body, req.getServerName());
                if (view != null) {
                    String day = today(0);
                    java.util.List<Rules.Bump> bumps = new java.util.ArrayList<>();
                    bumps.add(new Rules.Bump("view", view.path(), 1));
                    if (view.referrer() != null) bumps.add(new Rules.Bump("referrer", view.referrer(), 1));
                    store.bump(day, bumps);
                    String v = visitorOf(store, req, day);
                    store.once(day, java.util.List.of(new String[] {"visitor", v}, new String[] {"pvisitor", v + "|" + view.path()}));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // A beacon never fails.
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/interactions")
    public ResponseEntity<Void> countInteractions(HttpServletRequest req) throws Exception {
        String agent = req.getHeader("User-Agent") == null ? "" : req.getHeader("User-Agent");
        String body = body(req);
        try {
            Rules.Interactions batch = !agent.isEmpty() && !bots.matcher(agent).find() && body != null ? Rules.readInteractions(config, json, body) : null;
            Store store = batch != null ? store() : null;
            if (store != null && allow(store, "interaction", visitor(req))) {
                String day = today(0);
                store.bump(day, batch.bumps());
                if (!batch.uniques().isEmpty()) {
                    String v = visitorOf(store, req, day);
                    store.once(day, batch.uniques().stream().map(u -> new String[] {u.kind(), v + u.suffix()}).toList());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ResponseEntity.noContent().build();
    }

    private static final Pattern BEARER = Pattern.compile("^Bearer (.+)$");

    /** Null when the admin key was sent, else the refusal. */
    private ResponseEntity<Object> notAdmin(HttpServletRequest req) throws Exception {
        if (adminKey.isEmpty()) return refuse(404, "notFound");
        Matcher m = BEARER.matcher(req.getHeader("Authorization") == null ? "" : req.getHeader("Authorization"));
        boolean ok = m.matches() && MessageDigest.isEqual(sha256(m.group(1)), sha256(adminKey));
        return ok ? null : refuse(401, "unauthorized");
    }

    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/admin/messages")
    public ResponseEntity<Object> messages(HttpServletRequest req) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        Store store = store();
        return store == null ? noDatabase() : answer(200, Map.of("messages", store.messages(200)));
    }

    @DeleteMapping("/api/admin/messages/{id}")
    public ResponseEntity<Object> deleteMessage(@PathVariable String id, HttpServletRequest req) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        long n;
        try {
            n = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return refuse(404, "notFound");
        }
        Store store = store();
        if (store == null) return noDatabase();
        return store.deleteMessage(n) ? ResponseEntity.noContent().build() : refuse(404, "notFound");
    }

    @GetMapping("/api/admin/stats")
    public ResponseEntity<Object> stats(HttpServletRequest req) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        int days = 30;
        try {
            int asked = Integer.parseInt(req.getParameter("days") == null ? "" : req.getParameter("days"));
            if (asked >= 1 && asked <= 366) days = asked;
        } catch (NumberFormatException e) {
            // Not a number of days: the last 30.
        }
        Store store = store();
        return store == null ? noDatabase() : answer(200, store.stats(days));
    }

    @GetMapping("/api/admin/counts")
    public ResponseEntity<Object> counts(HttpServletRequest req) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        int days = 30;
        try {
            int asked = Integer.parseInt(req.getParameter("days") == null ? "" : req.getParameter("days"));
            if (asked >= 1 && asked <= 400) days = asked;
        } catch (NumberFormatException e) {
            // Not a number of days: the last 30.
        }
        Store store = store();
        if (store == null) return noDatabase();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("days", days);
        out.put("counts", Rules.summarize(store.counts(today(-(days - 1)))));
        return answer(200, out);
    }

    // ---------------------------------------------------------------- members (docs/62)

    /** Whether the visitor came over https (directly, or through a proxy the server trusts). */
    private boolean secure(HttpServletRequest req) {
        String proto = req.getHeader("X-Forwarded-Proto");
        return req.isSecure() || (trustProxy && proto != null && proto.split(",")[0].trim().equals("https"));
    }

    private void cookie(HttpServletRequest req, HttpServletResponse res, String value, long maxAge) {
        res.addHeader("Set-Cookie", Members.COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAge + (secure(req) ? "; Secure" : ""));
    }

    /** The visitor's session token: the cookie the server set, or the one the page kept. */
    private static String sessionToken(HttpServletRequest req) {
        if (req.getCookies() != null) for (jakarta.servlet.http.Cookie c : req.getCookies()) {
            if (c.getName().equals(Members.COOKIE) && Members.TOKEN.matcher(c.getValue()).matches()) return c.getValue();
        }
        Matcher m = BEARER.matcher(req.getHeader("Authorization") == null ? "" : req.getHeader("Authorization"));
        return m.matches() && Members.TOKEN.matcher(m.group(1)).matches() ? m.group(1) : null;
    }

    private static Members.Member signedIn(Store store, HttpServletRequest req) throws Exception {
        String token = sessionToken(req);
        return token == null ? null : store.sessionMember(Members.tokenHash(token), Members.time(0));
    }

    private String startSession(Store store, HttpServletRequest req, HttpServletResponse res, long memberId) throws Exception {
        String token = Members.newToken();
        store.addSession(Members.tokenHash(token), memberId, Members.time(Members.SESSION_DAYS * 86_400L));
        cookie(req, res, token, Members.SESSION_DAYS * 86_400L);
        return token;
    }

    private static Map<String, Object> view(String email, String name) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("email", email);
        m.put("name", name);
        return m;
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    @PostMapping("/api/members/signup")
    public ResponseEntity<Object> signUp(HttpServletRequest req, HttpServletResponse res) throws Exception {
        if (config.members() == null) return refuse(404, "notFound");
        Store store = store();
        if (store == null) return noDatabase();
        if (!allow(store, "member", visitor(req))) return refuse(429, "memberTooMany");
        String text = body(req);
        if (text == null) return refuse(413, "tooLong");
        Object read = Members.read(config, json, text, true);
        if (read instanceof Rules.Refusal r) return answer(r.status(), Map.of("message", r.message()));
        Members.Input input = (Members.Input) read;
        String mode = config.members().signup();
        if ("closed".equals(mode)) return refuse(403, "memberClosed");
        String status = "approve".equals(mode) ? "pending" : "active";
        Long id = store.addMember(input.email(), input.name(), Members.hash(input.password()), status);
        if (id == null) return refuse(409, "memberExists");
        if (status.equals("pending")) return answer(200, object("pending", true, "member", view(input.email(), input.name()), "message", config.message("memberWaiting")));
        return answer(200, object("token", startSession(store, req, res, id), "member", view(input.email(), input.name()), "message", config.message("memberWelcome")));
    }

    @PostMapping("/api/members/signin")
    public ResponseEntity<Object> signIn(HttpServletRequest req, HttpServletResponse res) throws Exception {
        if (config.members() == null) return refuse(404, "notFound");
        Store store = store();
        if (store == null) return noDatabase();
        if (!allow(store, "member", visitor(req))) return refuse(429, "memberTooMany");
        String text = body(req);
        Object read = text == null ? null : Members.read(config, json, text, false);
        if (!(read instanceof Members.Input input)) return refuse(401, "memberWrong");
        Members.Member row = store.memberByEmail(input.email());
        // Unknown emails take the same work as wrong passwords.
        boolean ok = Members.check(input.password(), row == null ? Members.DUMMY_HASH : row.hash());
        if (row == null || !ok) return refuse(401, "memberWrong");
        if (!row.status().equals("active")) return refuse(403, "memberPending");
        return answer(200, object("token", startSession(store, req, res, row.id()), "member", view(row.email(), row.name()), "message", config.message("memberSignedIn")));
    }

    @PostMapping("/api/members/signout")
    public ResponseEntity<Object> signOut(HttpServletRequest req, HttpServletResponse res) throws Exception {
        if (config.members() == null) return refuse(404, "notFound");
        Store store = store();
        if (store == null) return noDatabase();
        String token = sessionToken(req);
        String text = body(req);
        if (token == null && text != null) {
            try {
                tools.jackson.databind.JsonNode given = json.readTree(text).get("token");
                if (given != null && given.isString() && Members.TOKEN.matcher(given.stringValue()).matches()) token = given.stringValue();
            } catch (Exception e) {
                // No token in the body: nothing more to end.
            }
        }
        if (token != null) store.endSession(Members.tokenHash(token));
        cookie(req, res, "", 0);
        return answer(200, object("ok", true, "message", config.message("memberSignedOut")));
    }

    /** Collections served as JSON (docs/80), to any site: the answers are in server.json, the same on every server. */
    @GetMapping({"/api/data", "/api/data/", "/api/data/{model}", "/api/data/{model}/{record}"})
    public ResponseEntity<Object> data(@PathVariable(required = false) String model, @PathVariable(required = false) String record, HttpServletResponse res) {
        res.setHeader("Access-Control-Allow-Origin", "*");
        ServerConfig.Data data = config.data();
        String body = null;
        if (data != null) {
            if (model == null) body = data.list();
            else if (record == null) body = data.collections().get(model);
            else if (data.records().containsKey(model)) body = data.records().get(model).get(record);
        }
        if (body == null) return refuse(404, "notFound");
        res.setHeader("Cache-Control", "public, max-age=60");
        return answer(200, body);
    }

    @GetMapping("/api/members/me")
    public ResponseEntity<Object> me(HttpServletRequest req, HttpServletResponse res) throws Exception {
        if (config.members() == null) return refuse(404, "notFound");
        Store store = store();
        if (store == null) return noDatabase();
        res.setHeader("Cache-Control", "private, no-store");
        Members.Member member = signedIn(store, req);
        return member == null ? refuse(404, "memberNotSignedIn") : answer(200, Map.of("member", view(member.email(), member.name())));
    }

    // ---------------------------------------------------------------- records (docs/84)

    private Map<String, Object> recordView(Store.RecordRow r, Long memberId) {
        return object("id", String.valueOf(r.id()), "mine", memberId != null && memberId.equals(r.memberId()), "values", json.readTree(r.values()), "createdAt", r.createdAt());
    }

    private ServerConfig.RecordsCollection collection(String id) {
        return config.records() == null ? null : config.records().get(id);
    }

    /** A collection the members' records API answers for: not one kept to the app's own endpoints (docs/89). */
    private ServerConfig.RecordsCollection memberCollection(String id) {
        ServerConfig.RecordsCollection c = collection(id);
        return c == null || Boolean.TRUE.equals(c.endpointsOnly()) ? null : c;
    }

    /** A record's id in an address: a whole number, else there is no such record. */
    private static Long recordId(String raw) {
        return raw.matches("^[0-9]{1,15}$") ? Long.parseLong(raw) : null;
    }

    @GetMapping("/api/members/records/{collection}")
    public ResponseEntity<Object> listRecords(@PathVariable String collection, HttpServletRequest req, HttpServletResponse res) throws Exception {
        if (config.members() == null) return refuse(404, "notFound");
        Store store = store();
        if (store == null) return noDatabase();
        ServerConfig.RecordsCollection c = memberCollection(collection);
        if (c == null) return refuse(404, "recordNoCollection");
        res.setHeader("Cache-Control", "private, no-store");
        Members.Member member = signedIn(store, req);
        boolean mine = "1".equals(req.getParameter("mine"));
        if (("members".equals(c.readers()) || mine) && member == null) return refuse(401, "recordSignIn");
        Long memberId = member == null ? null : member.id();
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Store.RecordRow r : store.records(collection, mine ? memberId : null, config.rules().maxRecords())) out.add(recordView(r, memberId));
        return answer(200, Map.of("records", out));
    }

    @PostMapping("/api/members/records/{collection}")
    public ResponseEntity<Object> addRecord(@PathVariable String collection, HttpServletRequest req) throws Exception {
        if (config.members() == null) return refuse(404, "notFound");
        Store store = store();
        if (store == null) return noDatabase();
        ServerConfig.RecordsCollection c = memberCollection(collection);
        if (c == null) return refuse(404, "recordNoCollection");
        Members.Member member = signedIn(store, req);
        if (member == null) return refuse(401, "recordSignIn");
        String text = body(req);
        if (text == null) return refuse(413, "tooLong");
        Rules.RecordResult checked = Rules.checkRecord(config, json, c, text);
        if (checked instanceof Rules.Refusal r) return answer(r.status(), Map.of("message", r.message()));
        Map<String, Object> values = ((Rules.RecordValues) checked).values();
        if (!allow(store, "record", String.valueOf(member.id()))) return refuse(429, "recordTooMany");
        if (store.recordCount(collection) >= config.rules().maxRecords()) return answer(403, Map.of("message", c.full()));
        Store.RecordRow row = store.addRecord(collection, member.id(), json.writeValueAsString(values), Members.time(0));
        return answer(201, Map.of("record", recordView(row, member.id())));
    }

    @PutMapping("/api/members/records/{collection}/{id}")
    public ResponseEntity<Object> changeRecord(@PathVariable String collection, @PathVariable String id, HttpServletRequest req) throws Exception {
        return writeRecord(collection, id, req, true);
    }

    @DeleteMapping("/api/members/records/{collection}/{id}")
    public ResponseEntity<Object> deleteRecord(@PathVariable String collection, @PathVariable String id, HttpServletRequest req) throws Exception {
        return writeRecord(collection, id, req, false);
    }

    /** In order: the collection, the member, the id, the values, the limit, then the change. */
    private ResponseEntity<Object> writeRecord(String collection, String id, HttpServletRequest req, boolean change) throws Exception {
        if (config.members() == null) return refuse(404, "notFound");
        Store store = store();
        if (store == null) return noDatabase();
        ServerConfig.RecordsCollection c = memberCollection(collection);
        if (c == null) return refuse(404, "recordNoCollection");
        Members.Member member = signedIn(store, req);
        if (member == null) return refuse(401, "recordSignIn");
        Long n = recordId(id);
        if (n == null) return refuse(404, "recordNotFound");
        Map<String, Object> values = null;
        if (change) {
            String text = body(req);
            if (text == null) return refuse(413, "tooLong");
            Rules.RecordResult checked = Rules.checkRecord(config, json, c, text);
            if (checked instanceof Rules.Refusal r) return answer(r.status(), Map.of("message", r.message()));
            values = ((Rules.RecordValues) checked).values();
        }
        if (!allow(store, "record", String.valueOf(member.id()))) return refuse(429, "recordTooMany");
        Store.RecordRow row = store.record(collection, n);
        if (row == null) return refuse(404, "recordNotFound");
        if (row.memberId() == null || row.memberId() != member.id()) return refuse(403, "recordNotOwn");
        if (!change) {
            store.deleteRecord(collection, n);
            return ResponseEntity.noContent().build();
        }
        String text = json.writeValueAsString(values);
        store.updateRecord(n, text, Members.time(0));
        return answer(200, Map.of("record", recordView(new Store.RecordRow(n, member.id(), text, row.createdAt(), row.updatedAt()), member.id())));
    }

    /** The owner reads every record of a collection, and removes any (docs/84). */
    @GetMapping("/api/admin/records/{collection}")
    public ResponseEntity<Object> adminRecords(@PathVariable String collection, HttpServletRequest req) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        Store store = store();
        if (store == null) return noDatabase();
        if (collection(collection) == null) return refuse(404, "recordNoCollection");
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Store.RecordRow r : store.records(collection, null, config.rules().maxRecords())) out.add(object("id", String.valueOf(r.id()), "memberId", r.memberId(), "values", json.readTree(r.values()), "createdAt", r.createdAt(), "updatedAt", r.updatedAt()));
        return answer(200, Map.of("records", out));
    }

    @DeleteMapping("/api/admin/records/{collection}/{id}")
    public ResponseEntity<Object> adminDeleteRecord(@PathVariable String collection, @PathVariable String id, HttpServletRequest req) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        Store store = store();
        if (store == null) return noDatabase();
        if (collection(collection) == null) return refuse(404, "recordNoCollection");
        Long n = recordId(id);
        return n != null && store.deleteRecord(collection, n) ? ResponseEntity.noContent().build() : refuse(404, "recordNotFound");
    }

    @GetMapping("/api/admin/members")
    public ResponseEntity<Object> listMembers(HttpServletRequest req) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        Store store = store();
        if (store == null) return noDatabase();
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Members.Member m : store.members()) out.add(object("id", m.id(), "email", m.email(), "name", m.name(), "status", m.status(), "createdAt", m.createdAt()));
        return answer(200, Map.of("members", out));
    }

    @PostMapping("/api/admin/members/{id}/approve")
    public ResponseEntity<Object> approveMember(@PathVariable String id, HttpServletRequest req) throws Exception {
        return changeMember(id, req, true);
    }

    @DeleteMapping("/api/admin/members/{id}")
    public ResponseEntity<Object> removeMember(@PathVariable String id, HttpServletRequest req) throws Exception {
        return changeMember(id, req, false);
    }

    private ResponseEntity<Object> changeMember(String id, HttpServletRequest req, boolean approve) throws Exception {
        ResponseEntity<Object> refused = notAdmin(req);
        if (refused != null) return refused;
        long n;
        try {
            n = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return refuse(404, "notFound");
        }
        Store store = store();
        if (store == null) return noDatabase();
        return (approve ? store.approveMember(n) : store.removeMember(n)) ? ResponseEntity.noContent().build() : refuse(404, "notFound");
    }

    /** A members-only page (docs/62): kept in private/ and sent only with a session. */
    private ResponseEntity<Object> memberPage(Members.Page hit, HttpServletRequest req, HttpServletResponse res) throws Exception {
        if (!hit.slash()) {
            res.setStatus(301);
            res.setHeader("Location", hit.page() + "/");
            return null;
        }
        res.setHeader("Cache-Control", "private, no-store");
        Store store = store();
        if (store == null || signedIn(store, req) == null) {
            String signin = config.members().signin() == null ? "/" : config.members().signin();
            res.setStatus(302);
            res.setHeader("Location", signin.equals("/") ? "/" : signin + "/");
            return null;
        }
        byte[] page = Files.readAllBytes(privateDir.resolve(hit.file()));
        res.setContentType("text/html; charset=utf-8");
        res.setContentLengthLong(page.length);
        if (req.getMethod().equals("GET")) res.getOutputStream().write(page);
        return null;
    }

    private void notFound(HttpServletResponse res) throws IOException {
        res.setStatus(404);
        res.setContentType("text/html; charset=utf-8");
        res.getOutputStream().write(notFoundPage.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- the app's own endpoints (docs/88)

    /** Each endpoint with its address as a pattern (:name parts become named groups), in order. */
    private record Route(Map<String, Object> endpoint, Pattern pattern, java.util.List<String> names) {}

    private final java.util.List<Route> routes = routes();

    @SuppressWarnings("unchecked")
    private java.util.List<Route> routes() {
        try {
            Object logic = Logic.of(json.readTree(Files.readString(ServerConfig.root().resolve("server.json"))).get("logic"));
            java.util.List<Route> out = new java.util.ArrayList<>();
            if (!(logic instanceof java.util.List<?> list)) return out;
            for (Object e : list) {
                Map<String, Object> endpoint = (Map<String, Object>) e;
                java.util.List<String> names = new java.util.ArrayList<>();
                StringBuilder pattern = new StringBuilder("^/api/app");
                Matcher m = Pattern.compile(":([a-zA-Z][a-zA-Z0-9_]*)").matcher((String) endpoint.get("path"));
                int at = 0;
                String path = (String) endpoint.get("path");
                while (m.find()) {
                    pattern.append(Pattern.quote(path.substring(at, m.start()))).append("([^/]+)");
                    names.add(m.group(1));
                    at = m.end();
                }
                pattern.append(Pattern.quote(path.substring(at))).append("/?$");
                out.add(new Route(endpoint, Pattern.compile(pattern.toString()), names));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The app's collections in the database, written through the same rules as the records API. */
    private Logic.LogicStore logicStore(Store store) {
        return new Logic.LogicStore() {
            private Logic.Row row(Store.RecordRow r) {
                Object values = Logic.of(json.readTree(r.values()));
                @SuppressWarnings("unchecked")
                Map<String, Object> map = values instanceof Map ? (Map<String, Object>) values : new java.util.LinkedHashMap<>();
                return new Logic.Row(r.id(), r.memberId(), map, r.createdAt(), r.updatedAt());
            }

            private Object check(String collection, Map<String, Object> values) {
                ServerConfig.RecordsCollection c = collection(collection);
                if (c == null) return config.message("recordNoCollection");
                Rules.RecordResult checked = Rules.checkRecord(config, json, c, Logic.json(Map.of("values", values)));
                if (checked instanceof Rules.Refusal r) return r.message();
                return ((Rules.RecordValues) checked).values();
            }

            @Override
            public java.util.List<Logic.Row> list(String collection) throws Exception {
                java.util.List<Logic.Row> out = new java.util.ArrayList<>();
                if (collection(collection) == null) return out;
                for (Store.RecordRow r : store.records(collection, null, config.rules().maxRecords())) out.add(row(r));
                // In the order they were added (stores keep the newest first), as a database without an order gives them.
                java.util.Collections.reverse(out);
                return out;
            }

            @Override
            public Object create(String collection, Map<String, Object> values, Long memberId) throws Exception {
                Object checked = check(collection, values);
                if (checked instanceof String problem) return problem;
                if (store.recordCount(collection) >= config.rules().maxRecords()) return collection(collection).full();
                return row(store.addRecord(collection, memberId, json.writeValueAsString(checked), Members.time(0)));
            }

            @Override
            public Object update(String collection, long id, Map<String, Object> values) throws Exception {
                Object checked = check(collection, values);
                if (checked instanceof String problem) return problem;
                if (store.record(collection, id) == null) return null;
                store.updateRecord(id, json.writeValueAsString(checked), Members.time(0));
                return row(store.record(collection, id));
            }

            @Override
            public boolean remove(String collection, String id) throws Exception {
                return id.matches("^[0-9]{1,15}$") && store.deleteRecord(collection, Long.parseLong(id));
            }
        };
    }

    private static ResponseEntity<Object> logicAnswer(int status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).header("Cache-Control", "private, no-store").body(Logic.json(body).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> said(String message) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("message", message);
        return out;
    }

    /** /api/app/…: the first endpoint whose method and address match, else 404. */
    @RequestMapping("/api/app/**")
    public ResponseEntity<Object> app(HttpServletRequest req) throws Exception {
        String raw = req.getRequestURI();
        for (Route route : routes) {
            if (!route.endpoint().get("method").equals(req.getMethod())) continue;
            Matcher m = route.pattern().matcher(raw);
            if (!m.matches()) continue;
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            for (int i = 0; i < route.names().size(); i++) params.put(route.names().get(i), URLDecoder.decode(m.group(i + 1).replace("+", "%2B"), StandardCharsets.UTF_8));
            Object auth = route.endpoint().get("auth");
            if ("admin".equals(auth)) {
                ResponseEntity<Object> refused = notAdmin(req);
                if (refused != null) return refused;
            }
            Store store = store();
            if (store == null) return noDatabase();
            Map<String, Object> member = null;
            if ("member".equals(auth)) {
                Members.Member row = signedIn(store, req);
                if (row == null) return logicAnswer(401, said(config.message("recordSignIn")));
                member = new java.util.LinkedHashMap<>();
                member.put("id", (double) row.id());
                member.put("email", row.email());
                member.put("name", row.name() == null ? "" : row.name());
            }
            // GET and DELETE read their input from the query, the others from a JSON body.
            boolean fromQuery = req.getMethod().equals("GET") || req.getMethod().equals("DELETE");
            if (!fromQuery && !allow(store, "record", member != null ? "member:" + String.valueOf((long) (double) (Double) member.get("id")) : "visitor:" + visitor(req))) return logicAnswer(429, said(config.message("recordTooMany")));
            // Query values given once; a name given twice is left out, as the other servers read it.
            Map<String, Object> query = new java.util.LinkedHashMap<>();
            java.util.Set<String> twice = new java.util.HashSet<>();
            String qs = req.getQueryString();
            if (qs != null) for (String pair : qs.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (query.containsKey(k)) twice.add(k);
                query.put(k, v);
            }
            for (String k : twice) query.remove(k);
            Object input = query;
            if (!fromQuery) {
                String text = body(req);
                if (text == null) return logicAnswer(413, said(config.message("tooLong")));
                text = text.trim();
                try {
                    input = text.isEmpty() ? new java.util.LinkedHashMap<String, Object>() : Logic.of(json.readTree(text));
                } catch (tools.jackson.core.JacksonException e) {
                    return logicAnswer(400, said(config.message("unreadable")));
                }
            }
            try {
                Logic.Answer answer = new Logic(route.endpoint(), params, input, query, member, Members.time(0), logicStore(store)).answer();
                return logicAnswer(answer.status(), answer.body());
            } catch (Exception e) {
                e.printStackTrace();
                return logicAnswer(500, said("Server error"));
            }
        }
        return refuse(404, "notFound");
    }

    /** Everything else: an unknown API address, or a file of the site. */
    @RequestMapping("/**")
    public ResponseEntity<Object> file(HttpServletRequest req, HttpServletResponse res) throws Exception {
        String path = URLDecoder.decode(req.getRequestURI().replace("+", "%2B"), StandardCharsets.UTF_8);
        if (path.startsWith("/api/")) return refuse(404, "notFound");
        String method = req.getMethod();
        Members.Page hit = method.equals("GET") || method.equals("HEAD") ? Members.page(config, path) : null;
        if (hit != null) return memberPage(hit, req, res);
        boolean ok = (method.equals("GET") || method.equals("HEAD")) && !path.contains("\0") && !path.contains("\\");
        for (String segment : path.split("/")) if (segment.equals("..") || segment.startsWith(".")) ok = false;
        Path file = ok ? site.resolve(path.substring(1)).normalize() : null;
        if (file == null || !file.startsWith(site)) {
            notFound(res);
            return null;
        }
        if (!path.endsWith("/") && Files.isDirectory(file) && Files.isRegularFile(file.resolve("index.html"))) {
            String query = req.getQueryString();
            res.setStatus(301);
            res.setHeader("Location", req.getRequestURI() + "/" + (query == null ? "" : "?" + query));
            return null;
        }
        if (path.endsWith("/")) file = file.resolve("index.html");
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(site)) {
            notFound(res);
            return null;
        }
        String name = file.getFileName().toString();
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        res.setContentType(CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"));
        res.setContentLengthLong(Files.size(file));
        // Exact copies of imported pages run only sandboxed.
        if (path.startsWith("/_embeds/")) res.setHeader("Content-Security-Policy", config.embedCsp());
        if (method.equals("GET")) Files.copy(file, res.getOutputStream());
        return null;
    }
}
