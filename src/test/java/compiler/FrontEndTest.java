package compiler;

import static compiler.TestSupport.*;
import static compiler.Token.Kind.*;
import static org.junit.Assert.*;

import compiler.Ast.*;
import java.util.List;
import org.junit.Test;

/** Lexer and parser. */
public class FrontEndTest {

    @Test
    public void tokensHaveKindsValuesAndPositions() throws CompileError {
        List<Token> t = Lexer.tokenize("let x = 10; // comment\n  print x <= -3;");
        assertEquals(List.of(LET, IDENT, ASSIGN, INT, SEMI, PRINT, IDENT, LE, MINUS, INT, SEMI, EOF),
                t.stream().map(Token::kind).toList());
        assertEquals(10, t.get(3).value());
        assertEquals(new Pos(1, 9), t.get(3).pos());
        assertEquals(new Pos(2, 3), t.get(5).pos());   // print, after the comment and newline
        assertEquals(new Pos(2, 11), t.get(7).pos());  // <=
    }

    @Test
    public void lexicalErrorsPointAtTheProblem() {
        assertError("print 9223372036854775808;", 1, 7, "integer literal too large");
        assertError("let a = 1 & 2;", 1, 11, "expected '&&'");
        assertError("let a = 1;\nlet b = a # 2;", 2, 11, "unexpected character '#'");
        assertError("let a = 12abc;", 1, 9, "invalid number");
    }

    @Test
    public void largestLiteralIsAccepted() {
        assertEquals("9223372036854775807\n", output("print 9223372036854775807;"));
    }

    @Test
    public void precedenceAndAssociativity() throws CompileError {
        assertEquals("((1 + (2 * 3)) - 4)", expr("1 + 2 * 3 - 4"));
        assertEquals("((1 - 2) - 3)", expr("1 - 2 - 3"));
        assertEquals("((8 / 4) / 2)", expr("8 / 4 / 2"));
        assertEquals("(a || (b && c))", expr("a || b && c"));
        assertEquals("((a && b) || c)", expr("a && b || c"));
        assertEquals("(((-a) * b) % c)", expr("-a * b % c"));
        assertEquals("((!a) == b)", expr("!a == b"));
        assertEquals("((x < (y + 1)) == true)", expr("x < y + 1 == true"));
        assertEquals("(-(-x))", expr("--x"));
        assertEquals("((1 + 2) * 3)", expr("(1 + 2) * 3"));
        assertEquals("f[(a + 1), g[]]", expr("f(a + 1, g())"));
    }

    @Test
    public void statementsParse() throws CompileError {
        Program p = Parser.parse("""
                fn f(a: int, b: bool): int { return a; }
                fn g() { return; }
                let x: int = 1;
                x = x + 1;
                if (x > 1) print x; else { print 0; }
                while (false) {}
                g();
                """);
        assertEquals(2, p.functions().size());
        assertEquals(Ast.Type.VOID, p.functions().get(1).returnType());
        assertEquals(List.of(Let.class, Assign.class, If.class, While.class, ExprStmt.class),
                p.main().stream().map(Object::getClass).toList());
        assertEquals(Ast.Type.INT, ((Let) p.main().get(0)).declared());
    }

    @Test
    public void syntaxErrorsAreSpecific() {
        assertError("let x = 1\nprint x;", 2, 1, "expected ';' after the declaration, found 'print'");
        assertError("print 1 < 2 < 3;", 1, 13, "comparisons can't be chained");
        assertError("if (true) { fn f() {} }", 1, 13, "functions can only be declared at the top level");
        assertError("fn f() {\n  print 1;\n", 3, 1, "expected '}' to close the block opened at 1:8");
        assertError("let x;", 1, 6, "expected '=' (every variable needs an initial value)");
        assertError("print (1 + 2;", 1, 13, "expected ')'");
        assertError("print 1 + ;", 1, 11, "expected an expression, found ';'");
        assertError("fn f(a) {}", 1, 7, "expected ':' and a type after parameter 'a'");
        assertError("fn f(a: string) {}", 1, 9, "expected a type (int or bool)");
    }

    @Test
    public void deepNestingIsReportedNotCrashed() {
        String src = "print " + "(".repeat(Parser.MAX_NESTING + 5) + "1" + ")".repeat(Parser.MAX_NESTING + 5) + ";";
        CompileError e = compileError(src);
        assertTrue(e.detail, e.detail.contains("nested too deeply"));
    }

    @Test
    public void renderShowsLineAndCaret() {
        String src = "let a = 1;\nlet b = a + true;";
        String r = compileError(src).render(src);
        assertEquals("error at 2:11: '+' needs int operands, not int and bool\n  let b = a + true;\n            ^", r);
    }

    private static String expr(String src) throws CompileError {
        Program p = Parser.parse("print " + src + ";");
        return show(((Print) p.main().get(0)).value());
    }

    static void assertError(String src, int line, int col, String message) {
        CompileError e;
        try {
            Checker.check(src);
            throw new AssertionError("expected a compile error for: " + src);
        } catch (CompileError ce) {
            e = ce;
        }
        assertTrue("message was: " + e.detail, e.detail.contains(message));
        assertEquals("position of: " + e.detail, new Pos(line, col), e.pos);
    }
}
