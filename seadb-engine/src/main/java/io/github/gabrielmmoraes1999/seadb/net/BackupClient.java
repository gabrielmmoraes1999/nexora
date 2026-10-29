package io.github.gabrielmmoraes1999.seadb.net;

import io.github.gabrielmmoraes1999.seadb.BackupPiece;
import io.github.gabrielmmoraes1999.seadb.SeaException;
import io.github.gabrielmmoraes1999.seadb.RemoteClient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Copies a database that a server already has open.
 * The directory is on this machine. A full backup asks the server to reset
 * the live log only after every file has been written here.
 */
public final class BackupClient {

    private BackupClient() {
    }

    public static void copy(String host, int port, String database, String user, String password, String role,
                            boolean incremental, Path directory, IntConsumer progress) {
        if (host == null || host.isBlank()) {
            throw new SeaException("server host is required");
        }
        if (database == null || database.isBlank()) {
            throw new SeaException("database name is required");
        }
        if (user == null || user.isBlank() || password == null || password.isEmpty()) {
            throw new SeaException("user and password are required");
        }
        Socket socket = new Socket();
        DataOutputStream output = null;
        List<Path> partials = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        boolean started = false;
        boolean finished = false;
        try {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setTcpNoDelay(true);
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            RemoteClient local = RemoteClient.local();
            Wire.writeFrame(output, Wire.LOGIN, Wire.encodeLogin(
                    database, user, password, role, local.host(), local.osUser(), local.process()));
            readOk(input, "invalid response from the server");
            Wire.writeFrame(output, Wire.BACKUP, Wire.encodeBackupRequest(incremental));
            Wire.BackupOffer offer = Wire.decodeBackupOffer(readOk(input, "invalid response from the server"));
            started = true;
            Path folder = prepare(directory);
            rejectLiveDirectory(folder, offer.databaseDirectory());
            long total = 0;
            for (Wire.BackupFile file : offer.files()) {
                checkName(file.name());
                if (file.size() < 0 || total > Long.MAX_VALUE - file.size()) {
                    throw new SeaException("invalid backup file");
                }
                total += file.size();
            }
            long done = 0;
            report(progress, total == 0 ? 100 : 0);
            int index = 0;
            for (Wire.BackupFile file : offer.files()) {
                Path partial = folder.resolve(file.name() + ".partial");
                partials.add(partial);
                try (FileChannel channel = FileChannel.open(partial,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    long offset = 0;
                    while (offset < file.size()) {
                        int want = (int) Math.min(BackupPiece.CHUNK, file.size() - offset);
                        Wire.writeFrame(output, Wire.BACKUP_READ, Wire.encodeBackupRead(index, offset, want));
                        byte[] data = readOk(input, "invalid response from the server");
                        if (data.length != want) {
                            throw new SeaException("the backup ended before the file was complete");
                        }
                        ByteBuffer buffer = ByteBuffer.wrap(data);
                        while (buffer.hasRemaining()) {
                            channel.write(buffer);
                        }
                        offset += data.length;
                        done += data.length;
                        report(progress, total == 0 ? 100 : (int) Math.min(99L, done * 99L / total));
                    }
                    channel.force(true);
                }
                index++;
            }
            for (int i = 0; i < offer.files().size(); i++) {
                Path target = folder.resolve(offer.files().get(i).name());
                Files.move(partials.get(i), target, StandardCopyOption.REPLACE_EXISTING);
                written.add(target);
            }
            partials.clear();
            Wire.writeFrame(output, Wire.BACKUP_FINISH, new byte[0]);
            readOk(input, "invalid response from the server");
            finished = true;
            report(progress, 100);
            try {
                Wire.writeFrame(output, Wire.LOGOUT, new byte[0]);
                readOk(input, "invalid response from the server");
            } catch (IOException | SeaException ignored) {
                // the files are already in place and the live log was reset
            }
        } catch (SeaException exception) {
            throw exception;
        } catch (IOException exception) {
            String message = socket.isConnected()
                    ? "connection to the server failed: " + exception.getMessage()
                    : "could not connect to " + host + ":" + port + ": " + exception.getMessage();
            throw new SeaException(message, exception);
        } finally {
            if (started && !finished && output != null) {
                try {
                    Wire.writeFrame(output, Wire.BACKUP_ABORT, new byte[0]);
                } catch (IOException ignored) {
                    // the server drops the backup when the connection closes
                }
            }
            if (!finished) {
                for (Path partial : partials) {
                    deleteQuiet(partial);
                }
                for (Path file : written) {
                    deleteQuiet(file);
                }
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // the connection is already down
            }
        }
    }

    private static Path prepare(Path directory) {
        if (directory == null) {
            throw new SeaException("backup directory is required");
        }
        Path folder = directory.toAbsolutePath().normalize();
        if (Files.exists(folder) && !Files.isDirectory(folder)) {
            throw new SeaException("the backup destination is a directory");
        }
        try {
            Files.createDirectories(folder);
        } catch (IOException exception) {
            throw new SeaException("could not create the backup directory: " + exception.getMessage(), exception);
        }
        return folder;
    }

    private static void rejectLiveDirectory(Path folder, String databaseDirectory) {
        if (databaseDirectory == null || databaseDirectory.isBlank()) {
            return;
        }
        Path live = Path.of(databaseDirectory).toAbsolutePath().normalize();
        if (folder.equals(live)) {
            throw new SeaException("the backup cannot be placed in the database directory");
        }
    }

    private static void checkName(String name) {
        if (name == null || name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.equals(".") || name.equals("..")) {
            throw new SeaException("invalid backup file name");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".db") && !lower.endsWith(".log")) {
            throw new SeaException("invalid backup file name");
        }
    }

    private static byte[] readOk(DataInputStream input, String invalid) throws IOException {
        Wire.Frame response = Wire.readFrame(input);
        if (response.opcode() == Wire.FAIL) {
            throw new SeaException(Wire.decodeError(response.body()));
        }
        if (response.opcode() != Wire.OK) {
            throw new SeaException(invalid);
        }
        return response.body();
    }

    private static void deleteQuiet(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the incomplete copy is left for the next attempt
        }
    }

    private static void report(IntConsumer progress, int percent) {
        if (progress != null) {
            progress.accept(percent);
        }
    }
}
