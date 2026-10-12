package io.github.gabrielmmoraes1999.nexora;

/**
 * Erro de SQL, de integridade ou de leitura do arquivo da tabela.
 */
public class NexException extends RuntimeException {

    public NexException(String message) {
        super(message);
    }

    public NexException(String message, Throwable cause) {
        super(message, cause);
    }
}
