package org.mvel;

import static org.mvel.DataConversion.canConvert;
import static org.mvel.Operator.*;
import static org.mvel.Soundex.soundex;
import org.mvel.ast.LineLabel;
import org.mvel.debug.Debugger;
import org.mvel.debug.Frame;
import org.mvel.integration.VariableResolverFactory;
import org.mvel.util.ExecutionStack;
import static org.mvel.util.ParseTools.containsCheck;
import static org.mvel.util.PropertyTools.isEmpty;
import static org.mvel.util.PropertyTools.similarity;
import org.mvel.util.Stack;
import org.mvel.util.StringAppender;

import static java.lang.Class.forName;
import static java.lang.String.valueOf;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * This class contains the runtime for running compiled MVEL expressions.
 */
public class MVELRuntime {
    private static ThreadLocal<Map<String, Set<Integer>>> threadBreakpoints;
    private static ThreadLocal<Debugger> threadDebugger;

    /**
     * Main interpreter.
     *
     * @param debugger        -
     * @param expression      -
     * @param ctx             -
     * @param variableFactory -
     * @return -
     * @see org.mvel.MVEL
     */
    public static Object execute(boolean debugger, CompiledExpression expression, Object ctx, VariableResolverFactory variableFactory) {
        final ASTLinkedList node = new ASTLinkedList(expression.getTokens().firstNode());

        Stack stk = new ExecutionStack();
        Object v1, v2;

        ASTNode tk = null;
        Integer operator;

        try {
            while ((tk = node.nextNode()) != null) {
                if (tk.fields == -1) {
                    /**
                     * This may seem silly and redundant, however, when an MVEL script recurses into a block
                     * or substatement, a new runtime loop is entered.   Since the debugger state is not
                     * passed through the AST, it is not possible to forward the state directly.  So when we
                     * encounter a debugging symbol, we check the thread local to see if there is are registered
                     * breakpoints.  If we find them, we assume that we are debugging.
                     *
                     * The consequence of this of course, is that it's not ideal to compile expressions with
                     * debugging symbols which you plan to use in a production enviroment.
                     */
                    if (!debugger && threadBreakpoints != null && threadBreakpoints.get() != null) {
                        debugger = true;
                    }

                    /**
                     * If we're not debugging, we'll just skip over this.
                     */
                    if (debugger) {
                        LineLabel label = (LineLabel) tk;

                        try {
                            if (threadBreakpoints != null
                                    && threadBreakpoints.get().get(label.getSourceFile()).contains(label.getLineNumber())) {

                                if (threadDebugger == null || threadDebugger.get() == null) {
                                    throw new RuntimeException("no debugger registered to handle breakpoint.");
                                }

                                threadDebugger.get()
                                        .onBreak(new Frame(label.getSourceFile(), label.getLineNumber(), variableFactory, expression.getParserContext()));
                            }
                        }
                        catch (NullPointerException e) {
                            // do nothing for now.  this isn't as calus as it seems.   
                        }
                    }
                    continue;
                }

                if (stk.isEmpty()) {
                    stk.push(tk.getReducedValueAccelerated(ctx, ctx, variableFactory));
                }

                if (!tk.isOperator()) {
                    continue;
                }

                switch (operator = tk.getOperator()) {
                    case TERNARY:
                        if (!(Boolean) stk.pop()) {
                            //noinspection StatementWithEmptyBody
                            while (node.hasMoreNodes() && !node.nextNode().isOperator(Operator.TERNARY_ELSE)) ;
                        }
                        stk.clear();
                        continue;

                    case TERNARY_ELSE:
                        return stk.pop();

                    case END_OF_STMT:
                        /**
                         * If the program doesn't end here then we wipe anything off the stack that remains.
                         * Althought it may seem like intuitive stack optimizations could be leveraged by
                         * leaving hanging values on the stack,  trust me it's not a good idea.
                         */
                        if (node.hasMoreNodes()) {
                            stk.clear();
                        }

                        continue;
                }

                stk.push(node.nextNode().getReducedValueAccelerated(ctx, ctx, variableFactory), operator);

                try {
                    while (stk.size() > 1) {
                        operator = (Integer) stk.pop();
                        v1 = stk.pop();
                        v2 = stk.pop();

                        switch (operator) {
                            case CHOR:
                                if (!isEmpty(v2) || !isEmpty(v1)) {
                                    stk.clear();
                                    stk.push(!isEmpty(v2) ? v2 : v1);
                                }
                                else stk.push(null);
                                break;

                            case INSTANCEOF:
                                if (v1 instanceof Class)
                                    stk.push(((Class) v1).isInstance(v2));
                                else
                                    stk.push(forName(valueOf(v1)).isInstance(v2));

                                break;

                            case CONVERTABLE_TO:
                                if (v1 instanceof Class)
                                    stk.push(canConvert(v2.getClass(), (Class) v1));
                                else
                                    stk.push(canConvert(v2.getClass(), forName(valueOf(v1))));
                                break;

                            case CONTAINS:
                                stk.push(containsCheck(v2, v1));
                                break;

                            case BW_AND:
                                stk.push((Integer) v2 & (Integer) v1);
                                break;

                            case BW_OR:
                                stk.push((Integer) v2 | (Integer) v1);
                                break;

                            case BW_XOR:
                                stk.push((Integer) v2 ^ (Integer) v1);
                                break;

                            case BW_SHIFT_LEFT:
                                stk.push((Integer) v2 << (Integer) v1);
                                break;

                            case BW_USHIFT_LEFT:
                                int iv2 = (Integer) v2;
                                if (iv2 < 0) iv2 *= -1;
                                stk.push(iv2 << (Integer) v1);
                                break;

                            case BW_SHIFT_RIGHT:
                                stk.push((Integer) v2 >> (Integer) v1);
                                break;

                            case BW_USHIFT_RIGHT:
                                stk.push((Integer) v2 >>> (Integer) v1);
                                break;

                            case STR_APPEND:
                                stk.push(new StringAppender(valueOf(v2)).append(valueOf(v1)).toString());
                                break;

                            case SOUNDEX:
                                stk.push(soundex(valueOf(v1)).equals(soundex(valueOf(v2))));
                                break;

                            case SIMILARITY:
                                stk.push(similarity(valueOf(v1), valueOf(v2)));
                                break;
                        }
                    }
                }
                catch (ClassCastException e) {
                    throw new CompileException("syntax error or incomptable types", e);
                }
                catch (Exception e) {
                    throw new CompileException("failed to subEval expression", e);
                }
            }

            return stk.peek();
        }
        catch (NullPointerException e) {
            if (tk != null && tk.isOperator() && !node.hasMoreNodes()) {
                throw new CompileException("incomplete statement: "
                        + tk.getName() + " (possible use of reserved keyword as identifier: " + tk.getName() + ")");
            }
            else {
                throw e;
            }
        }
    }

    /**
     * Register a debugger breakpoint.
     * 
     * @param source - the source file the breakpoint is registered in
     * @param line - the line number of the breakpoint
     */
    public static void registerBreakpoint(String source, int line) {
        if (threadBreakpoints == null) {
            threadBreakpoints = new ThreadLocal<Map<String, Set<Integer>>>();
            threadBreakpoints.set(new HashMap<String, Set<Integer>>());
        }
        if (!threadBreakpoints.get().containsKey(source)) {
            threadBreakpoints.get().put(source, new HashSet<Integer>());
        }
        threadBreakpoints.get().get(source).add(line);
    }

    /**
     * Remove a specific breakpoint.
     *
     * @param source - the source file the breakpoint is registered in
     * @param line - the line number of the breakpoint to be removed
     */
    public static void removeBreakpoint(String source, int line) {
        if (threadBreakpoints != null && threadBreakpoints.get() != null) {
            threadBreakpoints.get().get(source).remove(line);
        }
    }

    /**
     * Reset all the currently registered breakpoints.
     */
    public static void clearAllBreakpoints() {
        if (threadBreakpoints != null && threadBreakpoints.get() != null) {
            threadBreakpoints.get().clear();
        }
    }

    public static boolean hasBreakpoints() {
        return threadBreakpoints != null && threadBreakpoints.get() != null && threadBreakpoints.get().size() != 0;
    }

    /**
     * Sets the Debugger instance to handle breakpoints.   A debugger may only be registered once per thread.
     * Calling this method more than once will result in the second and subsequent calls to simply fail silently.
     * To re-register the Debugger, you must call {@link #resetDebugger}
     * @param debugger - debugger instance
     */
    public static void setThreadDebugger(Debugger debugger) {
        if (threadDebugger == null) {
            threadDebugger = new ThreadLocal<Debugger>();
        }
        if (threadDebugger.get() == null) {
            threadDebugger.set(debugger);
        }
    }

    /**
     * Reset all information registered in the debugger, including the actual attached Debugger and registered
     * breakpoints.
     */
    public static void resetDebugger() {
         if (threadDebugger != null && threadDebugger.get() != null) {
             threadDebugger = null;
         }
         if (threadBreakpoints != null && threadBreakpoints.get() != null) {
             threadBreakpoints = null;
         }
    }
}
