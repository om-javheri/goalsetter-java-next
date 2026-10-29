package site;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public final class Logic {
    /** A record as a store keeps it: its values as JSON. */
    public record Row(long id, Long memberId, Map<String, Object> values, String createdAt, String updatedAt) {}

    /** What an endpoint reads and writes through; create and update answer a Row or what is wrong (a String). */
    public interface LogicStore {
        List<Row> list(String collection) throws Exception;

        Object create(String collection, Map<String, Object> values, Long memberId) throws Exception;

        Object update(String collection, long id, Map<String, Object> values) throws Exception;

        /** The id as text: one that isn't a whole number is no record here. */
        boolean remove(String collection, String id) throws Exception;
    }

    public record Answer(int status, Object body) {}

    private static final class Stop extends RuntimeException {
        final int status;
        final transient Object body;

        Stop(int status, Object body) {
            super(null, null, false, false);
            this.status = status;
            this.body = body;
        }
    }

    // ---------------------------------------------------------------- JSON

    /** JSON as values: objects keep their order, every number is a double. */
    public static Object of(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isBoolean()) return n.booleanValue();
        if (n.isNumber()) return n.doubleValue();
        if (n.isString()) return n.stringValue();
        if (n.isArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonNode x : n) out.add(of(x));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : n.propertyNames()) out.put(k, of(n.get(k)));
        return out;
    }

    /** A number as JavaScript writes it (String(n)): 2, 0.1, 1e+21, 1.5e-7. */
    public static String number(double n) {
        if (Double.isNaN(n) || Double.isInfinite(n)) return "null";
        if (n == 0) return "0";
        String s = Double.toString(Math.abs(n));
        int exp = 0;
        int e = s.indexOf('E');
        if (e >= 0) {
            exp = Integer.parseInt(s.substring(e + 1));
            s = s.substring(0, e);
        }
        int dot = s.indexOf('.');
        String whole = dot < 0 ? s : s.substring(0, dot);
        String frac = dot < 0 ? "" : s.substring(dot + 1);
        String all = whole + frac;
        String digits = all.replaceFirst("^0+", "");
        int point = whole.length() + exp - (all.length() - digits.length());
        digits = digits.replaceFirst("0+$", "");
        int k = digits.length();
        String sign = n < 0 ? "-" : "";
        if (k <= point && point <= 21) return sign + digits + "0".repeat(point - k);
        if (0 < point && point <= 21) return sign + digits.substring(0, point) + "." + digits.substring(point);
        if (-6 < point && point <= 0) return sign + "0." + "0".repeat(-point) + digits;
        int x = point - 1;
        return sign + digits.charAt(0) + (k > 1 ? "." + digits.substring(1) : "") + "e" + (x >= 0 ? "+" : "-") + Math.abs(x);
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    boolean lone = Character.isHighSurrogate(c) ? !(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) : Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || lone) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    /** JSON as JavaScript writes it (JSON.stringify). */
    public static String json(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof Boolean x) b.append(x ? "true" : "false");
        else if (v instanceof Number x) b.append(number(x.doubleValue()));
        else if (v instanceof String x) quote(b, x);
        else if (v instanceof List<?> list) {
            b.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) b.append(',');
                write(b, list.get(i));
            }
            b.append(']');
        } else {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> en : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, String.valueOf(en.getKey()));
                b.append(':');
                write(b, en.getValue());
            }
            b.append('}');
        }
    }

    // ---------------------------------------------------------------- the interpreter

    private int budget = 50_000;
    private final Map<String, Object> endpoint;
    private final Map<String, Object> params;
    private final Object rawInput;
    private final Map<String, Object> query;
    private final Map<String, Object> member;
    private final String now;
    private final LogicStore store;

    public Logic(Map<String, Object> endpoint, Map<String, Object> params, Object input, Map<String, Object> query, Map<String, Object> member, String now, LogicStore store) {
        this.endpoint = endpoint;
        this.params = params;
        this.rawInput = input;
        this.query = query;
        this.member = member;
        this.now = now;
        this.store = store;
    }

    private static Stop fail(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return new Stop(status, body);
    }

    private void spend() {
        if (--budget < 0) throw fail(500, "This endpoint did too much work.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v) {
        return (List<Object>) v;
    }

    private static boolean isObject(Object v) {
        return v instanceof Map;
    }

    private static boolean truthy(Object v) {
        if (v == null || Boolean.FALSE.equals(v) || "".equals(v)) return false;
        if (v instanceof Double d) return d != 0 && !d.isNaN();
        return true;
    }

    private static final Pattern NUMERIC = Pattern.compile("^[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?$");

    private static Double num(Object v) {
        double n;
        if (v instanceof Double d) n = d;
        else if (v instanceof Boolean b) n = b ? 1 : 0;
        else if (v instanceof String s) {
            String t = Rules.trim(s);
            if (t.isEmpty() || !NUMERIC.matcher(t).matches()) return null;
            n = Double.parseDouble(t);
        } else return null;
        return Double.isFinite(n) ? n : null;
    }

    private static String text(Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Double d) return number(d);
        return json(v);
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Double x && b instanceof Double y) return x.doubleValue() == y.doubleValue();
        if (a == null || b == null) return a == b;
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) if (!y.containsKey(e.getKey()) || !same(e.getValue(), y.get(e.getKey()))) return false;
            return true;
        }
        if (a instanceof List || a instanceof Map || b instanceof List || b instanceof Map) return false;
        return a.equals(b);
    }

    private static double order(Object a, Object b) {
        if (a instanceof Double x && b instanceof Double y) return x - y;
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        return Integer.signum(text(a).compareTo(text(b)));
    }

    private static final Pattern DATE = Pattern.compile("^([0-9]{4})-([0-9]{2})-([0-9]{2})(?:[T ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\\.[0-9]{1,9})?)?(?:Z|[+-]([0-9]{2}):[0-9]{2})?)?$");

    /** An ISO date or date and time, on the calendar. */
    private static boolean isDate(String s) {
        Matcher m = DATE.matcher(s);
        if (!m.matches()) return false;
        int[] n = new int[7];
        for (int i = 0; i < 7; i++) n[i] = m.group(i + 1) == null ? 0 : Integer.parseInt(m.group(i + 1));
        int y = n[0], mo = n[1], d = n[2];
        boolean leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
        int[] days = {31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return mo >= 1 && mo <= 12 && d >= 1 && d <= days[mo - 1] && n[3] < 24 && n[4] < 60 && n[5] < 60 && n[6] < 24;
    }

    private static Map<String, Object> view(Row r) {
        Map<String, Object> o = new LinkedHashMap<>(r.values());
        o.put("id", String.valueOf(r.id()));
        o.put("memberId", r.memberId() == null ? null : (double) r.memberId());
        o.put("createdAt", r.createdAt());
        o.put("updatedAt", r.updatedAt());
        return o;
    }

    // ---------------------------------------------------------------- input

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern URL = Pattern.compile("^https?://[^\\s]+$");
    /** Ids are text: digits here, uuids on the platform. */
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private Map<String, Object> readInput() {
        Map<String, Object> raw = isObject(rawInput) ? obj(rawInput) : Map.of();
        Map<String, Object> input = new LinkedHashMap<>();
        Object fields = endpoint.get("input");
        if (!isObject(fields)) return input;
        for (Map.Entry<String, Object> e : obj(fields).entrySet()) {
            String key = e.getKey();
            Map<String, Object> f = obj(e.getValue());
            Object v = raw.get(key);
            if (v == null || "".equals(v)) {
                if (Boolean.TRUE.equals(f.get("required"))) throw fail(400, key + " is required.");
                input.put(key, null);
                continue;
            }
            Object value = v;
            String type = (String) f.get("type");
            Double min = f.get("min") instanceof Double x ? x : null;
            Double max = f.get("max") instanceof Double x ? x : null;
            if (type.equals("number") || type.equals("integer")) {
                Double n = num(v);
                if (n == null || (type.equals("integer") && Math.floor(n) != n)) throw fail(400, key + " must be " + (type.equals("integer") ? "a whole number" : "a number") + ".");
                if (min != null && n < min) throw fail(400, key + " must be at least " + number(min) + ".");
                if (max != null && n > max) throw fail(400, key + " must be at most " + number(max) + ".");
                value = n;
            } else if (type.equals("boolean")) {
                if (Boolean.TRUE.equals(v) || "true".equals(v) || "1".equals(v)) value = true;
                else if (Boolean.FALSE.equals(v) || "false".equals(v) || "0".equals(v)) value = false;
                else throw fail(400, key + " must be yes or no.");
            } else if (type.equals("list")) {
                if (!(v instanceof List<?> l)) throw fail(400, key + " must be a list.");
                if (max != null && l.size() > max) throw fail(400, key + " has at most " + number(max) + " items.");
                if (min != null && l.size() < min) throw fail(400, key + " needs at least " + number(min) + " items.");
            } else if (type.equals("object")) {
                if (!isObject(v)) throw fail(400, key + " must be an object.");
            } else {
                if (!(v instanceof String) && !(v instanceof Double)) throw fail(400, key + " must be text.");
                String s = Rules.trim(text(v));
                double most = max != null ? max : type.equals("text") ? 5000 : 500;
                if (s.length() > most) throw fail(400, key + " is at most " + number(most) + " characters.");
                if (min != null && s.length() < min) throw fail(400, key + " needs at least " + number(min) + " characters.");
                if (type.equals("email") && !EMAIL.matcher(s).matches()) throw fail(400, key + " must be an email address.");
                if (type.equals("url") && !URL.matcher(s).matches()) throw fail(400, key + " must be a web address (https://…).");
                if (type.equals("datetime") && !isDate(s)) throw fail(400, key + " must be a date.");
                if (type.equals("id") && !ID.matcher(s).matches()) throw fail(400, key + " must be a record id.");
                if (f.get("options") instanceof List<?> options && !options.contains(s)) {
                    List<String> names = new ArrayList<>();
                    for (Object o : options) names.add(String.valueOf(o));
                    throw fail(400, key + " must be one of: " + String.join(", ", names) + ".");
                }
                value = s;
            }
            input.put(key, value);
        }
        return input;
    }

    // ---------------------------------------------------------------- expressions

    private Object lookup(String ref, Map<String, Object> scope) {
        if (ref.equals("$now")) return now;
        String[] parts = ref.substring(1).split("\\.", -1);
        Object v = scope.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (v instanceof List<?> l && p.matches("^[0-9]+$")) {
                int at;
                try {
                    at = Integer.parseInt(p);
                } catch (NumberFormatException e) {
                    at = Integer.MAX_VALUE;
                }
                v = at < l.size() ? l.get(at) : null;
            } else if (v instanceof Map<?, ?> m) v = m.get(p);
            else return null;
        }
        return v;
    }

    private static Map<String, Object> with(Map<String, Object> scope, String name, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(scope);
        next.put(name, value);
        return next;
    }

    private Object ev(Object x, Map<String, Object> scope) {
        spend();
        if (x instanceof String s) return s.startsWith("$$") ? s.substring(1) : s.startsWith("$") ? lookup(s, scope) : s;
        if (x instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object v : l) out.add(ev(v, scope));
            return out;
        }
        if (!isObject(x)) return x;
        Map<String, Object> o = obj(x);
        if (o.size() != 1 || !o.keySet().iterator().next().startsWith("$")) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : o.entrySet()) out.put(e.getKey(), ev(e.getValue(), scope));
            return out;
        }
        String op = o.keySet().iterator().next();
        Object given = o.get(op);
        List<Object> argsRaw = given instanceof List<?> ? list(given) : java.util.Collections.singletonList(given);
        switch (op) {
            case "$and" -> {
                for (Object a : argsRaw) if (!truthy(ev(a, scope))) return false;
                return true;
            }
            case "$or" -> {
                for (Object a : argsRaw) if (truthy(ev(a, scope))) return true;
                return false;
            }
            case "$if" -> {
                return ev(truthy(ev(argsRaw.get(0), scope)) ? argsRaw.get(1) : argsRaw.get(2), scope);
            }
            case "$filter", "$map" -> {
                Object items = ev(argsRaw.get(0), scope);
                List<Object> out = new ArrayList<>();
                if (!(items instanceof List<?> l)) return out;
                for (Object item : l) {
                    Object r = ev(argsRaw.get(1), with(scope, "item", item));
                    if (op.equals("$map")) out.add(r);
                    else if (truthy(r)) out.add(item);
                }
                return out;
            }
            default -> {}
        }
        List<Object> a = new ArrayList<>();
        for (Object v : argsRaw) a.add(ev(v, scope));
        Object a0 = a.isEmpty() ? null : a.get(0);
        Object a1 = a.size() < 2 ? null : a.get(1);
        switch (op) {
            case "$eq": return same(a0, a1);
            case "$ne": return !same(a0, a1);
            case "$lt": return a0 != null && a1 != null && order(a0, a1) < 0;
            case "$lte": return a0 != null && a1 != null && order(a0, a1) <= 0;
            case "$gt": return a0 != null && a1 != null && order(a0, a1) > 0;
            case "$gte": return a0 != null && a1 != null && order(a0, a1) >= 0;
            case "$not": return !truthy(a0);
            case "$add": return arith(a, '+');
            case "$sub": return arith(a, '-');
            case "$mul": return arith(a, '*');
            case "$div": return Double.valueOf(0).equals(zero(num(a1))) ? null : arith(a, '/');
            case "$mod": return Double.valueOf(0).equals(zero(num(a1))) ? null : arith(a, '%');
            case "$round": {
                Double n = num(a0);
                Double d = num(a1 == null ? 0.0 : a1);
                if (n == null) return null;
                double f = Math.pow(10, Math.max(0, Math.min(10, (long) (d == null ? 0 : d.doubleValue()))));
                return Math.floor(n * f + 0.5) / f;
            }
            case "$ceil":
            case "$floor":
            case "$abs": {
                Double n = num(a0);
                if (n == null) return null;
                return op.equals("$ceil") ? Math.ceil(n) : op.equals("$floor") ? Math.floor(n) : Math.abs(n);
            }
            case "$min":
            case "$max": {
                List<?> items = a.size() == 1 && a0 instanceof List<?> l ? l : a;
                Double best = null;
                for (Object v : items) {
                    Double n = num(v);
                    if (n == null) continue;
                    if (best == null || (op.equals("$min") ? n < best : n > best)) best = n;
                }
                return best;
            }
            case "$concat": {
                boolean lists = true;
                for (Object v : a) if (!(v instanceof List)) lists = false;
                if (lists) {
                    List<Object> out = new ArrayList<>();
                    for (Object v : a) out.addAll(list(v));
                    return out;
                }
                StringBuilder b = new StringBuilder();
                for (Object v : a) b.append(text(v));
                return b.toString();
            }
            case "$lower": return text(a0).toLowerCase(Locale.ROOT);
            case "$upper": return text(a0).toUpperCase(Locale.ROOT);
            case "$trim": return Rules.trim(text(a0));
            case "$contains": {
                if (a0 instanceof List<?> l) {
                    for (Object v : l) if (same(v, a1)) return true;
                    return false;
                }
                return text(a0).toLowerCase(Locale.ROOT).contains(text(a1).toLowerCase(Locale.ROOT));
            }
            case "$startsWith": return text(a0).toLowerCase(Locale.ROOT).startsWith(text(a1).toLowerCase(Locale.ROOT));
            case "$length":
            case "$count": return (double) (a0 instanceof List<?> l ? l.size() : a0 instanceof String s ? s.length() : a0 instanceof Map<?, ?> m ? m.size() : 0);
            case "$sum": {
                double s = 0;
                if (!(a0 instanceof List<?> l)) return s;
                String field = a.size() < 2 ? null : text(a1);
                for (Object v : l) {
                    Double n = num(field == null ? v : v instanceof Map<?, ?> m ? m.get(field) : null);
                    s += n == null ? 0 : n;
                }
                return s;
            }
            case "$pluck": {
                List<Object> out = new ArrayList<>();
                if (!(a0 instanceof List<?> l)) return out;
                String field = text(a1);
                for (Object v : l) out.add(v instanceof Map<?, ?> m ? m.get(field) : null);
                return out;
            }
            case "$first": return a0 instanceof List<?> l && !l.isEmpty() ? l.get(0) : null;
            case "$in": {
                if (!(a1 instanceof List<?> l)) return false;
                for (Object v : l) if (same(v, a0)) return true;
                return false;
            }
            case "$exists": return a0 != null && !"".equals(a0);
            case "$coalesce": {
                for (Object v : a) if (v != null && !"".equals(v)) return v;
                return null;
            }
            case "$number": return num(a0);
            case "$text": return text(a0);
            case "$newId": {
                byte[] bytes = new byte[12];
                new java.security.SecureRandom().nextBytes(bytes);
                return java.util.HexFormat.of().formatHex(bytes);
            }
            default: throw fail(500, "Unknown operator " + op + ".");
        }
    }

    /** -0 counts as 0. */
    private static Double zero(Double n) {
        return n != null && n == 0 ? 0.0 : n;
    }

    private static Double arith(List<Object> a, char op) {
        Iterator<Object> it = a.iterator();
        Double r = num(it.next());
        if (r == null) return null;
        while (it.hasNext()) {
            Double n = num(it.next());
            if (n == null) return null;
            r = switch (op) {
                case '+' -> r + n;
                case '-' -> r - n;
                case '*' -> r * n;
                case '/' -> r / n;
                default -> r % n;
            };
        }
        for (Object v : a) if (num(v) == null) return null;
        return Double.isFinite(r) ? r : null;
    }

    // ---------------------------------------------------------------- steps

    private static String idOf(Object v) {
        String s = text(v);
        return ID.matcher(s).matches() ? s : null;
    }

    private Map<String, Object> values(Object map, Map<String, Object> scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : obj(map).entrySet()) out.put(e.getKey(), ev(e.getValue(), scope));
        return out;
    }

    private Row find(String collection, String id, Object missing) throws Exception {
        if (id != null) for (Row r : store.list(collection)) if (String.valueOf(r.id()).equals(id)) return r;
        throw fail(404, missing == null ? "Not found." : (String) missing);
    }

    private static Map<String, Object> written(Object r, Object missing) {
        if (r == null) throw fail(404, missing == null ? "Not found." : (String) missing);
        if (r instanceof String problem) throw fail(400, problem);
        return view((Row) r);
    }

    private static int status(Map<String, Object> s, int fallback) {
        return s.get("status") instanceof Double d ? (int) d.doubleValue() : fallback;
    }

    private void run(List<Object> steps, Map<String, Object> scope) throws Exception {
        for (Object step : steps) {
            spend();
            Map<String, Object> s = obj(step);
            String collection = (String) s.get("collection");
            switch ((String) s.get("do")) {
                case "find" -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (Row r : store.list(collection)) rows.add(view(r));
                    if (s.containsKey("where")) {
                        List<Map<String, Object>> kept = new ArrayList<>();
                        for (Map<String, Object> record : rows) if (truthy(ev(s.get("where"), with(scope, "record", record)))) kept.add(record);
                        rows = kept;
                    }
                    if (s.get("sort") instanceof String sort && !sort.isEmpty()) {
                        boolean desc = sort.startsWith("-");
                        String key = desc ? sort.substring(1) : sort;
                        rows.sort((x, y) -> {
                            double o = (desc ? -1 : 1) * order(x.get(key), y.get(key));
                            return o < 0 ? -1 : o > 0 ? 1 : 0;
                        });
                    }
                    if (s.containsKey("skip")) {
                        Double n = num(ev(s.get("skip"), scope));
                        if (n != null) rows = new ArrayList<>(rows.subList((int) Math.min(rows.size(), Math.max(0, (long) n.doubleValue())), rows.size()));
                    }
                    if (s.containsKey("limit")) {
                        Double n = num(ev(s.get("limit"), scope));
                        if (n != null) rows = new ArrayList<>(rows.subList(0, (int) Math.min(rows.size(), Math.max(0, (long) n.doubleValue()))));
                    }
                    scope.put((String) s.get("as"), new ArrayList<Object>(rows));
                }
                case "get" -> scope.put((String) s.get("as"), view(find(collection, idOf(ev(s.get("id"), scope)), s.get("missing"))));
                case "create" -> {
                    Map<String, Object> r = written(store.create(collection, values(s.get("values"), scope), member == null ? null : (long) (double) (Double) member.get("id")), null);
                    if (s.get("as") instanceof String as) scope.put(as, r);
                }
                case "update" -> {
                    Row old = find(collection, idOf(ev(s.get("id"), scope)), s.get("missing"));
                    Map<String, Object> merged = new LinkedHashMap<>(old.values());
                    merged.putAll(values(s.get("values"), scope));
                    Map<String, Object> r = written(store.update(collection, old.id(), merged), s.get("missing"));
                    if (s.get("as") instanceof String as) scope.put(as, r);
                }
                case "delete" -> {
                    String id = idOf(ev(s.get("id"), scope));
                    if (id == null || !store.remove(collection, id)) throw fail(404, s.get("missing") == null ? "Not found." : (String) s.get("missing"));
                }
                case "set" -> scope.put((String) s.get("as"), ev(s.get("value"), scope));
                case "check" -> {
                    if (!truthy(ev(s.get("that"), scope))) throw fail(status(s, 400), (String) s.get("message"));
                }
                case "if" -> {
                    Object branch = truthy(ev(s.get("that"), scope)) ? s.get("then") : s.get("else");
                    run(branch == null ? List.of() : list(branch), scope);
                }
                case "each" -> {
                    Object items = ev(s.get("in"), scope);
                    if (!(items instanceof List<?> l)) break;
                    if (l.size() > 1000) throw fail(500, "A list to go through has at most 1,000 items.");
                    for (Object item : l) {
                        scope.put((String) s.get("as"), item);
                        run(list(s.get("steps")), scope);
                    }
                }
                case "return" -> {
                    Map<String, Object> ok = new LinkedHashMap<>();
                    ok.put("ok", true);
                    throw new Stop(status(s, 200), s.containsKey("body") ? ev(s.get("body"), scope) : ok);
                }
                default -> {}
            }
        }
    }

    public Answer answer() throws Exception {
        try {
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("input", readInput());
            scope.put("params", params);
            scope.put("query", query);
            scope.put("member", member);
            run(list(endpoint.get("steps")), scope);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            return new Answer(200, ok);
        } catch (Stop e) {
            return new Answer(e.status, e.body);
        }
    }
}
