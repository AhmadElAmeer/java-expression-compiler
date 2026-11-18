package compiler;

import static compiler.Token.Kind.*;

import compiler.Ast.*;
import compiler.Checker.FnInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates stack-VM bytecode from a checked program. Jumps are emitted with a placeholder
 * target and patched once the target is known (backpatching).
 *
 * <p>With {@code optimize}, expressions are constant-folded first, and branches and loops whose
 * condition folds to a constant lose their dead code. Folding never hides a runtime error:
 * {@code 1 / 0} or an overflowing constant is left for the VM to report.
 */
public final class Compiler {
    private final Checker.Result checked;
    private final boolean optimize;
    private final List<Integer> code = new ArrayList<>();
    private final List<Integer> lines = new ArrayList<>();
    private final List<Long> constants = new ArrayList<>();
    private final Map<Long, Integer> constantIndex = new HashMap<>();
    private int depth, maxDepth; // operand stack height within the current function

    private Compiler(Checker.Result checked, boolean optimize) {
        this.checked = checked;
        this.optimize = optimize;
    }

    public static Bytecode compile(Checker.Result checked, boolean optimize) {
        return new Compiler(checked, optimize).run();
    }

    public static Bytecode compile(String source, boolean optimize) throws CompileError {
        return compile(Checker.check(source), optimize);
    }

    private Bytecode run() {
        List<FnInfo> fns = checked.functions;
        Bytecode.FnEntry[] entries = new Bytecode.FnEntry[fns.size()];

        // Main first, so execution starts at pc 0 and the listing reads top-down.
        FnInfo main = fns.get(checked.mainIndex);
        int entry = beginFunction();
        for (Stmt s : checked.program.main()) stmt(s);
        emit(Op.HALT, lastLine());
        entries[main.index()] = new Bytecode.FnEntry(main.name(), entry, 0, main.locals(), maxDepth, false);

        for (FnInfo f : fns) {
            if (f.decl() == null) continue;
            entry = beginFunction();
            for (Stmt s : f.decl().body().stmts()) stmt(s);
            if (f.returnType() == Type.VOID) emit(Op.RET_VOID, code.size() == entry ? f.decl().pos().line() : lastLine());
            entries[f.index()] = new Bytecode.FnEntry(f.name(), entry, f.params().size(), f.locals(),
                    maxDepth, f.returnType() != Type.VOID);
        }

        int[] codeArr = code.stream().mapToInt(Integer::intValue).toArray();
        int[] lineArr = lines.stream().mapToInt(Integer::intValue).toArray();
        long[] constArr = constants.stream().mapToLong(Long::longValue).toArray();
        return new Bytecode(codeArr, lineArr, constArr, entries, checked.mainIndex);
    }

    private int beginFunction() {
        depth = 0;
        maxDepth = 0;
        return code.size();
    }

    // ---- statements ----

    private void stmt(Stmt s) {
        switch (s) {
            case Let l -> {
                expr(l.init());
                emit(Op.STORE, l.pos().line(), checked.slot(l));
            }
            case Assign a -> {
                expr(a.value());
                emit(Op.STORE, a.pos().line(), checked.slot(a));
            }
            case Print p -> {
                Expr e = expr(p.value());
                emit(checked.typeOf(e) == Type.BOOL ? Op.PRINT_BOOL : Op.PRINT_INT, p.pos().line());
            }
            case If i -> {
                Expr cond = optimize ? fold(i.cond()) : i.cond();
                if (optimize && cond instanceof Bool b) { // only the branch that can run
                    Stmt taken = b.value() ? i.then() : i.otherwise();
                    if (taken != null) stmt(taken);
                    return;
                }
                gen(cond);
                int toElse = emitJump(Op.JUMP_IF_FALSE, i.pos().line());
                stmt(i.then());
                if (i.otherwise() == null) {
                    patch(toElse);
                } else {
                    int toEnd = emitJump(Op.JUMP, i.pos().line());
                    patch(toElse);
                    stmt(i.otherwise());
                    patch(toEnd);
                }
            }
            case While w -> {
                Expr cond = optimize ? fold(w.cond()) : w.cond();
                int start = code.size();
                if (optimize && cond instanceof Bool b) {
                    if (!b.value()) return;           // while (false): the body is dead
                    stmt(w.body());                   // while (true): no test needed
                    emit(Op.JUMP, w.pos().line(), start);
                    return;
                }
                gen(cond);
                int toEnd = emitJump(Op.JUMP_IF_FALSE, w.pos().line());
                stmt(w.body());
                emit(Op.JUMP, w.pos().line(), start);
                patch(toEnd);
            }
            case Block b -> {
                for (Stmt inner : b.stmts()) stmt(inner);
            }
            case Return r -> {
                if (r.value() == null) {
                    emit(Op.RET_VOID, r.pos().line());
                } else {
                    expr(r.value());
                    emit(Op.RET, r.pos().line());
                }
            }
            case ExprStmt e -> {
                Expr x = expr(e.expr());
                if (checked.typeOf(x) != Type.VOID) emit(Op.POP, e.pos().line());
            }
        }
    }

    // ---- expressions ----

    /** Fold (when optimizing) and generate; returns the expression actually compiled. */
    private Expr expr(Expr e) {
        Expr x = optimize ? fold(e) : e;
        gen(x);
        return x;
    }

    private void gen(Expr e) {
        int line = e.pos().line();
        switch (e) {
            case Num n -> emit(Op.CONST, line, constant(n.value()));
            case Bool b -> emit(Op.CONST, line, constant(b.value() ? 1 : 0));
            case Var v -> emit(Op.LOAD, line, checked.slot(v));
            case Unary u -> {
                gen(u.operand());
                emit(u.op() == MINUS ? Op.NEG : Op.NOT, line);
            }
            case Binary b when b.op() == AND -> {
                // left && right:  left; JUMP_IF_FALSE f; right; JUMP end; f: CONST 0; end:
                gen(b.left());
                int toFalse = emitJump(Op.JUMP_IF_FALSE, line);
                int base = depth;
                gen(b.right());
                int toEnd = emitJump(Op.JUMP, line);
                patch(toFalse);
                depth = base;
                emit(Op.CONST, line, constant(0));
                patch(toEnd);
            }
            case Binary b when b.op() == OR -> {
                // left || right:  left; JUMP_IF_FALSE r; CONST 1; JUMP end; r: right; end:
                gen(b.left());
                int toRight = emitJump(Op.JUMP_IF_FALSE, line);
                int base = depth;
                emit(Op.CONST, line, constant(1));
                int toEnd = emitJump(Op.JUMP, line);
                patch(toRight);
                depth = base;
                gen(b.right());
                patch(toEnd);
            }
            case Binary b -> {
                gen(b.left());
                gen(b.right());
                emit(binaryOp(b.op()), line);
            }
            case Call c -> {
                for (Expr a : c.args()) gen(a);
                FnInfo f = checked.function(c.name());
                emit(Op.CALL, line, f.index());
                depth += -f.params().size() + (f.returnType() == Type.VOID ? 0 : 1);
                maxDepth = Math.max(maxDepth, depth);
            }
        }
    }

    private static int binaryOp(Token.Kind k) {
        return switch (k) {
            case PLUS -> Op.ADD; case MINUS -> Op.SUB; case STAR -> Op.MUL; case SLASH -> Op.DIV;
            case PERCENT -> Op.MOD; case EQ -> Op.EQ; case NE -> Op.NE; case LT -> Op.LT;
            case LE -> Op.LE; case GT -> Op.GT; case GE -> Op.GE;
            default -> throw new IllegalArgumentException(k.toString());
        };
    }

    // ---- constant folding ----

    Expr fold(Expr e) {
        return switch (e) {
            case Unary u -> {
                Expr x = fold(u.operand());
                if (u.op() == MINUS && x instanceof Num n && n.value() != Long.MIN_VALUE) {
                    yield new Num(-n.value(), u.pos());
                }
                if (u.op() == BANG && x instanceof Bool b) yield new Bool(!b.value(), u.pos());
                yield x == u.operand() ? u : checked.retype(new Unary(u.op(), x, u.pos()), u);
            }
            case Binary b -> {
                Expr l = fold(b.left());
                // Short-circuit with a constant left side; the right side is either dropped
                // (it would never run) or becomes the whole result.
                if (b.op() == AND && l instanceof Bool lb) yield lb.value() ? fold(b.right()) : l;
                if (b.op() == OR && l instanceof Bool lb) yield lb.value() ? l : fold(b.right());
                Expr r = fold(b.right());
                if (l instanceof Num x && r instanceof Num y) {
                    try {
                        long v = Arith.apply(b.op(), x.value(), y.value());
                        yield switch (b.op()) {
                            case LT, LE, GT, GE, EQ, NE -> new Bool(v != 0, b.pos());
                            default -> new Num(v, b.pos());
                        };
                    } catch (ArithmeticException ex) {
                        // Leave it: the program must still fail at runtime, on this line.
                    }
                }
                if (l instanceof Bool x && r instanceof Bool y && (b.op() == EQ || b.op() == NE)) {
                    yield new Bool((x.value() == y.value()) == (b.op() == EQ), b.pos());
                }
                yield l == b.left() && r == b.right() ? b : checked.retype(new Binary(b.op(), l, r, b.pos()), b);
            }
            case Call c -> {
                List<Expr> args = new ArrayList<>();
                boolean changed = false;
                for (Expr a : c.args()) {
                    Expr f = fold(a);
                    changed |= f != a;
                    args.add(f);
                }
                yield changed ? checked.retype(new Call(c.name(), args, c.pos()), c) : c;
            }
            default -> e;
        };
    }

    // ---- emission ----

    private int constant(long value) {
        return constantIndex.computeIfAbsent(value, v -> {
            constants.add(v);
            return constants.size() - 1;
        });
    }

    private void emit(int op, int line, int... operands) {
        code.add(op);
        lines.add(line);
        for (int x : operands) {
            code.add(x);
            lines.add(line);
        }
        depth += Op.stackEffect(op);
        maxDepth = Math.max(maxDepth, depth);
    }

    /** Emit a jump with a placeholder target; returns the operand's index for {@link #patch}. */
    private int emitJump(int op, int line) {
        emit(op, line, -1);
        return code.size() - 1;
    }

    private void patch(int operandIndex) {
        code.set(operandIndex, code.size());
    }

    private int lastLine() {
        return lines.isEmpty() ? 1 : lines.get(lines.size() - 1);
    }
}
