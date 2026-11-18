package compiler;

import static compiler.Token.Kind.*;

import compiler.Ast.*;
import compiler.Token.Kind;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser. Each grammar rule is one method; operator precedence comes from
 * which method calls which (lowest precedence first):
 *
 * <pre>
 * expression → or
 * or         → and ( "||" and )*
 * and        → equality ( "&amp;&amp;" equality )*
 * equality   → comparison ( ( "==" | "!=" ) comparison )*
 * comparison → term ( ( "&lt;" | "&lt;=" | "&gt;" | "&gt;=" ) term )?      (not chainable)
 * term       → factor ( ( "+" | "-" ) factor )*
 * factor     → unary ( ( "*" | "/" | "%" ) unary )*
 * unary      → ( "-" | "!" ) unary | primary
 * primary    → INT | "true" | "false" | IDENT | IDENT "(" args ")" | "(" expression ")"
 * </pre>
 *
 * The parser stops at the first error.
 */
public final class Parser {
    /**
     * Limit on nesting of parentheses, unary operators and statements. Each level costs about ten
     * Java frames here, so this keeps parsing safe on an ordinary thread stack.
     */
    static final int MAX_NESTING = 256;

    private final List<Token> tokens;
    private int p = 0;
    private int nesting = 0;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Program parse(String source) throws CompileError {
        return new Parser(Lexer.tokenize(source)).program();
    }

    private Program program() throws CompileError {
        List<Function> functions = new ArrayList<>();
        List<Stmt> main = new ArrayList<>();
        while (!at(EOF)) {
            if (at(FN)) functions.add(function());
            else main.add(statement());
        }
        return new Program(functions, main);
    }

    private Function function() throws CompileError {
        Token fn = expect(FN, "'fn'");
        Token name = expect(IDENT, "a function name");
        expect(LPAREN, "'(' after the function name");
        List<Param> params = new ArrayList<>();
        if (!at(RPAREN)) {
            do {
                Token pname = expect(IDENT, "a parameter name");
                expect(COLON, "':' and a type after parameter '" + pname.text() + "'");
                params.add(new Param(pname.text(), type(), pname.pos()));
            } while (match(COMMA));
        }
        expect(RPAREN, "')' after the parameters");
        Type ret = Type.VOID;
        if (match(COLON)) ret = type();
        if (!at(LBRACE)) throw error("expected '{' to start the body of '" + name.text() + "'");
        return new Function(name.text(), params, ret, block(), fn.pos());
    }

    private Type type() throws CompileError {
        if (match(INT_TYPE)) return Type.INT;
        if (match(BOOL_TYPE)) return Type.BOOL;
        throw error("expected a type (int or bool)");
    }

    private Stmt statement() throws CompileError {
        enter();
        try {
            Token t = peek();
            switch (t.kind()) {
                case FN:
                    throw error("functions can only be declared at the top level");
                case LET: {
                    advance();
                    Token name = expect(IDENT, "a variable name after 'let'");
                    Type declared = match(COLON) ? type() : null;
                    expect(ASSIGN, "'=' (every variable needs an initial value)");
                    Expr init = expression();
                    expect(SEMI, "';' after the declaration");
                    return new Let(name.text(), declared, init, name.pos());
                }
                case PRINT: {
                    advance();
                    Expr value = expression();
                    expect(SEMI, "';' after print");
                    return new Print(value, t.pos());
                }
                case IF: {
                    advance();
                    expect(LPAREN, "'(' after 'if'");
                    Expr cond = expression();
                    expect(RPAREN, "')' after the condition");
                    Stmt then = statement();
                    Stmt otherwise = match(ELSE) ? statement() : null;
                    return new If(cond, then, otherwise, t.pos());
                }
                case WHILE: {
                    advance();
                    expect(LPAREN, "'(' after 'while'");
                    Expr cond = expression();
                    expect(RPAREN, "')' after the condition");
                    return new While(cond, statement(), t.pos());
                }
                case RETURN: {
                    advance();
                    Expr value = at(SEMI) ? null : expression();
                    expect(SEMI, "';' after return");
                    return new Return(value, t.pos());
                }
                case LBRACE:
                    return block();
                default:
                    if (t.kind() == IDENT && peekAt(1).kind() == ASSIGN) {
                        advance();
                        advance();
                        Expr value = expression();
                        expect(SEMI, "';' after the assignment");
                        return new Assign(t.text(), value, t.pos());
                    }
                    Expr e = expression();
                    expect(SEMI, "';' after the expression");
                    return new ExprStmt(e, t.pos());
            }
        } finally {
            nesting--;
        }
    }

    private Block block() throws CompileError {
        Token open = expect(LBRACE, "'{'");
        List<Stmt> stmts = new ArrayList<>();
        while (!at(RBRACE)) {
            if (at(EOF)) throw error("expected '}' to close the block opened at " + open.pos());
            stmts.add(statement());
        }
        advance();
        return new Block(stmts, open.pos());
    }

    private Expr expression() throws CompileError {
        return or();
    }

    private Expr or() throws CompileError {
        Expr left = and();
        while (at(OR)) {
            Token op = advance();
            left = new Binary(OR, left, and(), op.pos());
        }
        return left;
    }

    private Expr and() throws CompileError {
        Expr left = equality();
        while (at(AND)) {
            Token op = advance();
            left = new Binary(AND, left, equality(), op.pos());
        }
        return left;
    }

    private Expr equality() throws CompileError {
        Expr left = comparison();
        while (at(EQ) || at(NE)) {
            Token op = advance();
            left = new Binary(op.kind(), left, comparison(), op.pos());
        }
        return left;
    }

    private Expr comparison() throws CompileError {
        Expr left = term();
        if (isComparison(peek().kind())) {
            Token op = advance();
            left = new Binary(op.kind(), left, term(), op.pos());
            if (isComparison(peek().kind())) {
                throw error("comparisons can't be chained; write 'a < b && b < c'");
            }
        }
        return left;
    }

    private static boolean isComparison(Kind k) {
        return k == LT || k == LE || k == GT || k == GE;
    }

    private Expr term() throws CompileError {
        Expr left = factor();
        while (at(PLUS) || at(MINUS)) {
            Token op = advance();
            left = new Binary(op.kind(), left, factor(), op.pos());
        }
        return left;
    }

    private Expr factor() throws CompileError {
        Expr left = unary();
        while (at(STAR) || at(SLASH) || at(PERCENT)) {
            Token op = advance();
            left = new Binary(op.kind(), left, unary(), op.pos());
        }
        return left;
    }

    private Expr unary() throws CompileError {
        if (at(MINUS) || at(BANG)) {
            Token op = advance();
            enter();
            try {
                return new Unary(op.kind(), unary(), op.pos());
            } finally {
                nesting--;
            }
        }
        return primary();
    }

    private Expr primary() throws CompileError {
        Token t = peek();
        switch (t.kind()) {
            case INT:
                advance();
                return new Num(t.value(), t.pos());
            case TRUE:
            case FALSE:
                advance();
                return new Bool(t.kind() == TRUE, t.pos());
            case IDENT:
                advance();
                if (!match(LPAREN)) return new Var(t.text(), t.pos());
                List<Expr> args = new ArrayList<>();
                if (!at(RPAREN)) {
                    do {
                        args.add(expression());
                    } while (match(COMMA));
                }
                expect(RPAREN, "')' after the arguments to '" + t.text() + "'");
                return new Call(t.text(), args, t.pos());
            case LPAREN: {
                advance();
                enter();
                try {
                    Expr e = expression();
                    expect(RPAREN, "')'");
                    return e;
                } finally {
                    nesting--;
                }
            }
            default:
                throw error("expected an expression, found " + t.describe());
        }
    }

    // ---- helpers ----

    private void enter() throws CompileError {
        if (++nesting > MAX_NESTING) {
            throw error("program is nested too deeply (limit " + MAX_NESTING + ")");
        }
    }

    private Token peek() {
        return tokens.get(p);
    }

    private Token peekAt(int ahead) {
        return tokens.get(Math.min(p + ahead, tokens.size() - 1));
    }

    private boolean at(Kind k) {
        return peek().kind() == k;
    }

    private Token advance() {
        Token t = tokens.get(p);
        if (t.kind() != EOF) p++;
        return t;
    }

    private boolean match(Kind k) {
        if (at(k)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(Kind k, String what) throws CompileError {
        if (at(k)) return advance();
        throw error("expected " + what + ", found " + peek().describe());
    }

    private CompileError error(String message) {
        return new CompileError(peek().pos(), message);
    }
}
