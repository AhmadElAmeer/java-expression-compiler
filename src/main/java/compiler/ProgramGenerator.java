package compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generates random, well-typed programs that always terminate: loops count up to a small bound
 * and never touch their counter otherwise, and a function may only call functions declared
 * before it, so there's no recursion. Some programs fail at runtime on purpose (division by
 * zero, overflow from large literals), which tests the error paths too.
 */
public final class ProgramGenerator {
    private record Var(String name, boolean isInt, boolean assignable) {}

    private static final long[] BIG = {
            Long.MAX_VALUE, -Long.MAX_VALUE, 4611686018427387904L, 3037000500L, 1L << 40};

    private final SplittableRandom rnd;
    private final StringBuilder sb = new StringBuilder();
    private int indent;
    private int names;
    private int callable; // functions f0 .. f(callable-1) may be called here
    private final List<Integer> arity = new ArrayList<>();

    public ProgramGenerator(long seed) {
        // Not java.util.Random: its first nextInt(4) is almost the same for consecutive seeds,
        // which made every program have exactly two functions.
        this.rnd = new SplittableRandom(seed);
    }

    public static String generate(long seed) {
        return new ProgramGenerator(seed).program();
    }

    private String program() {
        int functions = rnd.nextInt(4);
        for (int i = 0; i < functions; i++) {
            int params = 1 + rnd.nextInt(3);
            callable = i;
            List<Var> scope = new ArrayList<>();
            StringBuilder head = new StringBuilder("fn f" + i + "(");
            for (int p = 0; p < params; p++) {
                String name = "p" + p;
                head.append(p > 0 ? ", " : "").append(name).append(": int");
                scope.add(new Var(name, true, true));
            }
            line(head + "): int {");
            indent++;
            int n = rnd.nextInt(4);
            for (int s = 0; s < n; s++) stmt(scope, 1);
            line("return " + intExpr(scope, 3) + ";");
            indent--;
            line("}");
            arity.add(params);
        }
        callable = functions;
        List<Var> scope = new ArrayList<>();
        int n = 4 + rnd.nextInt(8);
        for (int s = 0; s < n; s++) stmt(scope, 0);
        return sb.toString();
    }

    private void stmt(List<Var> scope, int loopDepth) {
        int choice = rnd.nextInt(10);
        List<Var> assignable = scope.stream().filter(Var::assignable).toList();
        if (choice <= 2 || assignable.isEmpty() && choice <= 4) {
            boolean isInt = rnd.nextInt(3) > 0;
            String name = "v" + names++;
            String init = isInt ? intExpr(scope, 3) : boolExpr(scope, 3);
            line("let " + name + (rnd.nextBoolean() ? (isInt ? ": int" : ": bool") : "") + " = " + init + ";");
            scope.add(new Var(name, isInt, true));
        } else if (choice <= 4) {
            Var v = assignable.get(rnd.nextInt(assignable.size()));
            line(v.name() + " = " + (v.isInt() ? intExpr(scope, 3) : boolExpr(scope, 3)) + ";");
        } else if (choice <= 6) {
            line("print " + (rnd.nextInt(3) > 0 ? intExpr(scope, 3) : boolExpr(scope, 3)) + ";");
        } else if (choice <= 8 || loopDepth >= 2) {
            line("if (" + boolExpr(scope, 2) + ") {");
            block(scope, loopDepth);
            if (rnd.nextBoolean()) {
                line("} else {");
                block(scope, loopDepth);
            }
            line("}");
        } else {
            String counter = "w" + names++;
            int bound = 1 + rnd.nextInt(6);
            line("let " + counter + " = 0;");
            line("while (" + counter + " < " + bound + ") {");
            List<Var> inner = new ArrayList<>(scope);
            inner.add(new Var(counter, true, false)); // readable, never assigned by the body
            indent++;
            int n = 1 + rnd.nextInt(3);
            for (int s = 0; s < n; s++) stmt(inner, loopDepth + 1);
            line(counter + " = " + counter + " + 1;");
            indent--;
            line("}");
            scope.add(new Var(counter, true, false));
        }
    }

    private void block(List<Var> scope, int loopDepth) {
        List<Var> inner = new ArrayList<>(scope);
        indent++;
        int n = 1 + rnd.nextInt(3);
        for (int s = 0; s < n; s++) stmt(inner, loopDepth);
        indent--;
    }

    private String intExpr(List<Var> scope, int depth) {
        List<Var> ints = scope.stream().filter(Var::isInt).toList();
        if (depth == 0 || rnd.nextInt(10) < 3) {
            if (!ints.isEmpty() && rnd.nextBoolean()) return ints.get(rnd.nextInt(ints.size())).name();
            if (rnd.nextInt(25) == 0) {
                long big = BIG[rnd.nextInt(BIG.length)];
                return big < 0 ? "(" + big + ")" : Long.toString(big);
            }
            int v = rnd.nextInt(41) - 20;
            return v < 0 ? "(" + v + ")" : Integer.toString(v);
        }
        int choice = rnd.nextInt(12);
        if (choice < 8) {
            String op = new String[] {"+", "-", "*", "/", "%", "+", "-", "*"}[choice];
            String right = intExpr(scope, depth - 1);
            // Most divisors are kept in 2..14 so programs usually run to the end; one in six
            // is left raw so division by zero still gets tested.
            if ((op.equals("/") || op.equals("%")) && rnd.nextInt(6) > 0) right = "(" + right + " % 7 + 8)";
            return "(" + intExpr(scope, depth - 1) + " " + op + " " + right + ")";
        }
        if (choice < 10) return "-" + intExpr(scope, depth - 1);
        if (callable > 0) {
            int f = rnd.nextInt(callable);
            StringBuilder call = new StringBuilder("f" + f + "(");
            for (int a = 0; a < arity.get(f); a++) call.append(a > 0 ? ", " : "").append(intExpr(scope, depth - 1));
            return call.append(")").toString();
        }
        return intExpr(scope, depth - 1);
    }

    private String boolExpr(List<Var> scope, int depth) {
        List<Var> bools = scope.stream().filter(v -> !v.isInt()).toList();
        if (depth == 0 || rnd.nextInt(10) < 2) {
            if (!bools.isEmpty() && rnd.nextBoolean()) return bools.get(rnd.nextInt(bools.size())).name();
            return rnd.nextBoolean() ? "true" : "false";
        }
        return switch (rnd.nextInt(6)) {
            case 0, 1 -> "(" + intExpr(scope, depth - 1) + " "
                    + new String[] {"<", "<=", ">", ">=", "==", "!="}[rnd.nextInt(6)] + " " + intExpr(scope, depth - 1) + ")";
            case 2 -> "(" + boolExpr(scope, depth - 1) + " && " + boolExpr(scope, depth - 1) + ")";
            case 3 -> "(" + boolExpr(scope, depth - 1) + " || " + boolExpr(scope, depth - 1) + ")";
            case 4 -> "!" + boolExpr(scope, depth - 1);
            default -> "(" + boolExpr(scope, depth - 1) + (rnd.nextBoolean() ? " == " : " != ")
                    + boolExpr(scope, depth - 1) + ")";
        };
    }

    private void line(String s) {
        sb.append("    ".repeat(indent)).append(s).append('\n');
    }
}
