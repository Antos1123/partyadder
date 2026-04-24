package kr.antos112.partyadder.db;

import kr.antos112.partyadder.data.Party;
import kr.antos112.partyadder.data.PartyMember;
import kr.antos112.partyadder.data.Role;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class JdbcPartyRepository implements PartyRepository {
    private final DatabaseManager databaseManager;

    public JdbcPartyRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void initTables() {
        try (Connection c = databaseManager.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS parties (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL UNIQUE," +
                    "leader_uuid TEXT NOT NULL," +
                    "public_party INTEGER NOT NULL DEFAULT 1," +
                    "pvp_enabled INTEGER NOT NULL DEFAULT 0" +
                    ")");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS party_members (" +
                    "party_id INTEGER NOT NULL," +
                    "uuid TEXT NOT NULL," +
                    "role TEXT NOT NULL," +
                    "joined_at INTEGER NOT NULL," +
                    "last_seen INTEGER NOT NULL," +
                    "PRIMARY KEY (party_id, uuid)" +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<Long, Party> loadAll() {
        Map<Long, Party> parties = new HashMap<>();
        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id,name,leader_uuid,public_party,pvp_enabled FROM parties");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("id");
                Party party = new Party(id, rs.getString("name"), UUID.fromString(rs.getString("leader_uuid")),
                        rs.getInt("public_party") == 1, rs.getInt("pvp_enabled") == 1);
                parties.put(id, party);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT party_id,uuid,role,joined_at,last_seen FROM party_members");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long partyId = rs.getLong("party_id");
                Party party = parties.get(partyId);
                if (party == null) continue;
                PartyMember member = new PartyMember(
                        UUID.fromString(rs.getString("uuid")),
                        Role.valueOf(rs.getString("role")),
                        rs.getLong("joined_at"),
                        rs.getLong("last_seen")
                );
                party.getMembers().put(member.getUuid(), member);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return parties;
    }

    @Override
    public long createParty(String name, UUID leader, boolean publicParty, boolean pvpEnabled) {
        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO parties(name,leader_uuid,public_party,pvp_enabled) VALUES(?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, leader.toString());
            ps.setInt(3, publicParty ? 1 : 0);
            ps.setInt(4, pvpEnabled ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("No generated key");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteParty(long partyId) {
        try (Connection c = databaseManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement a = c.prepareStatement("DELETE FROM party_members WHERE party_id=?");
                 PreparedStatement b = c.prepareStatement("DELETE FROM parties WHERE id=?")) {
                a.setLong(1, partyId);
                a.executeUpdate();
                b.setLong(1, partyId);
                b.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void renameParty(long partyId, String newName) {
        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE parties SET name=? WHERE id=?")) {
            ps.setString(1, newName);
            ps.setLong(2, partyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updateLeader(long partyId, UUID leader) {
        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE parties SET leader_uuid=? WHERE id=?")) {
            ps.setString(1, leader.toString());
            ps.setLong(2, partyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updatePublic(long partyId, boolean publicParty) {
        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE parties SET public_party=? WHERE id=?")) {
            ps.setInt(1, publicParty ? 1 : 0);
            ps.setLong(2, partyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updatePvp(long partyId, boolean enabled) {
        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE parties SET pvp_enabled=? WHERE id=?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, partyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void addMember(long partyId, PartyMember member) {
        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO party_members(party_id,uuid,role,joined_at,last_seen) VALUES(?,?,?,?,?)")) {
            ps.setLong(1, partyId);
            ps.setString(2, member.getUuid().toString());
            ps.setString(3, member.getRole().name());
            ps.setLong(4, member.getJoinedAt());
            ps.setLong(5, member.getLastSeen());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updateMemberRole(long partyId, UUID uuid, Role role) {
        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE party_members SET role=? WHERE party_id=? AND uuid=?")) {
            ps.setString(1, role.name());
            ps.setLong(2, partyId);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updateMemberLastSeen(long partyId, UUID uuid, long lastSeen) {
        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE party_members SET last_seen=? WHERE party_id=? AND uuid=?")) {
            ps.setLong(1, lastSeen);
            ps.setLong(2, partyId);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void removeMember(long partyId, UUID uuid) {
        try (Connection c = databaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM party_members WHERE party_id=? AND uuid=?")) {
            ps.setLong(1, partyId);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
