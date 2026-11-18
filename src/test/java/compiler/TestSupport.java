package compiler;

import static org.junit.Assert.*;

import compiler.Ast.*;

final class TestSupport {
    private TestSupport() {}

    /** Enough for every test program; a VM bug that loops forever fails instead of hanging. */
    static final long STEP_LIMIT = 100_000_000;

    /** Run on the VM (plain and optimized) and the interpreter; all three must agree. Returns the outcome. */
    static Pipeline.Outcome runAll(String src) {
        try {
            Pipeline.Outcome tree = Pipeline.runInterpreter(src);
            Pipeline.Outcome vm = Pipeline.runVm(src, false, STEP_LIMIT);
            Pipeline.Outcome opt = Pipeline.runVm(src, true, STEP_LIMIT);
            assertEquals("vm output", tree.output(), vm.output());
            assertEquals("vm error", tree.error(), vm.error());
            assertEquals("vm -O output", tree.output(), opt.output());
            assertEquals("vm -O error", tree.error(), opt.error());
            return vm;
        } catch (CompileError e) {
            throw new AssertionError("unexpected compile error: " + e.render(src), e);
        }
    }

    static String output(String src) {
        Pipeline.Outcome o = runAll(src);
        assertNull("unexpected runtime error", o.error());
        return o.output();
    }

    static CompileError compileError(String src) {
        try {
            Pipeline.check(src);
        } catch (CompileError e) {
            return e;
        }
        throw new AssertionError("expected a compile error for:\n" + src);
    }

    /** Fully parenthesized form of an expression, to check precedence and associativity. */
    static String show(Expr e) {
        return switch (e) {
            case Num n -> Long.toString(n.value());
            case Bool b -> Boolean.toString(b.value());
            case Var v -> v.name();
            case Unary u -> "(" + Token.symbol(u.op()) + show(u.operand()) + ")";
            case Binary b -> "(" + show(b.left()) + " " + Token.symbol(b.op()) + " " + show(b.right()) + ")";
            case Call c -> c.name() + c.args().stream().map(TestSupport::show).toList();
        };
    }
}
