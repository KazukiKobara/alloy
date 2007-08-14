/*
 * Alloy Analyzer
 * Copyright (c) 2007 Massachusetts Institute of Technology
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA,
 * 02110-1301, USA
 */

package edu.mit.csail.sdg.alloy4compiler.parser;

import java.util.List;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.alloy4.ErrorWarning;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.alloy4compiler.ast.Expr;
import edu.mit.csail.sdg.alloy4compiler.ast.ExprBinary.Op;

/** Immutable; represents an expression of the form (x OP y). */

final class ExpBinary extends Exp {

    /** The binary operator. */
    public final Op op;

    /** The left-hand side. */
    public final Exp left;

    /** The right-hand side. */
    public final Exp right;

    /** Constructs a binary expression. */
    public ExpBinary(Pos pos, Op op, Exp left, Exp right) {
        super(pos);
        this.op=op;
        this.left=left;
        this.right=right;
    }

    /** Caches the span() result. */
    private Pos span=null;

    /** {@inheritDoc} */
    public Pos span() {
        Pos p=span;
        if (p==null) { p=pos.merge(left.span()).merge(right.span()); span=p; }
        return p;
    }

    /** {@inheritDoc} */
    public Expr check(Context cx, List<ErrorWarning> warnings) throws Err {
        Expr a=left.check(cx, warnings);
        Expr b=right.check(cx, warnings);
        return op.make(pos, a, b);
    }
}
