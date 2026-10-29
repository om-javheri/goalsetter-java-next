package site;

import java.util.List;
import java.util.Map;

/** Where messages and counters are kept. Nothing about visitors is kept. */
public interface Store {
    /** Keeps a message; only the newest keepMessages are kept. */
    void addMessage(Rules.Message m, String createdAt) throws Exception;

    /** The newest messages first. */
    List<Map<String, Object>> messages(int limit) throws Exception;

    boolean deleteMessage(long id) throws Exception;

    /** Adds to the day's counters. */
    void bump(String day, List<Rules.Bump> rows) throws Exception;

    /** Keeps rows counted once a day (count 1), leaving those already there: [kind, key]. */
    void once(String day, List<String[]> rows) throws Exception;

    /** Every counter since a day (YYYY-MM-DD), as kept. */
    List<Rules.Count> counts(String since) throws Exception;

    /** The visitor hash's secret: made once, at random, and kept. */
    String secret() throws Exception;

    /** The last days days (today included): views a day, by page and by referring site. */
    Map<String, Object> stats(int days) throws Exception;

    /** A new member's id, or null when the email already has an account (docs/62). */
    Long addMember(String email, String name, String hash, String status) throws Exception;

    Members.Member memberByEmail(String email) throws Exception;

    void addSession(String tokenHash, long memberId, String expiresAt) throws Exception;

    /** The active member a session belongs to, while it lasts (times as ISO 8601 text); else null. */
    Members.Member sessionMember(String tokenHash, String now) throws Exception;

    void endSession(String tokenHash) throws Exception;

    List<Members.Member> members() throws Exception;

    boolean approveMember(long id) throws Exception;

    /** Takes a member out: their account and sessions go; their records stay, without a member (docs/84). */
    boolean removeMember(long id) throws Exception;

    /** A record of a collection kept in the database (docs/84): its values as JSON text. */
    record RecordRow(long id, Long memberId, String values, String createdAt, String updatedAt) {}

    /** Newest first; only a member's own when memberId isn't null. */
    List<RecordRow> records(String collection, Long memberId, int limit) throws Exception;

    long recordCount(String collection) throws Exception;

    RecordRow addRecord(String collection, Long memberId, String values, String now) throws Exception;

    /** The record, if it is in the collection; else null. */
    RecordRow record(String collection, long id) throws Exception;

    void updateRecord(long id, String values, String now) throws Exception;

    boolean deleteRecord(String collection, long id) throws Exception;

    /** A database server keeps the sending limits itself, so they hold across instances; null: kept in memory. */
    default Boolean allow(String bucket, int perMinute) throws Exception {
        return null;
    }
}
