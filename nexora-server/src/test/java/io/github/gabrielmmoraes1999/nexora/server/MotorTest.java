package io.github.gabrielmmoraes1999.nexora.server;

import io.github.gabrielmmoraes1999.nexora.NexException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotorTest {

    @TempDir
    Path dir;

    @Test
    void jsonInvalidoNaoSobe() throws Exception {
        assertThrows(NexException.class, () -> Motor.execute(new String[]{"serve"}));
        Path missing = dir.resolve("ausente.json");
        NexException ausente = assertThrows(NexException.class, () -> Motor.execute(new String[]{"serve", missing.toString()}));
        assertTrue(ausente.getMessage().contains("ler"));

        Path bad = dir.resolve("ruim.json");
        Files.writeString(bad, "{");
        NexException invalido = assertThrows(NexException.class, () -> MotorConfig.read(bad));
        assertTrue(invalido.getMessage().contains("json"));

        Path semPorta = dir.resolve("sem-porta.json");
        Files.writeString(semPorta, """
                {"databases":[{"name":"loja","path":"loja.xdb"}]}
                """);
        assertTrue(assertThrows(NexException.class, () -> MotorConfig.read(semPorta)).getMessage().contains("porta"));

        Path zero = dir.resolve("zero.json");
        Files.writeString(zero, """
                {"port":0,"databases":[{"name":"loja","path":"loja.xdb"}]}
                """);
        assertTrue(assertThrows(NexException.class, () -> MotorConfig.read(zero)).getMessage().contains("porta"));

        Path repetido = dir.resolve("repetido.json");
        Files.writeString(repetido, """
                {"port":1,"databases":[{"name":"Loja","path":"a.xdb"},{"name":"loja","path":"b.xdb"}]}
                """);
        assertTrue(assertThrows(NexException.class, () -> MotorConfig.read(repetido)).getMessage().contains("repetido"));

        Path estranho = dir.resolve("estranho.json");
        Files.writeString(estranho, """
                {"port":1,"databases":[{"name":"loja","path":"a.xdb","extra":"x"}]}
                """);
        assertTrue(assertThrows(NexException.class, () -> MotorConfig.read(estranho)).getMessage().contains("desconhecida"));

        Path relativo = dir.resolve("motor.json");
        Files.writeString(relativo, """
                {"databases":[{"name":"loja","path":"dados/loja.xdb"}],"port":4580}
                """);
        MotorConfig config = MotorConfig.read(relativo);
        assertEquals("0.0.0.0", config.host());
        assertEquals(4580, config.port());
        assertEquals(dir.resolve("dados").resolve("loja.xdb").toAbsolutePath().normalize(), config.databases().get(0).path());
        assertEquals("loja", config.databases().get(0).name());
    }
}
