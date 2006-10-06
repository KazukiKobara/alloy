package edu.mit.csail.sdg.alloy4.node;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import edu.mit.csail.sdg.alloy4.helper.DirectedGraph;
import edu.mit.csail.sdg.alloy4.helper.Env;
import edu.mit.csail.sdg.alloy4.helper.ErrorSyntax;
import edu.mit.csail.sdg.alloy4.helper.IdentitySet;
import edu.mit.csail.sdg.alloy4.helper.Log;

/**
 * This class computes both the bounding type and the relevant type.
 *
 * <p/>
 * During the first pass, this typechecker
 * computing the "bounding type" of parent expressions
 * based on the bounding types of its children.
 * During this phase, it could also detect some irrelevant expressions.
 *
 * <p/>
 * During the second pass, this typechecker
 * computes the "relevant type" top-down:
 * the type of the parent expression is used to determine
 * whether the subexpressions are relevant or not.
 * This second pass is needed to resolve field/function/predicate overloading,
 * and can also detect some irrelevant expressions.
 *
 * <p/>
 * In general, during the first pass, we allow name overloading to propagate.
 * This enables more context-sensitivity for doing precise diambiguation.
 * However, there are a few places we force the name to be fully resolved
 * before proceeding:
 * <br/> (1) Parameters to a function/predicate call
 * <br/> (2) let x=a... (here we will always fully resolve a)
 * <br/> (3) quantifer x:a|b (here we first fully resolve a, and then fully resolve b)
 *
 * @author Felix Chang plus code adapted from Alloy 3
 */

public final class VisitTypechecker {

    /** True if we want to automatically cast between int and Int. */
    public static final boolean autoIntCast=false;

    /**
     * This maps each ambiguous ExprCall and ExprName node
     * to a list of possible Expr nodes that it should be replaced with.
     */
    private final Map<Expr,List<Expr>> objChoices=new LinkedHashMap<Expr,List<Expr>>();

    /**
     * This maps local names (that is, LET variable / QUANT variable / FUNCTION parameter)
     * to the object that it refers to.
     */
    private final Env<Type> env=new Env<Type>();

    private final IdentitySet<Expr> cache=new IdentitySet<Expr>();
    private Expr cache(Expr x) { cache.add(x); return x; }
    private boolean cached(Expr x) { return cache.contains(x); }

    /** This is a logger that will receive verbose debugging output during typechecking. */
    private Log log=null;

    // field&sig!=null    else if sig!=null     else if fun!=null     allelse
    private Field rootfield=null;
    private ParaSig rootsig=null;
    private ParaFun rootfun=null;
    private Unit rootunit=null;

    /** Private constructor (to ensure the only way to access the typechecker is via the check() method) */
    private VisitTypechecker() { }

    /** This is the main method to call to do typechecking. */
    public static ArrayList<ParaSig> check(Log log, ArrayList<Unit> units) {
        ParaSig.UNIV.subs.clear();
        ParaSig.UNIV.subs.add(ParaSig.SIGINT);
        VisitTypechecker tc=new VisitTypechecker();
        tc.log=log;
        while(fillParams(units,log)) {}
        while(mergeUnits(units,log)) {}
        ArrayList<ParaSig> allsigs=fillSig(units);
        tc.check(units,allsigs);
        return allsigs;
    }

    /** This is step 1: figure out the instantiating parameters of each module. */
    private static boolean fillParams(ArrayList<Unit> units, Log log) {
        boolean chg=false;
        ParaOpen missing=null;
        for(Unit u:units) for(Map.Entry<String, ParaOpen> f:u.opencmds.entrySet()) {
            Unit uu=u.opens.get(f.getKey());
            int j=uu.params.size();
            if (f.getValue().list.size() != j)
                throw new ErrorSyntax(u.pos, "To import the \""+uu.pos.filename+"\" module, you must provide exactly "+j+" parameters!");
            int i=0;
            for(Map.Entry<String,ParaSig> pp:uu.params.entrySet()) {
                String kn=pp.getKey();
                ParaSig old=pp.getValue();
                String vn=f.getValue().list.get(i); i++;
                Set<Object> v=u.lookup_sigORparam(vn);
                if (v.size()<1)
                {if (old==null) missing=f.getValue(); continue;}
                if (v.size()>1)
                    throw new ErrorSyntax(u.pos, "Failed to import the \""+uu.pos.filename+"\" module, because the signature named \""+vn+"\" is ambiguous");
                ParaSig vv=(ParaSig)(v.iterator().next());
                if (old==vv) continue;
                if (old!=null)
                    throw new ErrorSyntax(u.pos, "Failed to import the \""+uu.pos.filename+"\" module, because it is being imported more than once, with different arguments!");
                if (vv==ParaSig.NONE)
                    throw new ErrorSyntax(u.pos, "Failed to import the \""+uu.pos.filename+"\" module, because you cannot use \"none\" as an instantiating argument!");
                chg=true;
                uu.params.put(kn,vv);
                log.log("RESOLVE: "+f.getKey()+"/"+kn+" := "+vv.fullname+"\n");
            }
        }
        if (chg==false && missing!=null)
            throw missing.syntaxError("Failed to import the module, because one of the instantiating signature cannot be found");
        return chg;
    }

    /** This is step 2: merging modules that have same filename and instantiating arguments. */
    private static boolean mergeUnits(ArrayList<Unit> units, Log log) {
        // Before merging, the only pointers that go between Unit objects are
        // (1) a unit's "params" may point to a sig in another unit
        // (2) a unit's "opens" may point to another unit
        // So when we find that two units A and B should be merged,
        // we iterate through every unit (except B), and replace
        // pointers into B with pointers into A.
        boolean chg=false;
        for(int i=0; i<units.size(); i++) {
            Unit a=units.get(i);
            for(int j=i+1; j<units.size(); j++) {
                Unit b=units.get(j);
                if (a.pos.filename.equals(b.pos.filename) && a.params.equals(b.params)) {
                    log.log("MATCH FOUND ON "+a.pos.filename+"\n");
                    a.aliases.addAll(b.aliases);
                    Collections.sort(a.aliases, aliasComparator);
                    Map<String,ParaSig> asigs=new LinkedHashMap<String,ParaSig>(a.sigs);
                    for(Map.Entry<String,ParaSig> p:a.sigs.entrySet())
                        p.getValue().aliases=new ArrayList<String>(a.aliases);
                    for(Unit c:units) if (c!=b) {
                        for(Map.Entry<String,ParaSig> p:c.params.entrySet()) {
                            if (isin(p.getValue(),asigs)) p.setValue(a.sigs.get(p.getValue().name));
                            if (isin(p.getValue(),b.sigs)) p.setValue(a.sigs.get(p.getValue().name));
                        }
                        for(Map.Entry<String,Unit> p:c.opens.entrySet()) if (p.getValue()==b) p.setValue(a);
                    }
                    units.remove(j);
                    chg=true;
                }
            }
        }
        return chg;
    }

    private static<V> boolean isin(V x, Map<String,V> y) {
        for(Map.Entry<String,V> e:y.entrySet()) if (e.getValue()==x) return true;
        return false;
    }

    private static final Comparator<String> aliasComparator = new Comparator<String>() {
        public final int compare(String a, String b) {
            int alen=a.length();
            int blen=b.length();
            if (alen<blen) return -1;
            if (alen>blen) return 1;
            return a.compareTo(b);
        }
    };

    /**
     * This is step 3B: fill in the "sup", "sups", "subs", and "type" field of every ParaSig,
     * then return the list of all ParaSig (topologically sorted).
     * (meaning: if A extends or is subset of B, then B will preceed A in the list)
     *
     * <p/> We complain if a SIG tries to extend a SUBSETSIG.
     * <p/> We also complain if there is a cycle in SIG relationship.
     */
    private static ArrayList<ParaSig> fillSig(ArrayList<Unit> units) {
        ArrayList<ParaSig> sigs=new ArrayList<ParaSig>();
        for(Unit u:units) for(Map.Entry<String,ParaSig> si:u.sigs.entrySet()) {
            ParaSig s=si.getValue();
            s.resolveSup(u);
            s.resolveSups(u);
            sigs.add(s);
        }
        // Now we perform a topological sort on the sigs
        LinkedHashMap<ParaSig,Boolean> status=new LinkedHashMap<ParaSig,Boolean>(); // NONE=NONE FALSE=VISITING TRUE=VISITED
        ArrayList<ParaSig> list=new ArrayList<ParaSig>();
        for(ParaSig y:sigs) {
            Boolean v=status.get(y);
            if (v==null) tsort(y,status,list);
            else if (v==false) throw y.syntaxError("Circular extension detected, involving the signature named \""+y.fullname+"\"");
        }
        for(ParaSig y:list) if (y.sup()!=null) y.sup().subs.add(y);
        // Now we fill in the Type field of all the ParaSig objects.
        // Since the SIGS are topologically sorted, whenever we process a sig,
        // we know the Type values of its ancestors sigs are already computed.
        for(ParaSig y:list) {
            Type t=null;
            if (y.subset) for(ParaSig z:y.sups()) {
                if (t==null) t=z.type; else t=t.union(z.type);
            }
            if (t==null) t=Type.make(y);
            y.type=t;
        }
        return list;
    }

    private static void tsort(ParaSig x, LinkedHashMap<ParaSig,Boolean> status, ArrayList<ParaSig> list) {
        // Performs a topological sort
        status.put(x, Boolean.FALSE);
        ParaSig y=x.sup();
        if (y!=null && y!=ParaSig.UNIV) {
            Boolean v=status.get(y);
            if (v==null) tsort(y,status,list);
            else if (v==false) throw y.syntaxError("Circular extension detected, involving the signature named \""+y.fullname+"\"");
        }
        for(ParaSig yy:x.sups()) {
            Boolean v=status.get(yy);
            if (v==null) tsort(yy,status,list);
            else if (v==false) throw yy.syntaxError("Circular extension detected, involving the signature named \""+yy.fullname+"\"");
        }
        status.put(x, Boolean.TRUE);
        list.add(x);
    }

    /** This is step 4: typecheck everything. */
    private void check(ArrayList<Unit> units, List<ParaSig> allsigs) {
        for(ParaSig s:allsigs)
            check(s, units.get(0).lookupPath(s.path));
        for(Unit u:units)
            for(Map.Entry<String,List<ParaFun>> funi:u.funs.entrySet())
                for(ParaFun f:funi.getValue())
                    check(f,u);
        for(Unit u:units) check(u);
    }

    /** This is step 4A: typechecking every field. */
    private void check(ParaSig x, Unit u) {
        // When typechecking the fields:
        // * each field is allowed to refer to earlier fields in the same SIG,
        //   as well as fields declared in any ancestor sig (as long as those ancestor sigs are visible from here)
        //   as well as any visible SIG.
        // * For example, if A.als opens B.als, and B/SIGX extends A/SIGY,
        //   then B/SIGX's fields cannot refer to A/SIGY, nor any fields in A/SIGY)
        objChoices.clear();
        env.clear();
        int fi=0;
        List<VarDecl> newdecl=new ArrayList<VarDecl>();
        List<VarDecl> olddecl=x.decls;
        x.decls=newdecl;
        for(VarDecl d:olddecl) {
            Expr value=d.value;
            for(int ni=0; ni<d.names.size(); ni++) {
                Field f=x.fields.get(fi);
                if (ni==0) {
                    rootfield=f; rootsig=x; rootfun=null; rootunit=u; value=addOne(resolve(value));
                    if (value.type.arity()<1) throw x.typeError("Field declaration must be a set or relation, but its type is "+value.type);
                }
                f.halftype = value.type;
                f.fulltype = x.type.product_of_anyEmptyness(value.type);
                fi++;
                log.log("Unit ["+u.aliases.get(0)+"], Sig "+x.name+", Field "+f.name+": "+f.fulltype+"\n");
            }
            newdecl.add(new VarDecl(d.pos, d, value));
        }
    }

    /** This is step 4B: typechecking every the parameters and return types of every predicate/function. */
    private void check(ParaFun fun, Unit u) {
        // Now, typecheck all function/predicate PARAMETERS and RETURNTYPE
        // Each PARAMETER can refer to earlier parameter in the same function, and any SIG or FIELD visible from here.
        // Each RETURNTYPE can refer to the parameters of the same function, and any SIG or FIELD visible from here.
        rootfield=null; rootsig=null; rootfun=fun; rootunit=u;
        objChoices.clear();
        env.clear();
        List<VarDecl> newdecls=new ArrayList<VarDecl>();
        for(VarDecl d:fun.getDecls()) {
            Expr value=d.value;
            for(int ni=0; ni<d.names.size(); ni++) {
                String n=d.names.get(ni);
                if (ni==0) value=addOne(resolve(value));
                if (value.type.arity()<1) throw value.typeError("Function parameter must be a set or relation, but its type is "+value.type);
                env.put(n, value.type);
                log.log("Unit ["+u.aliases.get(0)+"], Pred/Fun "+fun.name+", Param "+n+": "+value.type+"\n");
            }
            newdecls.add(new VarDecl(d.pos, d, value));
        }
        Expr type=fun.getType();
        if (type!=null) {
            type=addOne(resolve(type));
            if (type.type.arity()<1) throw type.typeError("Function return type must be a set or relation, but its type is "+type.type);
            log.log("Unit ["+u.aliases.get(0)+"], Pred/Fun "+fun.name+", RETURN: "+type.type+"\n");
        }
        env.clear();
        fun.setDecls(newdecls);
        fun.setType(type);
    }

    /** This is step 4C: typecheck (1) pred/fun bodies, (2) sig facts, (3) standalone facts, (4) assertions. */
    private void check(Unit u) {
        // These can refer to any SIG/FIELD/FUN/PRED visible from here.
        objChoices.clear();
        env.clear();
        String uu=u.aliases.iterator().next();
        for(Map.Entry<String,List<ParaFun>> xi:u.funs.entrySet()) for(int xii=0; xii<xi.getValue().size(); xii++) {
            ParaFun x=xi.getValue().get(xii);
            env.clear();
            rootfield=null; rootsig=null; rootfun=null; rootunit=u;
            for(VarDecl d:x.getDecls()) for(String n:d.names) env.put(n, d.value.type);
            Expr value=resolve(x.getValue());
            log.log("Unit ["+uu+"], Pred/Fun "+x.name+", BODY:"+value.type+"\n");
            if (x.getType()==null) {
                if (!value.type.isBool) throw x.typeError("Predicate body must be a formula, but it has type "+value.type);
            } else {
                if (value.type.arity()<1) throw x.typeError("Function body must be a set or relation, but its type is "+value.type);
                if (value.type.arity()!=x.getType().type.arity()) throw x.typeError("Function body has type "+value.type+" but the return type must be "+x.getType().type);
                if (value.type.intersect(x.getType().type).hasNoTuple()) throw x.typeError("Function return value is disjoint from its return type! Function body has type "+value.type+" but the return type must be "+x.getType().type);
            }
            x.setValue(value);
        }
        env.clear();
        for(Map.Entry<String,ParaSig> xi:u.sigs.entrySet()) {
            ParaSig x=xi.getValue();
            if (x.appendedFacts==null) continue;
            rootfield=null; rootsig=x; rootfun=null; rootunit=u; x.appendedFacts=resolve(x.appendedFacts);
            if (!x.appendedFacts.type.isBool) throw x.typeError("Appended facts must be a formula, but it has type "+x.appendedFacts.type);
            log.log("Unit ["+uu+"], Sig "+x.name+", Appended: "+x.appendedFacts.type+"\n");
        }
        for(Map.Entry<String,ParaFact> xi:u.facts.entrySet()) {
            ParaFact x=xi.getValue();
            rootfield=null; rootsig=null; rootfun=null; rootunit=u; x.value=resolve(x.value);
            log.log("Unit ["+uu+"], Fact ["+x.name+"]: "+x.value.type+"\n");
            if (!x.value.type.isBool) throw x.typeError("Fact must be a formula, but it has type "+x.value.type);
        }
        for(Map.Entry<String,ParaAssert> xi:u.asserts.entrySet()) {
            ParaAssert x=xi.getValue();
            rootfield=null; rootsig=null; rootfun=null; rootunit=u; x.value=resolve(x.value);
            log.log("Unit ["+uu+"], Assert ["+x.name+"]: "+x.value.type+"\n");
            if (!x.value.type.isBool) throw x.typeError("Assertion must be a formula, but it has type "+x.value.type);
        }
    }

    //---------------------------------------------------------------------------------

    private Expr addOne(Expr x) {
        if (x instanceof ExprUnary) {
            ExprUnary y=(ExprUnary)x;
            if (y.op==ExprUnary.Op.SETMULT || y.op==ExprUnary.Op.ONEMULT || y.op==ExprUnary.Op.LONEMULT || y.op==ExprUnary.Op.SOMEMULT) return x;
        }
        if (x.type.isInt || x.type.isBool || x.type.arity()!=1) return x;
        return ExprUnary.Op.ONEMULT.make(x.pos, x, x.type);
    }

    /**
     * Helper method that throws a type error if x cannot possibly have integer type.
     *
     * @throws ErrorType if x cannot possibly have integer type
     */
    private void cint(Expr x) {
        if (!x.type.isInt)
            throw x.typeError("This must be an integer expression! Instead, it has the following possible type(s): "+x.type);
    }

    /**
     * Helper method that throws a type error if x cannot possibly have set/relation type.
     *
     * @throws ErrorType if x cannot possibly have set/relation type
     *
     * @return x.type if x can possibly have set/relation type
     */
    private Type cset(Expr x) {
        if (x.type.size()==0)
            throw x.typeError("This must be a set or relation! Instead, it has the following possible type(s): "+x.type);
        return x.type;
    }

    /**
     * Helper method that throws a type error if t cannot possibly have set/relation type.
     * (And if so, the type error will say that x cannot be allowed to have type t)
     *
     * @throws ErrorType if t cannot possibly have set/relation type
     */
    private void cset(Type t,Expr x) {
        if (t.size()==0)
            throw x.typeError("This must be a set or relation! Instead, it has the following possible type(s): "+t);
    }

    /**
     * Helper method that throws a type error if x cannot possibly have set/relation type.
     *
     * @throws ErrorType if x cannot possibly have set/relation type
     */
    private void cform(Expr x) {
        if (!x.type.isBool)
            throw x.typeError("This must be a formula expression! Instead, it has the following possible type(s): "+x.type);
    }

    /**
     * Helper method that throws a type error if t cannot possibly have formula type.
     * (And if so, the type error will say that x cannot be allowed to have type t)
     *
     * @throws ErrorType if t cannot possibly have formula type
     */
    private void cform(Type t, Expr x) {
        if (!t.isBool)
            throw x.typeError("This must be a formula expression! Instead, it has the following possible type(s): "+t);
    }

    /** Helper method that returns true iff (x is null, or x does not have any valid type) */
    private boolean isbad(Type x) {
        return x==null || (!x.isBool && !x.isInt && x.size()==0);
    }

    /**
     * Helper method that throws a type error if t is ambiguous.
     * (And if so, the type error will say that x cannot be allowed to have type t)
     */
    private void resolved(Type t, Expr x) {
        if (!t.isBool && !t.isInt && t.size()==0)
            throw x.typeError("This expression failed to be typechecked, because it has no possible type!");
        if (t.isBool && t.isInt)
            throw x.typeError("This expression is ambiguous! It has the following possible types: "+t);
        if ((t.isBool || t.isInt) && t.size()>0)
            throw x.typeError("This expression is ambiguous! It has the following possible types: "+t);
        if (t.size()>0 && t.arity()<=0)
            throw x.typeError("This expression is ambiguous! It has the following possible types: "+t);
    }

    /**
     * Typecheck a node bottom-up and then top-down in order to fully resolve it
     * (this is the main method that users should use to typecheck a node)
     *
     * @return a deep copy of X that is identical to X, except that all the type information are filled in
     * @throws ErrorType if the node (and all its subnodes) cannot be fully resolved unambiguously
     */
    public Expr resolve(Expr x) {
        x=x.accept(this); // bottom-up
        x=x.accept(this, x.type); // top-down
        resolved(x.type, x); // double check that the type info is unambiguous (complain if it's not)
        return x;
    }

    //===========================================================//
    /** Method that typechecks an ExprBinary object. First pass. */
    //===========================================================//

    public Expr visit(ExprBinary x) {
        if (cached(x)) return x;
        Expr left=x.left.accept(this);
        Expr right=x.right.accept(this);
        Type a,b,c=null;
        bigbreak: switch(x.op) {
        case LT: case LTE: case GT: case GTE:
            cint(left);
            cint(right);
            c=Type.FORMULA;
            break;
        case AND: case OR: case IFF: case IMPLIES:
            cform(left);
            cform(right);
            c=Type.FORMULA;
            break;
        case PLUSPLUS:
            // RESULT=LEFT+RIGHT, if exists a in left, b in right, such that "a.arity==b.arity" && "some dom(a)&dom(b)"
            a=cset(left);
            b=cset(right);
            for (Type.Rel bb:b)
                if (a.hasArity(bb.arity()))
                    for (Type.Rel aa:a)
                        if (aa.arity()==bb.arity() && aa.basicTypes.get(0).intersect(bb.basicTypes.get(0)).isNonEmpty())
                        { c=a.union(b); break bigbreak; }
            throw x.boundingTypeError("++ is irrelevant because its right hand side can never override the left hand side!",a,b);
        case PLUS:
            // RESULT=LEFT+RIGHT, if exists a in left, b in right, such that a.arity==b.arity
            a=left.type;
            b=right.type;
            if (a.hasCommonArity(b)) { c=a.union(b); if (a.isInt && b.isInt) c=Type.makeInt(c); break; }
            if (a.isInt && b.isInt) { c=Type.INT; break; }
            throw x.boundingTypeError("+ can be used only between 2 sets and relations of the same arity, or between 2 integer expressions!",a,b);
        case MINUS:
            // RESULT=LEFT, if exists a in left, b in right, such that a.arity==b.arity and (a&b is nonempty)
            a=left.type;
            b=right.type;
            if (a.size()>0 || b.size()>0) {
                if (a.intersect(b).hasTuple()) { c=a; if (a.isInt && b.isInt) c=Type.makeInt(c); break; }
                throw x.boundingTypeError("- is irrelevant because the two expressions are disjoint!",a,b);
            }
            if (a.isInt && b.isInt) { c=Type.INT; break; }
            throw x.boundingTypeError("- can be used only between 2 sets and relations of the same arity, or between 2 integer expressions!",a,b);
        case INTERSECT:
            // RESULT TYPE = { a&b | a in leftType, b in rightType, a.arity==b.arity } if it's nonempty()
            a=cset(left);
            b=cset(right);
            c=a.intersect(b);
            if (c.hasTuple()) break;
            throw x.boundingTypeError("& failed because there is an arity mismatch, or the 2 expressions are always disjoint!",a,b);
        case ARROW: case ANY_ARROW_SOME: case ANY_ARROW_ONE: case ANY_ARROW_LONE:
        case SOME_ARROW_ANY: case SOME_ARROW_SOME: case SOME_ARROW_ONE: case SOME_ARROW_LONE:
        case ONE_ARROW_ANY: case ONE_ARROW_SOME: case ONE_ARROW_ONE: case ONE_ARROW_LONE:
        case LONE_ARROW_ANY: case LONE_ARROW_SOME: case LONE_ARROW_ONE: case LONE_ARROW_LONE:
            // RESULT TYPE = { a->b  |  a in leftType,  b in rightType, a.isEmpty()==b.isEmpty() } if its size()>0
            a=cset(left);
            b=cset(right);
            c=a.product_of_sameEmptyness(b);
            if (c.size()>0) break;
            throw x.boundingTypeError("-> cannot be used to combine empty and non-empty types!",a,b);
        case DOMAIN:
            // RESULT TYPE = { (B1&A)->B2->B3 | exists unary A in left, exists B1->B2->B3 in right } if nonempty()
            a=cset(left);
            b=cset(right);
            c=b.domainRestrict(a);
            if (c.hasTuple()) break;
            throw x.boundingTypeError("<: failed because left and domain[right] are always disjoint!",a,b);
        case RANGE:
            // RESULT TYPE = { A1->A2->(A3&B) | exists unary B in right, exists A1->A2->A3 in left } if nonempty()
            a=cset(left);
            b=cset(right);
            c=a.rangeRestrict(b);
            if (c.hasTuple()) break;
            throw x.boundingTypeError(":> failed because range(left) and right are always disjoint!",a,b);
        case IN:
            // SUCCESS if exists a in left, and b in right, such that (a.arity==b.arity && a&b!=NONE)
            a=cset(left);
            b=cset(right);
            c=a.intersect(b);
            if (c.size()==0 || (a.hasTuple() && b.hasTuple() && c.hasNoTuple()))
                throw x.boundingTypeError("Subset operator is redundant, because the types are always disjoint!",a,b);
            if (a.hasNoTuple())
                throw x.boundingTypeError("Subset operator is redundant, because the left-hand-side expression is always empty!",a,b);
            c=Type.FORMULA;
            break;
        case EQUALS:
            // SUCCESS if exists a in left, and b in right, such that (a.arity==b.arity && a&b!=NONE)
            a=left.type;
            b=right.type;
            c=a.intersect(b);
            if (c.size()!=0 && (a.hasNoTuple() || b.hasNoTuple() || c.hasTuple())) {c=Type.FORMULA; break;}
            if (a.isInt && b.isInt) { c=Type.FORMULA; break; }
            throw x.boundingTypeError("= can be used only between 2 nondisjoint sets and relations, or 2 integer expressions!",a,b);
        }
        if (c==null) throw x.internalError("Unexpected operator ("+x.op+") encountered in ExprBinary typechecker!");
        return x.op.make(x.pos, left, right, c);
    }

    //============================================================//
    /** Method that typechecks an ExprBinary object. Second pass. */
    //============================================================//

    public final Expr visit(ExprBinary x, Type p) {
        if (cached(x)) return x;
        Type a=x.left.type, b=x.right.type;
        switch(x.op) {
        case IN: case EQUALS:
        case AND: case OR: case IFF: case IMPLIES:
        case LT: case LTE: case GT: case GTE: {
            if (x.op==ExprBinary.Op.IN) b=a.intersect(b);
            if (!p.isBool) throw x.typeError("This must be a set or relation!");
            break;
        }
        case INTERSECT: {
            // leftType'=parentType & leftType.  rightType'=parentType & rightType.
            if (p.size()==0) throw x.typeError("This must be a set or relation!");
            a=p.intersect(a); b=p.intersect(b);
            break;
        }
        case MINUS: {
            if (p.isInt && p.size()>0) throw x.typeError("This expression is ambiguous! Possible type(s) include: "+p);
            if (p.isInt) { a=Type.INT; b=Type.INT; break; }
            // leftType'=parentType.   rightType'=parentType & rightType.
            if (p.size()==0) throw x.typeError("This must be an integer, a set or a relation!");
            a=p;
            b=p.intersect(b);
            if (b.hasNoTuple()) throw x.relevantTypeError("Inessential difference (right expression is redundant)",a,b);
            break;
        }
        case PLUS: {
            if (p.isInt && p.size()>0) throw x.typeError("This expression is ambiguous! Possible type(s) include: "+p);
            if (p.isInt) { a=Type.INT; b=Type.INT; break; }
            if (p.size()==0) throw x.typeError("This must be an integer, a set or a relation!");
            // Intentional fall-through to the PLUSPLUS case
        }
        case PLUSPLUS: {
            // If child.type & parent.type is empty, an inessential union error is reported.
            // Otherwise, child.type := child.type & parent.type
            // ALSO: for OVERRIDE, make sure the essential left and right types are override-compatible
            if (p.size()==0) throw x.typeError("This must be a set or a relation!");
            a=p.intersect(a); if (a.hasNoTuple()) throw x.relevantTypeError("Inessential union: the left expression is redundant",a,b);
            b=p.intersect(b); if (b.hasNoTuple()) throw x.relevantTypeError("Inessential union: the right expression is redundant",a,b);
            if (x.op==ExprBinary.Op.PLUSPLUS && !b.canOverride(a)) throw x.relevantTypeError("Relevant types incompatible for relational override",a,b);
            break;
        }
        case ARROW: case ANY_ARROW_SOME: case ANY_ARROW_ONE: case ANY_ARROW_LONE:
        case SOME_ARROW_ANY: case SOME_ARROW_SOME: case SOME_ARROW_ONE: case SOME_ARROW_LONE:
        case ONE_ARROW_ANY: case ONE_ARROW_SOME: case ONE_ARROW_ONE: case ONE_ARROW_LONE:
        case LONE_ARROW_ANY: case LONE_ARROW_SOME: case LONE_ARROW_ONE: case LONE_ARROW_LONE: {
            // leftType'  == {r1 | r1 in leftType and there exists r2 in rightType such that r1->r2 in parentType}
            // rightType' == {r2 | r2 in rightType and there exists r1 in leftType such that r1->r2 in parentType}
            if (p.size()==0) throw x.typeError("This must be a set or a relation!");
            Type leftType = Type.make();
            Type rightType = Type.make();
            for (Type.Rel ar:a)
                for (Type.Rel br:b)
                    if (ar.isEmpty()==br.isEmpty() && p.hasArity(ar.arity()+br.arity()))
                        for (Type.Rel cr:p.intersect(Type.make(ar.product(br)))) {
                            if (cr.isEmpty()) continue;
                            List<ParaSig> bts = cr.basicTypes;
                            leftType = leftType.union(Type.make(bts,0,ar.arity()));
                            rightType = rightType.union(Type.make(bts,ar.arity(),bts.size()));
                        }
            a=leftType; b=rightType; break;
        }
        case DOMAIN: {
            // leftType' = {r1 | r1 in leftType and there exists r2 in rightType such that r1<:r2 in parentType}
            // rightType' = {r2 | r2 in rightType and there exists r1 in leftType such that r1<:r2 in parentType}
            if (p.size()==0) throw x.typeError("This must be a set or a relation!");
            Type leftType = Type.make();
            Type rightType = Type.make();
            for (Type.Rel ar:a) if (ar.arity()==1) for (Type.Rel br:b) if (p.hasArity(br.arity())) {
                Type.Rel r=br.columnRestrict(ar.basicTypes.get(0), 0);
                if (r.isEmpty()) continue;
                for (Type.Rel cr:p.intersect(Type.make(r))) {
                    List<ParaSig> bts = cr.basicTypes;
                    leftType = leftType.union(Type.make(bts, 0, 1));
                    rightType = rightType.union(Type.make(bts, 0, bts.size()));
                }
            }
            a=leftType; b=rightType; break;
        }
        case RANGE: {
            // leftType' = {r1 | r1 in leftType and there exists r2 in rightType such that r1:>r2 in parentType}
            // rightType' = {r2 | r2 in rightType and there exists r1 in leftType such that r1:>r2 in parentType}
            if (p.size()==0) throw x.typeError("This must be a set or a relation!");
            Type leftType = Type.make();
            Type rightType = Type.make();
            for (Type.Rel br:b) if (br.arity()==1) for (Type.Rel ar:a) if (p.hasArity(ar.arity())) {
                Type.Rel r=ar.columnRestrict(br.basicTypes.get(0), ar.arity()-1);
                if (r.isEmpty()) continue;
                for (Type.Rel cr:p.intersect(Type.make(r))) {
                    List<ParaSig> bts = cr.basicTypes;
                    leftType = leftType.union(Type.make(bts,0,bts.size()));
                    rightType = rightType.union(Type.make(bts,bts.size()-1, bts.size()));
                }
            }
            a=leftType; b=rightType; break;
        }
        }
        Expr left=x.left.accept(this,a);
        Expr right=x.right.accept(this,b);
        return x.op.make(x.pos, left, right, p);
    }

    //========================================================//
    /** Method that typechecks an ExprITE object. First pass. */
    //========================================================//

    public Expr visit(ExprITE x) {
        if (cached(x)) return x;
        Expr right=x.right.accept(this);
        Expr left=x.left.accept(this);
        Expr cond=x.cond.accept(this); cform(cond.type, cond);
        Type a=left.type, b=right.type, c=null;
        // RESULT TYPE = LEFT+RIGHT, if exists a in left, b in right, such that a.arity==b.arity
        if (a.size()>0 && b.size()>0 && a.hasCommonArity(b)) c=a.union(b);
        if (a.isInt && b.isInt) { if (c==null) c=Type.INT; else c=Type.makeInt(c); }
        if (a.isBool && b.isBool) { if (c==null) c=Type.FORMULA; else c=Type.makeBool(c); }
        if (c==null || isbad(c)) throw x.typeError("The THEN-clause and the ELSE-clause must match! Its THEN-clause has type "+a+" and the ELSE clause has type "+b);
        return new ExprITE(x.pos, cond, left, right, c);
    }

    //=========================================================//
    /** Method that typechecks an ExprITE object. Second pass. */
    //=========================================================//

    public final Expr visit(ExprITE x, Type p) {
        if (cached(x)) return x;
        Type a=x.left.type, b=x.right.type;
        resolved(p,x);
        if (p.size()>0) {
            // If child.type & parent.type is empty, an inessential union error is reported.
            // Otherwise, child.type := child.type & parent.type
            if (a.hasTuple()) {
                a=a.intersect(p);
                if (a.hasNoTuple()) throw x.typeError("Inessential If-Then-Else: the left expression is redundant");
            }
            if (b.hasTuple()) {
                b=b.intersect(p);
                if (b.hasNoTuple()) throw x.typeError("Inessential If-Then-Else: the right expression is redundant");
            }
        }
        Expr cond=x.cond.accept(this, x.cond.type);
        Expr left=x.left.accept(this, a);
        Expr right=x.right.accept(this, b);
        return new ExprITE(x.pos, cond, left, right, p);
    }

    //========================================================//
    /** Method that typechecks an ExprLet object. First pass. */
    //========================================================//

    public Expr visit(ExprLet x) {
        if (cached(x)) return x;
        Expr right=resolve(x.right);
        env.put(x.left, right.type);
        Expr sub=x.sub.accept(this);
        env.remove(x.left);
        if (isbad(sub.type)) throw sub.typeError("The body of a LET expression must be a set, an integer, or a formula!");
        return new ExprLet(x.pos, x.left, right, sub, sub.type);
    }

    //=========================================================//
    /** Method that typechecks an ExprITE object. Second pass. */
    //=========================================================//

    public Expr visit(ExprLet x, Type p) {
        if (cached(x)) return x;
        resolved(p,x);
        env.put(x.left, x.right.type);
        Expr sub=x.sub.accept(this,p);
        env.remove(x.left);
        return new ExprLet(x.pos, x.left, x.right, sub, p);
    }

    //=============================================================//
    /** Method that typechecks an ExprConstant object. First pass. */
    //=============================================================//

    public Expr visit(ExprConstant x) { return x; }

    //==============================================================//
    /** Method that typechecks an ExprConstant object. Second pass. */
    //==============================================================//

    public Expr visit(ExprConstant x, Type p) {
        if (cached(x)) return x;
        if (x.op==ExprConstant.Op.NUMBER) {
            if (!p.isInt) throw x.typeError("This must be an integer expression");
        } else if (x.op==ExprConstant.Op.IDEN) {
            if (p.arity()!=2) throw x.typeError("This must be a binary relation.");
        } else {
            if (p.arity()!=1) throw x.typeError("This must be a set.");
        }
        return x;
    }

    //==========================================================//
    /** Method that typechecks an ExprQuant object. First pass. */
    //==========================================================//

    public Expr visit(ExprQuant x) {
        if (cached(x)) return x;
        List<VarDecl> list=new ArrayList<VarDecl>();
        Type comp=null; // Stores the Union Type for a Set Comprehension expression
        for(int i=0;i<x.list.size();i++) {
            VarDecl d=x.list.get(i);
            VarDecl dd=new VarDecl(d.pos, d, addOne(resolve(d.value)));
            Expr v=dd.value;
            if (v.type.size()==0) cset(v);
            if (v.type.hasNoTuple()) throw v.typeError("This expression must not be an empty set!");
            if (x.op==ExprQuant.Op.COMPREHENSION) {
                if (v.type.arity()!=1) throw v.typeError("This expression must be a unary set!");
                for(int j=0; j<d.names.size(); j++) if (comp==null) comp=v.type; else comp=comp.product_of_sameEmptyness(v.type);
            }
            for(String j:d.names) env.put(j, v.type);
            list.add(dd);
        }
        Expr sub=x.sub.accept(this);
        for(int i=0; i<list.size(); i++) {
            VarDecl d=list.get(i);
            for(String j:d.names) env.remove(j);
        }
        if (x.op==ExprQuant.Op.COMPREHENSION) {
            if (comp==null || comp.hasNoTuple()) throw x.typeError("This set comprehension expression is always empty!");
            cform(sub.type, sub);
        }
        else if (x.op==ExprQuant.Op.SUM) { cint(sub); comp=Type.INT; }
        else { cform(sub.type, sub); comp=Type.FORMULA; }
        return x.op.make(x.pos, list, sub, comp);
    }

    //===========================================================//
    /** Method that typechecks an ExprQuant object. Second pass. */
    //===========================================================//

    public Expr visit(ExprQuant x, Type p) {
        if (cached(x)) return x;
        resolved(p,x);
        for(VarDecl d:x.list) {
            for(String j:d.names) env.put(j, d.value.type);
        }
        Expr sub=x.sub.accept(this, x.sub.type);
        for(VarDecl d:x.list) {
            for(String j:d.names) env.remove(j);
        }
        return x.op.make(x.pos, x.list, sub, p);
    }

    //=============================================================//
    /** Method that typechecks an ExprSequence object. First pass. */
    //=============================================================//

    public Expr visit(ExprSequence x) {
        if (cached(x)) return x;
        List<Expr> list=new ArrayList<Expr>();
        for(int i=0; i<x.list.size(); i++) {
            Expr newvalue=x.list.get(i).accept(this);
            cform(newvalue.type, newvalue);
            list.add(newvalue);
        }
        return new ExprSequence(x.pos, list);
    }

    //==============================================================//
    /** Method that typechecks an ExprSequence object. Second pass. */
    //==============================================================//

    public Expr visit(ExprSequence x, Type t) {
        if (cached(x)) return x;
        List<Expr> list=new ArrayList<Expr>();
        cform(t,x);
        for(int i=0; i<x.list.size(); i++) {
            Expr sub=x.list.get(i);
            if (sub==null) break;
            list.add(sub.accept(this, sub.type));
        }
        return new ExprSequence(x.pos, list);
    }

    //==========================================================//
    /** Method that typechecks an ExprUnary object. First pass. */
    //==========================================================//

    public Expr visit(ExprUnary x) {
        if (cached(x)) return x;
        Type ans=null;
        Expr sub=x.sub.accept(this);
        switch(x.op) {

        case NOT:
            cform(sub.type, sub); ans=Type.FORMULA; break;

        case SOMEMULT: case LONEMULT: case ONEMULT: case SETMULT:
            cset(sub); ans=sub.type; break;

        case SOME: case LONE: case ONE: case NO:
            cset(sub); ans=Type.FORMULA; break;

        case TRANSPOSE:
            cset(sub); ans=sub.type.transpose(); break;

        case RCLOSURE: case CLOSURE:
            // TYPE(^X) = ^TYPE(X)
            // TYPE(^X) = ^TYPE(X) + UNIV->UNIV
            // If TYPE(X) doesn't contain at least one Relation Type of arity 2, report an error!
            // If size of ^TYPE(X).^TYPE(X) is 0, report an error!
            cset(sub); if (!sub.type.hasArity(2)) throw sub.typeError("This expression's arity must be 2!");
            ans=sub.type.closure();
            if (ans.join(ans).size()==0) throw x.typeError("redundant closure operation (domain and range are sdoint)");
            if (x.op==ExprUnary.Op.RCLOSURE) ans=ans.union(Type.make(ParaSig.UNIV,ParaSig.UNIV));
            break;

        case CARDINALITY:
            cset(sub); ans=Type.INT; break;

        case INTTOATOM:
            cint(sub); ans=ParaSig.SIGINT.type; break;

        case SUM:
            // Report an error if TYPE(Subexpression) has empty intersection with SIGINT
            cset(sub); if (sub.type.intersect(ParaSig.SIGINT.type).hasTuple()) {ans=Type.INT; break;}
            throw sub.typeError("This expression must contain integer atoms! Instead, its possible type(s) are: "+x.sub.type);
        }
        return x.op.make(x.pos, sub, ans);
    }

    //===========================================================//
    /** Method that typechecks an ExprUnary object. Second pass. */
    //===========================================================//

    public final Expr visit(ExprUnary x, Type p) {
        if (cached(x)) return x;
        Type subtype=x.sub.type;
        resolved(p,x);
        switch(x.op) {
        case SOMEMULT: case LONEMULT: case ONEMULT: case SETMULT:
            cset(p,x); subtype=p;
            break;
        case TRANSPOSE:
            // exprType' = {r1 | r1 in exprType AND ~r1 in unaryExprType}
            cset(p,x); subtype=subtype.transpose().intersect(p).transpose();
            if (p.hasTuple() && subtype.hasNoTuple()) throw x.sub.typeError("Subexpression does not contribute to relevant type of parent");
            break;
        case RCLOSURE: case CLOSURE:
            // exprType' = {r1 | r1 in exprType AND there exist basic types
            // b1 and b2 such that b1->b2 in unaryExprType AND r1 is on the path from b1 to b2}
            cset(p,x); subtype = closureResolveChild(p, subtype);
            if (p.hasTuple() && subtype.hasNoTuple()) throw x.sub.typeError("Subexpression does not contribute to relevant type of parent");
            break;
        case INTTOATOM:
            cset(p,x); if (!p.isSubsetOf(ParaSig.SIGINT.type)) throw x.typeError("This expression should have been a subset of Int!");
            break;
        }
        Expr sub=x.sub.accept(this, subtype);
        return x.op.make(x.pos, sub, p);
    }

    /**
     * childType := { c1->c2 | c1->c2 in childType, AND exists p1->p2 in parentType
     *                 where p1..c1..c2..p2 is a path in closure graph }
     */
    private static Type closureResolveChild (Type parent, Type child) {
        Type answer=Type.make();
        if (parent.size()==0) return answer;
        IdentitySet<ParaSig> nodes=new IdentitySet<ParaSig>();
        DirectedGraph<ParaSig> graph=new DirectedGraph<ParaSig>();
        // For each (v1->v2) in childType, add (v1->v2) into the graph.
        for (Type.Rel c:child) if (c.arity()==2) {
            ParaSig a=c.basicTypes.get(0); if (!nodes.contains(a)) nodes.add(a);
            ParaSig b=c.basicTypes.get(1); if (!nodes.contains(b)) nodes.add(b);
            graph.addEdge(a,b);
        }
        // For each distinct v1 and v2 in the graph where v1&v2!=empty, add the edges v1->v2 and v2->v1.
        for (ParaSig a:nodes)
            for (ParaSig b:nodes)
                if (a!=b && a.intersect(b).isNonEmpty()) graph.addEdge(a,b);
        // For each ParaSig x in ParentType, if x has subtypes/supertypes in the graph, then connect them.
        for (Type.Rel p:parent) {
            ParaSig a=p.basicTypes.get(0);
            ParaSig b=p.basicTypes.get(1);
            // Add edges between a and all its subtypes and supertypes
            if (!nodes.contains(a)) {
                for (ParaSig other:nodes)
                    if (a!=other && !a.intersect(other).isEmpty())
                       { graph.addEdge(a,other); graph.addEdge(other,a); }
                nodes.add(a);
            }
            // Add edges between b and all its subtypes and supertypes
            if (!nodes.contains(b)) {
                for (ParaSig other:nodes)
                    if (b!=other && !b.intersect(other).isEmpty())
                       { graph.addEdge(b,other); graph.addEdge(other,b); }
                nodes.add(b);
            }
        }
        // For each c1->c2 in childType, add c1->c2 into the finalType
        // if there exists p1->p2 in parentType such that p1->..->c1->c2->..->p2 is a path in the graph.
        for (Type.Rel c:child) {
            ParaSig c1=c.basicTypes.get(0);
            ParaSig c2=c.basicTypes.get(1);
            for (Type.Rel p:parent) {
                ParaSig p1=p.basicTypes.get(0);
                ParaSig p2=p.basicTypes.get(1);
                if (graph.hasPath(p1,c1) && graph.hasPath(c2,p2)) { answer=answer.union(Type.make(c)); break; }
            }
        }
        return answer;
    }

    //=========================================================//
    /** Method that typechecks an ExprName object. First pass. */
    //=========================================================//

    public Expr visit(ExprName x) {
        if (cached(x)) return x;
        Type t=null,tt;
        List<Expr> objects=new ArrayList<Expr>();
        if (env.has(x.name)) {
            t=env.get(x.name);
            if (t!=null) objects.add(new ExprName(x.pos, x.name, null, t));
        }
        else {
            Set<Object> choices=rootunit.populate(rootfield, rootsig, rootfun, x.pos, x.name);
            if (choices.size()==0) ExprName.hint(x.pos, x.name);
            for(Object ch:choices) if (ch instanceof Expr) {
                objects.add((Expr)ch); tt=((Expr)ch).type; if (t==null) t=tt; else t=t.merge(tt);
            }
        }
        if (t==null || isbad(t)) throw x.typeError("The name \""+x.name+"\" failed to be typechecked here!");
        ExprName ans=new ExprName(x.pos, x.name, null, t);
        objChoices.put(ans, objects);
        return ans;
    }

    //==========================================================//
    /** Method that typechecks an ExprName object. Second pass. */
    //==========================================================//

    public Expr visit(ExprName x, Type t) {
        if (cached(x)) return x;
        resolved(t,x);
        List<Expr> choices=objChoices.get(x);
        objChoices.remove(x);
        if (choices==null) throw x.internalError("Unknown ExprName object encountered: "+x);
        Expr match=null;
        for(Expr ch:choices) {
            Type tt=ch.type;
            if (t==tt
                    ||(!t.isInt && !t.isBool && t.hasNoTuple())
                    ||(t.isInt && tt.isInt)
                    ||(t.isBool && tt.isBool)
                    ||t.intersect(tt).hasTuple()) {
                if (match!=null) throw x.typeError("The name \""+x.name+"\" is ambiguous here due to multiple match: "+match+" and "+ch);
                match=ch;
            }
        }
        if (match!=null) return match;
        throw x.typeError("The name \""+x.name+"\" failed to be typechecked here due to no match!");
    }

    //=========================================================//
    /** Method that typechecks an ExprJoin object. First pass. */
    //=========================================================//

    private List<Expr> resolveArgs(Expr x) {
        List<Expr> args=new ArrayList<Expr>();
        for(Expr ptr=x; ptr instanceof ExprJoin; ptr=((ExprJoin)ptr).right) {
            Expr left=((ExprJoin)ptr).left;
            cset(left=resolve(left));
            args.add(0,left);
        }
        return args;
    }

    public Expr visit(ExprJoin x) {
        // This is not optimal. eg. given b.a.(func[x,y,z]), "a" and "b" will be forced to be locally-unambiguous.
        // Another inefficiency: we don't jump forward, so sublists are re-Desugared again and again until the end of list.
        if (cached(x)) return x;
        int n=1;
        Expr ptr=x.right;
        while(ptr instanceof ExprJoin) { n++; ptr=((ExprJoin)ptr).right; }
        if (ptr instanceof ExprName && !env.has(((ExprName)ptr).name)) {
            String name=((ExprName)ptr).name;
            if (n==1 && name.equals("int")) {
                // Handles the builtin function int[] aka sum[]
                Expr arg1=resolveArgs(x).get(0);
                cset(arg1);
                if (arg1.type.intersect(ParaSig.SIGINT.type).hasTuple())
                    return cache(ExprUnary.Op.SUM.make(ptr.pos, arg1, Type.INT));
                throw arg1.typeError("This expression must contain integer atoms! Instead, its possible type(s) are: "+arg1.type);
            }
            if (n>1 && name.equals("disj")) {
                // Handles the builtin function disj[]
                List<Expr> args=resolveArgs(x);
                int arity = -1;
                for(Expr a:args) {
                    cset(a);
                    if (arity<0) arity=a.type.arity();
                    if (arity!=a.type.arity()) throw x.typeError("The builtin disj[] predicate can only accept arguments of the same arity!");
                }
                Expr answer=Expr.TRUE(x.pos);
                for(int ai=0; ai<args.size(); ai++) {
                  Expr a=args.get(ai);
                  for(int bi=ai+1; bi<args.size(); bi++) {
                      Expr b=args.get(bi);
                      answer=a.disj(b).and(answer);
                  }
                }
                return cache(answer);
            }
            Set<Object> choices=rootunit.populate(rootfield, rootsig, rootfun, ptr.pos, name);
            List<Expr> objects=new ArrayList<Expr>();
            if (containsApplicable(choices,n)) {
                List<Expr> args=resolveArgs(x);
                Type ans=null;
                for(Object ch:choices) {
                    Expr resultexpr;
                    int i=0;
                    if (ch instanceof Expr) resultexpr=(Expr)ch; else {
                        ParaFun f=(ParaFun)ch;
                        if (f.getArgCount()>args.size()) continue;
                        if (f.getArgCount()<args.size() && f.getType()==null) continue;
                        if (!applicable(f,args)) continue;
                        i=f.getArgCount();
                        resultexpr=new ExprCall(ptr.pos, f, args.subList(0,i), (f.getType()==null?Type.FORMULA:f.getType().type));
                    }
                    for(; i<args.size(); i++) resultexpr=args.get(i).relationalJoinUnchecked(resultexpr);
                    Type temp=resultexpr.type;
                    if (temp!=Type.FORMULA && temp.hasNoTuple()) continue;
                    objects.add(resultexpr);
                    if (ans==null) ans=temp; else ans=ans.merge(temp);
                }
                if (ans!=null) { Expr xx=new ExprName(x.pos, name, null, ans); objChoices.put(xx,objects); return xx; }
            }
        }
        // TYPE[A.B] = TYPE[A].TYPE[B] if there exist r1 in TYPE[A] and r2 in TYPE[B],
        // such that r1.arity+r2.arity>2, and range(r1)&dom(r2) nonempty.
        Expr left=x.left.accept(this);
        Expr right=x.right.accept(this);
        if (left.type.isInt && right instanceof ExprConstant && ((ExprConstant)(right)).op==ExprConstant.Op.SIGINT) {
            // Handles the builtin function Int[]
            left=resolve(x.left);
            if (left.type.isInt) return cache(ExprUnary.Op.INTTOATOM.make(right.pos, left, ParaSig.SIGINT.type));
        }
        cset(left);
        cset(right);
        Expr newx=new ExprJoin(x.pos, left, right, left.type.join(right.type));
        if (newx.type.hasNoTuple()) throw newx.typeError("The join operation here always yields an empty set! LeftType="+left.type+" RightType="+right.type);
        return newx;
    }

    private boolean applicable(ParaFun f, List<Expr> args) {
        int argi=0;
        for(VarDecl d:f.getDecls()) for(int j=0; j<d.names.size(); j++) {
            Type arg=args.get(argi).type;
            argi++;
            if (arg.size()==0) continue;
            if (d.value.type.size()==0) continue; // This should not happen, though.
            if (arg.hasNoTuple() || d.value.type.hasNoTuple()) if (arg.arity()==d.value.type.arity()) continue;
            if (arg.intersect(d.value.type).hasTuple()) continue;
            return false;
        }
        return true;
    }

    private boolean containsApplicable(Set<Object> x, int n) {
        for(Object y:x) if (y instanceof ParaFun && ((ParaFun)y).getArgCount()==n) return true;
        return false;
    }

    //==========================================================//
    /** Method that typechecks an ExprJoin object. Second pass. */
    //==========================================================//

    public Expr visit(ExprJoin x, Type p) {
        if (cached(x)) return x;
        // leftType' = {r1 | r1 in leftType and there exists r2 in rightType such that r1.r2 in parentType}
        // rightType' = {r2 | r2 in rightType and there exists r1 in leftType such that r1.r2 in parentType}
        Type leftType = Type.make();
        Type rightType = Type.make();
        for (Type.Rel a: x.left.type)
            for (Type.Rel b: x.right.type)
                if (p.hasArity(a.arity() + b.arity() - 2)) {
                    ParaSig joinType = a.basicTypes.get(a.arity()-1).intersect(b.basicTypes.get(0));
                    if (joinType.isEmpty()) continue;
                    for (Type.Rel c: p.intersect(Type.make(a.join(b)))) {
                        if (c.isEmpty()) continue;
                        List<ParaSig> bts = new LinkedList<ParaSig>(c.basicTypes);
                        bts.add(a.arity()-1, joinType);
                        leftType = leftType.union(Type.make(bts, 0, a.arity()));
                        rightType = rightType.union(Type.make(bts, a.arity()-1, bts.size()));
                    }
                }
        Expr left=x.left.accept(this,leftType);
        Expr right=x.right.accept(this,rightType);
        return new ExprJoin(x.pos, left, right, p);
    }

    //=========================================================//
    /** Method that typechecks an ExprCall object. First pass. */
    //=========================================================//

    public Expr visit(ExprCall x) {
        throw x.internalError("ExprCall objects shouldn't be encountered during the first pass!");
    }

    //==========================================================//
    /** Method that typechecks an ExprCall object. Second pass. */
    //==========================================================//

    public Expr visit(ExprCall x, Type t) {
        throw x.internalError("ExprCall objects shouldn't be encountered during the second pass!");
    }

    //==========================================================//
    // ---                  The End                             //
    //==========================================================//
}