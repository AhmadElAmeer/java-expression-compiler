package compiler;

/** Differential testing: random programs must behave identically in all three executors. */
public final class Fuzz {
    private Fuzz() {}

    public record Report(int programs, int completed, int failedAtRuntime, int mismatches, String firstMismatch) {}

    public static Report run(int count, long seed) {
        int completed = 0, failed = 0, mismatches = 0;
        String first = null;
        for (int i = 0; i < count; i++) {
            String src = ProgramGenerator.generate(seed + i);
            Checker.Result checked;
            try {
                checked = Checker.check(src);
            } catch (CompileError e) {
                throw new IllegalStateException("generator produced an invalid program (seed " + (seed + i) + "): "
                        + e.render(src) + "\n" + src);
            }
            Pipeline.Outcome tree = Pipeline.runInterpreter(checked);
            Pipeline.Outcome vm = Pipeline.runVm(Compiler.compile(checked, false), Long.MAX_VALUE);
            Pipeline.Outcome opt = Pipeline.runVm(Compiler.compile(checked, true), Long.MAX_VALUE);
            boolean same = same(tree, vm) && same(tree, opt);
            if (!same) {
                mismatches++;
                if (first == null) {
                    first = "seed " + (seed + i) + "\n" + src + "\ninterpreter: " + tree + "\nvm: " + vm + "\nvm -O: " + opt;
                }
            } else if (tree.ok()) {
                completed++;
            } else {
                failed++;
            }
        }
        return new Report(count, completed, failed, mismatches, first);
    }

    private static boolean same(Pipeline.Outcome a, Pipeline.Outcome b) {
        return a.output().equals(b.output()) && java.util.Objects.equals(a.error(), b.error());
    }
}
