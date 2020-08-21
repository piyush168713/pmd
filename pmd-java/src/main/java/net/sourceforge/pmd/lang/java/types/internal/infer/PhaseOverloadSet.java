/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.types.internal.infer;

import static net.sourceforge.pmd.lang.java.types.TypeOps.areOverrideEquivalent;
import static net.sourceforge.pmd.util.OptionalBool.NO;
import static net.sourceforge.pmd.util.OptionalBool.UNKNOWN;
import static net.sourceforge.pmd.util.OptionalBool.YES;
import static net.sourceforge.pmd.util.OptionalBool.definitely;

import java.util.List;

import org.checkerframework.checker.nullness.qual.NonNull;

import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.JTypeVar;
import net.sourceforge.pmd.lang.java.types.Substitution;
import net.sourceforge.pmd.lang.java.types.TypeConversion;
import net.sourceforge.pmd.lang.java.types.TypeOps;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.BranchingMirror;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.InvocationMirror.MethodCtDecl;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.LambdaExprMirror;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.MethodRefMirror;
import net.sourceforge.pmd.util.OptionalBool;

/**
 * An {@link OverloadSet} that is specific to an invocation expression
 * and resolution phase. It selects the most specific methods using the
 * actual values of arguments, as specified in https://docs.oracle.com/javase/specs/jls/se8/html/jls-15.html#jls-15.12.2.5.
 */
final class PhaseOverloadSet extends OverloadSet<MethodCtDecl> {

    private final Infer infer;
    private final MethodResolutionPhase phase;
    private final MethodCallSite site;


    PhaseOverloadSet(Infer infer, MethodResolutionPhase phase, MethodCallSite site) {
        this.infer = infer;
        this.phase = phase;
        this.site = site;
    }

    public MethodResolutionPhase getPhase() {
        return phase;
    }

    public MethodCallSite getSite() {
        return site;
    }

    public Infer getInfer() {
        return infer;
    }

    /**
     * It's a given that the method is applicable to the site.
     *
     * <p>https://docs.oracle.com/javase/specs/jls/se8/html/jls-15.html#jls-15.12.2.5
     */
    @Override
    void add(MethodCtDecl sig) {
        super.add(sig);
    }

    public @NonNull MethodCtDecl getMostSpecificOrLogAmbiguity(TypeInferenceLogger logger) {
        assert !isEmpty();
        List<MethodCtDecl> overloads = getOverloadsMutable();
        MethodCtDecl main = overloads.get(0);
        if (overloads.size() != 1) {
            logger.ambiguityError(site, main, overloads);
            main = main.asFailed();
        }
        return main;
    }

    @Override
    protected OptionalBool shouldTakePrecedence(MethodCtDecl m1, MethodCtDecl m2) {
        return isMoreSpecific(m1.getMethodType().internalApi().adaptedMethod(),
                              m2.getMethodType().internalApi().adaptedMethod());
    }


    private OptionalBool isMoreSpecific(@NonNull JMethodSig m1, @NonNull JMethodSig m2) {

        OptionalBool m1OverM2 = isMoreSpecificForExpr(m1, m2);

        if (m1OverM2.isKnown()) {
            return m1OverM2;
        } else if (areOverrideEquivalent(m1, m2)) {
            return OverloadSet.shouldAlwaysTakePrecedence(m1, m2, site.getExpr().getReceiverType());
        }

        return UNKNOWN;
    }

    private OptionalBool isMoreSpecificForExpr(JMethodSig m1, JMethodSig m2) {
        if (!m1.isGeneric() && !m2.isGeneric()) {
            return isMoreSpecificNonGeneric(m1, m2);
        } else if (m1.isGeneric() && m2.isGeneric()) {
            boolean m1OverM2 = isInferredMoreSpecific(m1, m2);
            boolean m2OverM1 = isInferredMoreSpecific(m2, m1);
            if (m1OverM2 ^ m2OverM1) {
                return definitely(m1OverM2);
            }
            return UNKNOWN;
        }

        OptionalBool m1OverM2 = isMoreSpecificNonGeneric(m1, m2);
        if (m2.isGeneric()) {
            return inferMoreSpecific(m1, m2, m1OverM2);
        } else {
            return inferMoreSpecific(m2, m1, m1OverM2.complement()).complement();
        }
    }


    // YES if m1 is more specific than m2
    // NO if m2 is more specific than m1
    // UNKNOWN if neither is more specific than the other
    private OptionalBool isMoreSpecificNonGeneric(JMethodSig m1, JMethodSig m2) {
        List<JTypeMirror> m1Formals = m1.getFormalParameters();
        List<JTypeMirror> m2Formals = m2.getFormalParameters();
        List<ExprMirror> args = site.getExpr().getArgumentExpressions();
        int k = args.size();
        int numKnownForM1 = 0;
        int numKnownForM2 = 0;
        for (int i = 0; i < k; i++) {
            JTypeMirror si = phase.ithFormal(m1Formals, i);
            JTypeMirror ti = phase.ithFormal(m2Formals, i);
            ExprMirror argi = args.get(i);

            // todo no need to do this twice, the method is asymmetric
            OptionalBool sOverT = isTypeMoreSpecificForArg(si, ti, argi);
            OptionalBool tOverS = isTypeMoreSpecificForArg(ti, si, argi);
            if (sOverT.isKnown() && tOverS.isKnown() && sOverT != tOverS) {
                return sOverT;
            }
            if (sOverT == YES) {
                numKnownForM1++;
            } // no else
            if (tOverS == YES) {
                numKnownForM2++;
            }
        }

        if (numKnownForM1 != numKnownForM2) {
            return definitely(numKnownForM1 > numKnownForM2);
        }

        if (phase.requiresVarargs() && m2Formals.size() == k + 1) {
            // if the varargs argument has length 0, then the last
            // formal of m1 must be more specific than the last formal of m2
            JTypeMirror last1 = phase.ithFormal(m1Formals, k);
            JTypeMirror last2 = m2Formals.get(k);
            if (last1.equals(last2)) { // only check for strict subtype
                return UNKNOWN;
            }
            return definitely(last1.isSubtypeOf(last2));
        }

        return UNKNOWN;
    }

    // YES if si is more specific than ti
    // NO if ti is more specific than si
    // UNKNOWN if neither is more specific than the other.
    //  Note that this includes the case where the argument
    //  expression's type was unresolved, which means it is
    //  compatible with any type and doesn't contribute information
    //  for specificity.
    private OptionalBool isTypeMoreSpecificForArg(JTypeMirror si, JTypeMirror ti, ExprMirror argExpr) {
        if (si.equals(ti)) {
            return UNKNOWN;
        }

        JTypeMirror standalone = argExpr.getStandaloneType();
        if (standalone != null && TypeOps.isUnresolved(standalone)) {
            if (standalone.equals(si)) {
                return YES;
            } else if (standalone.equals(ti)) {
                return NO;
            }
            return UNKNOWN;
        }

        // A type S is more specific than a type T for any expression if S <: T (§4.10).
        {
            // we know that both cannot be true at the same time as si != ti
            boolean isSubtype = si.isConvertibleTo(ti).somehow();
            if (isSubtype) {
                return YES;
            }
            isSubtype = ti.isConvertibleTo(si).somehow();
            if (isSubtype) {
                return NO;
            }
        }

        // TODO checks for lambdas/method refs are much more complicated
        return UNKNOWN;
    }


    private OptionalBool inferMoreSpecific(JMethodSig m1, JMethodSig m2, OptionalBool m1OverM2) {
        assert m2.isGeneric();
        boolean m1MoreSpecific = isInferredMoreSpecific(m1, m2);
        if (m1MoreSpecific && m1OverM2 != NO) {
            return YES;
        }
        return UNKNOWN;
    }


    private boolean isInferredMoreSpecific(JMethodSig m1, JMethodSig m2) {
        // https://docs.oracle.com/javase/specs/jls/se8/html/jls-18.html#jls-18.5.4
        try {
            return doInfer(m1, m2);
        } catch (ResolutionFailedException e) {
            return false;
        }
    }

    private boolean doInfer(JMethodSig m1, JMethodSig m2) {
        InferenceContext ctx = infer.newContextFor(m2);

        // even if m1 is generic, the type parameters of m1 are treated as type variables, not inference variables.

        JMethodSig m2p = ctx.mapToIVars(m2);

        List<ExprMirror> es = site.getExpr().getArgumentExpressions();
        List<JTypeMirror> m1Formals = m1.getFormalParameters();
        List<JTypeMirror> m2Formals = m2p.getFormalParameters();

        int k = es.size();

        for (int i = 0; i < k; i++) {
            JTypeMirror ti = phase.ithFormal(m2Formals, i);
            JTypeMirror si = phase.ithFormal(m1Formals, i);
            ExprMirror ei = es.get(i);

            OptionalBool siOverTi = isTypeMoreSpecificForArg(si, ti, ei);
            if (siOverTi == NO) {
                return false;
            } else if (siOverTi == UNKNOWN) {
                JMethodSig sfun = TypeOps.findFunctionalInterfaceMethod(si);
                JMethodSig tfun = TypeOps.findFunctionalInterfaceMethod(ti);
                if (sfun == null || tfun == null) {
                    infer.checkConvertibleOrDefer(ctx, si, ti, ei, phase, null);
                    continue;
                }


                // otherwise they're both functional interfaces
                if (!isFunctionTypeMoreSpecific(ctx, si, sfun, tfun, ei)) {
                    return false;
                }
            }
        }

        if (phase.requiresVarargs() && m2Formals.size() == k + 1) {
            // that is, the invocation has no arguments for the varargs, eg Stream.of()
            infer.checkConvertibleOrDefer(ctx, phase.ithFormal(m1Formals, k), m2Formals.get(k), site.getExpr(), phase, null);
        }

        ctx.solve();
        ctx.callListeners();

        return true;
    }

    private boolean isFunctionTypeMoreSpecific(InferenceContext ctx,
                                               JTypeMirror si,
                                               JMethodSig sfun,
                                               JMethodSig tfun,
                                               ExprMirror ei) {
        if (sfun.getArity() != tfun.getArity()
            || sfun.getTypeParameters().size() != tfun.getTypeParameters().size()) {
            return false;
        }

        // Note that the following is not implemented entirely

        // The rest is described in https://docs.oracle.com/javase/specs/jls/se13/html/jls-18.html#jls-18.5.4

        JMethodSig capturedSFun = TypeOps.findFunctionalInterfaceMethod(TypeConversion.capture(si));
        assert capturedSFun != null;

        if (!TypeOps.haveSameTypeParams(capturedSFun, sfun)) {
            return false;
        }


        List<JTypeVar> sparams = sfun.getTypeParameters();
        List<JTypeVar> tparams = tfun.getTypeParameters();
        Substitution tToS = Substitution.mapping(tparams, sparams);
        for (int j = 0; j < sparams.size(); j++) {
            JTypeVar aj = sparams.get(j);
            JTypeVar bj = tparams.get(j);

            JTypeMirror x = aj.getUpperBound();
            JTypeMirror y = bj.getUpperBound();
            if (TypeOps.mentionsAny(x, sfun.getTypeParameters()) && !ctx.isGround(y)) {
                return false;
            } else {
                TypeOps.isSameType(x, y.subst(tToS), true); // adds an equality constraint
            }
        }

        // todo something about formal params

        JTypeMirror rs = sfun.getReturnType();
        JTypeMirror rt = tfun.getReturnType();
        if (TypeOps.mentionsAny(rs, sparams) && !ctx.isGround(rt)) {
            return false;
        }

        return addGenericExprConstraintsRecursive(ctx, ei, rs, rt, tToS);
    }

    private boolean addGenericExprConstraintsRecursive(InferenceContext ctx, ExprMirror ei, JTypeMirror rs, JTypeMirror rt, Substitution tToS) {
        if (ei instanceof LambdaExprMirror) {
            // Otherwise, if ei is an explicitly typed lambda expression:
            //
            //    If RT is void, true.
            //
            //    todo: Otherwise, if RS and RT are functional interface types, and ei has at least one result expression, then for each result expression in ei, this entire second step is repeated to infer constraints under which RS is more specific than RT θ' for the given result expression.
            //
            //    Otherwise, if RS is a primitive type and RT is not, and ei has at least one result expression, and each result expression of ei is a standalone expression (§15.2) of a primitive type, true.
            //
            //    Otherwise, if RT is a primitive type and RS is not, and ei has at least one result expression, and each result expression of ei is either a standalone expression of a reference type or a poly expression, true.
            //
            //    Otherwise, ‹RS <: RT θ'›.

            LambdaExprMirror lambda = (LambdaExprMirror) ei;
            if (!lambda.isExplicitlyTyped()) {
                return false;
            }

            if (rt.isVoid()) {
                return true;
            } else if (rs.isVoid()) {
                return false;
            }

            if (rt.isPrimitive() ^ rs.isPrimitive()) {
                final boolean needsPrimitive = rs.isPrimitive();
                boolean ok = true;
                boolean atLeastOne = false;
                for (ExprMirror rexpr : lambda.getResultExpressions()) {
                    atLeastOne = true;
                    JTypeMirror std = rexpr.getStandaloneType();
                    // polys are treated as ref types
                    boolean hasPrimitive = std != null && std.isPrimitive();
                    ok &= needsPrimitive == hasPrimitive;
                }

                if (ok && atLeastOne) {
                    return true;
                }
            }

            infer.checkConvertibleOrDefer(ctx, rs, rt.subst(tToS), ei, phase, null);
            return true;
        } else if (ei instanceof MethodRefMirror) {
            //  Otherwise, if ei is an exact method reference:
            //
            //    If RT is void, true.
            //
            //    Otherwise, if RS is a primitive type and RT is not, and the compile-time declaration for ei has a primitive return type, true.
            //
            //    Otherwise if RT is a primitive type and RS is not, and the compile-time declaration for ei has a reference return type, true.
            //
            //    Otherwise, ‹RS <: RT θ'›.

            JMethodSig exact = ExprOps.getExactMethod((MethodRefMirror) ei);
            if (exact == null) {
                return false;
            }

            if (rt.isVoid()) {
                return true;
            } else if (rs.isVoid()) {
                return false;
            }

            if (rs.isPrimitive() ^ rt.isPrimitive()) {
                return exact.getReturnType().isPrimitive() == rs.isPrimitive();
            }

            infer.checkConvertibleOrDefer(ctx, rs, rt.subst(tToS), ei, phase, null);
            return true;
        } else if (ei instanceof BranchingMirror) {
            return ((BranchingMirror) ei).branchesMatch(e -> addGenericExprConstraintsRecursive(ctx, e, rs, rt, tToS));
        }

        return false;
    }

}
