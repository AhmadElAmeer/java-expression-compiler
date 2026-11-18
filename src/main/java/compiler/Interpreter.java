package compiler;

import static compiler.Token.Kind.*;

import compiler.Ast.*;
import compiler.Checker.FnInfo;

/**
 * Executes the checked syntax tree directly. It's slower than the VM, and it exists as an
 * independent reference: the tests and the fuzzer require both to print the same output and
 * fail with the same error on the same line.
 */
public final class Interpreter {
    private final Checker.Result checked;
    private final StringBuilder out;
    private int depth;

    /** Thrown to unwind a function body on {@code return}. No stack trace, so it's cheap. */
    private static final class ReturnSignal extends RuntimeException {
        final long value;

        ReturnSignal(long value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    public Interpreter(Checker.Result checked, StringBuilder out) {
        this.checked = checked;
        this.out = out;
    }

    public void run() {
        long[] frame = new long[checked.functions.get(checked.mainIndex).locals()];
        for (Stmt s : checked.program.main()) exec(s, frame);
    }

    private void exec(Stmt s, long[] f) {
        switch (s) {
            case Let l -> f[checked.slot(l)] = eval(l.init(), f);
            case Assign a -> f[checked.slot(a)] = eval(a.value(), f);
            case Print p -> {
                long v = eval(p.value(), f);
                if (checked.typeOf(p.value()) == Type.BOOL) out.append(v != 0 ? "true" : "false");
                else out.append(v);
                out.append('\n');
            }
            case If i -> {
                if (eval(i.cond(), f) != 0) exec(i.then(), f);
                else if (i.otherwise() != null) exec(i.otherwise(), f);
            }
            case While w -> {
                while (eval(w.cond(), f) != 0) exec(w.body(), f);
            }
            case Block b -> {
                for (Stmt inner : b.stmts()) exec(inner, f);
            }
            case Return r -> throw new ReturnSignal(r.value() == null ? 0 : eval(r.value(), f));
            case ExprStmt e -> eval(e.expr(), f);
        }
    }

    private long eval(Expr e, long[] f) {
        try {
            return switch (e) {
                case Num n -> n.value();
                case Bool b -> b.value() ? 1 : 0;
                case Var v -> f[checked.slot(v)];
                case Unary u -> u.op() == MINUS ? Math.negateExact(eval(u.operand(), f)) : eval(u.operand(), f) ^ 1;
                case Binary b when b.op() == AND -> eval(b.left(), f) != 0 ? eval(b.right(), f) : 0;
                case Binary b when b.op() == OR -> eval(b.left(), f) != 0 ? 1 : eval(b.right(), f);
                case Binary b -> {
                    long l = eval(b.left(), f);
                    long r = eval(b.right(), f);
                    yield Arith.apply(b.op(), l, r);
                }
                case Call c -> call(c, f);
            };
        } catch (ArithmeticException ex) {
            // Only the operator that failed reaches here: errors from operands are already RunErrors.
            throw new RunError(Arith.message(ex), e.pos().line());
        }
    }

    private long call(Call c, long[] caller) {
        FnInfo fn = checked.function(c.name());
        long[] frame = new long[fn.locals()];
        for (int i = 0; i < c.args().size(); i++) frame[i] = eval(c.args().get(i), caller);
        if (depth == Vm.MAX_DEPTH) {
            throw new RunError("stack overflow (more than " + Vm.MAX_DEPTH + " nested calls)", c.pos().line());
        }
        depth++;
        try {
            for (Stmt s : fn.decl().body().stmts()) exec(s, frame);
            return 0; // a function with no return type fell off its end
        } catch (ReturnSignal r) {
            return r.value;
        } finally {
            depth--;
        }
    }
}
