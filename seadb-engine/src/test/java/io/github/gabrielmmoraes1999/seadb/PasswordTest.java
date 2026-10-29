package io.github.gabrielmmoraes1999.seadb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordTest {

    @Test
    void confereHashesConhecidos() {
        assertTrue(Password.verify("$2a$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i", "abc"));
        assertTrue(Password.verify("$2b$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i", "abc"));
        assertTrue(Password.verify("$2y$06$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i", "abc"));
        assertTrue(Password.verify("$2a$10$WvvTPHKwdBJ3uk0Z37EMR.hLA2W6N9AEBhEgrAOljy2Ae5MtaSIUi", "abc"));
        assertTrue(Password.verify("$2a$10$.OGB/.SE/ueHAeqKBO2NC.rF/C7kcdJElM4zJuclNBPucne72ujfK", "dba"));
        assertTrue(Password.verify("$2a$10$.OGB/.SE/ueHAeqKBO2NC.ckpsnp1n4Xy4EFjFRc/pRng8LkyjE0e", "ação"));
        assertTrue(Password.verify("$2a$10$.OGB/.SE/ueHAeqKBO2NC.apgGS35u2k8X5tBZOxq6mLAJKsu/q3S", ""));
        assertTrue(Password.verify("$2a$04$.OGB/.SE/ueHAeqKBO2NC.tPKaUXr62cPg/Vzl7N/hP.8SSTEgzAC", "dba"));
        assertTrue(Password.verify("$2a$10$d4oBLO4xNiBxoHkXzQfY0.Mfw9WumXeZMx16o/oeW.l3Og2qtGbdO", "segredo"));
        assertTrue(Password.verify("$2a$10$Tz3mcpsC7f6NOWfXQRmrUeLOTxs..1laEFe5YksNglVfWQVLsyakW", "ação"));
        assertFalse(Password.verify("$2a$10$.OGB/.SE/ueHAeqKBO2NC.rF/C7kcdJElM4zJuclNBPucne72ujfK", "errada"));
        assertFalse(Password.verify("$2a$06$not-valid!!!!!!!!!!!!000000000000000000000", "abc"));
        assertFalse(Password.verify("$2a$03$If6bvum7DFjUnE9p2uDeDu0YHzrHM6tf.iqN8.yx.jNN1ILEf7h0i", "abc"));
        assertFalse(Password.verify(null, "abc"));
        assertFalse(Password.verify("$2a$10$WvvTPHKwdBJ3uk0Z37EMR.hLA2W6N9AEBhEgrAOljy2Ae5MtaSIUi", null));
    }

    @Test
    void gravaEConfere() {
        String hash = Password.hash("segredo");
        assertTrue(hash.startsWith("$2a$10$"));
        assertEquals(60, hash.length());
        assertTrue(Password.verify(hash, "segredo"));
        assertFalse(Password.verify(hash, "outro"));
        assertFalse(Password.hash("segredo").equals(hash));
    }

    @Test
    void senhaLongaDemais() {
        String longa = "a".repeat(73);
        assertThrows(IllegalArgumentException.class, () -> Password.hash(longa));
        assertFalse(Password.verify("$2a$10$WvvTPHKwdBJ3uk0Z37EMR.hLA2W6N9AEBhEgrAOljy2Ae5MtaSIUi", longa));
    }
}
