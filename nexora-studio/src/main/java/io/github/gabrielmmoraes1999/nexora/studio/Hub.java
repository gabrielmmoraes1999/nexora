package io.github.gabrielmmoraes1999.nexora.studio;

import java.awt.Component;
import java.sql.Connection;

/**
 * O que as abas pedem à janela: a conexão viva, o catálogo e outra aba.
 */
interface Hub {

    Component owner();

    Connection connection(String databaseId);

    String currentSchema(String databaseId);

    void refreshOutline(String databaseId);

    void openSql(String databaseId, String title, String sql);

    void openNode(Node node, boolean data);

    void message(String text);
}
