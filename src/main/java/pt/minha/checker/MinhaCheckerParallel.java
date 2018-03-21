package pt.minha.checker;

import pt.haslab.taz.TraceProcessor;
import pt.haslab.taz.events.*;
import pt.haslab.taz.utils.Utils;
import static pt.haslab.taz.events.EventType.*;
import pt.minha.checker.solver.Solver;
import pt.minha.checker.solver.Z3SolverParallel;
import pt.minha.checker.stats.Stats;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
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

    // Debug data
    public static HashSet<Integer> redundantEvents;

    //Redundancy Elimination structures
    //Map: thread id -> list of he ids of the messages ids and lock ids in the concurrency context
    public static Map<String, Set<String>> concurrencyContexts;
    //Map: location -> concurreny history of that location (set of message ids and lock ids)
    //public static Map<String, Set<String>> concurrencyHistories;
    //Map: location,hashCode(TETAthread)-> stack of Threads
    public static Map<MyPair<String, Integer>, Stack<String>> stacks;

    //Map: str(location pair),hashCode(TETAthread)-> stack of
    public static Map<MyPair<String, Integer>, Stack<MyPair<SocketEvent, SocketEvent>>> msgStacks;

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
        msgRaceCandidates = new HashSet<MyPair<? extends Event, ? extends Event>>();
        msgStacks = new HashMap<MyPair<String, Integer>, Stack<MyPair<SocketEvent, SocketEvent>>>();

        //DEBUG
        redundantEvents = new HashSet<Integer>();

        //Redundancy-check related initializations
        concurrencyContexts = new HashMap<String, Set<String>>();
        //concurrencyHistories = new HashMap<String, Set<String>>();
        stacks = new HashMap<MyPair<String, Integer>, Stack<String>>();

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
                    //removeRedundantMessageEvents();
                }

                //generate constraint model
                initSolver();
                long modelStart = System.currentTimeMillis();
                genProgramOrderConstraints();
                genCommunicationConstraints();
                genForkStartConstraints();
                genJoinExitConstraints();
                genWaitNotifyConstraints();
                genLockingConstraints();
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
/*
    private static void removeRedundantMessageEvents() {

        //build snd tree for each thread
        HashMap<String, Map<MyPair<String, String>, Stack>> msgStacks; // Map DestThreadID -> Map (SND_location, RCV_location) Msg events tree
        for(RCV i event) {
            if(not redundant (SND i, RCVi))
                insert(SND i, RCV i)
            else
                remove(SND i, RCV i)
        }

        //remove redundant 4tuples

    }
*/

    public static void printDebugInfo() {
        System.out.println("*************************************************");

        System.out.println("Concurrency contexts:");
        for(Map.Entry<String, Set<String>> cc : concurrencyContexts.entrySet()) {
            System.out.println(cc.getKey() + " : " + cc.getValue());
        }

        //System.out.println("Concurrency Histories:");
        //for(Map.Entry<String, Set<String>> cc : concurrencyHistories.entrySet()) {
            //System.out.println(cc.getKey() + " : " + cc.getValue());
        //}

        System.out.println("Stacks:");
        System.out.println(stacks.entrySet().toString());

        System.out.println("Redundant events:");
        System.out.println(redundantEvents.toString());

        System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
    }

    public static void removeRedundantEvents() {
        // Assumptions:
        // - The order of the events iterator corresponds to the chronological order of the events
        // - the function getStack in ReX depends on the current state of teta-loc
        Set<Event> toRemove = new HashSet<Event>();
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
                    //temporary use of hashCode
                    Utils.insertInMapToSets(concurrencyContexts, thread, String.valueOf(le.getVariable().hashCode()));
                    //the concurrency context changed
                    break;
                case UNLOCK:
                    SyncEvent ue = (SyncEvent) e;
                    //temporary use of hashCode
                    concurrencyContexts.get(thread).remove(ue.getVariable().hashCode());
                    //Utils.insertInMapToSets(concurrencyContexts, thread, String.valueOf(ue.getVariable().hashCode()));
                    //the concurrency context changed
                    break;
                //MEM Access
                case READ:
                case WRITE:
                    RWEvent rwe = (RWEvent) e;
                    if(checkRedundancy(rwe, thread)) {
                        //if an event is redundant, remove from the trace
                        events.remove();
                        //DEBUG info
                        redundantEvents.add(e.getEventNumber());
                        //remove from readSet and writeSet
                        (type == READ? trace.readEvents : trace.writeEvents).get(rwe.getVariable()).remove(rwe);
                        count++;
                    }
                    break;
                case RCV:
                    // remove message redundancy
                    SocketEvent rcve = (SocketEvent) e;
                    int concurrencyContextHash;
                    MyPair<SocketEvent, SocketEvent> snd_rcv = trace.msgEvents.get(rcve.getMessageId());
                    Set<String> concurrencyContext = concurrencyContexts.get(thread);

                    //TODO falta adicionar thread de destino
                    String event_pair_str = new MyPair<String, String>(snd_rcv.getFirst().getLoc(), rcve.getThread() + snd_rcv.getSecond().getLoc()).toString();
                    concurrencyContextHash = concurrencyContext==null?0:concurrencyContext.hashCode();
                    MyPair<String, Integer> key = new MyPair<String, Integer>(event_pair_str, concurrencyContextHash);

                    Stack<MyPair<SocketEvent,SocketEvent>> stack = msgStacks.get(key);
                    if(stack == null) {
                        stack = new Stack<MyPair<SocketEvent,SocketEvent>>();
                        msgStacks.put(key,stack);
                    }
                    if(stack.size() >= 2) {
                        //TODO eliminar SND e RCV
                        toRemove.add(snd_rcv.getFirst());
                        toRemove.add(snd_rcv.getSecond());
                        trace.msgEvents.remove(rcve.getMessageId());
                    } else {
                        stack.push(snd_rcv);
                    }

                    break;
                case SND:
                    SocketEvent se = (SocketEvent) e;
                    Utils.insertInMapToSets(concurrencyContexts, thread, se.getMessageId());
                    //the concurrency context changed
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
        Stats.redundantSocketEvents = toRemove.size();
        Stats.redundantEvents = count;
        removeSocketEvents(toRemove);
    }

    private static void removeSocketEvents(Set<Event> toRemove) {
        for (List<Event> t : trace.eventsPerThread.values()) {
            t.removeAll(toRemove);
        }
    }

    private static boolean checkRedundancy(RWEvent event, String thread) {
        String loc = event.getLoc();
        //Set<String> concurrencyHistory = concurrencyHistories.get(loc);
        Set<String> concurrencyContext = concurrencyContexts.get(thread);
        MyPair<String,Integer> key = new MyPair<String, Integer>(event.getLoc(), concurrencyContext==null?0:concurrencyContext.hashCode());
        Stack<String> stack = stacks.get(key);

        if(stack == null) {
            stack = new Stack<String>();
            //adds concurrency context of thread to concurrency history
            //Utils.insertAllInMapToSet(concurrencyHistories, loc, concurrencyContexts.get(thread));

            //calculates the new key for the current state of the concurrency history
            // Set<Long> newConcurrencyHistory = concurrencyHistories.get(loc);
            MyPair<String,Integer> newKey = new MyPair<String, Integer>(event.getLoc(),concurrencyContext==null?0:concurrencyContext.hashCode() );
            stacks.put(newKey, stack);

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
            System.out.println("\t"+pair);
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
                    //two RCV events are racing if their respective SND events have the same order
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
            System.out.println("\t"+pair);
        }//*/
    }

    public static void checkDataRaces() throws IOException {
        if(dataRaceCandidates.isEmpty()) {
            System.out.println("[MinhaChecker] No data races to check (" + Stats.totalDataRaceCandidates + " candidates)");
            return;
        }

        solver.writeComment("DATA RACE CONSTRAINTS");
        Stats.totalDataRaceCandidates = dataRaceCandidates.size();
        System.out.println("[MinhaChecker] Start data race checking ("+Stats.totalDataRaceCandidates +" candidates)");
        long checkingStart = System.currentTimeMillis();
        dataRaceCandidates = ((Z3SolverParallel) solver).checkRacesParallel(dataRaceCandidates);
        Stats.checkingTimeDataRace = System.currentTimeMillis() - checkingStart;
        Stats.totalDataRacePairs = dataRaceCandidates.size();
        System.out.println("#Data Race Candidates: "+Stats.totalDataRaceCandidates +" | #Actual Data Races: "+Stats.totalDataRacePairs);
        for(MyPair<? extends Event,? extends Event> conf : dataRaceCandidates){
            System.out.println("-- "+ orderedToString(conf));
        }
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

        solver.writeComment("MESSAGE RACE CONSTRAINTS");
        Stats.totalMsgRaceCandidates = msgRaceCandidates.size();
        System.out.println("[MinhaChecker] Start message race checking ("+Stats.totalMsgRaceCandidates +" candidates)");
        long checkingStart = System.currentTimeMillis();
        msgRaceCandidates = ((Z3SolverParallel) solver).checkRacesParallel(msgRaceCandidates);
        Stats.checkingTimeMsgRace = System.currentTimeMillis() - checkingStart;
        Stats.totalMsgRacePairs = msgRaceCandidates.size();
        System.out.println("#Message Race Candidates: "+Stats.totalMsgRaceCandidates +" | #Actual Message Races: "+Stats.totalMsgRacePairs);
        for(MyPair<? extends Event, ? extends Event> conf : msgRaceCandidates){
            //translate SND events to their respective RCV events
            SocketEvent snd1 = (SocketEvent) conf.getFirst();
            SocketEvent snd2 = (SocketEvent) conf.getSecond();
            SocketEvent rcv1 = trace.msgEvents.get(snd1.getMessageId()).getSecond();
            SocketEvent rcv2 = trace.msgEvents.get(snd2.getMessageId()).getSecond();
            MyPair<SocketEvent, SocketEvent> rcv_conf = new MyPair<SocketEvent, SocketEvent>(rcv1, rcv2);
            System.out.println("~~ "+rcv_conf);
        }
    }

    public static void initSolver() throws IOException {
        String solverPath = props.getProperty("solver-bin"); //set up solver path
        solver = Z3SolverParallel.getInstance();
        solver.init(solverPath);
    }

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
            boolean isMsgHandler = false;
            if (!events.isEmpty()) {
                //list with blocks of events encompassed by HANDLERBEGIN and HANDLEREND
                List<String> handlingBlocks = new ArrayList<String>();
                String globalConstHead = ""; //constraint specifying the order of events within the thread until the first handlerbegin event
                String globalConstTail = ""; //constraint specifying the order of events within the thread from the last handlerend event till the end of the execution

                for (Event e : events) {
                    String var = solver.declareIntVar(e.toString(), "0", "MAX");
                    solver.writeConst(var);

                    if(e.getType() == EventType.HNDLBEG){
                        handlingBlocks.add(e.toString());
                        isMsgHandler = true;
                    }
                    else if(isMsgHandler){ //TODO: testar se é END aqui
                        int last = handlingBlocks.size()-1;
                        String handlingStr = handlingBlocks.get(last);
                        handlingStr += (" "+e.toString());
                        handlingBlocks.set(last,handlingStr);
                        if(e.getType() == EventType.HNDLEND){
                            isMsgHandler = false;
                        }
                    }
                    else if(!isMsgHandler && !handlingBlocks.isEmpty()){
                        globalConstTail += (" " + e.toString());
                    }
                    else{
                        globalConstHead += (" " + e.toString());
                    }
                }

                //only write global order constraint if there is more than one event
                if (events.size() > 1) {

                    //naive way of ensuring that the constraint is written
                    // solely when there is more than one event in globalConstHead
                    if(globalConstHead.indexOf("@") != globalConstHead.lastIndexOf("@"))
                        solver.writeConst(solver.postNamedAssert(solver.cLt(globalConstHead), "PC"));

                    //i) write order constraints within receive handling blocks
                    //ii) write that last event from head sequence happens before the receive events
                    //iii) write that first event from tail sequence happens after the receive handling events
                    for(String hevents : handlingBlocks){
                        //i)
                        solver.writeConst(solver.postAssert(solver.cLt(hevents)));

                        //ii)
                        String lastFromHead = globalConstHead.substring(globalConstHead.lastIndexOf(" ")+1);
                        int firstPos = hevents.indexOf(" ");
                        if(firstPos!=-1) {
                            String firstFromHandler = hevents.substring(0, hevents.indexOf(" "));
                            solver.writeConst(solver.postAssert(solver.cLt(lastFromHead, firstFromHandler)));
                        }
                        //iii)
                        if(!globalConstTail.equals("")) {
                            int lastSpace = globalConstTail.indexOf(" ",1);
                            String firstFromTail = globalConstTail.substring(1, lastSpace==-1?globalConstTail.length():lastSpace ); //string starts with " "
                            String lastFromHandler = hevents.substring(hevents.lastIndexOf(" ") + 1);
                            solver.writeConst(solver.postAssert(solver.cLt(lastFromHandler, firstFromTail)));
                        }
                    }
                }
            }
        }
    }

    public static void genCommunicationConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate communication constraints");
        solver.writeComment("COMMUNICATION CONSTRAINTS");
        for (MyPair<SocketEvent, SocketEvent> pair : trace.msgEvents.values()) {
            if(pair.getFirst()!= null && pair.getSecond()!=null) {
                String cnst = solver.cLt(pair.getFirst().toString(), pair.getSecond().toString());
                solver.writeConst(solver.postNamedAssert(cnst,"COM"));
            }
        }
    }

    public static void genForkStartConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate fork-start constraints");
        solver.writeComment("FORK-START CONSTRAINTS");
        for(List<ThreadCreationEvent> l : trace.forkEvents.values()){
            for(ThreadCreationEvent e : l){
                String cnst = solver.cLt(e.toString(), "START_"+e.getChildThread());
                solver.writeConst(solver.postNamedAssert(cnst,"FS"));
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
        System.out.println("[MinhaChecker] Generate locking constraints");
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
