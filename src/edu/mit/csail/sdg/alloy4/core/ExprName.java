package edu.mit.csail.sdg.alloy4.core;



/**
 * Immutable; represents an identifier in the AST.
 *
 * <br/>
 * <br/> Invariant: name!=null
 * <br/> Invariant: name does not equal "" nor "@"
 * <br/> Invariant: if name contains '@', then '@' must only occur as the first character
 * <br/> Invariant: (object==null)
 * <br/>   &nbsp; &nbsp; || (object instanceof ParaSig)
 * <br/>   &nbsp; &nbsp; || (object instanceof ParaSig.Field.Full)
 * <br/>   &nbsp; &nbsp; || (object instanceof ParaFun, and the function has 0 parameters)
 *
 * @author Felix Chang
 */

public final class ExprName extends Expr {

    /** Accepts the return visitor. */
    @Override public Object accept(VisitReturn visitor) {
        return visitor.accept(this);
    }

    /** Accepts the typecheck visitor bottom-up. */
    @Override public Expr accept(VisitTypechecker visitor) {
        return visitor.accept(this);
    }

    /** Accepts the typecheck visitor top-down. */
    @Override public Expr accept(VisitTypechecker visitor, Type type) {
        return visitor.accept(this,type);
    }

    /** The name of the object that this name refers to. */
    public final String name;

    /**
     *  The object that this name refers to (null if this node hasn't been typechecked).
     *
     *  <p/> Note: After typechecking, this field can have the following possibilities:
     *  <br/> (1) null (meaning it's a quantification/let/function_call_parameter or it's univ/iden/none/Int)
     *  <br/> (2) ParaSig
     *  <br/> (3) ParaSig.Field.Full
     *  <br/> (4) parameter-less ParaFun
     */
    public final Object object;

    /**
     * Constructs a typechecked ExprName expression.
     *
     * @param pos - the original position in the file
     * @param name - the identifier
     * @param object - the object being referred to
     * @param type - the type
     *
     * @throws ErrorInternal if pos==null or name==null
     * @throws ErrorInternal if name is equal to "" or "@"
     * @throws ErrorInternal if name.lastIndexOf('@')>0
     * @throws ErrorInternal if object is not one of {null, ParaSig, ParaSig.Field.Full, ParaFun}
     * @throws ErrorInternal if object is a ParaFun with more than one parameter
     */
    public ExprName(Pos pos, String name, Object object, Type type) {
        super(pos, type, 0);
        this.name=nonnull(name);
        this.object=object;
        if (name.length()==0)
            throw syntaxError("The name of a variable must not be empty!");
        if (name.length()==1 && name.charAt(0)=='@')
            throw syntaxError("The name of a variable must not be \"@\"");
        if (name.lastIndexOf('@')>0)
            throw syntaxError("If a variable name contains @, it must be the first character!");
        if (object!=null
            && !(object instanceof ParaSig)
            && !(object instanceof ParaSig.Field.Full)
            && !(object instanceof ParaFun))
            throw internalError("ExprName object must be Sig, Sig.Field.Full, Fun, or null!");
        if (object instanceof ParaFun && ((ParaFun)object).argCount>0)
            throw internalError("If ExprName object is a function, it must have exactly 0 parameters!");
    }

    /**
     * Constructs an untypechecked ExprName expression.
     *
     * @param pos - the original position in the file
     * @param name - the identifier
     *
     * @throws ErrorInternal if pos==null or name==null
     * @throws ErrorInternal if name is equal to "" or "@"
     * @throws ErrorInternal if name.lastIndexOf('@')>0
     */
    public ExprName(Pos pos, String name) { this(pos, name, null, null); }

    /**
     * Convenience method that throws a syntax error exception saying the name "n" can't be found.
     * (In particular, if n is an old Alloy3 keyword, then
     * the message will tell the user to consult the documentation
     * on how to migrate old models to the new syntax.)
     *
     * @param p - the original position in the file that triggered the error
     * @param n - the identifier
     */
    public static void hint (Pos p, String n) {
        String msg="The name \""+n+"\" cannot be found.";
        if ("disj".equals(n) || "disjoint".equals(n) ||
            "exh".equals(n) || "exhaustive".equals(n) ||
            "part".equals(n) || "partition".equals(n) )
            msg=msg+" If you are migrating from Alloy 3, please see the "
                +"online documentation on how to translate models that use the \""
                +n+"\" keyword.";
        throw new ErrorSyntax(p, msg);
    }
}