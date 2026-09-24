package com.daoekinc.pinfit;

import com.daoekinc.pinfit.cli.PinfitCli;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

final class CliFixture {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private final PinfitCli cli;

    CliFixture(Path directory) {
        this(directory, "");
    }

    CliFixture(Path directory, String input) {
        this(directory, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    CliFixture(Path directory, InputStream input) {
        cli = new PinfitCli(directory, input, new PrintStream(output), new PrintStream(errors));
    }

    /** Input that fails on every read - for the "Cannot read confirmation" paths. */
    static InputStream failingInput() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stdin closed");
            }
        };
    }

    int run(String... arguments) {
        return cli.run(arguments);
    }

    String errors() {
        return errors.toString(StandardCharsets.UTF_8);
    }

    String output() {
        return output.toString(StandardCharsets.UTF_8);
    }
}
