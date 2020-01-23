package pt.haslab.spider;

import com.sun.tools.javac.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.haslab.spider.solver.Z3SolverParallel;
import pt.haslab.taz.TraceProcessor;
import pt.haslab.taz.causality.CausalPair;
import pt.haslab.taz.causality.MessageCausalPair;
import pt.haslab.taz.events.Event;
import pt.haslab.taz.events.EventType;
import pt.haslab.taz.events.RWEvent;
import pt.haslab.taz.events.SocketEvent;
import pt.haslab.taz.events.SyncEvent;
import pt.haslab.taz.events.ThreadCreationEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Collectors;

import static pt.haslab.taz.events.EventType.NOTIFYALL;

class RaceDetector
{

    private static final Logger logger = LoggerFactory.getLogger( RaceDetector.class );

    private HashSet<CausalPair<? extends Event, ? extends Event>> dataRaceCandidates;

    private HashSet<CausalPair<? extends Event, ? extends Event>> msgRaceCandidates;

    // HB model, used to encode message arrival constraints
    private HashMap<SocketEvent, Event> rcvNextEvent;

    private Z3SolverParallel solver;

    private TraceProcessor traceProcessor;

    public RaceDetector( Z3SolverParallel solver, TraceProcessor traceProcessor )
    {
        dataRaceCandidates = new HashSet<>();
        msgRaceCandidates = new HashSet<>();
        rcvNextEvent = new HashMap<>();
        this.solver = solver;
        this.traceProcessor = traceProcessor;
    }

    // TODO: use the same way to order a string as used in `getRacesAsLocationPairs`
    private static String causalPairToOrderedString(
                    CausalPair<? extends Event, ? extends Event> pair )
    {
        String fst =
                        pair.getFirst() != null
                                        ? pair.getFirst().toString() + ":" + pair.getFirst().getLineOfCode()
                                        : " ";
        String snd =
                        pair.getSecond() != null
                                        ? pair.getSecond().toString() + ":" + pair.getSecond().getLineOfCode()
                                        : " ";
        if ( fst.compareTo( snd ) < 0 )
        {
            return "(" + snd + ", " + fst + ")";
        }
        return "(" + fst + ", " + snd + ")";
    }

    public void generateConstraintModel()
                    throws IOException
    {
        long modelStart = System.currentTimeMillis();
        genIntraNodeConstraints();
        genInterNodeConstraints();
        double buildingModelTime = System.currentTimeMillis() - modelStart;
        logger.info(
                        "Time to generate constraint model: " + ( buildingModelTime / (double) 1000 ) + " seconds" );
    }

    public void checkConflicts()
                    throws IOException
    {
        genDataRaceCandidates();
        genMsgRaceCandidates();
        computeActualDataRaces();
        computeActualMsgRaces();
    }

    /**
     * Computes which of the data race candidates are actually data races. Must run
     * genDataRaceCandidates() before.
     */
    public void computeActualDataRaces()
    {
        if ( dataRaceCandidates.isEmpty() )
        {
            System.out.println(
                            "[MinhaChecker] No data races to check ("
                                            + Stats.INSTANCE.totalDataRaceCandidates
                                            + " candidates)" );
            return;
        }

        Stats.INSTANCE.totalDataRaceCandidates = dataRaceCandidates.size();
        Stats.INSTANCE.totalDataRaceCandidateLocations = countDataRaces();
        System.out.println(
                        "\n[MinhaChecker] Start data race checking ("
                                        + Stats.INSTANCE.totalDataRaceCandidates
                                        + " candidates)" );

        long checkingStart = System.currentTimeMillis();
        dataRaceCandidates = solver.checkRacesParallel( dataRaceCandidates );
        Stats.INSTANCE.checkingTimeDataRace = System.currentTimeMillis() - checkingStart;
        Stats.INSTANCE.totalDataRacePairs = dataRaceCandidates.size();
        Stats.INSTANCE.totalDataRacePairLocations = countDataRaces();

        System.out.println(
                        "\n#Data Race Candidates: "
                                        + Stats.INSTANCE.totalDataRaceCandidates
                                        + " | #Actual Data Races: "
                                        + Stats.INSTANCE.totalDataRacePairs );
        prettyPrintDataRaces();
    }

    /**
     * Generate all pairs of message race candidates. A pair of RCV operations is a candidate if: a)
     * both occur at the same node b) are either from different threads of from different message
     * handlers in the same thread
     *
     * @throws IOException
     */
    public void genMsgRaceCandidates()
                    throws IOException
    {
        List<MessageCausalPair> list = new ArrayList<>( traceProcessor.sndRcvPairs.values() );
        ListIterator<MessageCausalPair> pairIterator_i = list.listIterator( 0 );
        ListIterator<MessageCausalPair> pairIterator_j;

        solver.writeComment( "SOCKET CHANNEL CONSTRAINTS" );
        while ( pairIterator_i.hasNext() )
        {

            // Notice here that we are assuming that the collected trace
            // is agnostic of network partitioning. As such, we obtain only the first
            // element in the RCV events list for a message. This is true for all
            // the code in this class.
            SocketEvent rcv1 = pairIterator_i.next().getRcv( 0 );

            if ( rcv1 == null )
            {
                continue;
            }

            // advance iterator to have two different pairs
            pairIterator_j = list.listIterator( pairIterator_i.nextIndex() );

            while ( pairIterator_j.hasNext() )
            {
                SocketEvent rcv2 = pairIterator_j.next().getRcv( 0 );
                if ( rcv2 == null )
                {
                    continue;
                }

                if ( rcv1.conflictsWith( rcv2 ) )
                {
                    // make a pair with SND events because
                    // two messages a and b are racing if RCVa || SNDb
                    SocketEvent snd1 = traceProcessor.sndRcvPairs.get( rcv1.getMessageId() ).getSnd( 0 );
                    SocketEvent snd2 = traceProcessor.sndRcvPairs.get( rcv2.getMessageId() ).getSnd( 0 );
                    CausalPair<SocketEvent, SocketEvent> raceCandidate;

                    if ( rcv1.getEventId() < rcv2.getEventId() )
                    {
                        raceCandidate = new CausalPair<>( snd2, rcv1 );
                    }
                    else
                    {
                        raceCandidate = new CausalPair<>( snd1, rcv2 );
                    }
                    msgRaceCandidates.add( raceCandidate );

                    // if socket channel is TCP and SNDs are from the same thread,
                    // then add constraint stating that RCV1 happens before SND2
                    if ( snd1.getSocketType() == SocketEvent.SocketType.TCP
                                    && snd2.getSocketType() == SocketEvent.SocketType.TCP
                                    && snd1.getThread().equals( snd2.getThread() ) )
                    {

                        String cnst;
                        // check trace order of SND, as the iterator does not traverse the events
                        // according to the program order
                        if ( snd1.getEventId() < snd2.getEventId() )
                        {
                            cnst = solver.cLt( rcv1.toString(), snd2.toString() );
                        }
                        else
                        {
                            cnst = solver.cLt( rcv2.toString(), snd1.toString() );
                        }
                        solver.writeConst( solver.postNamedAssert( cnst, "TCP" ) );
                    }
                }
            }
        }
    }

    /**
     * Computes which of the message race candidates are actually message races. Must run
     * genMsgRaceCandidates() before.
     */
    public void computeActualMsgRaces()
    {
        if ( msgRaceCandidates.isEmpty() )
        {
            System.out.println(
                            "[MinhaChecker] No message races to check ("
                                            + Stats.INSTANCE.totalMsgRaceCandidates
                                            + " candidates)" );
            return;
        }

        Stats.INSTANCE.totalMsgRaceCandidates = msgRaceCandidates.size();

        System.out.println(
                        "\n[MinhaChecker] Start message race checking ("
                                        + Stats.INSTANCE.totalMsgRaceCandidates
                                        + " candidates)" );

        long checkingStart = System.currentTimeMillis();
        msgRaceCandidates = solver.checkRacesParallel( msgRaceCandidates );
        Stats.INSTANCE.checkingTimeMsgRace = System.currentTimeMillis() - checkingStart;
        Stats.INSTANCE.totalMsgRacePairs = msgRaceCandidates.size();

        // TODO: use the number of locations instead of number of causalPairs
        System.out.println(
                        "\n#Message Race Candidates: "
                                        + Stats.INSTANCE.totalMsgRaceCandidates
                                        + " | #Actual Message Races: "
                                        + Stats.INSTANCE.totalMsgRacePairs );
        prettyPrintMessageRaces();
        computeDataRacesFromMsgRaces();
    }

    private void computeDataRacesFromMsgRaces()
    {
        Set<String> dataRacesFromMsgs = new HashSet<>();
        for ( CausalPair<? extends Event, ? extends Event> causalPair : msgRaceCandidates )
        {

            SocketEvent e1 = (SocketEvent) causalPair.getFirst();
            SocketEvent e2 = (SocketEvent) causalPair.getSecond();

            // e1 and e2 may be either SND or RCV
            // get the corresponding message handler
            String msgId1 = e1.getMessageId();
            String msgId2 = e2.getMessageId();
            // once again, it is assumed that there is no network partition
            SocketEvent rcv1 = traceProcessor.sndRcvPairs.get( msgId1 ).getRcv( 0 );
            SocketEvent rcv2 = traceProcessor.sndRcvPairs.get( msgId2 ).getRcv( 0 );

            // check if rcv1 and rcv2 occur at the same node and short circuit otherwise
            if ( !rcv1.getNodeId().equals( rcv2.getNodeId() ) )
            {
                continue;
            }

            List<Event> handler1 = traceProcessor.handlerEvents.get( rcv1 );

            if ( handler1 == null )
            {
                continue;
            }

            // get variables accessed in handler 1
            // set of read events per variable
            Map<String, Set<RWEvent>> accessesRead1 = new HashMap<>();
            // set of write events per variable
            Map<String, Set<RWEvent>> accessesWrite1 = new HashMap<>();

            for ( Event e : handler1 )
            {
                switch ( e.getType() )
                {
                    case READ:
                        RWEvent readEvent = (RWEvent) e;
                        accessesRead1.computeIfAbsent( readEvent.getVariable(), k -> new HashSet<>() )
                                     .add( readEvent );
                        break;
                    case WRITE:
                        RWEvent writeEvent = (RWEvent) e;
                        accessesWrite1.computeIfAbsent( writeEvent.getVariable(), k -> new HashSet<>() )
                                      .add( writeEvent );
                        break;
                    default:
                        break;
                }
            }

            // Do the same for message 2
            List<Event> handler2 = traceProcessor.handlerEvents.get( rcv2 );

            if ( handler2 == null )
            {
                continue;
            }

            // get variables accessed in handler 2
            // set of read events per variable
            Map<String, Set<RWEvent>> accessesRead2 = new HashMap<>();
            // set of write events per variable
            Map<String, Set<RWEvent>> accessesWrite2 = new HashMap<>();
            for ( Event e : handler2 )
            {
                switch ( e.getType() )
                {
                    case READ:
                        RWEvent readEvent = (RWEvent) e;
                        accessesRead2.computeIfAbsent( readEvent.getVariable(), k -> new HashSet<>() )
                                     .add( readEvent );
                        break;
                    case WRITE:
                        RWEvent writeEvent = (RWEvent) e;
                        accessesWrite2.computeIfAbsent( writeEvent.getVariable(), k -> new HashSet<>() )
                                      .add( writeEvent );
                        break;
                    default:
                        break;
                }
            }

            // obtain sets of all variables accessed
            Set<String> accessedVars = new HashSet<>();
            accessedVars.addAll( accessesRead1.keySet() );
            accessedVars.addAll( accessesWrite1.keySet() );
            accessedVars.addAll( accessesRead2.keySet() );
            accessedVars.addAll( accessesWrite2.keySet() );

            // for all variables accessed:
            for ( String var : accessedVars )
            {
                // 1. for all write accesses to a variable, they collide with every write and read access
                for ( RWEvent event1 : accessesWrite1.computeIfAbsent( var, k -> new HashSet<>() ) )
                {
                    for ( RWEvent event2 : accessesWrite2.computeIfAbsent( var, k -> new HashSet<>() ) )
                    {
                        CausalPair<RWEvent, RWEvent> dataRace = new CausalPair<>( event1, event2 );
                        dataRacesFromMsgs.add( getRaceAsLocationPair( dataRace ) );
                    }
                }

                for ( RWEvent event1 : accessesWrite1.computeIfAbsent( var, k -> new HashSet<>() ) )
                {
                    for ( RWEvent event2 : accessesRead2.computeIfAbsent( var, k -> new HashSet<>() ) )
                    {
                        CausalPair<RWEvent, RWEvent> dataRace = new CausalPair<>( event1, event2 );
                        dataRacesFromMsgs.add( getRaceAsLocationPair( dataRace ) );
                    }
                }

                // 2. for all read accesses to a variable, they collide with every write access
                for ( RWEvent event1 : accessesRead1.computeIfAbsent( var, k -> new HashSet<>() ) )
                {
                    for ( RWEvent event2 : accessesWrite2.computeIfAbsent( var, k -> new HashSet<>() ) )
                    {
                        CausalPair<RWEvent, RWEvent> dataRace = new CausalPair<>( event1, event2 );
                        dataRacesFromMsgs.add( getRaceAsLocationPair( dataRace ) );
                    }
                }
            }
        }

        // print them
        int i = 0;
        System.out.println( "\n#Data Races from Message Races: " + dataRacesFromMsgs.size() );
        for ( String pair : dataRacesFromMsgs )
        {
            i++;
            System.out.println( " > Data Race #" + i + " : " + pair );
        }

        // update Stats data structure
        //Stats.INSTANCE.dataRacesFromMsgs = ...
    }

    private void prettyPrintDataRaces()
    {
        long i = 0;
        Set<String> pairsOfLocations = getRacesAsLocationPairs( dataRaceCandidates );

        for ( String pair : pairsOfLocations )
        {
            i++;
            System.out.println( " > Data Race #" + i + " : " + pair );
        }
    }

    private void prettyPrintMessageRaces()
    {
        int i = 1;
        for ( CausalPair<? extends Event, ? extends Event> conf : msgRaceCandidates )
        {
            // translate SND events to their respective RCV events
            SocketEvent snd1 = (SocketEvent) conf.getFirst();
            SocketEvent snd2 = (SocketEvent) conf.getSecond();
            SocketEvent rcv1 = traceProcessor.sndRcvPairs.get( snd1.getMessageId() ).getRcv( 0 );
            SocketEvent rcv2 = traceProcessor.sndRcvPairs.get( snd2.getMessageId() ).getRcv( 0 );
            CausalPair<SocketEvent, SocketEvent> rcv_conf = new CausalPair<>( rcv1, rcv2 );
            System.out.println(
                            " > Message-Message Race #" + i++ + " : " + causalPairToOrderedString( rcv_conf ) );

            // compute read-write sets for each message handler
            if ( !traceProcessor.handlerEvents.containsKey( rcv1 )
                            || !traceProcessor.handlerEvents.containsKey( rcv2 ) )
            {
                // System.out.println("\t-- No conflicts");
            }
            else
            {
                HashSet<RWEvent> readWriteSet1 = new HashSet<>();
                HashSet<RWEvent> readWriteSet2 = new HashSet<>();

                for ( Event e : traceProcessor.handlerEvents.get( rcv1 ) )
                {
                    if ( e.getType() == EventType.READ || e.getType() == EventType.WRITE )
                    {
                        readWriteSet1.add( (RWEvent) e );
                    }
                }

                for ( Event e : traceProcessor.handlerEvents.get( rcv2 ) )
                {
                    if ( e.getType() == EventType.READ || e.getType() == EventType.WRITE )
                    {
                        readWriteSet2.add( (RWEvent) e );
                    }
                }

                // check for conflicts
                for ( RWEvent e1 : readWriteSet1 )
                {
                    for ( RWEvent e2 : readWriteSet2 )
                    {
                        if ( conflictingEvents( e1, e2 ) )
                        {
                            CausalPair<RWEvent, RWEvent> race = new CausalPair<>( e1, e2 );
                            // System.out.println("\t-- conflict " + causalPairToOrderedString(race));
                        }
                    }
                }
            }
        }
    }

    /**
     * Determines whether two memory accesses are conflicting.
     * This functionality used to be provided by method `conflictsWith` of
     * Falcon's `RWEvent` class but it was producing wrong results due to its
     * use of the field `eventId` which is of no use in Spider
     */
    private boolean conflictingEvents( RWEvent e1, RWEvent e2 )
    {
        return ( e1.getType() == EventType.WRITE || e2.getType() == EventType.WRITE ) && e1.getNodeId()
                                                                                           .equals( e2.getNodeId() )
                        && e1.getVariable().equals( e2.getVariable() ) &&
                        !e1.getThread().equals( e2.getThread() );
    }

    /**
     * Generate all pairs of data race candidates. A pair of RW operations is a candidate if: a) at
     * least of one of the operations is a write b) both operations access the same variable c)
     * operations are from different threads, but from the same node.
     */
    public void genDataRaceCandidates()
    {
        for ( String var : traceProcessor.writeEvents.keySet() )
        {
            for ( RWEvent w1 : traceProcessor.writeEvents.get( var ) )
            {
                // pair with all other writes
                for ( RWEvent w2 : traceProcessor.writeEvents.get( var ) )
                {
                    if ( conflictingEvents( w1, w2 ) )
                    {
                        CausalPair<RWEvent, RWEvent> tmpPair = new CausalPair<>( w1, w2 );
                        dataRaceCandidates.add( tmpPair );
                    }
                }

                // pair with all other reads
                if ( traceProcessor.readEvents.containsKey( var ) )
                {
                    for ( RWEvent r2 : traceProcessor.readEvents.get( var ) )
                    {
                        CausalPair<RWEvent, RWEvent> tmpPair = new CausalPair<>( w1, r2 );
                        if ( conflictingEvents( w1, r2 ) )
                        {
                            dataRaceCandidates.add( tmpPair );
                        }
                    }
                }
            }
        }
    }

    private void genIntraNodeConstraints()
                    throws IOException
    {
        genProgramOrderConstraints();
        genForkStartConstraints();
        genJoinExitConstraints();
        genWaitNotifyConstraints();
        genLockingConstraints();
    }

    private void genInterNodeConstraints()
                    throws IOException
    {
        genSendReceiveConstraints();
        //genWeakMessageHandlingConstraints();
        genStrongMessageHandlingConstraints();
    }

    /**
     * Builds the order constraints within a segment and returns the position in the trace in which
     * the handler ends
     *
     * @return
     */
    private int genSegmentOrderConstraints( List<Event> events, int segmentStart )
                    throws IOException
    {

        // constraint representing the HB relation for the thread's segment
        StringBuilder orderConstraint = new StringBuilder();
        int segmentIt;

        for ( segmentIt = segmentStart; segmentIt < events.size(); segmentIt++ )
        {
            Event e = events.get( segmentIt );

            // declare variable
            String var = solver.declareIntVar( e.toString(), "0", "MAX" );
            solver.writeConst( var );

            // append event to the thread's segment
            orderConstraint.append( " " + e.toString() );

            // handle partial order within message handler
            if ( e.getType() == EventType.RCV
                            && segmentIt < ( events.size() - 1 )
                            && events.get( segmentIt + 1 ).getType() == EventType.HNDLBEG )
            {
                segmentIt = genSegmentOrderConstraints( events, segmentIt + 1 );

                // store event next to RCV to later encode the message arrival order
                if ( segmentIt < events.size() - 1 )
                {
                    rcvNextEvent.put( (SocketEvent) e, events.get( segmentIt + 1 ) );
                }
            }
            else if ( e.getType() == EventType.HNDLEND )
            {
                break;
            }
        }

        // write segment's order constraint
        solver.writeConst( solver.postNamedAssert( solver.cLt( orderConstraint.toString() ), "PC" ) );

        return segmentIt;
    }

    /**
     * Program order constraints encode the order within a thread's local trace. Asynchronous event
     * handling causes the same thread to have multiple segments (i.e. handlers), which breaks global
     * happens-before relation
     *
     * @throws IOException
     */
    private void genProgramOrderConstraints()
                    throws IOException
    {
        System.out.println( "[MinhaChecker] Generate program order constraints" );
        solver.writeComment( "PROGRAM ORDER CONSTRAINTS" );
        int max = 0;
        for ( SortedSet<Event> l : traceProcessor.eventsPerThread.values() )
        {
            max += l.size();
        }
        solver.writeConst( solver.declareIntVar( "MAX" ) );
        solver.writeConst( solver.postAssert( solver.cEq( "MAX", String.valueOf( max ) ) ) );

        // generate program order variables and constraints
        for ( SortedSet<Event> eventsSortedSet : traceProcessor.eventsPerThread.values() )
        {
            // TODO: be sure that the order of the list is the same as the SortedSet
            List<Event> events = new ArrayList<>( eventsSortedSet );
            if ( events.isEmpty() )
            {
                continue;
            }
            else if ( events.size() == 1 )
            {
                // if there's only one event, we just need to declare it as there are no program order
                // constraints
                String var = solver.declareIntVar( events.get( 0 ).toString(), "0", "MAX" );
                solver.writeConst( var );
            }
            else
            {
                // generate program constraints for the thread segment
                genSegmentOrderConstraints( events, 0 );
            }

            // build program order constraints for the whole thread trace
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

    private void genSendReceiveConstraints()
                    throws IOException
    {
        System.out.println( "[MinhaChecker] Generate communication constraints" );
        solver.writeComment( "SEND-RECEIVE CONSTRAINTS" );
        for ( MessageCausalPair pair : traceProcessor.sndRcvPairs.values() )
        {

            if ( pair.getSnd( 0 ) == null || pair.getRcv( 0 ) == null )
            {
                continue;
            }

            SocketEvent rcv = pair.getRcv( 0 );
            String cnst;

            // if there is a message handler, order SND with HANDLERBEGIN instead of RCV
            if ( !traceProcessor.handlerEvents.containsKey( rcv ) )
            {
                cnst = solver.cLt( pair.getSnd( 0 ).toString(), pair.getRcv( 0 ).toString() );
            }
            else
            {
                Event handlerbegin = traceProcessor.handlerEvents.get( rcv ).get( 0 );
                cnst = solver.cLt( pair.getSnd( 0 ).toString(), handlerbegin.toString() );
            }

            solver.writeConst( solver.postNamedAssert( cnst, "COM" ) );
        }
    }

    /**
     * Message Handling constraints encode: - message handler mutual exclusion - message arrival
     * order
     *
     * @throws IOException
     */
    private void genWeakMessageHandlingConstraints()
                    throws IOException
    {
        System.out.println( "[MinhaChecker] Generate message handling constraints" );
        solver.writeComment( "MESSAGE HANDLING CONSTRAINTS" );
        String TAG = "HND";

        HashMap<String, HashSet<SocketEvent>> rcvPerThread = new HashMap<>();

    /* encode mutual exclusion constraints, which state that two message handlers in the same thread
    must occur one before the other in any order */
        for ( SocketEvent rcv_i : traceProcessor.handlerEvents.keySet() )
        {
            // store all rcv events per thread-socket
            String key = rcv_i.getThread() + "-" + rcv_i.getDstPort();
            if ( !rcvPerThread.containsKey( key ) )
            {
                rcvPerThread.put( key, new HashSet<>() );
            }
            rcvPerThread.get( key ).add( rcv_i );

            for ( SocketEvent rcv_j : traceProcessor.handlerEvents.keySet() )
            {
                if ( rcv_i != rcv_j
                                && rcv_i.getThread().equals( rcv_j.getThread() )
                                && rcv_i.getDstPort() == rcv_j.getDstPort() )
                {

                    // mutual exclusion: HENDi < HBEGj V HENDj < HBEGi
                    String handlerBegin_i = traceProcessor.handlerEvents.get( rcv_i ).get( 0 ).toString();
                    String handlerEnd_i =
                                    traceProcessor
                                                    .handlerEvents
                                                    .get( rcv_i )
                                                    .get( traceProcessor.handlerEvents.get( rcv_i ).size() - 1 )
                                                    .toString();
                    String handlerBegin_j = traceProcessor.handlerEvents.get( rcv_j ).get( 0 ).toString();
                    String handlerEnd_j =
                                    traceProcessor
                                                    .handlerEvents
                                                    .get( rcv_j )
                                                    .get( traceProcessor.handlerEvents.get( rcv_j ).size() - 1 )
                                                    .toString();

                    String mutexConst =
                                    solver.cOr(
                                                    solver.cLt( handlerEnd_i, handlerBegin_j ),
                                                    solver.cLt( handlerEnd_j, handlerBegin_i ) );
                    solver.writeConst( solver.postNamedAssert( mutexConst, TAG ) );
                }
            }
        }

        /* encode possible message arrival order constraints, which state that each RCV event may be
         * "matched with" any message on the same socket */
        for ( HashSet<SocketEvent> rcvSet : rcvPerThread.values() )
        {
            // for all RCVi in rcvSet :
            // (RCVi < HNDBegin_i && HNDEnd_i < nextEvent) V (RCVi < HNDBegin_j && HNDEnd_j < nextEvent),
            // for all j != i
            for ( SocketEvent rcv_i : rcvSet )
            {
                Event nextEvent = rcvNextEvent.get( rcv_i );

                // if the RCV is the last event, then nextEvent == null
                if ( nextEvent == null )
                {
                    continue;
                }

                StringBuilder outerOr = new StringBuilder();
                for ( SocketEvent rcv_j : rcvSet )
                {
                    String handlerBegin_j = traceProcessor.handlerEvents.get( rcv_j ).get( 0 ).toString();
                    String handlerEnd_j =
                                    traceProcessor
                                                    .handlerEvents
                                                    .get( rcv_j )
                                                    .get( traceProcessor.handlerEvents.get( rcv_j ).size() - 1 )
                                                    .toString();
                    String innerAnd =
                                    solver.cLt(
                                                    rcv_i.toString()
                                                                    + " "
                                                                    + handlerBegin_j
                                                                    + " "
                                                                    + handlerEnd_j
                                                                    + " "
                                                                    + nextEvent.toString() );
                    outerOr.append( innerAnd + " " );
                }
                solver.writeConst( solver.postNamedAssert( solver.cOr( outerOr.toString() ), TAG ) );
            }
        }
    }

    private void genStrongMessageHandlingConstraints()
                    throws IOException
    {
        System.out.println( "[MinhaChecker] Generate message handling constraints" );
        solver.writeComment( "MESSAGE HANDLING CONSTRAINTS" );
        String TAG = "HND";

        Map<String, Set<SocketEvent>> rcvPerThread =
                        new HashMap<>();                // map: thread-socket -> list of rcv events
        Map<String, Set<Pair<String, String>>> handlersPerThread =
                        new HashMap<>();  // map: thread-socket -> list of pairs (h_begin, h_end)
        for ( SocketEvent rcv : traceProcessor.handlerEvents.keySet() )
        {
            String key = rcv.getThread() + "-" + rcv.getDstPort();
            rcvPerThread.putIfAbsent( key, new HashSet<>() );
            handlersPerThread.putIfAbsent( key, new HashSet<>() );

            // store all rcv events per thread-socket
            rcvPerThread.get( key ).add( rcv );

            // store all handler pairs per thread-socket
            List<Event> rcvHandler = traceProcessor.handlerEvents.get( rcv );
            Pair<String, String> handlerDelimiters = new Pair<>(
                            rcvHandler.get( 0 ).toString(),
                            rcvHandler.get( rcvHandler.size() - 1 ).toString() );
            handlersPerThread.get( key ).add( handlerDelimiters );
        }

        // for each rcv, add assert with disjunction of rcv-handler linkages
        for ( String threadSocket : rcvPerThread.keySet() )
        {

            Set<SocketEvent> rcvSet = rcvPerThread.get( threadSocket );
            Set<Pair<String, String>> handlerSet = handlersPerThread.get( threadSocket );
            for ( SocketEvent rcv : rcvSet )
            {
                String rcvHandlerConstraints =
                                genRcvHandlerLinkageConstraints( rcv, rcvNextEvent.get( rcv ), handlerSet );
                solver.writeConst( solver.postNamedAssert( rcvHandlerConstraints, TAG ) );
            }
        }
    }

    /**
     * Receive Handler linkage constraints encode the mapping between a given Rcv event
     * and all handlers whose messages arrive at the same thread of R and through the same socket.
     *
     * Let R be a Rcv event and H the set of handlers belonging to the same thread of R and the same socket.
     * The receive-handler linkage constraints state that for all h_i \in H:
     *    R = hBegin_i
     *      && hEnd_i < {event next to R in the thread timeline}
     *      && ( for all {h_j \in H, h_j != h_i}: hEnd_i < hBegin_j || hEnd_j < hBegin_i )
     *
     * Briefly speaking, the constraints state that R has to be mapped to one and only one handler.
     * In particular, if R is mapped to h_i, then all other h_j occur either before or after h_i.
     *
     * @throws IOException
     */
    private String genRcvHandlerLinkageConstraints( SocketEvent rcv, Event nextEvent,
                                                    Set<Pair<String, String>> handlers )
    {
        StringBuilder outerOr = new StringBuilder();

        // for all h_i \in H, map R with h_i
        for ( Pair<String, String> handler_i : handlers )
        {
            String handlerBegin_i = handler_i.fst;
            String handlerEnd_i = handler_i.snd;
            StringBuilder innerAnd = new StringBuilder();

            // R = hBegin_i
            String rcvHndLinkage = solver.cEq( rcv.toString(), handlerBegin_i );
            if ( nextEvent != null )
            {
                // when there's an event following R, we augment the constraint as follows:
                //   R = hBegin_i && hEnd_i < nextEvent
                rcvHndLinkage = rcvHndLinkage + " " + solver.cLt( handlerEnd_i, nextEvent.toString() );
            }

            innerAnd.append( rcvHndLinkage + " " );

            // for all h_j \in H, h_j != h_i: hEnd_i < hBegin_j || hEnd_j < hBegin_i
            for ( Pair<String, String> handler_j : handlers )
            {
                if ( handler_i == handler_j )
                    continue;

                String handlerBegin_j = handler_j.fst;
                String handlerEnd_j = handler_j.snd;

                // hEnd_i < hBegin_j || hEnd_j < hBegin_i
                String mutexConst = solver.cOr( solver.cLt( handlerEnd_i, handlerBegin_j ),
                                                solver.cLt( handlerEnd_j, handlerBegin_i ) );

                innerAnd.append( mutexConst + " " );
            }

            outerOr.append( solver.cAnd( innerAnd.toString() ) + " " );
        }

        return solver.cOr( outerOr.toString() );
    }

    private void genForkStartConstraints()
                    throws IOException
    {
        System.out.println( "[MinhaChecker] Generate fork-start constraints" );
        solver.writeComment( "FORK-START CONSTRAINTS" );
        for ( List<ThreadCreationEvent> l : traceProcessor.forkEvents.values() )
        {
            for ( ThreadCreationEvent e : l )
            {

                // don't add fork-start constraints for threads that are spawned but don't start
                if ( traceProcessor.eventsPerThread.containsKey( e.getChildThread() ) )
                {
                    String cnst = solver.cLt( e.toString(), "START_" + e.getChildThread() );
                    solver.writeConst( solver.postNamedAssert( cnst, "FS" ) );
                }
            }
        }
    }

    private void genJoinExitConstraints()
                    throws IOException
    {
        System.out.println( "[MinhaChecker] Generate join-end constraints" );
        solver.writeComment( "JOIN-END CONSTRAINTS" );
        for ( List<ThreadCreationEvent> l : traceProcessor.joinEvents.values() )
        {
            for ( ThreadCreationEvent e : l )
            {
                String cnst = solver.cLt( "END_" + e.getChildThread(), e.toString() );
                solver.writeConst( solver.postNamedAssert( cnst, "JE" ) );
            }
        }
    }

    private void genLockingConstraints()
                    throws IOException
    {
        System.out.println( "[MinhaChecker] Generate locking constraints" );
        solver.writeComment( "LOCKING CONSTRAINTS" );
        for ( String var : traceProcessor.lockEvents.keySet() )
        {
            // for two lock/unlock pairs on the same locking object,
            // one pair must be executed either before or after the other
            ListIterator<CausalPair<SyncEvent, SyncEvent>> pairIterator_i =
                            traceProcessor.lockEvents.get( var ).listIterator( 0 );
            ListIterator<CausalPair<SyncEvent, SyncEvent>> pairIterator_j;

            while ( pairIterator_i.hasNext() )
            {
                CausalPair<SyncEvent, SyncEvent> pair_i = pairIterator_i.next();
                // advance iterator to have two different pairs
                pairIterator_j =
                                traceProcessor.lockEvents.get( var ).listIterator( pairIterator_i.nextIndex() );

                while ( pairIterator_j.hasNext() )
                {
                    CausalPair<SyncEvent, SyncEvent> pair_j = pairIterator_j.next();

                    // there is no need to add constraints for locking pairs of the same thread
                    // as they are already encoded in the program order constraints
                    if ( pair_i.getFirst().getThread().equals( pair_j.getFirst().getThread() ) )
                    {
                        continue;
                    }

                    // Ui < Lj || Uj < Li
                    String cnstUi_Lj =
                                    solver.cLt( pair_i.getSecond().toString(), pair_j.getFirst().toString() );
                    String cnstUj_Li =
                                    solver.cLt( pair_j.getSecond().toString(), pair_i.getFirst().toString() );
                    String cnst = solver.cOr( cnstUi_Lj, cnstUj_Li );
                    solver.writeConst( solver.postNamedAssert( cnst, "LC" ) );
                }
            }
        }
    }

    private void genWaitNotifyConstraints()
                    throws IOException
    {
        System.out.println( "[MinhaChecker] Generate wait-notify constraints" );
        solver.writeComment( "WAIT-NOTIFY CONSTRAINTS" );
        // map: notify event -> list of all binary vars corresponding to that
        // notify
        HashMap<SyncEvent, List<String>> binaryVars = new HashMap<>();

        // for a given condition, each notify can be mapped to any wait
        // but a wait can only have a single notify
        for ( String condition : traceProcessor.waitEvents.keySet() )
        {
            for ( SyncEvent wait : traceProcessor.waitEvents.get( condition ) )
            {
                StringBuilder globalOr = new StringBuilder();

                for ( SyncEvent notify : traceProcessor.notifyEvents.get( condition ) )
                {
                    // binary var used to indicate whether the signal operation is mapped to a wait operation
                    // or not
                    String binVar =
                                    "B_"
                                                    + condition
                                                    + "-W_"
                                                    + wait.getThread()
                                                    + "_"
                                                    + wait.getEventId()
                                                    + "-N_"
                                                    + notify.getThread()
                                                    + "_"
                                                    + notify.getEventId();

                    if ( !binaryVars.containsKey( notify ) )
                    {
                        binaryVars.put( notify, new ArrayList<>() );
                    }
                    binaryVars.get( notify ).add( binVar );

                    // const: Oa_sg < Oa_wt && b^{a_sg}_{a_wt} = 1
                    globalOr.append(
                                    solver.cAnd( solver.cLt( notify.toString(), wait.toString() ),
                                                 solver.cEq( binVar, "1" ) ) );
                    solver.writeConst( solver.declareIntVar( binVar, 0, 1 ) );
                }
                solver.writeConst( solver.postNamedAssert( solver.cOr( globalOr.toString() ), "WN" ) );
            }
        }

        // add constraints stating that a given notify can only be mapped to a single wait operation
        for ( SyncEvent notify : binaryVars.keySet() )
        {
            // for notifyAll, we don't constrain the number of waits that can be matched with this notify
            if ( notify.getType() == NOTIFYALL )
            {
                // const: Sum_{x \in WT} b^{a_sg}_{x} >= 0
                solver.writeConst(
                                solver.postNamedAssert(
                                                solver.cGeq( solver.cSummation( binaryVars.get( notify ) ), "0" ),
                                                "WN" ) );
            }
            else
            {
                // const: Sum_{x \in WT} b^{a_sg}_{x} <= 1
                solver.writeConst(
                                solver.postNamedAssert(
                                                solver.cLeq( solver.cSummation( binaryVars.get( notify ) ), "1" ),
                                                "WN" ) );
            }
        }
    }

    /**
     * Computes the set of pairs of locations that correspond to races from the set of `CausalPair`s
     *
     * @param causalPairs
     * @return
     */
    private Set<String> getRacesAsLocationPairs(
                    Set<CausalPair<? extends Event, ? extends Event>> causalPairs )
    {
        return causalPairs.stream()
                          .map( this::getRaceAsLocationPair )
                          .collect( Collectors.toSet() );
    }

    private String getRaceAsLocationPair( CausalPair<? extends Event, ? extends Event> causalPair )
    {
        String[] locations = {
                        causalPair.getFirst().getLineOfCode(), causalPair.getSecond().getLineOfCode()
        };
        Arrays.sort( locations );
        return "(" + locations[0] + ", " + locations[1] + ")";
    }

    private long countDataRaces()
    {
        return getRacesAsLocationPairs( dataRaceCandidates ).size();
    }
}
