package io.github.gabrielmmoraes1999.nexora.dbsrv.console;

import io.github.gabrielmmoraes1999.nexora.NexException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgumentsTest {

    @TempDir
    Path dir;

    @Test
    void personalFicaNaMaquina() {
        Arguments arguments = Arguments.parse(Kind.PERSONAL, new String[]{"-p", "4590", "C:/data/store.db"});
        assertEquals("127.0.0.1", arguments.host());
        assertEquals(4590, arguments.port());
        assertEquals("store", arguments.databases().get(0).name());
        assertEquals(Path.of("C:/data/store.db"), arguments.databases().get(0).path());

        Arguments named = Arguments.parse(Kind.PERSONAL, new String[]{"C:/data/store.db", "-n", "caixa"});
        assertEquals("caixa", named.databases().get(0).name());

        assertTrue(assertThrows(NexException.class,
                () -> Arguments.parse(Kind.PERSONAL, new String[]{"-n", "servidor", "C:/data/store.db"})).getMessage().contains("follows"));

        Arguments link = Arguments.parse(Kind.PERSONAL, new String[]{"-x", "tcpip(port=4600)", "store.db"});
        assertEquals(4600, link.port());
        assertEquals("store", link.databases().get(0).name());

        NexException remote = assertThrows(NexException.class,
                () -> Arguments.parse(Kind.PERSONAL, new String[]{"--host", "0.0.0.0", "store.db"}));
        assertTrue(remote.getMessage().contains("this machine"));
    }

    @Test
    void networkOuveARede() {
        Arguments arguments = Arguments.parse(Kind.NETWORK, new String[]{
                "-x", "tcpip(host=192.168.0.10;port=4581)", "C:/data/store.db", "C:/data/other.db"});
        assertEquals("192.168.0.10", arguments.host());
        assertEquals(4581, arguments.port());
        assertEquals(2, arguments.databases().size());
        assertEquals("other", arguments.databases().get(1).name());

        Arguments plain = Arguments.parse(Kind.NETWORK, new String[0]);
        assertEquals("0.0.0.0", plain.host());
        assertEquals(Arguments.DEFAULT_PORT, plain.port());
        assertEquals(Arguments.DEFAULT_CACHE, plain.cache());
        assertNull(plain.applyLog());
        assertTrue(plain.databases().isEmpty());
    }

    @Test
    void opcoesDeCacheELog() {
        assertEquals(List.of("-x", "tcpip(port=4580)", "-a", "banco.log"),
                Options.split("-x \"tcpip(port=4580)\" -a banco.log"));
        assertThrows(NexException.class, () -> Options.split("-x \"tcpip(port=4580)"));

        Arguments apply = Arguments.parse(Kind.PERSONAL, new String[]{
                dir.resolve("store.db").toString(), "-c", "16M", "-a", "banco.log", "-x", "tcpip(port=4701)"
        });
        assertEquals("16M", apply.cache());
        assertEquals(4701, apply.port());
        assertEquals(dir.resolve("banco.log").toAbsolutePath().normalize(), apply.applyLog());
        assertEquals("2048K", Arguments.parse(Kind.NETWORK, new String[]{"-c", "2048kb", "a.db"}).cache());
        assertEquals("32M", Arguments.parse(Kind.NETWORK, new String[]{"-c", "32", "a.db"}).cache());
        assertTrue(assertThrows(NexException.class,
                () -> Arguments.parse(Kind.PERSONAL, new String[]{"-c", "grande", "a.db"})).getMessage().contains("cache"));
        assertTrue(assertThrows(NexException.class,
                () -> Arguments.parse(Kind.PERSONAL, new String[]{"-a", "banco.log"})).getMessage().contains("one database"));
    }

    @Test
    void ajudaEErros() {
        assertTrue(Arguments.parse(Kind.PERSONAL, new String[]{"--help"}).help());
        assertTrue(Arguments.parse(Kind.NETWORK, new String[]{"-?"}).help());
        assertTrue(assertThrows(NexException.class,
                () -> Arguments.parse(Kind.NETWORK, new String[]{"-p", "zero"})).getMessage().contains("port"));
        assertTrue(assertThrows(NexException.class,
                () -> Arguments.parse(Kind.NETWORK, new String[]{"-x", "http"})).getMessage().contains("link"));
        assertTrue(assertThrows(NexException.class,
                () -> Arguments.parse(Kind.PERSONAL, new String[]{"a.db", "other/a.db"})).getMessage().contains("duplicate"));
        assertTrue(assertThrows(NexException.class,
                () -> Arguments.parse(Kind.NETWORK, new String[]{"--quiet"})).getMessage().contains("unknown"));
        assertTrue(Arguments.usage(Kind.PERSONAL).contains("nexora-dbeng"));
        assertTrue(Arguments.usage(Kind.NETWORK).contains("nexora-dbsrv"));
    }
}
