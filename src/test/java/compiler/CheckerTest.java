package compiler;

import static compiler.FrontEndTest.assertError;
import static compiler.TestSupport.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class CheckerTest {

    @Test
    public void namesMustBeDeclared() {
        assertError("print y;", 1, 7, "undefined variable 'y'");
        assertError("y = 1;", 1, 1, "undefined variable 'y'");
        assertError("print f(1);", 1, 7, "undefined function 'f'");
        assertError("fn f(): int { return 1; }\nprint f;", 2, 7, "'f' is a function; call it with ()");
    }

    @Test
    public void scopes() {
        assertError("let a = 1;\nlet a = 2;", 2, 5, "'a' is already declared in this scope");
        assertError("fn f(a: int) { let a = 2; }", 1, 20, "'a' is already declared");
        assertError("fn f(a: int, a: int) {}", 1, 14, "'a' is already declared");
        assertError("if (true) { let t = 1; }\nprint t;", 2, 7, "undefined variable 't'");
        assertError("if (true) let t = 1;\nprint t;", 2, 7, "undefined variable 't'");
        assertError("while (false) let t = 1;\nprint t;", 2, 7, "undefined variable 't'");
        // Shadowing in an inner block is allowed, and the outer variable is untouched.
        assertEquals("2\n1\n", output("let a = 1;\n{ let a = 2; print a; }\nprint a;"));
        // The initializer sees the outer variable, not the one being declared.
        assertEquals("11\n", output("let a = 1;\n{ let a = a + 10; print a; }"));
        assertError("let b = b;", 1, 9, "undefined variable 'b'");
    }

    @Test
    public void operatorTypes() {
        assertError("print 1 + true;", 1, 9, "'+' needs int operands, not int and bool");
        assertError("print true < false;", 1, 12, "'<' needs int operands");
        assertError("print 1 && true;", 1, 9, "'&&' needs bool operands, not int and bool");
        assertError("print 1 == true;", 1, 9, "can't compare int with bool");
        assertError("print -true;", 1, 7, "'-' needs an int, not a bool");
        assertError("print !1;", 1, 7, "'!' needs a bool, not an int");
        assertEquals("true\nfalse\n", output("print true == !false;\nprint 1 != 1;"));
    }

    @Test
    public void declarationsAndAssignments() {
        assertError("let a: int = true;", 1, 14, "'a' is declared int but its initial value is bool");
        assertError("let a = 1;\na = false;", 2, 5, "can't assign bool to 'a', which is int");
        assertError("if (1) {}", 1, 5, "'if' condition must be bool, not int");
        assertError("while (0 + 1) {}", 1, 10, "'while' condition must be bool, not int");
    }

    @Test
    public void callsMatchSignatures() {
        String f = "fn f(a: int, b: bool): int { return a; }\n";
        assertError(f + "print f(1);", 2, 7, "'f' takes 2 arguments, but 1 was given");
        assertError(f + "print f(1, 2);", 2, 12, "argument 2 of 'f' must be bool, not int");
        assertError("fn g() {}\nprint g();", 2, 7, "'g' returns nothing, so it has no value to use");
        assertError("fn g() {}\nlet x = g();", 2, 9, "'g' returns nothing");
        assertError("fn f() {}\nfn f() {}", 2, 1, "function 'f' is already declared");
        // A function can be called before it's declared.
        assertEquals("6\n", output("print twice(3);\nfn twice(x: int): int { return x * 2; }"));
    }

    @Test
    public void returns() {
        assertError("return 1;", 1, 1, "'return' outside a function");
        assertError("fn f(): int { return; }", 1, 15, "'f' must return an int");
        assertError("fn f() { return 1; }", 1, 17, "'f' has no return type, so it can't return a value");
        assertError("fn f(): int { return true; }", 1, 22, "'f' must return int, not bool");
        assertError("fn f(x: int): int { if (x > 0) { return 1; } }", 1, 1,
                "function 'f' can reach its end without returning an int");
        assertError("fn f(): int { while (true) { return 1; } }", 1, 1, "can reach its end");
        // Both branches return, so this is fine.
        assertEquals("-1\n", output("fn sign(x: int): int { if (x < 0) { return -1; } else { return 1; } }\nprint sign(-5);"));
    }
}
