package pt.haslab.spider.solver;

import pt.haslab.spider.Stats;
import pt.haslab.taz.causality.CausalPair;
import pt.haslab.taz.events.Event;
import pt.haslab.taz.events.SocketEvent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by nunomachado on 03/04/17.
 */

// TODO: define an instance of Solver which uses the Z3 API instead of creating a new process

public class Z3SolverParallel
                implements Solver
{
    private static Z3SolverParallel instance = null;

    private static FileWriter outfile;

    private static final String MODEL_FILENAME = "modelParallel.smt2";

    private static int CORES;

    private static Process[] z3Processes;

    private static BufferedReader[] readers;

    private static BufferedWriter[] writers;

    private ConcurrentHashMap<Integer, CausalPair<? extends Event, ? extends Event>> racesFound;

    private Z3SolverParallel()
    {
    }

    public static Z3SolverParallel getInstance()
    {
        if ( instance == null )
        {
            instance = new Z3SolverParallel();
        }
        return instance;
    }

    public void init( String solverPath )
                    throws IOException
    {
        CORES = Runtime.getRuntime().availableProcessors();
        z3Processes = new Process[CORES];
        readers = new BufferedReader[CORES];
        writers = new BufferedWriter[CORES];
        outfile = new FileWriter( new File( MODEL_FILENAME ) );

        for ( int i = 0; i < CORES; i++ )
        {
            ProcessBuilder builder = new ProcessBuilder( solverPath, "-smt2", "-in" );
            builder.redirectErrorStream( true );
            z3Processes[i] = builder.start();
            InputStream pout = z3Processes[i].getInputStream();
            OutputStream pin = z3Processes[i].getOutputStream();

            readers[i] = new BufferedReader( new InputStreamReader( pout ) );
            writers[i] = new BufferedWriter( new OutputStreamWriter( pin ) );
        }

        this.writeConst( "(set-option :produce-unsat-cores true)" );
    }

    public void close()
                    throws IOException
    {
        for ( int i = 0; i < CORES; i++ )
        {
            z3Processes[i].destroy();
        }
        outfile.close();
    }

    public void flush()
                    throws IOException
    {
        outfile.flush();
        for ( int i = 0; i < CORES; i++ )
        {
            writers[i].flush();
        }
    }

    public void writeConst( String constraint )
                    throws IOException
    {
        for ( int i = 0; i < CORES; i++ )
        {
            writers[i].write( constraint + "\n" );
        }
        outfile.write( constraint + "\n" );
    }

    public void writeComment( String comment )
                    throws IOException
    {
        for ( int i = 0; i < CORES; i++ )
        {
            writers[i].write( "\n; " + comment + "\n" );
        }
        outfile.write( "\n; " + comment + "\n" );
    }

    public HashSet<CausalPair<? extends Event, ? extends Event>> checkRacesParallel(
                    HashSet<CausalPair<? extends Event, ? extends Event>> candidates )
    {
        try
        {
            racesFound = new ConcurrentHashMap<>();
            int batchsize = candidates.size() / CORES;
            List<CausalPair<? extends Event, ? extends Event>> candidatesList =
                            new ArrayList<>( candidates );
            WorkerZ3[] workers = new WorkerZ3[CORES];
            int start = 0;
            int end;
            for ( int i = 0; i < CORES; i++ )
            {
                if ( i < CORES - 1 )
                {
                    end = start + batchsize;
                }
                else
                {
                    end = candidates.size();
                }
                WorkerZ3 w = new WorkerZ3( i, start, end, candidatesList );
                workers[i] = w;
                w.start();
                start = end;
            }
            for ( int i = 0; i < CORES; i++ )
            {
                workers[i].join();
            }
        }
        catch ( Exception e )
        {
            e.printStackTrace();
        }
        finally
        {
            return new HashSet<>( racesFound.values() );
        }
    }

    public String cAnd( String exp1, String exp2 )
    {
        return "(and " + exp1 + " " + exp2 + ")";
    }

    public String cAnd( String exp1 )
    {
        return "(and " + exp1 + ")";
    }

    public String cOr( String exp1, String exp2 )
    {
        return "(or " + exp1 + " " + exp2 + ")";
    }

    public String cOr( String exp1 )
    {
        return "(or " + exp1 + ")";
    }

    public String cEq( String exp1, String exp2 )
    {
        return "(= " + exp1 + " " + exp2 + ")";
    }

    public String cNeq( String exp1, String exp2 )
    {
        return "(not (= " + exp1 + " " + exp2 + "))";
    }

    public String cGeq( String exp1, String exp2 )
    {
        return "(>= " + exp1 + " " + exp2 + ")";
    }

    public String cGt( String exp1, String exp2 )
    {
        return "(> " + exp1 + " " + exp2 + ")";
    }

    public String cLeq( String exp1, String exp2 )
    {
        return "(<= " + exp1 + " " + exp2 + ")";
    }

    public String cLt( String exp1, String exp2 )
    {
        return "(< " + exp1 + " " + exp2 + ")";
    }

    public String cLt( String exp1 )
    {
        return "(< " + exp1 + " )";
    }

    public String cDiv( String exp1, String exp2 )
    {
        return "(div " + exp1 + " " + exp2 + ")";
    }

    public String cMod( String exp1, String exp2 )
    {
        return "(mod " + exp1 + " " + exp2 + ")";
    }

    public String cPlus( String exp1, String exp2 )
    {
        return "(+ " + exp1 + " " + exp2 + ")";
    }

    public String cMinus( String exp1, String exp2 )
    {
        return "(- " + exp1 + " " + exp2 + ")";
    }

    public String cMult( String exp1, String exp2 )
    {
        return "(* " + exp1 + " " + exp2 + ")";
    }

    public String cSummation( List<String> sum )
    {
        String res = "(+";
        for ( String s : sum )
        {
            res += ( " " + s );
        }
        res += ")";
        return res;
    }

    public String cMinimize( String constraint )
    {
        return "(minimize " + constraint + ")";
    }

    public String cMaximize( String constraint )
    {
        return "(maximize " + constraint + ")";
    }

    public String declareIntVar( String varname )
    {
        return "(declare-const " + varname + " Int)";
    }

    public String declareIntVar( String varname, int min, int max )
    {
        String ret = ( "(declare-const " + varname + " Int)\n" );
        ret += ( "(assert (and (>= " + varname + " " + min + ") (<= " + varname + " " + max + ")))" );
        return ret;
    }

    public String declareIntVar( String varname, String min, String max )
    {
        String ret = ( "(declare-const " + varname + " Int)\n" );
        ret += ( "(assert (and (>= " + varname + " " + min + ") (<= " + varname + " " + max + ")))" );
        return ret;
    }

    public String postAssert( String constraint )
    {
        // synchronized (this) {
        Stats.INSTANCE.numConstraints++;
        // }
        return ( "(assert " + constraint + ")" );
    }

    public String postNamedAssert( String constraint, String label )
    {
        // synchronized (this) {
        Stats.INSTANCE.numConstraints++;
        // }
        return ( "(assert (! " + constraint + ":named " + label + Stats.INSTANCE.numConstraints + "))" );
    }

    private String push()
    {
        return "(push)";
    }

    private String pop()
    {
        return "(pop)";
    }

    private String checkSat()
    {
        return "(check-sat)";
    }

    class WorkerZ3
                    extends Thread
    {
        private int id;

        private int startPos;

        private int endPos;

        List<CausalPair<? extends Event, ? extends Event>> candidates;

        WorkerZ3(
                        int tid,
                        int start,
                        int end,
                        List<CausalPair<? extends Event, ? extends Event>> candidatePairs )
        {
            id = tid;
            startPos = start;
            endPos = end;
            candidates = candidatePairs;
        }

        @Override
        public void run()
        {
            try
            {
                System.out.println(
                                "["
                                                + id
                                                + "] Started worker for batch ["
                                                + startPos
                                                + ", "
                                                + endPos
                                                + "]\t(z3 process "
                                                + z3Processes[id]
                                                + ")" );

                // check data races for a portion of the list
                for ( int i = startPos; i < endPos; i++ )
                {
                    CausalPair<? extends Event, ? extends Event> candidate = candidates.get( i );
                    boolean isMsgRace = ( candidate.getFirst() instanceof SocketEvent );
                    boolean isConflict =
                                    checkRace(
                                                    candidate.getFirst().toString(), candidate.getSecond().toString(),
                                                    isMsgRace );
                    if ( isConflict )
                    {
                        racesFound.put( candidate.hashCode(), candidate );
                    }
                }
            }
            catch ( Exception e )
            {
                e.printStackTrace();
            }
        }

        boolean checkRace( String e1, String e2, boolean isMsgRace )
        {
            try
            {
                writers[id].write( push() + "\n" );
                String conflictConst;
                if ( isMsgRace )
                    conflictConst = postNamedAssert( cLt( e1, e2 ), "RACE" );
                else
                    conflictConst = postNamedAssert( cEq( e1, e2 ), "RACE" );
                // System.out.println("CONSTRAINT: "+conflictConst);
                writers[id].write( conflictConst + "\n" );
                writers[id].write( checkSat() + "\n" );
                writers[id].write( pop() + "\n" );
                writers[id].flush();

                String isConflict;
                do
                {
                    isConflict = readers[id].readLine();
                    // System.out.println(isConflict);
                    if ( isConflict.contains( "error" ) )
                        System.out.println( e1 + " " + e2 + " --> " + isConflict );
                }
                while ( isConflict.contains( "error" ) );

                return ( isConflict.equals( "sat" ) );
            }
            catch ( Exception e )
            {
                e.printStackTrace();
            }
            return false;
        }
    }
}
