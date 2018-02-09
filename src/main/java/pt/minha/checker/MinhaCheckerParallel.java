package pt.minha.checker;

import org.json.JSONException;
import org.json.JSONObject;
import pt.minha.checker.events.*;
import pt.minha.checker.solver.Solver;
import pt.minha.checker.solver.Z3SolverParallel;
import pt.minha.checker.stats.Stats;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.locks.Lock;

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
    public static Map<Long, MyPair<SocketEvent, SocketEvent>> msgEvents;   //Map: message id -> pair of events (snd,rcv)
    public static Map<String, List<MyPair<LockEvent, LockEvent>>> lockEvents;    //Map: variable -> list of pairs of locks/unlocks
    public static Map<String, List<Event>> threadExecution;                //Map: thread -> list of all events in that thread's execution
    public static Map<String, List<RWEvent>> readSet;                      //Map: variable -> list of reads to that variable by all threads
    public static Map<String, List<RWEvent>> writeSet;                     //Map: variable -> list of writes to that variable by all threads
    public static Map<String, List<ThreadSyncEvent>> forkSet;              //Map: thread -> list of thread's fork events
    public static Map<String, List<ThreadSyncEvent>> joinSet;              //Map: thread -> list of thread's join events
    public static HashSet<MyPair<RWEvent,RWEvent>> conflictCandidates;

    //Redundancy Elimination structures
    //Map: thread id -> list of he ids of the messages in the concurrency context
    public static Map<String, List<Long>> concurrencyContexts;
    //Map: location -> concurreny history of that location (set of message ids)
    public static Map<Integer, Set<Long>> concurrencyHistories;
    //Map: hashCode(TETAlocation), thread -> stack of Threads
    public static Map<MyPair<Integer, String>, Stack<String>> stacks;

    //Set of threads whose concurrency context was changed
    public static Set<String> changedConcurrencyContexts;

    //solver stuff
    public static Solver solver;

    public static void main(String args[]) {
        msgEvents = new HashMap<Long, MyPair<SocketEvent, SocketEvent>>();
        lockEvents = new HashMap<String, List<MyPair<LockEvent,LockEvent>>>();
        threadExecution = new HashMap<String, List<Event>>();
        readSet = new HashMap<String, List<RWEvent>>();
        writeSet = new HashMap<String, List<RWEvent>>();
        forkSet = new HashMap<String, List<ThreadSyncEvent>>();
        joinSet = new HashMap<String, List<ThreadSyncEvent>>();
        conflictCandidates = new HashSet<MyPair<RWEvent, RWEvent>>();

        //Redundancy-check related initializations
        changedConcurrencyContexts = new HashSet<String>();
        concurrencyContexts = new HashMap<String, List<Long>>();
        concurrencyHistories = new HashMap<Integer, Set<Long>>();
        stacks = new HashMap<MyPair<Integer, String>, Stack<String>>();

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

                //printDataStructures();
                //printRWSet();
                //printLocks();

                //generate constraint model
                initSolver();
                long modelStart = System.currentTimeMillis();
                genProgramOrderConstraints();
                genCommunicationConstraints();
                genForkStartConstraints();
                genJoinExitConstraints();
                Stats.buildingModeltime = System.currentTimeMillis() - modelStart;

                //check conflicts
                genConflictCandidates();
                checkConflicts();
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
     */
    public static <K,V> void insertInMapToLists(Map<K,List<V>> map, K key, V value) {
        List<V> values_list = map.get(key);

        if(values_list == null) {
            values_list = new ArrayList<V>();
            map.put(key, values_list);
        }
        values_list.add(value);
    }

    public static <K,V> void insertAllInMapToLists(Map<K,Set<V>> map, K key, Collection<V> values) {
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

    public static void removeRedundantEvents() {
        // What I am assuming: the function getStack in ReX depends on teta-loc (and Gama-t?yes)
        //                     Teta-t and Gama-t can only grow or stay the same, never decrease during ReX

        long count = 0;
        EventIterator events = new EventIterator(threadExecution.values());
        while(events.hasNext()) {
            Event e = events.next();
            String thread = e.getThread();
            EventType type = e.getType();

            if(type == null)
                throw new RuntimeException("EventType not known");

            switch (type) {
                //TODO: Add REL
                //MEM Access
                case READ:
                case WRITE:
                    RWEvent rwe = (RWEvent) e;
                    if(checkRedundancy(rwe, thread, rwe.getLoc())) {
                        //if an event is redundant, remove from the trace
                        events.remove();
                        //remove from readSet and writeSet
                        if(type == READ) {
                            readSet.get(rwe.getVariable()).remove(rwe);
                        } else {
                            writeSet.get(rwe.getVariable()).remove(rwe);
                        }
                        count++;
                    }
                    break;

                case SND:
                    SocketEvent se = (SocketEvent) e;
                    insertInMapToLists(concurrencyContexts, thread, se.getMsgId());
                    //the concurrency context changed
                    changedConcurrencyContexts.add(thread);
                    break;

                default:
                    break;
            }
        }
        Stats.redundantEvents = count;
    }

    private static boolean checkRedundancy(Event event, String thread, int loc) {
        Set<Long> concurrencyHistory = concurrencyHistories.get(loc);
        MyPair<Integer,String> key = new MyPair<Integer, String>(concurrencyHistory == null? 0 : concurrencyHistory.hashCode(), thread);
        Stack<String> stack = stacks.get(key);

        if(stack == null || changedConcurrencyContexts.contains(thread)) {
            //the stack is empty or the concurrency context of the thread was changed: in both
            //cases, we need a new stack
            stack = new Stack<String>();
            //adds concurrency context of thread to concurrency history
            insertAllInMapToLists(concurrencyHistories, loc, concurrencyContexts.get(thread));

            //calculates the new key for the current state of the concurrency history
            Set<Long> newConcurrencyHistory = concurrencyHistories.get(loc);
            MyPair<Integer,String> newKey = new MyPair<Integer, String>(newConcurrencyHistory == null? 0 : newConcurrencyHistory.hashCode(), thread);
            stacks.put(key, stack);

            stack.push(thread);
            //marks the concurrency of the thread as unchenaged if it isnt already
            changedConcurrencyContexts.remove(thread);
            return false;
        } else if(stack.contains(thread) || stack.size() == 2) {
            //if the stack already contains the thread or is full
            return true;
        } else {
            //Stack has size 1 and does not contain the thread
            stack.push(thread);
            return false;
        }
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
                int loc = event.getInt("loc");
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
                int location = event.getInt("loc");
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


    public static void genConflictCandidates() {
        // generate all pairs of conflict candidates
        // a pair of RW operations is a candidate if:
        // a) at least of one of the operations is a write
        // b) both operations access the same variable
        // c) operations are from different threads, but from the same node
        for(String var : writeSet.keySet()){
            for(RWEvent w1 : writeSet.get(var)){

                //pair with all other writes
                for(RWEvent w2 : writeSet.get(var)){
                    if(w1.conflictsWith(w2)){
                        MyPair<RWEvent,RWEvent> tmpPair = new MyPair<RWEvent, RWEvent>(w1,w2);
                        if(!conflictCandidates.contains(tmpPair)){
                            conflictCandidates.add(tmpPair);
                        }
                    }
                }

                //pair with all other reads
                for(RWEvent r2 : readSet.get(var)){
                    MyPair<RWEvent,RWEvent> tmpPair = new MyPair<RWEvent, RWEvent>(w1,r2);
                    if(w1.conflictsWith(r2) && !conflictCandidates.contains(tmpPair)){
                        conflictCandidates.add(tmpPair);
                    }
                }
            }
        }

        //DEBUG: print candidate pairs
        /*for(MyPair<RWEvent,RWEvent> pair : conflictCandidates){
            System.out.println(pair);
        }*/
    }

    public static void checkConflicts() throws IOException{
        solver.writeComment("CONFLICT CONSTRAINTS");
        Stats.totalCandidatePairs = conflictCandidates.size();
        System.out.println("[MinhaChecker] Start data race checking ("+Stats.totalCandidatePairs+" candidates)");
        long checkingStart = System.currentTimeMillis();
        conflictCandidates = ((Z3SolverParallel) solver).checkConflictsParallel(conflictCandidates);
        Stats.checkingTime = System.currentTimeMillis() - checkingStart;
        Stats.totalDataRacePairs = conflictCandidates.size();
        System.out.println("#Conflict Candidates: "+Stats.totalCandidatePairs+" | #Actual Conflicts: "+Stats.totalDataRacePairs);
        for(MyPair<RWEvent,RWEvent> conf : conflictCandidates){
            System.out.println("-- "+conf);
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
}
