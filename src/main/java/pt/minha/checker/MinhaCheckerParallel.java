package pt.minha.checker;

import com.sun.corba.se.impl.orbutil.concurrent.Sync;
import pt.haslab.taz.TraceProcessor;
import pt.haslab.taz.causality.CausalPair;
import pt.haslab.taz.causality.MessageCausalPair;
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
    public static HashSet<CausalPair<? extends Event,? extends Event>> dataRaceCandidates;
    public static HashSet<CausalPair<? extends Event,? extends Event>> msgRaceCandidates;
    public static HashSet<CausalPair<SocketEvent, SocketEvent>> redundantSndRcv;

    //HB model
    //used to encode message arrival constraints
    public static HashMap<SocketEvent, Event> rcvNextEvent;

    // Debug data
    public static HashSet<Event> redundantEvents;

    //Metadata for detecting possible redundant send/receives
    static Map<String, Integer> threadCounters;

    //Redundancy Elimination structures
    //Map: thread id -> Msg Context (Counter)
    public static Map<String, Integer> msgContexts;
    //Map: thread id -> list of he ids of the messages ids and lock ids in the concurrency context
    public static Map<String, Set<String>> concurrencyContexts;
    //Map: location,hashCode(TETAthread)-> stack of Threads
    public static Map<String, Stack<String>> stacks;

    //Map: str(location pair),hashCode(TETAthread)-> stack of
    public static Map<String, Stack<CausalPair<SocketEvent, SocketEvent>>> msgStacks;

    //solver stuff
    public static Solver solver;

    public static void printIteratorOrder() {
        EventIterator events = new EventIterator(trace.eventsPerThread.values());
        System.out.println(">>>>>>>>>>>");
        while (events.hasNext()) {
            Event e = events.next();
            System.out.println(e.getEventId() + " :: " + e.toString());
        }
        System.out.println(">>>>>>>>>>>");
    }

    public static void main(String args[]) {
        dataRaceCandidates = new HashSet<CausalPair<? extends Event, ? extends Event>>();
        msgContexts = new HashMap<String,Integer>();
        msgRaceCandidates = new HashSet<CausalPair<? extends Event, ? extends Event>>();
        msgStacks = new HashMap<String, Stack<CausalPair<SocketEvent, SocketEvent>>>();
        rcvNextEvent = new HashMap<SocketEvent, Event>();
        redundantSndRcv = new HashSet<CausalPair<SocketEvent, SocketEvent>>();

        redundantEvents = new HashSet<Event>();

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

                //aggregate partitioned messages to facilitate message race detection
                trace.aggregateAllPartitionedMessages();

                //remove redundant events
                if((args.length == 1 && ("--removeRedundancy".equals(args[0]) || "-r".equals(args[0]))) ||
                                "true".equals(props.getProperty("redundancy-elimination"))) {
                    removeRedundantEvents();
                    //writeTrace("toCleanTrace.txt");
                    pruneEvents();
                    //writeTrace("cleanTrace.txt");
                }

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

    private static void writeTrace(String path) {
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

        //Map key (snd_location + thread counter of send + rcv_location + thread counter of rcv) -> stack of pairs SND/RCV
        Map<String, Stack<MessageCausalPair>> socketStacks =
                        new HashMap<String, Stack<MessageCausalPair>>();

        // Records for each SND event its corresponding thread counter
        Map<Event, Integer> countersOnEvents = new HashMap<Event, Integer>();
        threadCounters = new HashMap<String, Integer>();

        for(String thread : trace.eventsPerThread.keySet()) {
            threadCounters.put(thread, 0);
        }

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
                        redundantEvents.add(e);
                        removeEventMetadata(rwe);
                        count++;
                    } else {
                        threadCounters.put(thread, threadCounters.get(thread)+1);
                    }
                    break;
                case SND:
                    SocketEvent se = (SocketEvent) e;
                    Utils.insertInMapToSets(concurrencyContexts, thread, se.getMessageId());
                    countersOnEvents.put(e, threadCounters.get(thread));
                    break;
                case RCV:
                    SocketEvent rcve = (SocketEvent) e;
                    String messageId = rcve.getMessageId();
                    SocketEvent snde = trace.sndFromMessageId(messageId);
                    String key = snde == null? "null" : (snde.getLineOfCode() + ":" + countersOnEvents.get(snde)) + "::" + rcve.getLineOfCode() +
                                    ":" + threadCounters.get(thread);
                    Stack<MessageCausalPair> s = socketStacks.get(key);

                    if(s == null) {
                        s = new Stack<MessageCausalPair>();
                        socketStacks.put(key, s);
                    }

                    if(s.size() >= 2) {
                        redundantSndRcv.add(new CausalPair<SocketEvent, SocketEvent>(snde,rcve));
                    } else {
                        s.push(trace.msgEvents.get(messageId));
                    }
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
            System.out.println("-- Event " + e.getEventId() + " : " + e.toString());
            //printDebugInfo();
        }
        Stats.redundantEvents = count;
    }

    private static <X,Y> CausalPair<X,Y> getPairWithSameSecondTerm(Collection<CausalPair<X,Y>> coll, Y term) {
        if(term == null) {
            for(CausalPair<X,Y> pair : coll) {
                Y snd = pair.getSecond();
                if(snd == null)
                    return pair;
            }
            return null;
        }

        for(CausalPair<X,Y> pair : coll) {
            Y snd = pair.getSecond();
            if(term.equals(snd)) {
                return pair;
            }
        }

        return null;
    }

    private static <X,Y> boolean contains2ndTerm(Collection<CausalPair<X,Y>> coll, Y elem) {
        return getPairWithSameSecondTerm(coll, elem) != null;
    }

    private static void pruneEvents() {
        //can be optimized to check only once every part of the code
        Set<String> checkedThreads = new HashSet<String>();
        Set<String> threadsToRemove = new HashSet<String>();
        Set<Event> prunedEvents = new HashSet<Event>();

        for(String thread : trace.eventsPerThread.keySet()) {
            int i = 0;
            if(checkedThreads.contains(thread)) {
                continue;
            }

            List<Event> events = trace.eventsPerThread.get(thread);
            for(Event e : events) {
                EventType type = e.getType();
                if(prunedEvents.contains(e)) {
                    continue;
                }
                switch (type) {
                    case CREATE:
                        ThreadCreationEvent tce = (ThreadCreationEvent) e;
                        String child = tce.getChildThread();

                        if(canRemoveBlock(trace.eventsPerThread.get(child))) {
                            // marks events to remove instead of removing in order to prevent changes in the
                            // iterated collection
                            ThreadCreationEvent join = trace.getCorrespondingJoin(tce);
                            prunedEvents.add(tce);
                            prunedEvents.addAll(trace.eventsPerThread.get(child));
                            checkedThreads.add(child);
                            threadsToRemove.add(child);

                            if(join != null) {
                                prunedEvents.add(join);
                            }
                        }
                        break;

                    case RCV:
                        List<Event> handler = trace.handlerEvents.get(e);
                        CausalPair<SocketEvent, SocketEvent> pair = getPairWithSameSecondTerm(redundantSndRcv, (SocketEvent) e);

                        //if the send/rcv is redundant and there is no message handler

                        if(handler == null && pair != null) {
                            //                            removeEventMetadata(e);
                            //                            prunedEvents.add(pair.getFirst());
                            //                            prunedEvents.add(pair.getSecond());

                            redundantSndRcv.remove(pair);
                        }
                        break;
                    case LOCK:
                        SyncEvent lockEvent = (SyncEvent) e;
                        SyncEvent unlockEvent = trace.getCorrespondingUnlock(lockEvent);

                        if(unlockEvent != null) {
                            List<Event> subTrace = events.subList(i, events.indexOf(unlockEvent) + 1);
                            if (canRemoveBlock(subTrace)) {
                                prunedEvents.add(e);
                                prunedEvents.add(unlockEvent);
                            }
                        }
                        break;
                    default:
                        break;
                }
                i++;
            }
            checkedThreads.add(thread);
        }

        for(Event e : prunedEvents) {
            removeEventMetadata(e);
            System.out.println("To Remove: " + e);
            trace.eventsPerThread.get(e.getThread()).remove(e);
        }

        //remove redundant SND/RCV pairs that have redundant handlers
        for(CausalPair<SocketEvent, SocketEvent> pair : redundantSndRcv) {
            SocketEvent se = (SocketEvent) pair.getFirst();
            SocketEvent rcve = (SocketEvent) pair.getSecond();

            String thread = rcve.getThread();
            List<Event> list = trace.handlerEvents.get(rcve);
            //System.out.println("~~> " + pair);
            //System.out.println("LIST: " + list);
            if(canRemoveHandler(list)) {
                List<Event> events = trace.eventsPerThread.get(thread);
                prunedEvents.addAll(list);
                prunedEvents.add(rcve);
                prunedEvents.add(se);

                events.removeAll(list);
                events.remove(rcve);
                events.remove(se);
                removeEventMetadata(rcve);
                removeEventMetadata(se);

                //trace.handlerEvents.remove(pair.getSecond());
                //trace.msgEvents.remove(pair.getFirst().getMessageId());
                //System.out.println("REMOVED SND: " + se);
            }
        }

        Stats.prunedEvents = prunedEvents.size();
    }

    private static <K,V> boolean removeFromMapToLists(Map<K,List<V>> map, K key, V value) {
        List<V> l = map.get(key);
        if(l != null) {
            return l.remove(value);
        }
        return false;
    }

    //Only removes the data associated with this event from the Trace Processor metadata (not from eventsPerThread)
    private static void removeEventMetadata(Event e) {
        if(e == null) {
            return;
        }
        String var;
        EventType type = e.getType();
        String thread = e.getThread();
        switch(type) {
            case SND:
                //removes both SND and RCV from msgEvents
                SocketEvent socketEvent = (SocketEvent) e;
                String msgId = socketEvent.getMessageId();
                trace.msgEvents.remove(msgId);
                break;
            case RCV:
                //remove msg handler
                SocketEvent rcvEvent = (SocketEvent)  e;
                List<Event> handler = trace.handlerEvents.remove(rcvEvent);
                if(handler != null) {
                    for(Event x : handler) {
                        removeEventMetadata(x);
                    }
                }
                break;
            case CREATE:
                trace.forkEvents.get(thread).remove(e);
                break;
            case JOIN:
                trace.joinEvents.get(thread).remove(e);
                break;
            case LOCK:
            case UNLOCK:
                SyncEvent syncEvent = (SyncEvent) e;
                var = syncEvent.getVariable();
                List<CausalPair<SyncEvent,SyncEvent>> pairs = trace.lockEvents.get(var);
                if(pairs != null) {
                    CausalPair<SyncEvent, SyncEvent> res = null;
                    for(CausalPair<SyncEvent,SyncEvent> pair : pairs) {
                        SyncEvent fst = pair.getFirst();
                        SyncEvent snd = pair.getSecond();
                        if(syncEvent.equals(fst) || syncEvent.equals(snd)) {
                            res = pair;
                            break;
                        }
                    }

                    if(res != null) {
                        pairs.remove(res);
                    }
                }
                break;
            case READ:
                RWEvent readEvent = (RWEvent) e;
                var = readEvent.getVariable();
                removeFromMapToLists(trace.readEvents, var, readEvent);
                break;
            case WRITE:
                RWEvent writeEvent = (RWEvent) e;
                var = writeEvent.getVariable();
                removeFromMapToLists(trace.writeEvents, var, writeEvent);
                break;
            case WAIT:
                SyncEvent waitEvent = (SyncEvent) e;
                var = waitEvent.getVariable();
                removeFromMapToLists(trace.waitEvents, var, waitEvent);
                break;
            case NOTIFY:
            case NOTIFYALL:
                SyncEvent notifyEvent = (SyncEvent) e;
                var = notifyEvent.getVariable();
                removeFromMapToLists(trace.notifyEvents, var, notifyEvent);
                break;
        }

        for(List<Event> l : trace.handlerEvents.values()) {
            if(l.remove(e)) {
                return;
            }
        }
    }

    private static boolean canRemoveHandler(List<Event> handler) {
        Set<SyncEvent> openLocks = new HashSet<SyncEvent>();
        for(Event e : handler) {
            EventType type = e.getType();
            if(redundantEvents.contains(e)) {
                continue;
            }
            switch(type) {
                case SND:
                case RCV:
                case READ:
                case WRITE:
                case NOTIFY:
                case NOTIFYALL:
                case WAIT:
                case CREATE:
                    return false;
                case LOCK:
                    openLocks.add((SyncEvent) e);
                    break;
                case UNLOCK:
                    if(!openLocks.remove((SyncEvent) e)) {
                        // tried to unlock a thread open outside the handler
                        return false;
                    }
                    break;
                default:
                    break;
            }
        }
        return openLocks.isEmpty();
    }

    private static boolean canRemoveBlock(List<Event> events) {
        for(Event e : events) {
            EventType type = e.getType();
            if(type == SND || type == RCV || type == WRITE || type == READ || type == NOTIFY || type == NOTIFYALL || type == WAIT) {
                return false;
            } else if(type == CREATE) {
                ThreadCreationEvent tce = (ThreadCreationEvent) e;
                if(!canRemoveBlock(trace.eventsPerThread.get(tce.getChildThread()))) {
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
                        CausalPair<RWEvent, RWEvent> tmpPair = new CausalPair<RWEvent, RWEvent>(w1, w2);
                        if (!dataRaceCandidates.contains(tmpPair)) {
                            dataRaceCandidates.add(tmpPair);
                        }
                    }
                }

                //pair with all other reads
                if(trace.readEvents.containsKey(var)) {
                    for (RWEvent r2 : trace.readEvents.get(var)) {
                        CausalPair<RWEvent, RWEvent> tmpPair = new CausalPair<RWEvent, RWEvent>(w1, r2);
                        if (w1.conflictsWith(r2) && !dataRaceCandidates.contains(tmpPair)) {
                            dataRaceCandidates.add(tmpPair);
                        }
                    }
                }
            }
        }

        //DEBUG: print candidate pairs
        System.out.println("Data Race candidates: ");
        for(CausalPair<? extends Event,? extends Event> pair : dataRaceCandidates){
            System.out.println("\t"+orderedToString(pair));
        }//*/
    }

    public static void genMsgRaceCandidates() throws IOException {
        // generate all pairs of message race candidates
        // a pair of RCV operations is a candidate if:
        // a) both occur at the same node
        // b) are either from different threads of from different message handlers in the same thread
        List<MessageCausalPair> list = new ArrayList<MessageCausalPair>( trace.msgEvents.values());
        ListIterator<MessageCausalPair> pairIterator_i = list.listIterator(0);
        ListIterator<MessageCausalPair> pairIterator_j;

        solver.writeComment("SOCKET CHANNEL CONSTRAINTS");
        while(pairIterator_i.hasNext()){
            SocketEvent rcv1 = pairIterator_i.next().getRcv();

            if(rcv1 == null)
                continue;

            //advance iterator to have two different pairs
            pairIterator_j = list.listIterator(pairIterator_i.nextIndex());

            while(pairIterator_j.hasNext()){
                SocketEvent rcv2 = pairIterator_j.next().getRcv();
                if(rcv2 == null)
                    continue;

                if(rcv1.conflictsWith(rcv2)){
                    //make a pair with SND events because
                    //two messages a and b are racing if RCVa || SNDb
                    SocketEvent snd1 = trace.msgEvents.get(rcv1.getMessageId()).getSnd();
                    SocketEvent snd2 = trace.msgEvents.get(rcv2.getMessageId()).getSnd();
                    CausalPair<SocketEvent,SocketEvent> raceCandidate;

                    if(rcv1.getEventId() < rcv2.getEventId())
                        raceCandidate = new CausalPair<SocketEvent, SocketEvent>(snd2,rcv1);
                    else
                        raceCandidate = new CausalPair<SocketEvent, SocketEvent>(snd1,rcv2);
                    msgRaceCandidates.add(raceCandidate);

                    //if socket channel is TCP and SNDs are from the same thread,
                    // then add constraint stating that RCV1 happens before SND2
                    if(snd1.getSocketType() == SocketEvent.SocketType.TCP
                                    && snd2.getSocketType() == SocketEvent.SocketType.TCP
                                    && snd1.getThread().equals(snd2.getThread())){

                        String cnst;
                        //check trace order of SND, as the iterator does not traverse the events
                        //according to the program order
                        if(snd1.getEventId() < snd2.getEventId())
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
        for(CausalPair<? extends Event,? extends Event> pair : msgRaceCandidates){
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

    static String orderedToString(CausalPair<? extends Event,? extends Event> pair) {
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
        for(CausalPair<? extends Event,? extends Event> race : dataRaceCandidates){
            System.out.println("-- "+ orderedToString(race));
        }
    }

    public static void prettyPrintMessageRaces(){
        for(CausalPair<? extends Event, ? extends Event> conf : msgRaceCandidates){
            //translate SND events to their respective RCV events
            SocketEvent snd1 = (SocketEvent) conf.getFirst();
            SocketEvent snd2 = (SocketEvent) conf.getSecond();
            SocketEvent rcv1 = trace.msgEvents.get(snd1.getMessageId()).getRcv();
            SocketEvent rcv2 = trace.msgEvents.get(snd2.getMessageId()).getRcv();
            CausalPair<SocketEvent, SocketEvent> rcv_conf = new CausalPair<SocketEvent, SocketEvent>(rcv1, rcv2);
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
                            CausalPair<RWEvent, RWEvent> race = new CausalPair<RWEvent, RWEvent>(e1, e2);
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
        for (MessageCausalPair pair : trace.msgEvents.values()) {

            if ( pair.getSnd() == null && pair.getRcv() == null )
                continue;

            SocketEvent rcv = pair.getRcv();
            String cnst = "";

            //if there is a message handler, order SND with HANDLERBEGIN instead of RCV
            if(!trace.handlerEvents.containsKey(rcv))
                cnst = solver.cLt(pair.getSnd().toString(), pair.getRcv().toString());
            else{
                Event handlerbegin = trace.handlerEvents.get(rcv).get(0);
                cnst = solver.cLt(pair.getSnd().toString(), handlerbegin.toString());
            }

            solver.writeConst(solver.postNamedAssert(cnst,"COM"));

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
            ListIterator<CausalPair<SyncEvent, SyncEvent>> pairIterator_i = trace.lockEvents.get(var).listIterator(0);
            ListIterator<CausalPair<SyncEvent, SyncEvent>> pairIterator_j;

            while(pairIterator_i.hasNext()){
                CausalPair<SyncEvent, SyncEvent> pair_i = pairIterator_i.next();
                //advance iterator to have two different pairs
                pairIterator_j =  trace.lockEvents.get(var).listIterator(pairIterator_i.nextIndex());

                while(pairIterator_j.hasNext()) {
                    CausalPair<SyncEvent, SyncEvent> pair_j = pairIterator_j.next();

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
                    String binVar = "B_"+condition+"-W_" + wait.getThread()+"_"+wait.getEventId()+"-N_"+notify.getThread()+"_"+notify.getEventId();

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
