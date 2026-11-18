package compiler;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Convenience entry points: source text in, output and error out. */
public final class Pipeline {
    private Pipeline() {}

    /** What a program printed, the runtime error it stopped with (or null), and VM steps (or -1). */
    public record Outcome(String output, String error, long steps) {
        public boolean ok() {
            return error == null;
        }
    }

    public static Outcome runVm(String source, boolean optimize, long maxSteps) throws CompileError {
        return runVm(compile(source, optimize), maxSteps);
    }

    /** Check and compile on the big stack, so long operator chains can't overflow the Java stack. */
    public static Bytecode compile(String source, boolean optimize) throws CompileError {
        return onBigStackChecked(() -> Compiler.compile(source, optimize));
    }

    public static Checker.Result check(String source) throws CompileError {
        return onBigStackChecked(() -> Checker.check(source));
    }

    public static Outcome runVm(Bytecode bc, long maxSteps) {
        return onBigStack(() -> {
            StringBuilder out = new StringBuilder();
            Vm vm = new Vm(bc, out, maxSteps);
            try {
                vm.run();
                return new Outcome(out.toString(), null, vm.steps());
            } catch (RunError e) {
                return new Outcome(out.toString(), e.getMessage(), vm.steps());
            }
        });
    }

    public static Outcome runInterpreter(String source) throws CompileError {
        return runInterpreter(check(source));
    }

    public static Outcome runInterpreter(Checker.Result checked) {
        return onBigStack(() -> {
            StringBuilder out = new StringBuilder();
            try {
                new Interpreter(checked, out).run();
                return new Outcome(out.toString(), null, -1);
            } catch (RunError e) {
                return new Outcome(out.toString(), e.getMessage(), -1);
            }
        });
    }

    /**
     * The interpreter recurses on the Java stack (several Java frames per program call), so
     * 10,000 nested calls need more than the default thread stack. Run on a 1 GB one.
     */
    static <T> T onBigStackChecked(Callable<T> task) throws CompileError {
        try {
            return onBigStack(task);
        } catch (Wrapped w) {
            throw w.error;
        }
    }

    /** Carries a CompileError out of the big-stack thread. */
    private static final class Wrapped extends RuntimeException {
        final CompileError error;

        Wrapped(CompileError error) {
            super(error);
            this.error = error;
        }
    }

    static <T> T onBigStack(Callable<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(null, () -> {
            try {
                result.set(task.call());
            } catch (CompileError e) {
                failure.set(new Wrapped(e));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "big-stack", 1L << 30);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        Throwable e = failure.get();
        if (e instanceof RuntimeException re) throw re;
        if (e instanceof Error err) throw err;
        if (e != null) throw new IllegalStateException(e);
        return result.get();
    }
}
