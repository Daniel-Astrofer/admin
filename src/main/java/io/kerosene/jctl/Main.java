package io.kerosene.jctl;

import picocli.CommandLine;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(new CommandLine(new KeroseneJavaCli()).execute(args));
    }
}
