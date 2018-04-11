package pt.minha.checker;

import com.sun.corba.se.impl.orbutil.concurrent.Sync;
import pt.haslab.taz.TraceProcessor;
import pt.haslab.taz.events.*;
import pt.haslab.taz.utils.Utils;
import static pt.haslab.taz.events.EventType.*;
import pt.minha.checker.solver.Solver;
import pt.minha.checker.solver.Z3SolverParallel;
import pt.minha.checker.stats.Stats;

import java.io.*;
import java.util.*;


/**
 * Created by nunomachado on 30/03/17.
 */
public class MinhaCheckerParallel {

    //properties
    public static Properties props;

    //event trace processor
    public static TraceProcessor trace;

    //data structures
    public static HashSet<MyPair<? extends Event,? extends Event>> dataRaceCandidates;
    public static HashSet<MyPair<? extends Event,? extends Event>> msgRaceCandidates;

    //HB model
    //used to encode message arrival constraints
    public static HashMap<SocketEvent, Event> rcvNextEvent;

    // Debug data
    public static HashSet<Integer> redundantEvents;

    //Redundancy Elimination structures
    //Map: thread id -> Msg Context (Counter)
    public static Map<String, Integer> msgContexts;
    //Map: thread id -> list of he ids of the messages ids and lock ids in the concurrency context
    public static Map<String, Set<String>> concurrencyContexts;
    //Map: location,hashCode(TETAthread)-> stack of Threads
    public static Map<String, Stack<String>> stacks;

    //Map: str(location pair),hashCode(TETAthread)-> stack of
    public static Map<String, Stack<MyPair<SocketEvent, SocketEvent>>> msgStacks;

    //solver stuff
    public static Solver solver;

    public static void printIteratorOrder() {
        EventIterator events = new EventIterator(trace.eventsPerThread.values());
        System.out.println(">>>>>>>>>>>");
        while (events.hasNext()) {
            Event e = events.next();
            System.out.println(e.getEventNumber() + " :: " + e.toString());
        }
        System.out.println(">>>>>>>>>>>");
    }

    public static void main(String args[]) {

        dataRaceCandidates = new HashSet<MyPair<? extends Event, ? extends Event>>();
        msgContexts = new HashMap<String,Integer>();
        msgRaceCandidates = new HashSet<MyPair<? extends Event, ? extends Event>>();
        msgStacks = new HashMap<String, Stack<MyPair<SocketEvent, SocketEvent>>>();
        rcvNextEvent = new HashMap<SocketEvent, Event>();

        //DEBUG
        redundantEvents = new HashSet<Integer>();

        //Redundancy-check related initializations
        concurrencyContexts = new HashMap<String, Set<String>>();
        //concurrencyHistories = new HashMap<String, Set<String>>();
        stacks = new HashMap<String, Stack<String>>();

        try {
            String propFile = "checker.racedetection.properties";
            props = new Properties();
            InputStream is = ClassLoader.getSystemClassLoader().getResourceAsStream(propFile);
            if (is != null) {
                props.load(is);

                //populate data structures
                String traceFile = props.getProperty("event-file");
                trace = TraceProcessor.INSTANCE;
                trace.loadEventTrace(traceFile);
                Stats.numEventsTrace = trace.getNumberOfEvents();

                //remove redundant events
                if((args.length == 1 && ("--removeRedundancy".equals(args[0]) || "-r".equals(args[0]))) ||
                        "true".equals(props.getProperty("redundancy-elimination"))) {
                    removeRedundantEvents();
                    pruneEvents();
                }
                //writeCleanTrace("cleanTrace.txt");

                //generate constraint model
                initSolver();
                long modelStart = System.currentTimeMillis();
                genIntraNodeConstraints();
                genInterNodeConstraints();
                Stats.buildingModeltime = System.currentTimeMillis() - modelStart;

                //check conflicts
                genDataRaceCandidates();
                genMsgRaceCandidates();
                checkDataRaces();
                checkMsgRaces();
                solver.close();//*/

                Stats.printStats();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void writeCleanTrace(String path) {
        PrintWriter pw = null;

        try {
            File file = new File(path);
            FileWriter fw = new FileWriter(file, true);
            pw = new PrintWriter(fw);
            EventIterator events = new EventIterator(trace.eventsPerThread.values());
            while(events.hasNext()) {
                Event e = events.next();
                pw.println(e);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (pw != null) {
                pw.close();
            }
        }

    }

    public static void printDebugInfo() {
        System.out.println("*************************************************");

        System.out.println("Concurrency contexts:");
        for(Map.Entry<String, Set<String>> cc : concurrencyContexts.entrySet()) {
            System.out.println(cc.getKey() + " : " + cc.getValue());
        }

        System.out.println("Stacks:");
        System.out.println(stacks.entrySet().toString());

        System.out.println("Redundant events:");
        System.out.println(redundantEvents.toString());

        System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
    }

    public static void removeRedundantEvents() {
        // Assumptions:
        //  The order of the events iterator corresponds to the chronological order of the events
        //  the function getStack in ReX depends only on the current state of teta-loc
        long count = 0;
        EventIterator events = new EventIterator(trace.eventsPerThread.values());
        while(events.hasNext()) {
            Event e = events.next();
            String thread = e.getThread();
            EventType type = e.getType();

            if(type == null)
                throw new RuntimeException("EventType not known");

            switch (type) {
                case LOCK:
                    SyncEvent le = (SyncEvent) e;
                    Utils.insertInMapToSets(concurrencyContexts, thread, String.valueOf(le.getVariable().hashCode()));
                    break;
                case UNLOCK:
                    SyncEvent ue = (SyncEvent) e;
                    concurrencyContexts.get(thread).remove(ue.getVariable().hashCode());
                    break;
                case READ:
                case WRITE:
                    //MEM Access
                    RWEvent rwe = (RWEvent) e;
                    if(checkRedundancy(rwe, thread)) {
                        //if an event is redundant, remove from the trace
                        events.remove();
                        //DEBUG info
                        redundantEvents.add(e.getEventNumber());
                        //System.out.println("DEBUG: Removed event " + e.toString());
                        (type == READ? trace.readEvents : trace.writeEvents).get(rwe.getVariable()).remove(rwe);
                        count++;
                    }
                    break;
                case SND:
                    SocketEvent se = (SocketEvent) e;
                    Utils.insertInMapToSets(concurrencyContexts, thread, se.getMessageId());
                    break;
                case CREATE:
                    // handles CREATE events the same way it handles SND
                    ThreadCreationEvent tse = (ThreadCreationEvent) e;
                    Utils.insertInMapToSets(concurrencyContexts, thread, String.valueOf(tse.hashCode()));
                    break;
                default:
                    // advance e
                    break;
            }
            System.out.println("-- Event " + e.getEventNumber() + " : " + e.toString());
            //printDebugInfo();
        }
        Stats.redundantEvents = count;
    }


    private static boolean canRemoveBlock(List<Event> block) {
        int i = 0;
        for(Event e : block) {
            i++;
            EventType type = e.getType();
            switch(type) {
                case SND:
                case RCV:
                    // fall trhough
                case WRITE:
                case READ:
                    return false;
                case LOCK:
                    break;

                case HNDLBEG:

                case START:

                default:
                    break;
            }
        }
        return true;
    }

    private static void pruneEvents() {
        //can be optimized to check only once every part of the code
        Set<String> checkedThreads = new HashSet<String>();
        Set<String> threadsToRemove = new HashSet<String>();
        Set<Event> toRemove;
        int prunedEvents = 0;

        for(String thread : trace.eventsPerThread.keySet()) {
            int i = 0;
            toRemove = new HashSet<Event>();
            if(checkedThreads.contains(thread)) {
                continue;
            }

            List<Event> events = trace.eventsPerThread.get(thread);
            for(Event e : events) {
                EventType type = e.getType();
                if(toRemove.contains(e)) {
                    continue;
                }
                switch (type) {
                    //can become faster if we toRemove becomes a map of thread -> events to remove on that
                    //thread
                    case CREATE:
                        ThreadCreationEvent tce = (ThreadCreationEvent) e;
                        String child = tce.getChildThread();
                        //adicionar thread criada às consultadas
                        //if(canRemoveBlock(trace.eventsPerThread.get(tce.getChildThread()))) {
                        if(threadsToRemove.contains(child) || canRemoveThread(trace.eventsPerThread.get(child))) {
                            //toRemove.addAll(trace.eventsPerThread.get(tce.getChildThread()));

                            // marks events to remove instead of removing in order to prevent changes in the
                            // iterated collection
                            ThreadCreationEvent join = trace.getCorrespondingJoin(tce);
                            toRemove.add(tce);
                            toRemove.add(join);
                            checkedThreads.add(child);
                            threadsToRemove.add(child);

                            //remove events from corresponding data structures
                            trace.forkEvents.get(thread).remove(tce);
                            prunedEvents += 1;

                            List<ThreadCreationEvent> joins = trace.joinEvents.get(thread);
                            if(joins != null) {
                                joins.remove(join);
                                prunedEvents += 1;
                            }
                        }
                        break;

                    //TODO - add
                    //case START:
                    //    break;
                    //TODO - handler begin
                    case LOCK:
                        /*
                        SyncEvent lockEvent = (SyncEvent) e;
                        SyncEvent unlockEvent = trace.getCorrespondingUnlock(lockEvent);
                        List<Event> subTrace = events.subList(i, events.indexOf(unlockEvent) + 1);
                        if(canRemoveLockBlock(subTrace, toRemove, lockEvent.getVariable())) {
                            toRemove.add(e);
                            toRemove.add(unlockEvent);
                            //toRemove.addAll(subTrace);
                        }
                        */
                        break;
                    default:
                        break;
                }
                i++;
            }
            //TODO: remove events from other data structures
            checkedThreads.add(thread);
            System.out.println("To Remove: " + toRemove);
            events.removeAll(toRemove);
        }
        //trace.eventsPerThread.keySet().removeAll(threadsToRemove);

        // Cleaning of data structures
        for(String thread : threadsToRemove) {
            List<Event> events = trace.eventsPerThread.remove(thread);
            prunedEvents += events.size();
            trace.forkEvents.remove(thread);
            trace.joinEvents.remove(thread);
            for(Event e : events) {
                EventType type = e.getType();
                if(type == LOCK) {
                    //removes both lock and unlock
                    MyPair<SyncEvent, SyncEvent> delete = null;
                    SyncEvent se = (SyncEvent) e;
                    List<MyPair<SyncEvent,SyncEvent>> lockEvents = trace.lockEvents.get(se.getVariable());
                    for(MyPair<SyncEvent, SyncEvent> s : lockEvents) {
                        if(s.getFirst().equals(se)) {
                            delete = s;
                            break;
                        }
                    }
                    lockEvents.remove(delete);
                }
            }
        }
        Stats.prunedEvents = prunedEvents;

    }

    private static boolean canRemoveLockBlock(List<Event> subseq, Set<Event> toRemove, String variable) {
         for(Event e : subseq.subList(1, subseq.size())) {
            EventType type = e.getType();
            if(type == SND || type == RCV || type == WRITE || type == READ || type == NOTIFY || type == NOTIFYALL || type == WAIT) {
                return false;
            } else if(type == LOCK) {
                if(((SyncEvent) e).getVariable().equals(variable)) {
                    //if the lock is reentrant
                    toRemove.add(e);
                }
            }
        }
        return true;
    }

    private static boolean canRemoveThread(List<Event> events) {
        for(Event e : events) {
            EventType type = e.getType();
            if(type == SND || type == RCV || type == WRITE || type == READ || type == NOTIFY || type == NOTIFYALL || type == WAIT) {
                return false;
            } else if(type == CREATE) {
                ThreadCreationEvent tce = (ThreadCreationEvent) e;
                if(!canRemoveThread(trace.eventsPerThread.get(tce.getChildThread()))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean checkRedundancy(RWEvent event, String thread) {
        String loc = event.getLineOfCode();
        Set<String> concurrencyContext = concurrencyContexts.get(thread);
        String key = event.getLineOfCode() + ":" + (concurrencyContext==null?0:concurrencyContext.hashCode()) + ":" + event.getType();

        Stack<String> stack = stacks.get(key);

        if(stack == null) {
            stack = new Stack<String>();
            stacks.put(key, stack);

            stack.push(thread);
            return false;
        } else if(stack.contains(thread) || stack.size() == 2) {
            //if the stack already contains the thread or is full
            return true;
        } else if(stack.size() == 1) {
            //Stack has size 1 and does not contain the thread
            stack.push(thread);
            return false;
        }
        return false;
    }

    public static void genDataRaceCandidates() {
        // generate all pairs of data race candidates
        // a pair of RW operations is a candidate if:
        // a) at least of one of the operations is a write
        // b) both operations access the same variable
        // c) operations are from different threads, but from the same node
        for(String var : trace.writeEvents.keySet()){
            for(RWEvent w1 : trace.writeEvents.get(var)){

                //pair with all other writes
                for (RWEvent w2 : trace.writeEvents.get(var)) {
                    if (w1.conflictsWith(w2)) {
                        MyPair<RWEvent, RWEvent> tmpPair = new MyPair<RWEvent, RWEvent>(w1, w2);
                        if (!dataRaceCandidates.contains(tmpPair)) {
                            dataRaceCandidates.add(tmpPair);
                        }
                    }
                }

                //pair with all other reads
                if(trace.readEvents.containsKey(var)) {
                    for (RWEvent r2 : trace.readEvents.get(var)) {
                        MyPair<RWEvent, RWEvent> tmpPair = new MyPair<RWEvent, RWEvent>(w1, r2);
                        if (w1.conflictsWith(r2) && !dataRaceCandidates.contains(tmpPair)) {
                            dataRaceCandidates.add(tmpPair);
                        }
                    }
                }
            }
        }

        //DEBUG: print candidate pairs
        System.out.println("Data Race candidates: ");
        for(MyPair<? extends Event,? extends Event> pair : dataRaceCandidates){
            System.out.println("\t"+orderedToString(pair));
        }//*/
    }

    public static void genMsgRaceCandidates() throws IOException {
        // generate all pairs of message race candidates
        // a pair of RCV operations is a candidate if:
        // a) both occur at the same node
        // b) are either from different threads of from different message handlers in the same thread
        List<MyPair<SocketEvent, SocketEvent>> list = new ArrayList<MyPair<SocketEvent, SocketEvent>>(trace.msgEvents.values());
        ListIterator<MyPair<SocketEvent, SocketEvent>> pairIterator_i = list.listIterator(0);
        ListIterator<MyPair<SocketEvent, SocketEvent>> pairIterator_j;

        solver.writeComment("SOCKET CHANNEL CONSTRAINTS");
        while(pairIterator_i.hasNext()){
            SocketEvent rcv1 = pairIterator_i.next().getSecond();
            if(rcv1 == null)
                continue;

            //advance iterator to have two different pairs
            pairIterator_j = list.listIterator(pairIterator_i.nextIndex());

            while(pairIterator_j.hasNext()){
                SocketEvent rcv2 = pairIterator_j.next().getSecond();
                if(rcv2 == null)
                    continue;

                if(rcv1.conflictsWith(rcv2)){
                    //make a pair with SND events because
                    //two messages a and b are racing if RCVa || SNDb
                    SocketEvent snd1 = trace.msgEvents.get(rcv1.getMessageId()).getFirst();
                    SocketEvent snd2 = trace.msgEvents.get(rcv2.getMessageId()).getFirst();
                    MyPair<SocketEvent,SocketEvent> raceCandidate;

                    if(rcv1.getEventNumber() < rcv2.getEventNumber())
                        raceCandidate = new MyPair<SocketEvent, SocketEvent>(snd2,rcv1);
                    else
                        raceCandidate = new MyPair<SocketEvent, SocketEvent>(snd1,rcv2);
                    msgRaceCandidates.add(raceCandidate);

                    //if socket channel is TCP and SNDs are from the same thread,
                    // then add constraint stating that RCV1 happens before SND2
                    if(snd1.getSocketType() == SocketEvent.SocketType.TCP
                            && snd2.getSocketType() == SocketEvent.SocketType.TCP
                            && snd1.getThread().equals(snd2.getThread())){

                        String cnst;
                        //check trace order of SND, as the iterator does not traverse the events
                        //according to the program order
                        if(snd1.getEventNumber() < snd2.getEventNumber())
                            cnst = solver.cLt(rcv1.toString(), snd2.toString());
                        else
                            cnst = solver.cLt(rcv2.toString(), snd1.toString());
                        solver.writeConst(solver.postNamedAssert(cnst,"TCP"));
                    }
                }
            }
        }

        //DEBUG: print candidate pairs
        System.out.println("Message Race candidates: ");
        for(MyPair<? extends Event,? extends Event> pair : msgRaceCandidates){
            System.out.println("\t"+orderedToString(pair));
        }//*/
    }

    public static void checkDataRaces() throws IOException {
        if(dataRaceCandidates.isEmpty()) {
            System.out.println("[MinhaChecker] No data races to check (" + Stats.totalDataRaceCandidates + " candidates)");
            return;
        }

        Stats.totalDataRaceCandidates = dataRaceCandidates.size();
        System.out.println("\n[MinhaChecker] Start data race checking ("+Stats.totalDataRaceCandidates +" candidates)");

        long checkingStart = System.currentTimeMillis();
        dataRaceCandidates = ((Z3SolverParallel) solver).checkRacesParallel(dataRaceCandidates);
        Stats.checkingTimeDataRace = System.currentTimeMillis() - checkingStart;
        Stats.totalDataRacePairs = dataRaceCandidates.size();

        System.out.println("\n#Data Race Candidates: "+Stats.totalDataRaceCandidates +" | #Actual Data Races: "+Stats.totalDataRacePairs);
        prettyPrintDataRaces();
    }

    static String orderedToString(MyPair<? extends Event,? extends Event> pair) {
        String fst = pair.getFirst() != null ? pair.getFirst().toString() : " ";
        String snd = pair.getSecond() != null ? pair.getSecond().toString() : " ";
        if(fst.compareTo(snd) < 0) {
            return "(" + snd + ", " + fst + ")";
        }
        return "(" + fst + ", " + snd + ")";

    }

    public static void checkMsgRaces() throws IOException {
        if(msgRaceCandidates.isEmpty()) {
            System.out.println("[MinhaChecker] No message races to check (" + Stats.totalDataRaceCandidates + " candidates)");
            return;
        }

        Stats.totalMsgRaceCandidates = msgRaceCandidates.size();

        System.out.println("\n[MinhaChecker] Start message race checking ("+Stats.totalMsgRaceCandidates +" candidates)");

        long checkingStart = System.currentTimeMillis();
        msgRaceCandidates = ((Z3SolverParallel) solver).checkRacesParallel(msgRaceCandidates);
        Stats.checkingTimeMsgRace = System.currentTimeMillis() - checkingStart;
        Stats.totalMsgRacePairs = msgRaceCandidates.size();

        System.out.println("\n#Message Race Candidates: "+Stats.totalMsgRaceCandidates +" | #Actual Message Races: "+Stats.totalMsgRacePairs);
        prettyPrintMessageRaces();
    }


    public static void prettyPrintDataRaces(){
        for(MyPair<? extends Event,? extends Event> race : dataRaceCandidates){
            System.out.println("-- "+ orderedToString(race));
        }
    }

    public static void prettyPrintMessageRaces(){
        for(MyPair<? extends Event, ? extends Event> conf : msgRaceCandidates){
            //translate SND events to their respective RCV events
            SocketEvent snd1 = (SocketEvent) conf.getFirst();
            SocketEvent snd2 = (SocketEvent) conf.getSecond();
            //TODO: double check if this covers all cases of partitioned messages
            SocketEvent rcv1 = trace.msgEvents.containsKey(snd1.getMessageId()) ? trace.msgEvents.get(snd1.getMessageId()).getSecond() : trace.msgEvents.get(snd1.getMessageId()+".0").getSecond();
            SocketEvent rcv2 = trace.msgEvents.containsKey(snd2.getMessageId()) ? trace.msgEvents.get(snd2.getMessageId()).getSecond() : trace.msgEvents.get(snd2.getMessageId()+".0").getSecond();
            MyPair<SocketEvent, SocketEvent> rcv_conf = new MyPair<SocketEvent, SocketEvent>(rcv1, rcv2);
            System.out.println("~~ "+orderedToString(rcv_conf));

            //compute read-write sets for each message handler
            if(!trace.handlerEvents.containsKey(rcv1) || !trace.handlerEvents.containsKey(rcv2)){
                System.out.println("\t-- No conflicts");
            }
            else {
                HashSet<RWEvent> readWriteSet1 = new HashSet<RWEvent>();
                HashSet<RWEvent> readWriteSet2 = new HashSet<RWEvent>();

                for(Event e : trace.handlerEvents.get(rcv1)){
                    if(e.getType() == EventType.READ || e.getType() == EventType.WRITE)
                        readWriteSet1.add((RWEvent)e);
                }

                for(Event e : trace.handlerEvents.get(rcv2)){
                    if(e.getType() == EventType.READ || e.getType() == EventType.WRITE)
                        readWriteSet2.add((RWEvent) e);
                }

                //check for conflicts
                for(RWEvent e1 : readWriteSet1){
                    for(RWEvent e2 : readWriteSet2){
                        if(e1.conflictsWith(e2)){
                            MyPair<RWEvent, RWEvent> race = new MyPair<RWEvent, RWEvent>(e1, e2);
                            System.out.println("\t-- conflict "+ orderedToString(race));
                        }
                    }
                }
            }
        }

    }

    public static void initSolver() throws IOException {
        String solverPath = props.getProperty("solver-bin"); //set up solver path
        solver = Z3SolverParallel.getInstance();
        solver.init(solverPath);
    }


    public static void genIntraNodeConstraints() throws IOException{
        genProgramOrderConstraints();
        genForkStartConstraints();
        genJoinExitConstraints();
        genWaitNotifyConstraints();
        genLockingConstraints();
    }

    public static void genInterNodeConstraints() throws IOException {
        genSendReceiveConstraints();
        genMessageHandlingConstraints();
    }

        /**
         * Builds the order constraints within a segment and returns the position in the trace in which the handler ends
         * @return
         */
    public static int genSegmentOrderConstraints(List<Event> events, int segmentStart) throws IOException{

        //constraint representing the HB relation for the thread's segment
        StringBuilder orderConstraint = new StringBuilder();
        int segmentIt = 0;

        for(segmentIt = segmentStart; segmentIt < events.size(); segmentIt++) {
            Event e = events.get(segmentIt);

            //declare variable
            String var = solver.declareIntVar(e.toString(), "0", "MAX");
            solver.writeConst(var);

            //append event to the thread's segment
            orderConstraint.append(" " + e.toString());

            //handle partial order within message handler
            if (e.getType() == EventType.RCV &&
                    segmentIt < (events.size() - 1) &&
                    events.get(segmentIt + 1).getType() == EventType.HNDLBEG) {
                segmentIt = genSegmentOrderConstraints(events, segmentIt + 1);

                //store event next to RCV to later encode the message arrival order
                if(segmentIt < events.size() - 1)
                    rcvNextEvent.put((SocketEvent) e, events.get(segmentIt+1));
            }
            else if(e.getType() == EventType.HNDLEND)
                break;
        }

        //write segment's order constraint
        solver.writeConst(solver.postNamedAssert(solver.cLt(orderConstraint.toString()), "PC"));

        return segmentIt;
    }

    /**
     * Program order constraints encode the order within a thread's local trace. Asynchronous event handling causes
     * the same thread to have multiple segments (i.e. handlers), which breaks global happens-before relation
     * @throws IOException
     */
    public static void genProgramOrderConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate program order constraints");
        solver.writeComment("PROGRAM ORDER CONSTRAINTS");
        int max = 0;
        for (List<Event> l : trace.eventsPerThread.values()) {
            max += l.size();
        }
        solver.writeConst(solver.declareIntVar("MAX"));
        solver.writeConst(solver.postAssert(solver.cEq("MAX", String.valueOf(max))));

        //generate program order variables and constraints
        for (List<Event> events : trace.eventsPerThread.values()) {

            if (events.isEmpty())
                continue;
            //if there's only one event, we just need to declare it as there are no program order constraints
            else if (events.size() == 1) {
                String var = solver.declareIntVar(events.get(0).toString(), "0", "MAX");
                solver.writeConst(var);
            }
            //generate program constraints for the thread segment
            else
                genSegmentOrderConstraints(events, 0);

            //build program order constraints for the whole thread trace
            /*StringBuilder orderConstraint = new StringBuilder();
            for(Event e : events){
                //declare variable
                String var = solver.declareIntVar(e.toString(), "0", "MAX");
                solver.writeConst(var);

                //append to order constraint
                orderConstraint.append(" "+e.toString());
            }
            solver.writeConst(solver.postNamedAssert(solver.cLt(orderConstraint.toString()), "PC")); */
        }
    }

    public static void genSendReceiveConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate communication constraints");
        solver.writeComment("SEND-RECEIVE CONSTRAINTS");
        for (MyPair<SocketEvent, SocketEvent> pair : trace.msgEvents.values()) {
            if(pair.getFirst()!= null && pair.getSecond()!=null) {
                SocketEvent rcv = pair.getSecond();
                String cnst = "";
                //if there are message handler, order SND with HANDLERBEGIN instead of RCV
                if(!trace.handlerEvents.containsKey(rcv))
                    cnst = solver.cLt(pair.getFirst().toString(), pair.getSecond().toString());
                else{
                    Event handlerbegin = trace.handlerEvents.get(rcv).get(0);
                    cnst = solver.cLt(pair.getFirst().toString(), handlerbegin.toString());
                }

                solver.writeConst(solver.postNamedAssert(cnst,"COM"));
            }
        }
    }

    /**
     * Message Handling constraints encode:
     * - message handler mutual exclusion
     * - message arrival order
     * @throws IOException
     */
    public static void genMessageHandlingConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate message handling constraints");
        solver.writeComment("MESSAGE HANDLING CONSTRAINTS");
        String TAG = "HND";

        HashMap<String, HashSet<SocketEvent>> rcvPerThread = new HashMap<String, HashSet<SocketEvent>>();

        /* encode mutual exclusion constraints, which state that two message handlers in the same thread
           must occur one before the other in any order */
        for(SocketEvent rcv_i : trace.handlerEvents.keySet()){
            //store all rcv events per thread-socket
            String key = rcv_i.getThread()+"-"+rcv_i.getDstPort();
            if(!rcvPerThread.containsKey(key)){
                rcvPerThread.put(key, new HashSet<SocketEvent>());
            }
            rcvPerThread.get(key).add(rcv_i);

            for(SocketEvent rcv_j : trace.handlerEvents.keySet()) {
                if(rcv_i != rcv_j
                        && rcv_i.getThread().equals(rcv_j.getThread())
                        && rcv_i.getDstPort() == rcv_j.getDstPort()){

                    //mutual exclusion: HENDi < HBEGj V HENDj < HBEGi
                    String handlerBegin_i = trace.handlerEvents.get(rcv_i).get(0).toString();
                    String handlerEnd_i = trace.handlerEvents.get(rcv_i).get(trace.handlerEvents.get(rcv_i).size()-1).toString();
                    String handlerBegin_j = trace.handlerEvents.get(rcv_j).get(0).toString();
                    String handlerEnd_j = trace.handlerEvents.get(rcv_j).get(trace.handlerEvents.get(rcv_j).size()-1).toString();

                    String mutexConst = solver.cOr(solver.cLt(handlerEnd_i, handlerBegin_j), solver.cLt(handlerEnd_j, handlerBegin_i));
                    solver.writeConst(solver.postNamedAssert(mutexConst, TAG));
                }
            }
        }

        /* encode possible message arrival order constraints, which state that each RCV event may be
         * "matched with" any message on the same socket */
        for(HashSet<SocketEvent> rcvSet : rcvPerThread.values()){
            //for all RCVi in rcvSet :
            //(RCVi < HNDBegin_i && HNDEnd_i < nextEvent) V (RCVi < HNDBegin_j && HNDEnd_j < nextEvent), for all j != i
            for(SocketEvent rcv_i : rcvSet){
                Event nextEvent = rcvNextEvent.get(rcv_i);

                //if the RCV is the last event, then nextEvent == null
                if(nextEvent == null)
                    continue;

                StringBuilder outerOr = new StringBuilder();
                for(SocketEvent rcv_j : rcvSet){
                    String handlerBegin_j = trace.handlerEvents.get(rcv_j).get(0).toString();
                    String handlerEnd_j = trace.handlerEvents.get(rcv_j).get(trace.handlerEvents.get(rcv_j).size()-1).toString();
                    String innerAnd = solver.cLt(rcv_i.toString()+" "+handlerBegin_j+" "+handlerEnd_j+" "+nextEvent.toString());
                    outerOr.append(innerAnd+" ");
                }
                solver.writeConst(solver.postNamedAssert(solver.cOr(outerOr.toString()), TAG));
            }
        }
    }



    public static void genForkStartConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate fork-start constraints");
        solver.writeComment("FORK-START CONSTRAINTS");
        for(List<ThreadCreationEvent> l : trace.forkEvents.values()){
            for(ThreadCreationEvent e : l){

                //don't add fork-start constraints for threads that are spawned but don't start
                if(trace.eventsPerThread.containsKey(e.getChildThread())) {
                    String cnst = solver.cLt(e.toString(), "START_" + e.getChildThread());
                    solver.writeConst(solver.postNamedAssert(cnst, "FS"));
                }
            }
        }
    }

    public static void genJoinExitConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate join-end constraints");
        solver.writeComment("JOIN-END CONSTRAINTS");
        for(List<ThreadCreationEvent> l : trace.joinEvents.values()){
            for(ThreadCreationEvent e : l){
                String cnst = solver.cLt("END_"+e.getChildThread(), e.toString());
                solver.writeConst(solver.postNamedAssert(cnst,"JE"));
            }
        }
    }


    public static void genLockingConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate locking constraints");
        solver.writeComment("LOCKING CONSTRAINTS");
        for(String var : trace.lockEvents.keySet()){
            // for two lock/unlock pairs on the same locking object,
            // one pair must be executed either before or after the other
            ListIterator<MyPair<SyncEvent, SyncEvent>> pairIterator_i = trace.lockEvents.get(var).listIterator(0);
            ListIterator<MyPair<SyncEvent, SyncEvent>> pairIterator_j;

            while(pairIterator_i.hasNext()){
                MyPair<SyncEvent, SyncEvent> pair_i = pairIterator_i.next();
                //advance iterator to have two different pairs
                pairIterator_j =  trace.lockEvents.get(var).listIterator(pairIterator_i.nextIndex());

                while(pairIterator_j.hasNext()) {
                    MyPair<SyncEvent, SyncEvent> pair_j = pairIterator_j.next();

                    //there is no need to add constraints for locking pairs of the same thread
                    //as they are already encoded in the program order constraints
                    if (pair_i.getFirst().getThread().equals(pair_j.getFirst().getThread()))
                        continue;

                    // Ui < Lj || Uj < Li
                    String cnstUi_Lj = solver.cLt(pair_i.getSecond().toString(), pair_j.getFirst().toString());
                    String cnstUj_Li = solver.cLt(pair_j.getSecond().toString(), pair_i.getFirst().toString());
                    String cnst = solver.cOr(cnstUi_Lj, cnstUj_Li);
                    solver.writeConst(solver.postNamedAssert(cnst,"LC"));
                }
            }
        }
    }


    public static void genWaitNotifyConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate wait-notify constraints");
        solver.writeComment("WAIT-NOTIFY CONSTRAINTS");
        HashMap<SyncEvent, List<String>> binaryVars = new HashMap<SyncEvent, List<String>>(); //map: notify event -> list of all binary vars corresponding to that notify

        //for a given condition, each notify can be mapped to any wait
        //but a wait can only have a single notify
        for(String condition : trace.waitEvents.keySet()){
            for(SyncEvent wait : trace.waitEvents.get(condition)) {
                StringBuilder globalOr = new StringBuilder();

                for(SyncEvent notify : trace.notifyEvents.get(condition)){
                    //binary var used to indicate whether the signal operation is mapped to a wait operation or not
                    String binVar = "B_"+condition+"-W_" + wait.getThread()+"_"+wait.getEventNumber()+"-N_"+notify.getThread()+"_"+notify.getEventNumber();

                    if(!binaryVars.containsKey(notify)){
                        binaryVars.put(notify, new ArrayList<String>());
                    }
                    binaryVars.get(notify).add(binVar);

                    //const: Oa_sg < Oa_wt && b^{a_sg}_{a_wt} = 1
                    globalOr.append(solver.cAnd(solver.cLt(notify.toString(), wait.toString()), solver.cEq(binVar, "1")));
                    solver.writeConst(solver.declareIntVar(binVar, 0, 1));
                }
                solver.writeConst(solver.postNamedAssert(solver.cOr(globalOr.toString()),"WN"));
            }
        }

        //add constraints stating that a given notify can only be mapped to a single wait operation
        for(SyncEvent notify : binaryVars.keySet()){
            //for notifyAll, we don't constrain the number of waits that can be matched with this notify
            if(notify.getType() == NOTIFYALL) {
                //const: Sum_{x \in WT} b^{a_sg}_{x} >= 0
                solver.writeConst(solver.postNamedAssert(solver.cGeq(solver.cSummation(binaryVars.get(notify)), "0"), "WN"));
            } else{
                //const: Sum_{x \in WT} b^{a_sg}_{x} <= 1
                solver.writeConst(solver.postNamedAssert(solver.cLeq(solver.cSummation(binaryVars.get(notify)), "1"),"WN"));
            }
        }
    }
}
