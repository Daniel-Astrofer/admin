package io.kerosene.jctl.bootstrap;

import io.kerosene.jctl.adapters.in.cli.KeroseneJavaCli;
import picocli.CommandLine;

/** Process entry point that delegates argument parsing and execution to Picocli. */
public final class Main {
    private Main() {}

    /** Runs the CLI and terminates the process with Picocli's command status.
     * @param args process arguments parsed by Picocli
     */
    public static void main(String[] args) {
        System.exit(new CommandLine(new KeroseneJavaCli()).execute(args));
    }
}
