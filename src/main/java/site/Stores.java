package site;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opens the site's store (docs/57). */
public final class Stores {
    private Stores() {}

    /** The database server's address: DATABASE_URL (or MONGODB_URI, as the Vercel MongoDB Atlas integration names it). */
    static String databaseUrl() {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isEmpty()) url = System.getenv("MONGODB_URI");
        return url == null ? "" : url;
    }

    /** The kinds of address each database is reached at (docs/90), as UniversalIDE keeps them. */
    static boolean fits(String kind, String url) {
        String scheme = switch (kind == null ? "" : kind) {
            case "postgres" -> "^postgres(ql)?://[^/?#]+";
            case "mysql" -> "^mysql://[^/?#]+";
            case "mongodb" -> "^mongodb(\\+srv)?://[^/?#]+";
            default -> null;
        };
        return scheme != null && url != null && Pattern.compile(scheme, Pattern.CASE_INSENSITIVE).matcher(url).find();
    }

    private static String fromPlatform = null;
    private static long platformFailedAt = 0;

    /**
     * Without DATABASE_URL, the database's address from UniversalIDE (docs/90): asked once with the
     * app's key (UNIVERSALIDE_APP_KEY), kept in memory only, checked against this server's kind of
     * database; nothing about it is logged.
     */
    static synchronized String databaseUrl(ServerConfig config, JsonMapper json) throws Exception {
        String url = databaseUrl();
        if (!url.isEmpty()) return url;
        if (fromPlatform != null) return fromPlatform;
        String key = System.getenv("UNIVERSALIDE_APP_KEY");
        String base = System.getenv("UNIVERSALIDE_URL");
        if (base == null || base.isEmpty()) base = config.platform() == null ? "" : config.platform().url();
        if (key == null || key.isEmpty() || base == null || base.isEmpty() || config.database() == null) return "";
        // After a failed ask, wait a little before asking again (every request would otherwise ask).
        if (System.currentTimeMillis() - platformFailedAt < 30_000) throw new IllegalStateException("UniversalIDE did not give the database address; asking again shortly.");
        platformFailedAt = System.currentTimeMillis();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/api/app-config"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + key).header("Accept", "application/json").GET().build();
        HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("UniversalIDE did not give the database address (" + res.statusCode() + ").");
        JsonNode given = json.readTree(res.body()).get("databaseUrl");
        String got = given != null && given.isString() ? given.stringValue() : "";
        if (!fits(config.database(), got)) throw new IllegalStateException("UniversalIDE gave an address that is not a " + config.database() + " database.");
        fromPlatform = got;
        platformFailedAt = 0;
        return got;
    }

    /** Null when the site's database server isn't set up. */
    public static Store open(Path dataDir, int keepMessages, JsonMapper json, ServerConfig config) throws Exception {
        String url = databaseUrl(config, json);
        return url.isEmpty() ? null : new PostgresStore(url, keepMessages, json);
    }
}
