package pt.minha.checker.solver;

import pt.minha.checker.events.MyPair;
import pt.minha.checker.events.RWEvent;
import pt.minha.checker.stats.Stats;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by nunomachado on 03/04/17.
 */
public class Z3SolverParallel implements Solver {
    private static Z3SolverParallel instance = null;
    private static FileWriter outfile;
    public static String MODEL_FILENAME = "modelParallel.txt";
    public static String SOLVER_PATH;
    public static int CORES;
    private static Process[] z3Processes;
    private static BufferedReader[] readers;
    private static BufferedWriter[] writers;
    private ConcurrentHashMap<Integer,MyPair<RWEvent,RWEvent>> dataRacesFound;

    private Z3SolverParallel(){}

    public static Z3SolverParallel getInstance(){
        if(instance == null) {
            instance = new Z3SolverParallel();
        }
        return instance;
    }

    public void init(String solverPath) throws IOException{
        CORES = Runtime.getRuntime().availableProcessors();
        z3Processes = new Process[CORES];
        readers = new BufferedReader[CORES];
        writers = new BufferedWriter[CORES];
        SOLVER_PATH = solverPath;
        outfile = new FileWriter(new File(MODEL_FILENAME));

        for(int i = 0; i < CORES; i++){
            ProcessBuilder builder = new ProcessBuilder(solverPath,"-smt2","-in");
            builder.redirectErrorStream(true);
            z3Processes[i] = builder.start();
            InputStream pout = z3Processes[i].getInputStream();
            OutputStream pin = z3Processes[i].getOutputStream();

            readers[i] = new BufferedReader(new InputStreamReader(pout));
            writers[i] = new BufferedWriter(new OutputStreamWriter(pin));
        }

        this.writeConst("(set-option :produce-unsat-cores true)");
        dataRacesFound = new ConcurrentHashMap<Integer,MyPair<RWEvent, RWEvent>>();
    }

    public void close() throws IOException {
        for(int i = 0; i < CORES; i++){
            z3Processes[i].destroy();
        }
        outfile.close();
    }

    public void flush() throws IOException {
        outfile.flush();
        for(int i = 0; i < CORES; i++){
            writers[i].flush();
        }
    }

    public void writeConst(String constraint) throws IOException{
        for(int i = 0; i < CORES; i++){
            writers[i].write(constraint+"\n");
        }
        outfile.write(constraint+"\n");
    }

    public void writeComment(String comment)  throws IOException{
        for(int i = 0; i < CORES; i++){
            writers[i].write("\n; "+comment+"\n");
        }
        outfile.write("\n; "+comment+"\n");
    }

    /*
     * Use checkConflictsParallel instead of checkConflict with Z3SolverParallel
     */
    public boolean checkConflict(String e1, String e2) {
        return false;
    }


    public HashSet<MyPair<RWEvent,RWEvent>> checkConflictsParallel(HashSet<MyPair<RWEvent,RWEvent>> candidates)
    {
        try {

            int batchsize = candidates.size() / CORES;
            List<MyPair<RWEvent, RWEvent>> candidatesList = new ArrayList<MyPair<RWEvent, RWEvent>>(candidates);
            WorkerZ3[] workers = new WorkerZ3[CORES];
            int start = 0;
            int end;
            for (int i = 0; i < CORES; i++) {
                if(i == CORES-1){
                    end = candidates.size();
                }
                else{
                    end = start+batchsize;
                }
                WorkerZ3 w = new WorkerZ3(i,start, end, candidatesList);
                workers[i] = w;
                w.start();
                start = end;
            }
            for (int i = 0; i < CORES; i++) {
                workers[i].join();
            }
        }
        catch (Exception e){
            e.printStackTrace();
        }
        finally {
            return  new HashSet<MyPair<RWEvent,RWEvent>>(dataRacesFound.values());
        }
    }

    public String cAnd(String exp1, String exp2) {
        return "(and "+exp1+" "+exp2+")";
    }

    public String cAnd(String exp1) {
        return "(and "+exp1+ ")";
    }

    public String cOr(String exp1, String exp2) {
        return "(or "+exp1+" "+exp2+")";
    }

    public String cOr(String exp1) {
        return "(or "+exp1+")";
    }

    public String cEq(String exp1, String exp2) {
        return "(= "+exp1+" "+exp2+")";
    }

    public String cNeq(String exp1, String exp2) {
        return "(not (= "+exp1+" "+exp2+"))";
    }

    public String cGeq(String exp1, String exp2) {
        return "(>= "+exp1+" "+exp2+")";
    }

    public String cGt(String exp1, String exp2) {
        return "(> "+exp1+" "+exp2+")";
    }

    public String cLeq(String exp1, String exp2) {
        return "(<= "+exp1+" "+exp2+")";
    }

    public String cLt(String exp1, String exp2) {
        return "(< "+exp1+" "+exp2+")";
    }

    public String cLt(String exp1) {
        return "(< "+exp1+" )";
    }

    public String cDiv(String exp1, String exp2) {
        return "(div "+exp1+" "+exp2+")";
    }

    public String cMod(String exp1, String exp2) {
        return "(mod "+exp1+" "+exp2+")";
    }

    public String cPlus(String exp1, String exp2) {
        return "(+ "+exp1+" "+exp2+")";
    }

    public String cMinus(String exp1, String exp2) {
        return "(- "+exp1+" "+exp2+")";
    }

    public String cMult(String exp1, String exp2) {
        return "(* "+exp1+" "+exp2+")";
    }

    public String cSummation(List<String> sum) {
        String res = "(+";
        for(String s : sum)
        {
            res +=(" "+s);
        }
        res += ")";
        return res;
    }

    public String cMinimize(String constraint) {
        return "(minimize "+constraint+")";
    }

    public String cMaximize(String constraint) {
        return "(maximize "+constraint+")";
    }

    public String declareIntVar(String varname) {
        String ret = "(declare-const "+varname+" Int)";
        return ret;
    }

    public String declareIntVar(String varname, int min, int max) {
        String ret = ("(declare-const "+varname+" Int)\n");
        ret += ("(assert (and (>= "+varname+" "+min+") (<= "+varname+" "+max+")))");
        return ret;
    }

    public String declareIntVar(String varname, String min, String max) {
        String ret = ("(declare-const "+varname+" Int)\n");
        ret += ("(assert (and (>= "+varname+" "+min+") (<= "+varname+" "+max+")))");
        return ret;
    }


    public String postAssert(String constraint) {
        Stats.numConstraints++;
        return ("(assert "+constraint+")");
    }

    public String postNamedAssert(String constraint, String label) {
        Stats.numConstraints++;
        return ("(assert (! "+constraint+":named "+label+"))");
    }

    public String push(){
        return "(push)";
    }

    public String pop(){
        return "(pop)";
    }

    public String checkSat(){
        return "(check-sat)";
    }



    public class WorkerZ3 extends Thread {
        private int id;
        private int startPos;
        private int endPos;
        List<MyPair<RWEvent,RWEvent>> candidates;

        public WorkerZ3(int tid, int start, int end, List<MyPair<RWEvent,RWEvent>> candidatePairs) throws IOException{
            id = tid;
            startPos = start;
            endPos = end;
            candidates = candidatePairs;
        }

        @Override
        public void run(){
            try {
                System.out.println("["+id+"] Started worker for batch ["+startPos+", "+endPos+"]\t(z3 process "+z3Processes[id]+")");

                //check data races for a portion of the list
                for(int i = startPos; i < endPos; i++){
                    MyPair<RWEvent,RWEvent> candidate = candidates.get(i);
                    boolean isConflict = checkConflict(candidate.getFirst().toString(), candidate.getSecond().toString());
                    if (isConflict) {
                        dataRacesFound.put(candidate.hashCode(), candidate);
                    }
                }
            }
            catch (Exception e){
                e.printStackTrace();
            }
        }

        public boolean checkConflict(String e1, String e2){
            try {
                 writers[id].write(push()+"\n");
                writers[id].write(postAssert(cEq(e1, e2))+"\n");
                writers[id].write(checkSat()+"\n");
                writers[id].write(pop()+"\n");
                writers[id].flush();

                String isConflict;
                do {
                    isConflict = readers[id].readLine();
                    //System.out.println(isConflict);
                    if(isConflict.contains("error"))
                       System.out.println(e1 + " " + e2 + " --> " + isConflict);
                }while (isConflict.contains("error"));

                return (isConflict.equals("sat"));
            }
            catch (Exception e){
                e.printStackTrace();
            }
            return false;
        }
    }

}
