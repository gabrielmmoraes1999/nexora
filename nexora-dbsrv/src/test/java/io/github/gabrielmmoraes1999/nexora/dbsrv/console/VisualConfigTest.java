package io.github.gabrielmmoraes1999.nexora.dbsrv.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualConfigTest {

    @TempDir
    Path dir;

    @Test
    void guardaECarregaOsCampos() throws Exception {
        Path file = dir.resolve("dbeng.properties");
        VisualConfig.Fields fields = new VisualConfig.Fields(
                "C:\\data\\store.db",
                "16M",
                "-x \"tcpip(port=4700)\" -a banco.log");
        VisualConfig.save(file, fields);
        assertEquals(fields, VisualConfig.load(file));
        assertTrue(Files.readString(file).contains("database"));
    }

    @Test
    void arquivoAusenteOuQuebrado() throws Exception {
        assertNull(VisualConfig.load(dir.resolve("missing.properties")));
        Path broken = dir.resolve("broken.properties");
        Files.writeString(broken, "\\uZZ");
        assertNull(VisualConfig.load(broken));
    }

    @Test
    void janelaUsaOSalvoQuandoALinhaEstaVazia() {
        Arguments plain = Arguments.parse(Kind.PERSONAL, new String[0]);
        VisualConfig.Fields saved = new VisualConfig.Fields("C:/data/store.db", "32M", "-x tcpip(port=4590)");
        assertEquals(saved, VisualConfig.display(plain, saved));

        Arguments network = Arguments.parse(Kind.NETWORK, new String[0]);
        assertEquals("0.0.0.0", network.host());
        assertEquals(saved, VisualConfig.display(network, saved));
    }

    @Test
    void linhaDeComandoGanhaDoSalvo() {
        Arguments cache = Arguments.parse(Kind.PERSONAL, new String[]{"-c", "64M"});
        VisualConfig.Fields saved = new VisualConfig.Fields("C:/data/store.db", "32M", "-x tcpip(port=4590)");
        VisualConfig.Fields shown = VisualConfig.display(cache, saved);
        assertEquals("", shown.database());
        assertEquals("64M", shown.cache());
        assertEquals("", shown.options());

        Arguments port = Arguments.parse(Kind.NETWORK, new String[]{"-x", "tcpip(port=4600)"});
        assertEquals("-x tcpip(port=4600)", VisualConfig.display(port, saved).options());
        assertEquals(Arguments.DEFAULT_CACHE, VisualConfig.display(plainWithoutSave(), null).cache());
    }

    private static Arguments plainWithoutSave() {
        return Arguments.parse(Kind.PERSONAL, new String[0]);
    }
}
