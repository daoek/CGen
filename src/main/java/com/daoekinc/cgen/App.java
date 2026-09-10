package com.daoekinc.cgen;

import com.daoekinc.cgen.cli.CGenCli;
import java.nio.file.Path;

public class App {
    public static void main(String[] args) {
        int exitCode = new CGenCli(Path.of("."), System.out, System.err).run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
