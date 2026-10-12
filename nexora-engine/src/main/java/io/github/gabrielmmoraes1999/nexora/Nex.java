package io.github.gabrielmmoraes1999.nexora;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Linha de comando para criar um arquivo {@code .xdb}.
 *
 * <pre>{@code
 * java -jar nexora-jdbc-1.0.jar create loja.xdb --page-size 8192 --user DBA --password segredo
 * }</pre>
 */
public final class Nex {

    private Nex() {
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
        if (!args[0].equalsIgnoreCase("create")) {
            throw new NexException("comando desconhecido: " + args[0] + "\n\n" + uso());
        }
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
            throw new NexException("informe o arquivo .xdb");
        }
        Path path = Path.of(arquivo);
        DatabaseOptions options = new DatabaseOptions(
                pageSize == null ? Format.DEFAULT_PAGE_SIZE : pageSize,
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

    static String uso() {
        return """
                Uso:
                  java -jar nexora-jdbc-1.0.jar create <arquivo.xdb> [opções]

                Cria o arquivo e o usuário DBA. A página não muda depois.
                O motor de rede é o projeto nexora-server.

                Opções:
                  --page-size <n>          4096, 8192, 16384 ou 32768 (padrão 8192)
                  --user <nome>            usuário DBA (padrão DBA)
                  --password <senha>       senha do DBA (padrão dba)
                  --name <nome>            nome do banco (padrão: nome do arquivo)
                  --description <texto>    descrição (padrão Nexora)
                  --charset <conjunto>     padrão UTF8

                Exemplo:
                  java -jar nexora-jdbc-1.0.jar create C:/dados/loja.xdb --page-size 16384 --password segredo --name LOJA
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
