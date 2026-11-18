package compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private Main() {}

    private static final String USAGE = """
            usage:
              run <file> [-O] [--interp] [--max-steps N]   compile and run (or interpret the tree)
              dis <file> [-O]                              print the bytecode
              fuzz [count] [seed]                          compare interpreter, VM and VM -O on random programs
              bench [repeats]                              time the examples
            """;

    public static void main(String[] args) throws Exception {
        System.exit(Pipeline.onBigStack(() -> run(args)));
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.print(USAGE);
            return 64;
        }
        switch (args[0]) {
            case "run", "dis" -> {
                if (args.length < 2) {
                    System.err.print(USAGE);
                    return 64;
                }
                boolean optimize = false, interp = false;
                long maxSteps = Long.MAX_VALUE;
                for (int i = 2; i < args.length; i++) {
                    switch (args[i]) {
                        case "-O" -> optimize = true;
                        case "--interp" -> interp = true;
                        case "--max-steps" -> maxSteps = Long.parseLong(args[++i]);
                        default -> {
                            System.err.println("unknown option " + args[i]);
                            return 64;
                        }
                    }
                }
                String src = read(args[1]);
                if (src == null) return 66;
                try {
                    Checker.Result checked = Checker.check(src);
                    if (args[0].equals("dis")) {
                        System.out.print(Compiler.compile(checked, optimize).disassemble());
                        return 0;
                    }
                    Pipeline.Outcome o = interp ? Pipeline.runInterpreter(checked)
                            : Pipeline.runVm(Compiler.compile(checked, optimize), maxSteps);
                    System.out.print(o.output());
                    System.out.flush();
                    if (!o.ok()) {
                        System.err.println("runtime error at " + o.error());
                        return 2;
                    }
                    return 0;
                } catch (CompileError e) {
                    System.err.println(e.render(src));
                    return 1;
                }
            }
            case "fuzz" -> {
                int count = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
                long seed = args.length > 2 ? Long.parseLong(args[2]) : 1;
                Fuzz.Report r = Fuzz.run(count, seed);
                System.out.printf("%d programs (seeds %d-%d): %d ran to completion, %d stopped with a runtime error, "
                        + "%d mismatches%n", r.programs(), seed, seed + count - 1, r.completed(), r.failedAtRuntime(),
                        r.mismatches());
                if (r.firstMismatch() != null) System.out.println("first mismatch:\n" + r.firstMismatch());
                return r.mismatches() == 0 ? 0 : 1;
            }
            case "bench" -> {
                Bench.run(Path.of("examples"), args.length > 1 ? Integer.parseInt(args[1]) : 5);
                return 0;
            }
            default -> {
                System.err.print(USAGE);
                return 64;
            }
        }
    }

    private static String read(String file) {
        try {
            return Files.readString(Path.of(file));
        } catch (IOException e) {
            System.err.println("can't read " + file + ": " + e.getMessage());
            return null;
        }
    }
}
