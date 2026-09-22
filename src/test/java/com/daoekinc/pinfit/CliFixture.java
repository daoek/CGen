package com.daoekinc.pinfit;

import com.daoekinc.pinfit.cli.PinfitCli;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
        cli = new PinfitCli(directory, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output), new PrintStream(errors));
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
