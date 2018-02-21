package pt.minha.checker;

import org.json.JSONException;
import org.json.JSONObject;
import pt.minha.checker.events.*;
import pt.minha.checker.solver.Solver;
import pt.minha.checker.solver.Z3SolverParallel;
import pt.minha.checker.stats.Stats;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static javax.swing.UIManager.get;
import static pt.minha.checker.events.EventType.LOCK;
import static pt.minha.checker.events.EventType.READ;

/**
 * Created by nunomachado on 30/03/17.
 */
public class MinhaCheckerParallel {

    //properties
    public static Properties props;

    //data structures
    public static Map<Long, MyPair<SocketEvent, SocketEvent>> msgEvents;       //Map: message id -> pair of events (snd,rcv)
    public static Map<String, List<MyPair<LockEvent, LockEvent>>> lockEvents;  //Map: variable -> list of pairs of locks/unlocks
    public static Map<String, List<Event>> threadExecution;                    //Map: thread -> list of all events in that thread's execution
    public static Map<String, List<RWEvent>> readSet;                          //Map: variable -> list of reads to that variable by all threads
    public static Map<String, List<RWEvent>> writeSet;                         //Map: variable -> list of writes to that variable by all threads
    public static Map<String, List<ThreadSyncEvent>> forkSet;                  //Map: thread -> list of thread's fork events
    public static Map<String, List<ThreadSyncEvent>> joinSet;                  //Map: thread -> list of thread's join events
    public static HashSet<MyPair<? extends Event,? extends Event>> dataRaceCandidates;
    public static HashSet<MyPair<? extends Event,? extends Event>> msgRaceCandidates;

    // Debug data
    public static HashSet<Integer> redundantEvents;

    //Redundancy Elimination structures
    //Map: thread id -> list of he ids of the messages ids and lock ids in the concurrency context
    public static Map<String, Set<Long>> concurrencyContexts;
    //Map: location -> concurreny history of that location (set of message ids and lock ids)
    public static Map<String, Set<Long>> concurrencyHistories;
    //Map: location,hashCode(TETAthread)-> stack of Threads
    public static Map<MyPair<String, Integer>, Stack<String>> stacks;

    //solver stuff
    public static Solver solver;

    public static void printIteratorOrder() {
        EventIterator events = new EventIterator(threadExecution.values());
        System.out.println(">>>>>>>>>>>");
        while (events.hasNext()) {
            Event e = events.next();
            System.out.println(e.getId() + " :: " + e.toString());
        }
        System.out.println(">>>>>>>>>>>");
    }

    public static void main(String args[]) {
        msgEvents = new HashMap<Long, MyPair<SocketEvent, SocketEvent>>();
        lockEvents = new HashMap<String, List<MyPair<LockEvent,LockEvent>>>();
        threadExecution = new HashMap<String, List<Event>>();
        readSet = new HashMap<String, List<RWEvent>>();
        writeSet = new HashMap<String, List<RWEvent>>();
        forkSet = new HashMap<String, List<ThreadSyncEvent>>();
        joinSet = new HashMap<String, List<ThreadSyncEvent>>();
        dataRaceCandidates = new HashSet<MyPair<? extends Event, ? extends Event>>();
        msgRaceCandidates = new HashSet<MyPair<? extends Event, ? extends Event>>();

        //DEBUG
        redundantEvents = new HashSet<Integer>();

        //Redundancy-check related initializations
        concurrencyContexts = new HashMap<String, Set<Long>>();
        concurrencyHistories = new HashMap<String, Set<Long>>();
        stacks = new HashMap<MyPair<String, Integer>, Stack<String>>();

        try {
            String propFile = "checker.racedetection.properties";
            props = new Properties();
            InputStream is = ClassLoader.getSystemClassLoader().getResourceAsStream(propFile);
            if (is != null) {
                props.load(is);

                //populate data structures
                loadEvents();

                //remove redundant events
                removeRedundantEvents();

                //generate constraint model
                initSolver();
                long modelStart = System.currentTimeMillis();
                genProgramOrderConstraints();
                genCommunicationConstraints();
                genForkStartConstraints();
                genJoinExitConstraints();
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

    static void printRWSet() {
        System.out.println("*** READ SET");
        for(List<RWEvent> eventList : readSet.values()) {
            for(RWEvent rwe : eventList) {
                System.out.println("*** " + rwe.toString());
            }
        }
        System.out.println("*** WRITE SET");
        for(List<RWEvent> eventList : writeSet.values()) {
            for(RWEvent rwe : eventList) {
                System.out.println("*** " + rwe.toString());
            }
        }
    }

    static void printLocks() {
        System.out.println("*** LOCK SET");
        for(List<MyPair<LockEvent, LockEvent>> eventList : lockEvents.values()) {
            for(MyPair<LockEvent, LockEvent> e : eventList) {
                String fst = e.getFirst() != null? e.getFirst().toString() : null;
                String snd = e.getSecond() != null? e.getSecond().toString() : null;
                System.out.println("*** " + fst  +" -> " + snd);
            }
        }
    }

    /**
     * Inserts a value in a list associated with a key and creates the
     * list if it doesnt exist already
     * Returns true iff the value didnt exist before in the list
     */
    public static <K,V> boolean insertInMapToLists(Map<K,List<V>> map, K key, V value) {
        List<V> values_list = map.get(key);

        if(values_list == null) {
            values_list = new ArrayList<V>();
            map.put(key, values_list);
        }
        boolean contains = values_list.contains(value);
        values_list.add(value);
        return !contains;
    }

    public static <K,V> boolean insertInMapToSets(Map<K,Set<V>> map, K key, V value) {
        Set<V> values_list = map.get(key);

        if(values_list == null) {
            values_list = new HashSet<V>();
            map.put(key, values_list);
        }
        boolean contains = values_list.contains(value);
        values_list.add(value);
        return !contains;
    }

    public static <K,V> void insertAllInMapToSet(Map<K,Set<V>> map, K key, Collection<V> values) {
        if(values == null){
            return;
        }
        Set<V> valuesSet = map.get(key);

        if(valuesSet == null) {
            valuesSet = new HashSet<V>();
            map.put(key, valuesSet);
        }
        valuesSet.addAll(values);
    }

    public static void printDebugInfo() {
        System.out.println("*************************************************");

        System.out.println("Concurrency contexts:");
        for(Map.Entry<String, Set<Long>> cc : concurrencyContexts.entrySet()) {
            System.out.println(cc.getKey() + " : " + cc.getValue().toString());
        }

        System.out.println("Concurrency Histories:");
        for(Map.Entry<String, Set<Long>> cc : concurrencyHistories.entrySet()) {
            System.out.println(cc.getKey() + " : " + cc.getValue().toString());
        }

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

        long count = 0;
        EventIterator events = new EventIterator(threadExecution.values());
        while(events.hasNext()) {
            Event e = events.next();
            String thread = e.getThread();
            EventType type = e.getType();

            if(type == null)
                throw new RuntimeException("EventType not known");

            switch (type) {
                case UNLOCK:
                    LockEvent le = (LockEvent) e;
                    //temporary use of hashCode
                    insertInMapToSets(concurrencyContexts, thread, (long) le.getVariable().hashCode());
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
                        redundantEvents.add(e.getId());
                        //remove from readSet and writeSet
                        (type == READ? readSet : writeSet).get(rwe.getVariable()).remove(rwe);
                        count++;
                    }
                    break;

                case SND:
                    SocketEvent se = (SocketEvent) e;
                    insertInMapToSets(concurrencyContexts, thread, se.getMsgId());
                    //the concurrency context changed
                    break;
                case CREATE:
                    // handles CREATE events the same way it handles SND
                    ThreadSyncEvent tse = (ThreadSyncEvent) e;
                    insertInMapToSets(concurrencyContexts, thread, (long) tse.hashCode());
                    break;

                default:
                    // advance e
                    break;
            }
            System.out.println("-- Event " + e.getId() + " : " + e.toString());
            printDebugInfo();

        }
        Stats.redundantEvents = count;
    }

    private static boolean checkRedundancy(RWEvent event, String thread) {
        String loc = event.getLoc();
        Set<Long> concurrencyHistory = concurrencyHistories.get(loc);
        Set<Long> concurrencyContext = concurrencyContexts.get(thread);
        MyPair<String,Integer> key = new MyPair<String, Integer>(event.getLoc(), concurrencyContext==null?0:concurrencyContext.hashCode());
        Stack<String> stack = stacks.get(key);

        if(stack == null) {
            //the stack is empty or the concurrency context of the thread was changed: in both
            //cases, we need a new stack
            stack = new Stack<String>();
            //adds concurrency context of thread to concurrency history
            insertAllInMapToSet(concurrencyHistories, loc, concurrencyContexts.get(thread));

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

    public static void loadEvents() throws IOException, JSONException {
        String events = props.getProperty("event-file");
        BufferedReader br = new BufferedReader(new FileReader(events));
        String line = br.readLine();
        while (line != null) {
            JSONObject object = new JSONObject(line);
            parseJSONEvent(object);
            line = br.readLine();

        }
        //printDataStructures();
    }


    private static void parseJSONEvent(JSONObject event) throws JSONException {
        EventType type = EventType.getEventType(event.getString("type"));
        String thread = event.getString("thread");

        if(type == null)
            throw new JSONException("Unknown event type: " + event.getString("type"));

        Stats.numEventsTrace++;

        //initialize thread map data structures
        if (!threadExecution.containsKey(thread)) {
            threadExecution.put(thread, new LinkedList<Event>());
            forkSet.put(thread, new LinkedList<ThreadSyncEvent>());
            joinSet.put(thread, new LinkedList<ThreadSyncEvent>());
        }

        //populate data structures
        switch (type) {
            case RCV:
            case SND:
                long msgId = event.getLong("message");
                String dst = event.getString("dst");
                String src = event.getString("src");
                String socketId = event.getString("socket");
                long timestamp = event.getLong("timestamp");
                SocketEvent se = new SocketEvent(thread, type, msgId, src, dst, socketId, timestamp);

                //the send event must always appear before the receive one
                //so it suffices to create the entry only in the former case
                if (type == EventType.SND) {
                    MyPair<SocketEvent, SocketEvent> pair = new MyPair<SocketEvent, SocketEvent>(se, null);
                    msgEvents.put(msgId, pair);
                } else {
                    msgEvents.get(msgId).setSecond(se);
                }
                threadExecution.get(thread).add(se);
                break;
            case READ:
            case WRITE:
                String loc = event.getString("loc");
                String var = event.getString("variable");
                long counter = event.getLong("counter");
                RWEvent rwe = new RWEvent(thread, type, loc, var, counter);
                threadExecution.get(thread).add(rwe);
                if(type == READ) {
                    if(!readSet.containsKey(var)){
                        readSet.put(var,new LinkedList<RWEvent>());
                    }
                    readSet.get(var).add(rwe);
                }
                else{
                    if(!writeSet.containsKey(var)){
                        writeSet.put(var,new LinkedList<RWEvent>());
                    }
                    writeSet.get(var).add(rwe);
                }
                break;
            case START:
            case END:
                Event te = new Event(thread, type);
                threadExecution.get(thread).add(te);
                break;
            case HNDLBEG:
            case HNDLEND:
                String method = event.getString("method");
                counter = event.getLong("counter");
                HandlerEvent he = new HandlerEvent(thread, type, method, counter);
                threadExecution.get(thread).add(he);
                break;
            case CREATE:
            case JOIN:
                String child = event.getString("child");
                Event tse = new ThreadSyncEvent(thread, type, child);
                threadExecution.get(thread).add(tse);
                if (type == EventType.CREATE)
                    forkSet.get(thread).add((ThreadSyncEvent) tse);
                else
                    joinSet.get(thread).add((ThreadSyncEvent) tse);
                break;
            case LOCK:
            case UNLOCK:
                String location = event.getString("loc");
                String variable = event.getString("variable");
                long count = event.getLong("counter");
                LockEvent lockEvent = new LockEvent(thread, type, location, variable, count);
                threadExecution.get(thread).add(lockEvent);
                if (type == LOCK) {
                    List<MyPair<LockEvent, LockEvent>> pairList = lockEvents.get(variable);
                    MyPair<LockEvent,LockEvent> pair = pairList!=null? pairList.get(pairList.size() - 1) : null;
                    if(pair == null || pair.getSecond() != null) {
                        // Only adds the lock event if the previous lock event has a corresponding unlock
                        // in order to handle Reentrant Locks
                        insertInMapToLists(lockEvents, variable, new MyPair<LockEvent, LockEvent>(lockEvent, null));
                    }
                } else {
                    // second component is the unlock event associated with the lock
                    List<MyPair<LockEvent, LockEvent>> pairList = lockEvents.get(variable);
                    MyPair<LockEvent,LockEvent> pair = pairList!=null? pairList.get(pairList.size() - 1) : null;
                    if(pair == null) {
                        insertInMapToLists(lockEvents, variable, new MyPair<LockEvent, LockEvent>(null,lockEvent));
                    } else {
                        pair.setSecond(lockEvent);
                    }
                }
                break;
            default:
                throw new JSONException("Unknown event type: " + type);
        }
    }

    private static void printDataStructures() {
        System.out.println("--- THREAD EVENTS ---");
        for (String t : threadExecution.keySet()) {
            System.out.println("#" + t);
            for (Event e : threadExecution.get(t)) {
                System.out.println(" " + e.toString());
            }
        }

        System.out.println("\n--- SOCKET MESSAGE EVENTS ---");
        for (MyPair<SocketEvent, SocketEvent> se : msgEvents.values()) {
            System.out.println(se.getFirst() + " -> " + se.getSecond());
        }

        System.out.println("\n--- READ EVENTS ---");
        for (List<RWEvent> rset : readSet.values()) {
            for (RWEvent r : rset) {
                System.out.println(r);
            }
        }

        System.out.println("\n--- WRITE EVENTS ---");
        for (List<RWEvent> wset : writeSet.values()) {
            for (RWEvent w : wset) {
                System.out.println(w);
            }
        }

        System.out.println("\n--- FORK EVENTS ---");
        for (List<ThreadSyncEvent> fset : forkSet.values()) {
            for (Event f : fset) {
                System.out.println(f);
            }
        }
        System.out.println("\n--- JOIN EVENTS ---");
        for (List<ThreadSyncEvent> jset : joinSet.values()) {
            for (Event j : jset) {
                System.out.println(j);
            }
        }
    }


    public static void genDataRaceCandidates() {
        // generate all pairs of data race candidates
        // a pair of RW operations is a candidate if:
        // a) at least of one of the operations is a write
        // b) both operations access the same variable
        // c) operations are from different threads, but from the same node
        for(String var : writeSet.keySet()){
            for(RWEvent w1 : writeSet.get(var)){

                //pair with all other writes
                for (RWEvent w2 : writeSet.get(var)) {
                    if (w1.conflictsWith(w2)) {
                        MyPair<RWEvent, RWEvent> tmpPair = new MyPair<RWEvent, RWEvent>(w1, w2);
                        if (!dataRaceCandidates.contains(tmpPair)) {
                            dataRaceCandidates.add(tmpPair);
                        }
                    }
                }

                //pair with all other reads
                if(readSet.containsKey(var)) {
                    for (RWEvent r2 : readSet.get(var)) {
                        MyPair<RWEvent, RWEvent> tmpPair = new MyPair<RWEvent, RWEvent>(w1, r2);
                        if (w1.conflictsWith(r2) && !dataRaceCandidates.contains(tmpPair)) {
                            dataRaceCandidates.add(tmpPair);
                        }
                    }
                }
            }
        }

        //DEBUG: print candidate pairs
        /*for(MyPair<RWEvent,RWEvent> pair : dataRaceCandidates){
            System.out.println(pair);
        }//*/
    }

    public static void genMsgRaceCandidates(){
        // generate all pairs of message race candidates
        // a pair of RCV operations is a candidate if:
        // a) both occur at the same node
        // b) are either from different threads of from different message handlers in the same thread
        List<MyPair<SocketEvent, SocketEvent>> list = new ArrayList<MyPair<SocketEvent, SocketEvent>>(msgEvents.values());
        ListIterator<MyPair<SocketEvent, SocketEvent>> pairIterator_i = list.listIterator(0);
        ListIterator<MyPair<SocketEvent, SocketEvent>> pairIterator_j;

        while(pairIterator_i.hasNext()){
            SocketEvent rcv1 = pairIterator_i.next().getSecond();
            //advance iterator to have two different pairs
            pairIterator_j = list.listIterator(pairIterator_i.nextIndex());

            while(pairIterator_j.hasNext()){
                SocketEvent rcv2 = pairIterator_j.next().getSecond();
                if(rcv1.conflictsWith(rcv2)){
                    //make a pair with SND events because
                    //two RCV events are racing if their respective SND events have the same order
                    SocketEvent snd1 = msgEvents.get(rcv1.getMsgId()).getFirst();
                    SocketEvent snd2 = msgEvents.get(rcv2.getMsgId()).getFirst();
                    MyPair<SocketEvent,SocketEvent> raceCandidate = new MyPair<SocketEvent, SocketEvent>(snd1,snd2);
                    msgRaceCandidates.add(raceCandidate);
                }
            }
        }

        //DEBUG: print candidate pairs
        /*for(MyPair<? extends Event,? extends Event> pair : msgRaceCandidates){
            System.out.println(pair);
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
            System.out.println("-- "+conf);
        }
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
            SocketEvent rcv1 = msgEvents.get(snd1.getMsgId()).getSecond();
            SocketEvent rcv2 = msgEvents.get(snd2.getMsgId()).getSecond();
            MyPair<SocketEvent, SocketEvent> rcv_conf = new MyPair<SocketEvent, SocketEvent>(rcv1, rcv2);
            System.out.println("-- "+rcv_conf);
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
        for (List<Event> l : threadExecution.values()) {
            max += l.size();
        }
        solver.writeConst(solver.declareIntVar("MAX"));
        solver.writeConst(solver.postAssert(solver.cEq("MAX", String.valueOf(max))));

        //generate program order variables and constraints
        for (List<Event> events : threadExecution.values()) {
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
                        solver.writeConst(solver.postAssert(solver.cLt(globalConstHead)));

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
        for (MyPair<SocketEvent, SocketEvent> pair : msgEvents.values()) {
            if(pair.getFirst()!= null && pair.getSecond()!=null) {
                String cnst = solver.cLt(pair.getFirst().toString(), pair.getSecond().toString());
                solver.writeConst(solver.postAssert(cnst));
            }
        }
    }

    public static void genForkStartConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate fork-start constraints");
        solver.writeComment("FORK-START CONSTRAINTS");
        for(List<ThreadSyncEvent> l : forkSet.values()){
            for(ThreadSyncEvent e : l){
                String cnst = solver.cLt(e.toString(), "START_"+e.getChild());
                solver.writeConst(solver.postAssert(cnst));
            }
        }
    }

    public static void genJoinExitConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate join-end constraints");
        solver.writeComment("JOIN-END CONSTRAINTS");
        for(List<ThreadSyncEvent> l : joinSet.values()){
            for(ThreadSyncEvent e : l){
                String cnst = solver.cLt("END_"+e.getChild(), e.toString());
                solver.writeConst(solver.postAssert(cnst));
            }
        }
    }


    public static void genLockingConstraints() throws IOException {
        System.out.println("[MinhaChecker] Generate locking constraints");
        solver.writeComment("LOCKING CONSTRAINTS");
        for(String var : lockEvents.keySet()){
            // for two lock/unlock pairs on the same locking object,
            // one pair must be executed either before or after the other
            ListIterator<MyPair<LockEvent, LockEvent>> pairIterator_i = lockEvents.get(var).listIterator(0);
            ListIterator<MyPair<LockEvent, LockEvent>> pairIterator_j;

            while(pairIterator_i.hasNext()){
                MyPair<LockEvent, LockEvent> pair_i = pairIterator_i.next();
                //advance iterator to have two different pairs
                pairIterator_j =  lockEvents.get(var).listIterator(pairIterator_i.nextIndex());

                while(pairIterator_j.hasNext()) {
                    MyPair<LockEvent, LockEvent> pair_j = pairIterator_j.next();

                    //there is no need to add constraints for locking pairs of the same thread
                    //as they are already encoded in the program order constraints
                    if (pair_i.getFirst().getThread().equals(pair_j.getFirst().getThread()))
                        continue;

                    // Ui < Lj || Uj < Li
                    String cnstUi_Lj = solver.cLt(pair_i.getSecond().toString(), pair_j.getFirst().toString());
                    String cnstUj_Li = solver.cLt(pair_j.getSecond().toString(), pair_i.getFirst().toString());
                    String cnst = solver.cOr(cnstUi_Lj, cnstUj_Li);
                    solver.writeConst(solver.postAssert(cnst));
                }
            }
        }
    }
}
