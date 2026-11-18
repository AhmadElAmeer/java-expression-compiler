package compiler;

import static compiler.TestSupport.*;
import static org.junit.Assert.*;

import org.junit.Test;

/** Bytecode shape, constant folding and the differential fuzzer. */
public class CompilerTest {

    @Test
    public void fibCompilesToTheExpectedBytecode() throws CompileError {
        String dis = Compiler.compile("fn fib(n: int): int {\n  if (n < 2) { return n; }\n  return fib(n - 1) + fib(n - 2);\n}\nprint fib(5);", false)
                .disassemble();
        String expected = """
                fn <main>  params=0 locals=0 max_stack=1
                     0  CONST          5          ; line 5
                     2  CALL           fib
                     4  PRINT_INT
                     5  HALT

                fn fib  params=1 locals=1 max_stack=3
                     6  LOAD           0          ; line 2
                     8  CONST          2
                    10  LT
                    11  JUMP_IF_FALSE  -> 16
                    13  LOAD           0
                    15  RET
                    16  LOAD           0          ; line 3
                    18  CONST          1
                    20  SUB
                    21  CALL           fib
                    23  LOAD           0
                    25  CONST          2
                    27  SUB
                    28  CALL           fib
                    30  ADD
                    31  RET
                """;
        assertEquals(expected, dis);
    }

    @Test
    public void foldingComputesConstantsAtCompileTime() throws CompileError {
        Bytecode plain = Compiler.compile("print 60 * 60 * 24;", false);
        Bytecode opt = Compiler.compile("print 60 * 60 * 24;", true);
        assertEquals(7, plain.instructionCount());  // CONST CONST MUL CONST MUL PRINT HALT
        assertEquals(3, opt.instructionCount());    // CONST 86400, PRINT, HALT
        assertTrue(opt.disassemble().contains("CONST          86400"));
        assertEquals("86400\n", output("print 60 * 60 * 24;"));
    }

    @Test
    public void foldingRemovesDeadBranchesAndLoops() throws CompileError {
        String src = "if (1 > 2) { print 1; } else { print 2; }\nwhile (false && true) { print 3; }\nif (!true) { print 4; }";
        Bytecode opt = Compiler.compile(src, true);
        assertEquals(3, opt.instructionCount()); // CONST 2, PRINT, HALT
        assertEquals("2\n", output(src));
    }

    @Test
    public void foldingKeepsRuntimeErrors() throws CompileError {
        for (String src : new String[] {"print 1;\nprint 1 / 0;", "print 1;\nprint 9223372036854775807 * 2;",
                "print 1;\nprint -(-9223372036854775807 - 1);"}) {
            Pipeline.Outcome o = Pipeline.runVm(src, true, Long.MAX_VALUE);
            assertEquals("1\n", o.output());
            assertNotNull(src, o.error());
            assertTrue(o.error(), o.error().startsWith("line 2:"));
        }
    }

    @Test
    public void foldingNeverDropsCallsThatWouldRun() {
        String src = "fn hi(): bool { print 7; return true; }\nprint true && hi();\nprint false || hi();\nprint hi() && true;";
        assertEquals("7\ntrue\n7\ntrue\n7\ntrue\n", output(src));
    }

    @Test
    public void foldingReducesWorkOnTheConstantsExample() throws Exception {
        String src = ExecutionTest.example("constants");
        Pipeline.Outcome plain = Pipeline.runVm(src, false, Long.MAX_VALUE);
        Pipeline.Outcome opt = Pipeline.runVm(src, true, Long.MAX_VALUE);
        assertEquals(plain.output(), opt.output());
        assertTrue(opt.steps() + " vs " + plain.steps(), opt.steps() < plain.steps() * 0.75);
    }

    @Test
    public void constantsAreShared() throws CompileError {
        Bytecode bc = Compiler.compile("print 5 + 5 + 5;\nprint 5;", false);
        assertEquals(1, bc.constants.length);
    }

    @Test
    public void generatorCoversTheLanguage() {
        StringBuilder all = new StringBuilder();
        for (int seed = 0; seed < 200; seed++) all.append(ProgramGenerator.generate(seed));
        // Rename declarations so "f1(" below can only match a call.
        String corpus = all.toString().replace("fn f", "fn F");
        for (String feature : new String[] {"fn F2(", "f0(", "f2(", "while (", "} else {", "&&", "||", " / ", " % ",
                " < ", " == ", " != ", "!(", "-(", ": bool =", "print ", "return ", "9223372036854775807"}) {
            assertTrue("generated programs never contain: " + feature, corpus.contains(feature));
        }
    }

    @Test
    public void randomProgramsBehaveTheSameEverywhere() {
        Fuzz.Report r = Fuzz.run(1500, 100_000);
        assertEquals(r.firstMismatch(), 0, r.mismatches());
        // Both paths must actually be exercised.
        assertTrue("completed: " + r.completed(), r.completed() > 750);
        assertTrue("failed: " + r.failedAtRuntime(), r.failedAtRuntime() > 150);
    }
}
