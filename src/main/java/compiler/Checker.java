package compiler;

import static compiler.Token.Kind.*;

import compiler.Ast.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static checks: every name is declared, every operator gets operands of the right type, calls
 * match their function's signature, and functions that return a value do so on every path.
 *
 * <p>It also resolves each variable to a numbered slot in its function's frame. The results are
 * kept in side tables keyed by node identity, so the tree itself stays immutable.
 */
public final class Checker {

    /** Signature and frame size of one function. The main program is the last entry. */
    public record FnInfo(int index, String name, List<Type> params, Type returnType, Function decl, int locals) {}

    /** Everything later stages need. */
    public static final class Result {
        public final Program program;
        public final List<FnInfo> functions;
        public final int mainIndex;
        final Map<String, FnInfo> byName;
        final IdentityHashMap<Expr, Type> types;
        final IdentityHashMap<Object, Integer> slots;

        Result(Program program, List<FnInfo> functions, Map<String, FnInfo> byName,
               IdentityHashMap<Expr, Type> types, IdentityHashMap<Object, Integer> slots) {
            this.program = program;
            this.functions = functions;
            this.mainIndex = functions.size() - 1;
            this.byName = byName;
            this.types = types;
            this.slots = slots;
        }

        public FnInfo function(String name) {
            return byName.get(name);
        }

        /** Type of an expression; literals created by the optimizer are typed by their class. */
        public Type typeOf(Expr e) {
            if (e instanceof Num) return Type.INT;
            if (e instanceof Bool) return Type.BOOL;
            Type t = types.get(e);
            if (t == null) throw new IllegalStateException("untyped expression " + e);
            return t;
        }

        /** Frame slot of a Let, Assign, Var or Param node. */
        public int slot(Object node) {
            Integer s = slots.get(node);
            if (s == null) throw new IllegalStateException("unresolved node " + node);
            return s;
        }

        /** Record the type of a node the optimizer built from {@code original}. */
        <T extends Expr> T retype(T node, Expr original) {
            types.put(node, typeOf(original));
            return node;
        }
    }

    private record Local(int slot, Type type) {}

    private final Program program;
    private final Map<String, FnInfo> byName = new HashMap<>();
    private final IdentityHashMap<Expr, Type> types = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Integer> slots = new IdentityHashMap<>();
    private final Deque<Map<String, Local>> scopes = new ArrayDeque<>();
    private int nextSlot;
    private FnInfo current; // null while checking the main program

    private Checker(Program program) {
        this.program = program;
    }

    public static Result check(Program program) throws CompileError {
        return new Checker(program).run();
    }

    public static Result check(String source) throws CompileError {
        return check(Parser.parse(source));
    }

    private Result run() throws CompileError {
        // Pass 1: signatures, so functions can call each other in any order.
        List<FnInfo> functions = new ArrayList<>();
        for (Function f : program.functions()) {
            if (byName.containsKey(f.name())) {
                throw new CompileError(f.pos(), "function '" + f.name() + "' is already declared");
            }
            List<Type> params = f.params().stream().map(Param::type).toList();
            FnInfo info = new FnInfo(functions.size(), f.name(), params, f.returnType(), f, 0);
            byName.put(f.name(), info);
            functions.add(info);
        }

        // Pass 2: bodies.
        for (int i = 0; i < functions.size(); i++) {
            FnInfo info = functions.get(i);
            Function f = info.decl();
            current = info;
            nextSlot = 0;
            scopes.push(new HashMap<>());
            for (Param p : f.params()) declare(p.name(), p.type(), p.pos(), p);
            // The body shares the parameters' scope, so `let x` can't silently shadow parameter x.
            for (Stmt s : f.body().stmts()) stmt(s);
            scopes.pop();
            if (f.returnType() != Type.VOID && !alwaysReturns(f.body())) {
                throw new CompileError(f.pos(), "function '" + f.name()
                        + "' can reach its end without returning " + article(f.returnType()));
            }
            FnInfo done = new FnInfo(i, f.name(), info.params(), f.returnType(), f, nextSlot);
            functions.set(i, done);
            byName.put(f.name(), done);
        }

        current = null;
        nextSlot = 0;
        scopes.push(new HashMap<>());
        for (Stmt s : program.main()) stmt(s);
        scopes.pop();
        functions.add(new FnInfo(functions.size(), "<main>", List.of(), Type.VOID, null, nextSlot));
        return new Result(program, functions, byName, types, slots);
    }

    private void stmt(Stmt s) throws CompileError {
        switch (s) {
            case Let l -> {
                Type t = value(l.init());
                if (l.declared() != null && l.declared() != t) {
                    throw new CompileError(l.init().pos(), "'" + l.name() + "' is declared " + l.declared()
                            + " but its initial value is " + t);
                }
                declare(l.name(), t, l.pos(), l);
            }
            case Assign a -> {
                Local local = lookup(a.name());
                if (local == null) throw new CompileError(a.pos(), "undefined variable '" + a.name() + "'");
                Type t = value(a.value());
                if (t != local.type()) {
                    throw new CompileError(a.value().pos(), "can't assign " + t + " to '" + a.name()
                            + "', which is " + local.type());
                }
                slots.put(a, local.slot());
            }
            case Print pr -> value(pr.value());
            case If i -> {
                condition(i.cond(), "if");
                scoped(i.then());
                if (i.otherwise() != null) scoped(i.otherwise());
            }
            case While w -> {
                condition(w.cond(), "while");
                scoped(w.body());
            }
            case Block b -> {
                scopes.push(new HashMap<>());
                for (Stmt inner : b.stmts()) stmt(inner);
                scopes.pop();
            }
            case Return r -> {
                if (current == null) throw new CompileError(r.pos(), "'return' outside a function");
                Type want = current.returnType();
                if (r.value() == null) {
                    if (want != Type.VOID) {
                        throw new CompileError(r.pos(), "'" + current.name() + "' must return " + article(want));
                    }
                } else {
                    if (want == Type.VOID) {
                        throw new CompileError(r.value().pos(), "'" + current.name()
                                + "' has no return type, so it can't return a value");
                    }
                    Type got = value(r.value());
                    if (got != want) {
                        throw new CompileError(r.value().pos(), "'" + current.name() + "' must return "
                                + want + ", not " + got);
                    }
                }
            }
            case ExprStmt e -> expr(e.expr());
        }
    }

    /** A branch or loop body gets its own scope even when it isn't a block. */
    private void scoped(Stmt s) throws CompileError {
        scopes.push(new HashMap<>());
        stmt(s);
        scopes.pop();
    }

    private void condition(Expr cond, String keyword) throws CompileError {
        Type t = value(cond);
        if (t != Type.BOOL) {
            throw new CompileError(cond.pos(), "'" + keyword + "' condition must be bool, not " + t);
        }
    }

    /** Check an expression that must produce a value (not a call to a function returning nothing). */
    private Type value(Expr e) throws CompileError {
        Type t = expr(e);
        if (t == Type.VOID) {
            String name = e instanceof Call c ? c.name() : "this expression";
            throw new CompileError(e.pos(), "'" + name + "' returns nothing, so it has no value to use");
        }
        return t;
    }

    private Type expr(Expr e) throws CompileError {
        Type t = switch (e) {
            case Num n -> Type.INT;
            case Bool b -> Type.BOOL;
            case Var v -> {
                Local local = lookup(v.name());
                if (local == null) {
                    String hint = byName.containsKey(v.name()) ? " ('" + v.name() + "' is a function; call it with ())" : "";
                    throw new CompileError(v.pos(), "undefined variable '" + v.name() + "'" + hint);
                }
                slots.put(v, local.slot());
                yield local.type();
            }
            case Unary u -> {
                Type operand = value(u.operand());
                Type need = u.op() == MINUS ? Type.INT : Type.BOOL;
                if (operand != need) {
                    throw new CompileError(u.pos(), "'" + Token.symbol(u.op()) + "' needs " + article(need)
                            + ", not " + article(operand));
                }
                yield need;
            }
            case Binary b -> binary(b);
            case Call c -> {
                FnInfo f = byName.get(c.name());
                if (f == null) throw new CompileError(c.pos(), "undefined function '" + c.name() + "'");
                if (c.args().size() != f.params().size()) {
                    throw new CompileError(c.pos(), "'" + c.name() + "' takes " + f.params().size()
                            + " argument" + (f.params().size() == 1 ? "" : "s") + ", but " + c.args().size()
                            + (c.args().size() == 1 ? " was" : " were") + " given");
                }
                for (int i = 0; i < c.args().size(); i++) {
                    Type got = value(c.args().get(i));
                    if (got != f.params().get(i)) {
                        throw new CompileError(c.args().get(i).pos(), "argument " + (i + 1) + " of '" + c.name()
                                + "' must be " + f.params().get(i) + ", not " + got);
                    }
                }
                yield f.returnType();
            }
        };
        types.put(e, t);
        return t;
    }

    private Type binary(Binary b) throws CompileError {
        Type l = value(b.left());
        Type r = value(b.right());
        String op = "'" + Token.symbol(b.op()) + "'";
        switch (b.op()) {
            case PLUS, MINUS, STAR, SLASH, PERCENT, LT, LE, GT, GE -> {
                if (l != Type.INT || r != Type.INT) {
                    throw new CompileError(b.pos(), op + " needs int operands, not " + l + " and " + r);
                }
                return switch (b.op()) {
                    case LT, LE, GT, GE -> Type.BOOL;
                    default -> Type.INT;
                };
            }
            case EQ, NE -> {
                if (l != r) throw new CompileError(b.pos(), "can't compare " + l + " with " + r);
                return Type.BOOL;
            }
            case AND, OR -> {
                if (l != Type.BOOL || r != Type.BOOL) {
                    throw new CompileError(b.pos(), op + " needs bool operands, not " + l + " and " + r);
                }
                return Type.BOOL;
            }
            default -> throw new IllegalStateException("unexpected operator " + b.op());
        }
    }

    private void declare(String name, Type type, Pos pos, Object node) throws CompileError {
        Map<String, Local> scope = scopes.peek();
        if (scope.containsKey(name)) {
            throw new CompileError(pos, "'" + name + "' is already declared in this scope");
        }
        Local local = new Local(nextSlot++, type);
        scope.put(name, local);
        slots.put(node, local.slot());
    }

    private Local lookup(String name) {
        for (Map<String, Local> scope : scopes) { // innermost first
            Local l = scope.get(name);
            if (l != null) return l;
        }
        return null;
    }

    /**
     * Conservative: a block returns if any statement in it returns; an if returns only if both
     * branches do. Loops never count, even {@code while (true)}.
     */
    static boolean alwaysReturns(Stmt s) {
        return switch (s) {
            case Return r -> true;
            case Block b -> b.stmts().stream().anyMatch(Checker::alwaysReturns);
            case If i -> i.otherwise() != null && alwaysReturns(i.then()) && alwaysReturns(i.otherwise());
            default -> false;
        };
    }

    private static String article(Type t) {
        return t == Type.INT ? "an int" : t == Type.BOOL ? "a bool" : "nothing";
    }
}
