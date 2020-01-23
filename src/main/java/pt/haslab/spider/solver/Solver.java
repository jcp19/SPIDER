package pt.haslab.spider.solver;

import java.io.IOException;
import java.util.List;

/**
 * Created by nunomachado on 03/04/17.
 */
public interface Solver
{

    void init( String solverPath )
                    throws IOException;

    void close()
                    throws IOException;

    void flush()
                    throws IOException;

    void writeConst( String constraint )
                    throws IOException;

    void writeComment( String comment )
                    throws IOException;

    String cAnd( String exp1, String exp2 );

    String cAnd( String exp1 );

    String cOr( String exp1, String exp2 );

    String cOr( String exp1 );

    String cEq( String exp1, String exp2 );

    String cNeq( String exp1, String exp2 );

    String cGeq( String exp1, String exp2 );

    String cGt( String exp1, String exp2 );

    String cLeq( String exp1, String exp2 );

    String cLt( String exp1, String exp2 );

    String cLt( String exp1 );

    String cDiv( String exp1, String exp2 );

    String cMod( String exp1, String exp2 );

    String cPlus( String exp1, String exp2 );

    String cMinus( String exp1, String exp2 );

    String cMult( String exp1, String exp2 );

    String cSummation( List<String> sum );

    String cMinimize( String constraint );

    String cMaximize( String constraint );

    String declareIntVar( String varname );

    String declareIntVar( String varname, int min, int max );

    String declareIntVar( String varname, String min, String max );

    String postAssert( String constraint );

    String postNamedAssert( String constraint, String label );
}
