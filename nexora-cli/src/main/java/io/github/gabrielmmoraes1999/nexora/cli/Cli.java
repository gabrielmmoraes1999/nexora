package io.github.gabrielmmoraes1999.nexora.cli;

import io.github.gabrielmmoraes1999.nexora.Database;
import io.github.gabrielmmoraes1999.nexora.DatabaseOptions;
import io.github.gabrielmmoraes1999.nexora.NexException;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Linha de comando do Nexora. Cria o {@code .db}, faz o backup do par e aplica um log.
 * O JDBC e o motor abrem um arquivo que já existe.
 *
 * <pre>{@code
 * java -jar nexora-cli-1.0.jar create loja.db --page-size 8192 --user DBA --password segredo
 * java -jar nexora-cli-1.0.jar backup completo loja.db C:/dados/backup
 * java -jar nexora-cli-1.0.jar backup modificacao loja.db C:/dados/logs
 * java -jar nexora-cli-1.0.jar apply C:/dados/backup/loja.db loja.log
 * java -jar nexora-cli-1.0.jar export loja.log C:/dados/loja.sql
 * }</pre>
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) {
        try {
            execute(args);
        } catch (NexException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }

    public static void execute(String[] args) {
        if (args != null && args.length > 0 && help(args[0])) {
            System.out.println(uso());
            return;
        }
        if (args == null || args.length == 0) {
            throw new NexException(uso());
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(args);
            case "backup" -> backup(args);
            case "apply" -> apply(args);
            case "export" -> export(args);
            default -> throw new NexException("comando desconhecido: " + args[0] + "\n\n" + uso());
        }
    }

    private static void create(String[] args) {
        String arquivo = null;
        Integer pageSize = null;
        String user = null;
        String password = null;
        String name = null;
        String description = null;
        String charset = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-")) {
                if (arquivo != null) {
                    throw new NexException("arquivo informado mais de uma vez");
                }
                arquivo = arg;
                continue;
            }
            String key = arg;
            String value = null;
            int equals = arg.indexOf('=');
            if (equals >= 0) {
                key = arg.substring(0, equals);
                value = arg.substring(equals + 1);
            }
            if (value == null) {
                if (i + 1 >= args.length || args[i + 1].startsWith("-")) {
                    throw new NexException("falta o valor de " + key);
                }
                value = args[++i];
            }
            switch (normal(key)) {
                case "page-size" -> pageSize = parsePageSize(value);
                case "user" -> user = value;
                case "password" -> password = value;
                case "name" -> name = value;
                case "description" -> description = value;
                case "charset" -> charset = value;
                default -> throw new NexException("opção desconhecida: " + key);
            }
        }
        if (arquivo == null || arquivo.isBlank()) {
            throw new NexException("informe o arquivo .db");
        }
        Path path = Path.of(arquivo);
        DatabaseOptions options = new DatabaseOptions(
                pageSize == null ? DatabaseOptions.defaults().pageSize() : pageSize,
                user == null ? "DBA" : user,
                password == null ? "dba" : password,
                name == null ? fileName(path) : name,
                description == null ? "Nexora" : description,
                charset == null ? "UTF8" : charset);
        Database.create(path, options);
        System.out.println("banco criado: " + path.toAbsolutePath().normalize());
        System.out.println("página: " + options.pageSize());
        System.out.println("usuário: " + options.user());
        System.out.println("nome: " + options.name());
    }

    private static void backup(String[] args) {
        String kind = "completo";
        int dbAt = 1;
        if (args.length >= 2 && backupKind(args[1]) != null) {
            kind = backupKind(args[1]);
            dbAt = 2;
        }
        if (args.length != dbAt + 2 || args[dbAt].isBlank() || args[dbAt + 1].isBlank()) {
            throw new NexException("""
                    uso: backup completo <arquivo.db> <pasta>
                    uso: backup modificacao <arquivo.db> <pasta>""");
        }
        Path database = Path.of(args[dbAt]);
        Path directory = Path.of(args[dbAt + 1]);
        if (kind.equals("modificacao")) {
            Database.backupLog(database, directory);
            System.out.println("backup de modificação gravado em: " + directory.toAbsolutePath().normalize());
            return;
        }
        Database.backup(database, directory);
        System.out.println("backup completo gravado em: " + directory.toAbsolutePath().normalize());
    }

    private static String backupKind(String text) {
        String kind = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "completo", "modificacao" -> kind;
            default -> null;
        };
    }

    private static void apply(String[] args) {
        if (args.length != 3 || args[1].isBlank() || args[2].isBlank()) {
            throw new NexException("uso: apply <arquivo.db> <arquivo.log>");
        }
        Path database = Path.of(args[1]);
        Path log = Path.of(args[2]);
        Database.applyLog(database, log);
        System.out.println("log aplicado em: " + database.toAbsolutePath().normalize());
    }

    private static void export(String[] args) {
        if (args.length != 3 || args[1].isBlank() || args[2].isBlank()) {
            throw new NexException("uso: export <arquivo.log> <arquivo.sql>");
        }
        Path source = Path.of(args[1]);
        Path sql = Path.of(args[2]);
        int[] shown = {-1};
        Database.exportSql(source, sql, percent -> {
            if (percent == shown[0]) {
                return;
            }
            shown[0] = percent;
            System.out.print("\rexportando: " + percent + "%");
            System.out.flush();
        });
        System.out.println();
        System.out.println("sql gravado em: " + sql.toAbsolutePath().normalize());
    }

    static String uso() {
        return """
                Uso:
                  java -jar nexora-cli-1.0.jar <comando> [argumentos]

                Comandos:
                  create <arquivo.db>          cria o .db, o .log e o usuário DBA
                  backup completo <arquivo.db> <pasta>     copia o .db e o .log e zera o log vivo
                  backup modificacao <arquivo.db> <pasta>  copia só o .log
                  backup <arquivo.db> <pasta>              o mesmo que completo
                  apply <arquivo.db> <arquivo.log>  aplica o log sobre o .db
                  export <arquivo.log> <arquivo.sql>  grava o SQL do log

                A página não muda depois da criação.
                O JDBC e o motor abrem um arquivo que já existe.
                O log guarda as mudanças desde a criação ou o último backup completo.
                O backup de modificação deixa o log vivo no lugar.
                export também aceita o .db e usa o .log ao lado.

                Opções de create:
                  --page-size <n>          4096, 8192, 16384 ou 32768 (padrão 8192)
                  --user <nome>            usuário DBA (padrão DBA)
                  --password <senha>       senha do DBA (padrão dba)
                  --name <nome>            nome do banco (padrão: nome do arquivo)
                  --description <texto>    descrição (padrão Nexora)
                  --charset <conjunto>     padrão UTF8

                Exemplo:
                  java -jar nexora-cli-1.0.jar create C:/dados/loja.db --page-size 16384 --password segredo --name LOJA
                  java -jar nexora-cli-1.0.jar backup completo C:/dados/loja.db C:/dados/backup
                  java -jar nexora-cli-1.0.jar backup modificacao C:/dados/loja.db C:/dados/logs
                  java -jar nexora-cli-1.0.jar apply C:/dados/backup/loja.db C:/dados/loja.log
                  java -jar nexora-cli-1.0.jar export C:/dados/loja.log C:/dados/loja.sql
                """;
    }

    private static boolean help(String arg) {
        return arg.equalsIgnoreCase("--help") || arg.equalsIgnoreCase("-h");
    }

    private static String normal(String key) {
        String text = key;
        while (text.startsWith("-")) {
            text = text.substring(1);
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private static int parsePageSize(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new NexException("tamanho de página inválido: use 4096, 8192, 16384 ou 32768");
        }
    }

    private static String fileName(Path file) {
        Path nome = file.getFileName();
        if (nome == null) {
            return "NEXORA";
        }
        String stem = nome.toString();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        if (stem.isBlank()) {
            return "NEXORA";
        }
        return stem;
    }
}
