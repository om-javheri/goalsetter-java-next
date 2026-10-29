package site;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** What the site's server knows about it: written by UniversalIDE into server.json (docs/39). */
public record ServerConfig(
        /** Forms that send messages here: form id → how many fields it has. */
        Map<String, Integer> forms,
        /** The site's page paths. */
        List<String> pages,
        Limits rules,
        Map<String, String> messages,
        String botPattern,
        String embedCsp,
        /** A database server (mongodb, mysql), or null for SQLite. */
        String database,
        /** What the click tracker may report per page (docs/59); null: not counted. */
        Tracking tracking,
        /** Visitors' accounts (docs/62); null: the site has none. */
        Members members,
        /** Collections served as JSON (docs/80); null: none is. */
        Data data,
        /** Collections kept in the database (docs/84): who reads them and how values are checked; null: none is. */
        Map<String, RecordsCollection> records,
        /** UniversalIDE, asked for the database's address when the host gives none (docs/90); null: not asked. */
        Platform platform) {

    public record Platform(String url) {}

    public record RecordsCollection(String name, String readers, Boolean endpointsOnly, String full, List<RecordField> fields) {}

    /** How a field's value is read and checked, with the answers already worded. */
    /** fallback: the "default" kept when the value is left out (a json field's as JSON text); defaultNow: the time of the write (docs/89). */
    public record RecordField(String name, String read, boolean required, String pattern, Integer max, List<String> options, String requiredText, String problem, @com.fasterxml.jackson.annotation.JsonProperty("default") Object fallback, Boolean defaultNow) {}

    /** The answers of /api/data themselves: the list, each collection, each record by id and address part. */
    public record Data(String list, Map<String, String> collections, Map<String, Map<String, String>> records) {}

    public record Limits(int maxFields, int maxLabel, int maxValue, int maxTotal, int maxBody, int messagesPerMinute, int viewsPerMinute, int interactionsPerMinute, int maxEvents, int keepMessages, int maxPage, int membersPerMinute, int maxRecords, int recordWritesPerMinute, int maxRecordValue) {}

    /** Who may join (open, approve, closed), the members-only pages (path → file in private/) and where to sign in. */
    public record Members(String signup, Map<String, String> pages, String signin) {}

    public record Tracking(int heatCells, Map<String, TrackedPage> pages) {}

    public record TrackedPage(int sections, List<Integer> forms, List<String> labels, List<String> outHosts) {}

    /** The project folder: server.json, not-found.html and site/ are here (SITE_ROOT, else the working folder). */
    public static Path root() {
        String root = System.getenv("SITE_ROOT");
        return Path.of(root == null || root.isEmpty() ? "" : root).toAbsolutePath().normalize();
    }

    public static ServerConfig load(JsonMapper json) throws IOException {
        return json.readValue(Files.readString(root().resolve("server.json")), ServerConfig.class);
    }

    public String message(String key) {
        return messages.get(key);
    }
}
