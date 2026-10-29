package io.github.gabrielmmoraes1999.seadb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SeaCatalogTest {
    @TempDir Path dir;

    @Test
    void legacyMetadataExposesSeaNames() {
        Column column = new Column("NEX$USER_NAME", DataType.VARCHAR, 63, 0, true, false, 0, false, null);
        Schema old = new Schema("NEX$USERS", List.of(column), new int[]{0}, List.of(), List.of());
        Schema decoded = MetaCodec.decode(MetaCodec.encode(old, 1, 0, List.of(), List.of(), new long[0])).schema;
        assertEquals("SEA$USERS", decoded.name);
        assertEquals("SEA$USER_NAME", decoded.columns.getFirst().name);
        assertEquals(Relation.key("public", "SEA$USERS"),
                Relation.fromCatalog("NEX$USERS", decoded.name).key());
    }

    @Test
    void seaCatalogSupportsLegacySqlWithoutChangingLiterals() {
        Path file = dir.resolve("catalog.db");
        Database.create(file, DatabaseOptions.defaults());
        try (Database db = Database.open(file, "dba", "dba")) {
            assertEquals("DBA", db.query("SELECT SEA$USER_NAME FROM SEA$USERS").rows().getFirst().getString("SEA$USER_NAME"));
            assertEquals("NEX$USERS", db.query("SELECT 'NEX$USERS' AS literal FROM NEX$DATABASE").rows().getFirst().getString("literal"));
            assertThrows(SeaException.class, () -> db.execute("DROP TABLE SEA$USERS"));
        }
    }
}
