package com.daoekinc.pinfit;

import com.daoekinc.pinfit.cli.PinfitCli;
import java.nio.file.Path;

public class App {
    public static void main(String[] args) {
        int exitCode = new PinfitCli(Path.of("."), System.out, System.err).run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
