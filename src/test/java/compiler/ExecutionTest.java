package compiler;

import static compiler.TestSupport.*;
import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** End-to-end behaviour. Every program runs on the interpreter, the VM and the optimized VM. */
public class ExecutionTest {

    @Test
    public void examplesProduceKnownAnswers() throws Exception {
        // Answers checked independently in Python.
        assertEquals("196418\n", output(example("fib")));
        assertEquals("6057\n", output(example("primes")));
        assertEquals("26623\n307\n", output(example("collatz")));
        assertEquals("331884\n", output(example("gcd")));
        assertEquals("-2800750\n", output(example("constants")));
    }

    @Test
    public void integerSemanticsMatchJava() {
        assertEquals("-3\n-1\n3\n1\n-3\n", output("print -7 / 2;\nprint -7 % 2;\nprint 7 / 2;\nprint 7 % -2;\nprint 7 / -2;"));
        assertEquals("-9223372036854775808\n0\n",
                output("let m = -9223372036854775807 - 1;\nprint m;\nprint m % -1;"));
    }

    @Test
    public void runtimeErrorsNameTheLine() {
        assertError("print 1;\nprint 10 / (5 - 5);\nprint 2;", "1\n", "line 2: division by zero");
        assertError("let z = 0;\nprint 1 % z;", "", "line 2: division by zero");
        assertError("print 9223372036854775807 + 1;", "", "line 1: integer overflow");
        assertError("let m = -9223372036854775807 - 1;\nprint -m;", "", "line 2: integer overflow");
        assertError("let m = -9223372036854775807 - 1;\nprint m / -1;", "", "line 2: integer overflow");
        assertError("let big = 3037000500;\nprint big * big;", "", "line 2: integer overflow");
        // A multi-line expression: the error names the line of the operator that failed.
        assertError("let z = 0;\nlet r = 1 +\n    10 / z;", "", "line 3: division by zero");
        // The error is reported where the operator is, even inside a function.
        assertError("fn avg(t: int, n: int): int {\n  return t / n;\n}\nprint avg(4, 2);\nprint avg(4, 0);",
                "2\n", "line 2: division by zero");
    }

    @Test
    public void shortCircuitSkipsTheRightSide() {
        String src = """
                fn loud(x: bool): bool { print 99; return x; }
                print false && loud(true);
                print true || loud(true);
                print true && loud(false);
                print false || loud(true);
                print 1 == 0 && 1 / 0 == 1;
                """;
        assertEquals("false\ntrue\n99\nfalse\n99\ntrue\nfalse\n", output(src));
    }

    @Test
    public void argumentsAndOperandsEvaluateLeftToRight() {
        String src = """
                fn say(x: int): int { print x; return x; }
                fn three(a: int, b: int, c: int): int { return a * 100 + b * 10 + c; }
                print say(1) - say(2);
                print three(say(4), say(5), say(6));
                """;
        assertEquals("1\n2\n-1\n4\n5\n6\n456\n", output(src));
    }

    @Test
    public void recursionAndMutualRecursion() {
        String src = """
                fn even(n: int): bool { if (n == 0) { return true; } return odd(n - 1); }
                fn odd(n: int): bool { if (n == 0) { return false; } return even(n - 1); }
                fn fact(n: int): int { if (n <= 1) { return 1; } return n * fact(n - 1); }
                print even(10);
                print odd(7);
                print fact(20);
                """;
        assertEquals("true\ntrue\n2432902008176640000\n", output(src));
        assertError("fn fact(n: int): int { if (n <= 1) { return 1; } return n * fact(n - 1); }\nprint fact(21);",
                "", "line 1: integer overflow");
    }

    @Test
    public void parametersAreCopiesAndLocalsAreFresh() {
        String src = """
                fn bump(x: int): int { x = x + 1; return x; }
                fn count(n: int): int { let c = 0; while (c < n) { c = c + 1; } return c; }
                let a = 5;
                print bump(a);
                print a;
                print count(3) + count(2);
                """;
        assertEquals("6\n5\n5\n", output(src));
    }

    @Test
    public void voidFunctionsAndExpressionStatements() {
        String src = """
                fn show(x: int) { if (x < 0) { print 0; return; } print x; }
                fn sq(x: int): int { return x * x; }
                show(-4);
                show(4);
                sq(3);
                """;
        assertEquals("0\n4\n", output(src));
    }

    @Test
    public void infiniteLoopsCanEndWithReturn() {
        String src = """
                fn firstSquareAbove(n: int): int {
                    let i = 0;
                    while (true) {
                        if (i * i > n) { return i; }
                        i = i + 1;
                    }
                    return -1;
                }
                print firstSquareAbove(50);
                """;
        assertEquals("8\n", output(src));
    }

    @Test
    public void callDepthLimitIsExactlyTenThousand() {
        String down = "fn down(n: int): int {\n  if (n == 0) { return 0; }\n  return 1 + down(n - 1);\n}\n";
        assertEquals("9999\n", output(down + "print down(9999);"));     // 10,000 nested calls
        assertError(down + "print down(10000);", "", "line 3: stack overflow (more than 10000 nested calls)");
    }

    @Test
    public void stepLimitStopsInfiniteLoops() throws CompileError {
        Pipeline.Outcome o = Pipeline.runVm("let i = 0;\nwhile (true) {\n  i = i + 1;\n}", false, 10_000);
        assertEquals("line 3: step limit exceeded (10000 instructions)", o.error());
        assertEquals(10_001, o.steps()); // counts the instruction that was refused
    }

    @Test
    public void stackGrowsBeyondItsInitialSize() {
        // 300 arguments are 300 values on the operand stack at once, beyond the initial 256 slots,
        // in the main frame and again inside a function that makes the same call.
        String params = java.util.stream.IntStream.range(0, 300).mapToObj(i -> "a" + i + ": int")
                .collect(java.util.stream.Collectors.joining(", "));
        String args = String.join(", ", java.util.Collections.nCopies(300, "1"));
        String sum = java.util.stream.IntStream.range(0, 300).mapToObj(i -> "a" + i)
                .collect(java.util.stream.Collectors.joining(" + "));
        String src = "fn total(" + params + "): int { return " + sum + "; }\n"
                + "fn again(x: int): int { return x + total(" + args + "); }\n"
                + "print total(" + args + ");\nprint again(1);";
        assertEquals("300\n301\n", output(src));
    }

    @Test
    public void longOperatorChainsCompile() {
        // 100,000 terms: a left-nested tree 100,000 levels deep, handled on the big-stack thread.
        assertEquals("100000\n", output("print " + String.join(" + ", java.util.Collections.nCopies(100_000, "1")) + ";"));
    }

    private static void assertError(String src, String outputBefore, String error) {
        Pipeline.Outcome o = runAll(src);
        assertEquals(outputBefore, o.output());
        assertEquals(error, o.error());
    }

    static String example(String name) throws Exception {
        return Files.readString(Path.of("examples", name + ".mini"));
    }
}
