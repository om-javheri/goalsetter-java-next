package site;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Records as rows of the records table: the values as JSON text, with the member who added them. */
final class SqlRecords {
    private SqlRecords() {}

    private static Store.RecordRow row(ResultSet r) throws SQLException {
        long member = r.getLong("member_id");
        // Asked right after its column: wasNull() is about the last one read.
        Long memberId = r.wasNull() ? null : member;
        return new Store.RecordRow(r.getLong("id"), memberId, r.getString("record_values"), r.getString("created_at"), r.getString("updated_at"));
    }

    static List<Store.RecordRow> list(Connection db, String collection, Long memberId, int limit) throws SQLException {
        String sql = memberId == null ? "SELECT * FROM records WHERE collection = ? ORDER BY id DESC LIMIT ?" : "SELECT * FROM records WHERE collection = ? AND member_id = ? ORDER BY id DESC LIMIT ?";
        try (PreparedStatement q = db.prepareStatement(sql)) {
            q.setString(1, collection);
            if (memberId == null) q.setInt(2, limit);
            else {
                q.setLong(2, memberId);
                q.setInt(3, limit);
            }
            List<Store.RecordRow> out = new ArrayList<>();
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) out.add(row(r));
            }
            return out;
        }
    }

    static long count(Connection db, String collection) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("SELECT COUNT(*) FROM records WHERE collection = ?")) {
            q.setString(1, collection);
            try (ResultSet r = q.executeQuery()) {
                return r.next() ? r.getLong(1) : 0;
            }
        }
    }

    static Store.RecordRow add(Connection db, String collection, Long memberId, String values, String now) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("INSERT INTO records (collection, member_id, record_values, created_at, updated_at) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            q.setString(1, collection);
            // The app's own endpoints may add records without a member (docs/88).
            if (memberId == null) q.setNull(2, java.sql.Types.BIGINT);
            else q.setLong(2, memberId);
            q.setString(3, values);
            q.setString(4, now);
            q.setString(5, now);
            q.executeUpdate();
            try (ResultSet keys = q.getGeneratedKeys()) {
                keys.next();
                return new Store.RecordRow(keys.getLong(1), memberId, values, now, now);
            }
        }
    }

    static Store.RecordRow get(Connection db, String collection, long id) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("SELECT * FROM records WHERE collection = ? AND id = ?")) {
            q.setString(1, collection);
            q.setLong(2, id);
            try (ResultSet r = q.executeQuery()) {
                return r.next() ? row(r) : null;
            }
        }
    }

    static void update(Connection db, long id, String values, String now) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("UPDATE records SET record_values = ?, updated_at = ? WHERE id = ?")) {
            q.setString(1, values);
            q.setString(2, now);
            q.setLong(3, id);
            q.executeUpdate();
        }
    }

    static boolean delete(Connection db, String collection, long id) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("DELETE FROM records WHERE collection = ? AND id = ?")) {
            q.setString(1, collection);
            q.setLong(2, id);
            return q.executeUpdate() > 0;
        }
    }

    /** A member taken out: their records stay, without a member. */
    static void unown(Connection db, long memberId) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("UPDATE records SET member_id = NULL WHERE member_id = ?")) {
            q.setLong(1, memberId);
            q.executeUpdate();
        }
    }
}
