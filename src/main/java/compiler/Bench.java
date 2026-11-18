package compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Times the tree-walking interpreter against the VM, with and without constant folding. */
public final class Bench {
    private Bench() {}

    static final List<String> PROGRAMS = List.of("fib", "primes", "collatz", "gcd", "constants");

    public static void run(Path examples, int repeats) throws IOException, CompileError {
        System.out.printf("%d timed runs per cell after 2 warm-up runs; median wall time.%n", repeats);
        System.out.printf("Java %s, %d cores%n%n", System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors());
        System.out.printf("%-10s %12s %12s %12s %9s %16s %16s %8s%n", "program", "tree (ms)", "vm (ms)",
                "vm -O (ms)", "tree/vm", "vm instrs run", "vm -O instrs run", "saved");
        for (String name : PROGRAMS) {
            String src = Files.readString(examples.resolve(name + ".mini"));
            Checker.Result checked = Checker.check(src);
            Bytecode plain = Compiler.compile(checked, false);
            Bytecode opt = Compiler.compile(checked, true);

            Pipeline.Outcome ref = Pipeline.runInterpreter(checked);
            Pipeline.Outcome a = Pipeline.runVm(plain, Long.MAX_VALUE);
            Pipeline.Outcome b = Pipeline.runVm(opt, Long.MAX_VALUE);
            if (!ref.output().equals(a.output()) || !ref.output().equals(b.output()) || !ref.ok()) {
                throw new IllegalStateException(name + ": executors disagree or the program failed");
            }

            double tree = median(repeats, () -> Pipeline.runInterpreter(checked));
            double vm = median(repeats, () -> Pipeline.runVm(plain, Long.MAX_VALUE));
            double vmo = median(repeats, () -> Pipeline.runVm(opt, Long.MAX_VALUE));
            System.out.printf("%-10s %12.1f %12.1f %12.1f %8.1fx %,16d %,16d %7.1f%%%n", name, tree, vm, vmo,
                    tree / vm, a.steps(), b.steps(), 100.0 * (a.steps() - b.steps()) / a.steps());
        }
    }

    private static double median(int repeats, Runnable r) {
        r.run();
        r.run();
        double[] ms = new double[repeats];
        for (int i = 0; i < repeats; i++) {
            long t0 = System.nanoTime();
            r.run();
            ms[i] = (System.nanoTime() - t0) / 1e6;
        }
        Arrays.sort(ms);
        return ms[repeats / 2];
    }
}
