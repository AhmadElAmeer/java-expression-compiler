package compiler;

import compiler.Token.Kind;
import java.util.List;

/** The syntax tree. Nodes are immutable; the checker records types and slots in side tables. */
public final class Ast {
    private Ast() {}

    public enum Type {
        INT, BOOL, VOID;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    public sealed interface Expr permits Num, Bool, Var, Unary, Binary, Call {
        Pos pos();
    }

    public record Num(long value, Pos pos) implements Expr {}
    public record Bool(boolean value, Pos pos) implements Expr {}
    public record Var(String name, Pos pos) implements Expr {}
    /** {@code pos} is the operator's position. */
    public record Unary(Kind op, Expr operand, Pos pos) implements Expr {}
    /** {@code pos} is the operator's position, so runtime errors point at it. */
    public record Binary(Kind op, Expr left, Expr right, Pos pos) implements Expr {}
    public record Call(String name, List<Expr> args, Pos pos) implements Expr {}

    public sealed interface Stmt permits Let, Assign, Print, If, While, Block, Return, ExprStmt {
        Pos pos();
    }

    /** {@code declared} is null when the type is inferred from the initializer. */
    public record Let(String name, Type declared, Expr init, Pos pos) implements Stmt {}
    public record Assign(String name, Expr value, Pos pos) implements Stmt {}
    public record Print(Expr value, Pos pos) implements Stmt {}
    /** {@code otherwise} is null when there is no else branch. */
    public record If(Expr cond, Stmt then, Stmt otherwise, Pos pos) implements Stmt {}
    public record While(Expr cond, Stmt body, Pos pos) implements Stmt {}
    public record Block(List<Stmt> stmts, Pos pos) implements Stmt {}
    /** {@code value} is null for a bare {@code return;}. */
    public record Return(Expr value, Pos pos) implements Stmt {}
    public record ExprStmt(Expr expr, Pos pos) implements Stmt {}

    public record Param(String name, Type type, Pos pos) {}
    public record Function(String name, List<Param> params, Type returnType, Block body, Pos pos) {}
    /** Top-level statements form the implicit main program. */
    public record Program(List<Function> functions, List<Stmt> main) {}
}
