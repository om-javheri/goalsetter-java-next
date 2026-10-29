package site;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The checks on messages and page views, the same as the site's other servers. */
public final class Rules {
    private Rules() {}

    public record Field(String label, String value) {}

    /** A message that passed every check, or why it was refused. */
    public sealed interface Result permits Message, Refusal {}

    public record Message(String formId, String page, List<Field> fields) implements Result {}

    public record Refusal(int status, String message) implements Result, RecordResult {}

    /** A record's values that passed every check (docs/84), or why not. */
    public sealed interface RecordResult permits RecordValues, Refusal {}

    public record RecordValues(Map<String, Object> values) implements RecordResult {}

    public record View(String path, String referrer) {}

    /** A counter to add to. */
    public record Bump(String kind, String key, long count) {}

    /** A row counted once a day per visitor: its kind and what follows the visitor's hash in its key. */
    public record Unique(String kind, String suffix) {}

    public record Interactions(List<Bump> bumps, List<Unique> uniques) {}

    /** One day's counter. */
    public record Count(String day, String kind, String key, long count) {}

    public static final Set<String> UNIQUE_KINDS = Set.of("visitor", "pvisitor", "dvisitor", "clicker", "pclicker", "uclick");
    private static final Set<String> DEVICES = Set.of("desktop", "tablet", "phone");
    private static final Set<Integer> DEPTHS = Set.of(25, 50, 75, 100);
    public static final int MAX_KEY = 255;
    private static final Pattern SPACES = Pattern.compile("[\\s\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]+");

    /** Whitespace as JavaScript trims it. */
    private static final Pattern EDGES = Pattern.compile("^[\\s\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]+|[\\s\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]+$");
    private static final Set<String> FIELD_KEYS = Set.of("label", "value");

    public static String trim(String s) {
        return EDGES.matcher(s).replaceAll("");
    }

    private static final Pattern PLAIN = Pattern.compile("[\\s,]");
    private static final Pattern NUMBER = Pattern.compile("^-?[0-9]+(\\.[0-9]+)?$");
    private static final Pattern YES = Pattern.compile("^(yes|y|true|1)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO = Pattern.compile("^(no|n|false|0)$", Pattern.CASE_INSENSITIVE);

    private static String fill(String text, String field, String name) {
        return text.replace("{field}", field).replace("{name}", name);
    }

    /**
     * A record's values (docs/84), read like the platform reads them (checkRecord in UniversalIDE):
     * only the collection's fields, each read by type, then its pattern, length and options.
     */
    public static RecordResult checkRecord(ServerConfig config, JsonMapper json, ServerConfig.RecordsCollection collection, String body) {
        JsonNode data = parse(json, body);
        if (data == null) return new Refusal(400, config.message("recordUnreadable"));
        JsonNode sent = data.isObject() ? data.get("values") : null;
        if (sent == null || !sent.isObject()) return new Refusal(400, config.message("recordShape"));
        Set<String> names = new HashSet<>();
        for (ServerConfig.RecordField f : collection.fields()) names.add(f.name());
        for (String key : sent.propertyNames()) {
            if (!names.contains(key)) return new Refusal(400, fill(config.message("recordUnknownField"), key.length() > 40 ? key.substring(0, 40) : key, collection.name()));
        }
        int maxValue = config.rules().maxRecordValue();
        Map<String, Object> values = new LinkedHashMap<>();
        for (ServerConfig.RecordField f : collection.fields()) {
            JsonNode v = sent.get(f.name());
            if ("json".equals(f.read())) {
                // A list or an object (docs/89), at most max characters as JSON; kept as it was sent.
                JsonNode given = v == null || v.isNull() ? (f.fallback() instanceof String text ? parse(json, text) : null) : v;
                if (given == null || given.isNull()) {
                    if (f.required()) return new Refusal(400, f.requiredText());
                    values.put(f.name(), null);
                    continue;
                }
                if (!given.isObject() && !given.isArray()) return new Refusal(400, f.problem());
                if (Logic.json(Logic.of(given)).length() > (f.max() == null ? 20000 : f.max())) return new Refusal(400, fill(config.message("recordLong"), f.name(), ""));
                values.put(f.name(), given);
                continue;
            }
            if (v != null && !v.isNull() && !v.isString() && !v.isNumber() && !v.isBoolean()) return new Refusal(400, fill(config.message("recordSingle"), f.name(), ""));
            if (v != null && v.isString() && v.stringValue().length() > maxValue) return new Refusal(400, fill(config.message("recordLong"), f.name(), ""));
            Object value = v == null || v.isNull() ? null : v.isString() ? v.stringValue() : v.isBoolean() ? (Object) v.booleanValue() : v.numberValue();
            if (value instanceof String s) {
                String t = trim(s);
                String plain = PLAIN.matcher(t).replaceAll("");
                if (t.isEmpty()) value = null;
                else if ("number".equals(f.read())) {
                    if (NUMBER.matcher(plain).matches()) {
                        double d = Double.parseDouble(plain);
                        value = plain.contains(".") || Math.abs(d) >= 9e15 ? (Object) d : (Object) (long) d;
                    } else value = t;
                } else if ("boolean".equals(f.read())) value = YES.matcher(t).matches() ? (Object) true : NO.matcher(t).matches() ? (Object) false : t;
                else value = "text".equals(f.read()) ? s.replace("\r\n", "\n") : t;
            }
            // Left out or empty: the field's default, checked like a sent value (docs/89).
            if (value == null && Boolean.TRUE.equals(f.defaultNow())) value = Members.time(0);
            else if (value == null && f.fallback() != null) value = f.fallback();
            if (value == null) {
                if (f.required()) return new Refusal(400, f.requiredText());
                values.put(f.name(), null);
                continue;
            }
            if ("number".equals(f.read()) && !(value instanceof Number n && Double.isFinite(n.doubleValue()))) return new Refusal(400, f.problem());
            if ("boolean".equals(f.read()) && !(value instanceof Boolean)) return new Refusal(400, f.problem());
            if (!"number".equals(f.read()) && !"boolean".equals(f.read()) && !(value instanceof String)) return new Refusal(400, fill(config.message("recordText"), f.name(), ""));
            if (value instanceof String s) {
                if (f.options() != null) {
                    String match = null;
                    for (String o : f.options()) if (o.toLowerCase(java.util.Locale.ROOT).equals(s.toLowerCase(java.util.Locale.ROOT))) { match = o; break; }
                    if (match == null) return new Refusal(400, f.problem());
                    value = match;
                    s = match;
                }
                if ((f.pattern() != null && !Pattern.compile(f.pattern()).matcher(s).find()) || (f.max() != null && s.length() > f.max())) return new Refusal(400, f.problem());
            }
            values.put(f.name(), value);
        }
        return new RecordValues(values);
    }

    private static JsonNode parse(JsonMapper json, String body) {
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** The page a message came from: one of the site's pages, else the home page. */
    public static String pagePath(List<String> pages, String page) {
        String path = (page == null ? "" : page).split("[?#]", -1)[0];
        if (path.endsWith("/index.html")) path = path.substring(0, path.length() - "index.html".length());
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return pages.contains(path) ? path : "/";
    }

    /** Checks a form message (the body as sent: text/plain or JSON) against the form and the rules. */
    public static Result checkMessage(ServerConfig config, JsonMapper json, String formId, String body) {
        ServerConfig.Limits rules = config.rules();
        Refusal unreadable = new Refusal(400, config.message("unreadable"));
        JsonNode data = parse(json, body);
        if (data == null || !data.isObject()) return unreadable;
        JsonNode fields = data.get("fields");
        if (fields == null || !fields.isArray() || fields.size() < 1 || fields.size() > rules.maxFields()) return unreadable;
        List<Field> sent = new ArrayList<>();
        for (JsonNode f : fields) {
            if (!f.isObject() || !FIELD_KEYS.containsAll(f.propertyNames())) return unreadable;
            JsonNode label = f.get("label");
            JsonNode value = f.get("value");
            if (label == null || value == null || !label.isString() || !value.isString()) return unreadable;
            // Java counts string length in UTF-16 units, as JavaScript does.
            if (label.stringValue().length() > rules.maxLabel() || value.stringValue().length() > rules.maxValue()) return unreadable;
            sent.add(new Field(label.stringValue(), value.stringValue()));
        }
        JsonNode page = data.get("page");
        if (page != null && (!page.isString() || page.stringValue().length() > rules.maxPage())) return unreadable;

        Integer formFields = config.forms().get(formId);
        if (formFields == null) return new Refusal(404, config.message("unknownForm"));
        if (sent.size() > formFields) return new Refusal(400, config.message("tooManyFields"));
        if (sent.stream().allMatch(f -> trim(f.value()).isEmpty())) return new Refusal(400, config.message("empty"));
        if (sent.stream().mapToInt(f -> f.label().length() + f.value().length()).sum() > rules.maxTotal()) return new Refusal(400, config.message("tooLong"));
        List<Field> kept = sent.stream().map(f -> new Field(trim(f.label()), trim(f.value()))).toList();
        return new Message(formId, pagePath(config.pages(), page == null ? null : page.stringValue()), kept);
    }

    /** A counter's key as kept: at most MAX_KEY characters. */
    public static String key(String k) {
        return k.length() > MAX_KEY ? k.substring(0, MAX_KEY) : k;
    }

    /** A button's or link's text as the platform compares it: trimmed, spaces run together, 80 characters. */
    static String normalizeLabel(String s) {
        String t = SPACES.matcher(trim(s)).replaceAll(" ");
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    private static boolean isIndex(JsonNode v, int size) {
        return v != null && v.isIntegralNumber() && v.canConvertToInt() && v.intValue() >= 0 && v.intValue() < size;
    }

    /**
     * A batch of clicks, scrolling and forms (docs/59), counted as the platform counts them: only for
     * what the page has; a page view, its 25% steps and the sections it reached once each.
     */
    public static Interactions readInteractions(ServerConfig config, JsonMapper json, String body) {
        ServerConfig.Tracking tracking = config.tracking();
        if (tracking == null) return null;
        JsonNode data = parse(json, body);
        if (data == null || !data.isObject() || data.get("path") == null || !data.get("path").isString()) return null;
        String path = data.get("path").stringValue();
        ServerConfig.TrackedPage page = tracking.pages().get(path);
        JsonNode deviceNode = data.get("device");
        if (page == null || deviceNode == null || !deviceNode.isString() || !DEVICES.contains(deviceNode.stringValue())) return null;
        String device = deviceNode.stringValue();
        Map<String, Bump> counts = new LinkedHashMap<>();
        java.util.function.BiConsumer<String, String> add = (kind, key) -> counts.merge(kind + "\0" + key, new Bump(kind, key, 1), (a, b) -> new Bump(a.kind(), a.key(), a.count() + 1));
        Map<String, Unique> uniques = new LinkedHashMap<>();
        Set<String> once = new HashSet<>();
        JsonNode events = data.get("events");
        int n = 0;
        if (events != null && events.isArray()) for (JsonNode e : events) {
            if (n++ >= config.rules().maxEvents()) break;
            if (!e.isObject()) continue;
            JsonNode tNode = e.get("t");
            String t = tNode != null && tNode.isString() ? tNode.stringValue() : "";
            if (t.equals("view")) {
                if (once.add("view")) {
                    add.accept("scroll", path + "|0");
                    add.accept("device", device);
                    uniques.put("dvisitor", new Unique("dvisitor", "|" + device));
                }
                continue;
            }
            if (t.equals("depth")) {
                JsonNode d = e.get("d");
                if (d != null && d.isIntegralNumber() && d.canConvertToInt() && DEPTHS.contains(d.intValue()) && once.add("depth" + d.intValue())) add.accept("scroll", path + "|" + d.intValue());
                continue;
            }
            if (!isIndex(e.get("s"), page.sections())) continue;
            int s = e.get("s").intValue();
            if (t.equals("click")) {
                if (isIndex(e.get("x"), tracking.heatCells()) && isIndex(e.get("y"), tracking.heatCells())) add.accept("heat", path + "|" + device + "|" + s + "|" + e.get("x").intValue() + "|" + e.get("y").intValue());
                JsonNode labelNode = e.get("label");
                String label = labelNode != null && labelNode.isString() ? normalizeLabel(labelNode.stringValue()) : "";
                if (!label.isEmpty() && page.labels().contains(label)) {
                    add.accept("click", path + "|" + label);
                    uniques.put("uclick|" + label, new Unique("uclick", "|" + path + "|" + label));
                    uniques.put("clicker", new Unique("clicker", ""));
                    uniques.put("pclicker", new Unique("pclicker", "|" + path));
                }
                JsonNode outNode = e.get("out");
                String host = outNode != null && outNode.isString() ? outNode.stringValue().toLowerCase(java.util.Locale.ROOT) : "";
                if (!host.isEmpty() && page.outHosts().contains(host)) add.accept("out", host);
            } else if (t.equals("form")) {
                JsonNode aNode = e.get("a");
                String a = aNode != null && aNode.isString() ? aNode.stringValue() : "";
                if (page.forms().contains(s) && (a.equals("start") || a.equals("send"))) add.accept("form", path + "|" + s + "|" + a);
            } else if (t.equals("seen") && once.add("seen" + s)) {
                add.accept("seen", path + "|" + s);
            }
        }
        return new Interactions(new ArrayList<>(counts.values()), new ArrayList<>(uniques.values()));
    }

    /** The day's visitor: a keyed hash of the address and browser that can't be reversed or linked across days. */
    public static String visitorHash(String secret, String day, String address, String agent) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((secret + "|" + day).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((address + "|" + agent).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The counters as GET /api/admin/counts answers them: rows kept once a day per visitor counted, with the hash cut off. */
    public static List<Count> summarize(List<Count> rows) {
        Map<String, Count> out = new TreeMap<>();
        for (Count r : rows) {
            boolean unique = UNIQUE_KINDS.contains(r.kind());
            String key = unique ? (r.key().length() > 33 ? r.key().substring(33) : "") : r.key();
            String k = r.day() + "\0" + r.kind() + "\0" + key;
            Count had = out.get(k);
            out.put(k, new Count(r.day(), r.kind(), key, (had == null ? 0 : had.count()) + (unique ? 1 : r.count())));
        }
        return new ArrayList<>(out.values());
    }

    /** A page view to count: the page's path, and the referring site's host when it is another site. */
    public static View readView(ServerConfig config, JsonMapper json, String body, String ownHost) {
        JsonNode data = parse(json, body);
        if (data == null || !data.isObject() || data.get("path") == null || !data.get("path").isString()) return null;
        String path = data.get("path").stringValue().split("[?#]", -1)[0];
        if (!config.pages().contains(path)) return null;
        String referrer = null;
        JsonNode ref = data.get("referrer");
        if (ref != null && ref.isString() && !ref.stringValue().isEmpty()) {
            try {
                URI uri = new URI(ref.stringValue());
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                if ((scheme.equals("http") || scheme.equals("https")) && !host.isEmpty() && !host.equals(ownHost.toLowerCase()) && host.length() <= 253) referrer = host;
            } catch (Exception e) {
                // Not an address: no referrer.
            }
        }
        return new View(path, referrer);
    }
}
