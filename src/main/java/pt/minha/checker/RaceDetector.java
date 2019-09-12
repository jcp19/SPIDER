package pt.minha.checker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.haslab.taz.TraceProcessor;
import pt.haslab.taz.causality.CausalPair;
import pt.haslab.taz.causality.MessageCausalPair;
import pt.haslab.taz.events.Event;
import pt.haslab.taz.events.EventType;
import pt.haslab.taz.events.RWEvent;
import pt.haslab.taz.events.SocketEvent;
import pt.haslab.taz.events.SyncEvent;
import pt.haslab.taz.events.ThreadCreationEvent;
import pt.minha.checker.solver.Z3SolverParallel;

import static pt.haslab.taz.events.EventType.NOTIFYALL;

// TODO: reagroup methods logically

class RaceDetector {
  private static final Logger logger = LoggerFactory.getLogger(RaceDetector.class);
  private HashSet<CausalPair<? extends Event, ? extends Event>> dataRaceCandidates;
  private HashSet<CausalPair<? extends Event, ? extends Event>> msgRaceCandidates;
  // HB model, used to encode message arrival constraints
  private HashMap<SocketEvent, Event> rcvNextEvent;
  private Z3SolverParallel solver;
  private TraceProcessor traceProcessor;

  public RaceDetector(Z3SolverParallel solver, TraceProcessor traceProcessor) {
    dataRaceCandidates = new HashSet<>();
    msgRaceCandidates = new HashSet<>();
    rcvNextEvent = new HashMap<>();
    this.solver = solver;
    this.traceProcessor = traceProcessor;
  }

  public void generateConstraintModel() throws IOException {
    long modelStart = System.currentTimeMillis();
    genIntraNodeConstraints();
    genInterNodeConstraints();
    double buildingModelTime = System.currentTimeMillis() - modelStart;
    logger.info(
        "Time to generate constraint model: " + (buildingModelTime / (double) 1000) + " seconds");
  }

  public void checkConflicts() throws IOException {
    genDataRaceCandidates();
    genMsgRaceCandidates();
    computeActualDataRaces();
    computeActualMsgRaces();
  }

  /**
   * Computes which of the data race candidates are actually data races. Must run
   * genDataRaceCandidates() before.
   */
  public void computeActualDataRaces() {
    if (dataRaceCandidates.isEmpty()) {
      System.out.println(
          "[MinhaChecker] No data races to check ("
              + Stats.totalDataRaceCandidates
              + " candidates)");
      return;
    }

    Stats.totalDataRaceCandidates = dataRaceCandidates.size();
    Stats.totalDataRaceCandidateLocations = countDataRaces();
    System.out.println(
        "\n[MinhaChecker] Start data race checking ("
            + Stats.totalDataRaceCandidates
            + " candidates)");

    long checkingStart = System.currentTimeMillis();
    dataRaceCandidates = solver.checkRacesParallel(dataRaceCandidates);
    Stats.checkingTimeDataRace = System.currentTimeMillis() - checkingStart;
    Stats.totalDataRacePairs = dataRaceCandidates.size();
    Stats.totalDataRacePairLocations = countDataRaces();

    System.out.println(
        "\n#Data Race Candidates: "
            + Stats.totalDataRaceCandidates
            + " | #Actual Data Races: "
            + Stats.totalDataRacePairs);
    prettyPrintDataRaces();
  }

  /**
   * Generate all pairs of message race candidates. A pair of RCV operations is a candidate if: a)
   * both occur at the same node b) are either from different threads of from different message
   * handlers in the same thread
   *
   * @throws IOException
   */
  public void genMsgRaceCandidates() throws IOException {
    List<MessageCausalPair> list = new ArrayList<>(traceProcessor.msgEvents.values());
    ListIterator<MessageCausalPair> pairIterator_i = list.listIterator(0);
    ListIterator<MessageCausalPair> pairIterator_j;

    solver.writeComment("SOCKET CHANNEL CONSTRAINTS");
    while (pairIterator_i.hasNext()) {
      SocketEvent rcv1 = pairIterator_i.next().getRcv();

      if (rcv1 == null) {
        continue;
      }

      // advance iterator to have two different pairs
      pairIterator_j = list.listIterator(pairIterator_i.nextIndex());

      while (pairIterator_j.hasNext()) {
        SocketEvent rcv2 = pairIterator_j.next().getRcv();
        if (rcv2 == null) {
          continue;
        }

        if (rcv1.conflictsWith(rcv2)) {
          // make a pair with SND events because
          // two messages a and b are racing if RCVa || SNDb
          SocketEvent snd1 = traceProcessor.msgEvents.get(rcv1.getMessageId()).getSnd();
          SocketEvent snd2 = traceProcessor.msgEvents.get(rcv2.getMessageId()).getSnd();
          CausalPair<SocketEvent, SocketEvent> raceCandidate;

          if (rcv1.getEventId() < rcv2.getEventId()) {
            raceCandidate = new CausalPair<>(snd2, rcv1);
          } else {
            raceCandidate = new CausalPair<>(snd1, rcv2);
          }
          msgRaceCandidates.add(raceCandidate);

          // if socket channel is TCP and SNDs are from the same thread,
          // then add constraint stating that RCV1 happens before SND2
          if (snd1.getSocketType() == SocketEvent.SocketType.TCP
              && snd2.getSocketType() == SocketEvent.SocketType.TCP
              && snd1.getThread().equals(snd2.getThread())) {

            String cnst;
            // check trace order of SND, as the iterator does not traverse the events
            // according to the program order
            if (snd1.getEventId() < snd2.getEventId()) {
              cnst = solver.cLt(rcv1.toString(), snd2.toString());
            } else {
              cnst = solver.cLt(rcv2.toString(), snd1.toString());
            }
            solver.writeConst(solver.postNamedAssert(cnst, "TCP"));
          }
        }
      }
    }
  }

  // TODO: use the same way to order a string as used in `getRacesAsLocationPairs`
  private static String causalPairToOrderedString(
      CausalPair<? extends Event, ? extends Event> pair) {
    String fst = pair.getFirst() != null ? pair.getFirst().toString() : " ";
    String snd = pair.getSecond() != null ? pair.getSecond().toString() : " ";
    if (fst.compareTo(snd) < 0) {
      return "(" + snd + ", " + fst + ")";
    }
    return "(" + fst + ", " + snd + ")";
  }

  /**
   * Computes which of the message race candidates are actually message races. Must run
   * genMsgRaceCandidates() before.
   */
  public void computeActualMsgRaces() {
    if (msgRaceCandidates.isEmpty()) {
      System.out.println(
          "[MinhaChecker] No message races to check ("
              + Stats.totalMsgRaceCandidates
              + " candidates)");
      return;
    }

    Stats.totalMsgRaceCandidates = msgRaceCandidates.size();

    System.out.println(
        "\n[MinhaChecker] Start message race checking ("
            + Stats.totalMsgRaceCandidates
            + " candidates)");

    long checkingStart = System.currentTimeMillis();
    msgRaceCandidates = solver.checkRacesParallel(msgRaceCandidates);
    Stats.checkingTimeMsgRace = System.currentTimeMillis() - checkingStart;
    Stats.totalMsgRacePairs = msgRaceCandidates.size();

    // TODO: use the number of locations instead of number of causalPairs
    System.out.println(
        "\n#Message Race Candidates: "
            + Stats.totalMsgRaceCandidates
            + " | #Actual Message Races: "
            + Stats.totalMsgRacePairs);
    prettyPrintMessageRaces();
  }

  private void prettyPrintDataRaces() {
    long i = 0;
    Set<String> pairsOfLocations = getRacesAsLocationPairs(dataRaceCandidates);
    System.out.println("Data Races:");

    for (String pair : pairsOfLocations) {
      i++;
      System.out.println(" > Data Race #" + i + " : " + pair);
    }
  }

  private void prettyPrintMessageRaces() {
    for (CausalPair<? extends Event, ? extends Event> conf : msgRaceCandidates) {
      // translate SND events to their respective RCV events
      SocketEvent snd1 = (SocketEvent) conf.getFirst();
      SocketEvent snd2 = (SocketEvent) conf.getSecond();
      SocketEvent rcv1 = traceProcessor.msgEvents.get(snd1.getMessageId()).getRcv();
      SocketEvent rcv2 = traceProcessor.msgEvents.get(snd2.getMessageId()).getRcv();
      CausalPair<SocketEvent, SocketEvent> rcv_conf = new CausalPair<>(rcv1, rcv2);
      System.out.println("~~ " + causalPairToOrderedString(rcv_conf));

      // compute read-write sets for each message handler
      if (!traceProcessor.handlerEvents.containsKey(rcv1)
          || !traceProcessor.handlerEvents.containsKey(rcv2)) {
        System.out.println("\t-- No conflicts");
      } else {
        HashSet<RWEvent> readWriteSet1 = new HashSet<>();
        HashSet<RWEvent> readWriteSet2 = new HashSet<>();

        for (Event e : traceProcessor.handlerEvents.get(rcv1)) {
          if (e.getType() == EventType.READ || e.getType() == EventType.WRITE) {
            readWriteSet1.add((RWEvent) e);
          }
        }

        for (Event e : traceProcessor.handlerEvents.get(rcv2)) {
          if (e.getType() == EventType.READ || e.getType() == EventType.WRITE) {
            readWriteSet2.add((RWEvent) e);
          }
        }

        // check for conflicts
        for (RWEvent e1 : readWriteSet1) {
          for (RWEvent e2 : readWriteSet2) {
            if (e1.conflictsWith(e2)) {
              CausalPair<RWEvent, RWEvent> race = new CausalPair<>(e1, e2);
              System.out.println("\t-- conflict " + causalPairToOrderedString(race));
            }
          }
        }
      }
    }
  }

  /**
   * Generate all pairs of data race candidates. A pair of RW operations is a candidate if: a) at
   * least of one of the operations is a write b) both operations access the same variable c)
   * operations are from different threads, but from the same node.
   */
  public void genDataRaceCandidates() {
    for (String var : traceProcessor.writeEvents.keySet()) {
      for (RWEvent w1 : traceProcessor.writeEvents.get(var)) {
        // pair with all other writes
        for (RWEvent w2 : traceProcessor.writeEvents.get(var)) {
          if (w1.conflictsWith(w2)) {
            CausalPair<RWEvent, RWEvent> tmpPair = new CausalPair<>(w1, w2);
            dataRaceCandidates.add(tmpPair);
          }
        }

        // pair with all other reads
        if (traceProcessor.readEvents.containsKey(var)) {
          for (RWEvent r2 : traceProcessor.readEvents.get(var)) {
            CausalPair<RWEvent, RWEvent> tmpPair = new CausalPair<>(w1, r2);
            if (w1.conflictsWith(r2)) {
              dataRaceCandidates.add(tmpPair);
            }
          }
        }
      }
    }
  }

  private void genIntraNodeConstraints() throws IOException {
    genProgramOrderConstraints();
    genForkStartConstraints();
    genJoinExitConstraints();
    genWaitNotifyConstraints();
    genLockingConstraints();
  }

  private void genInterNodeConstraints() throws IOException {
    genSendReceiveConstraints();
    genMessageHandlingConstraints();
  }

  /**
   * Builds the order constraints within a segment and returns the position in the trace in which
   * the handler ends
   *
   * @return
   */
  private int genSegmentOrderConstraints(List<Event> events, int segmentStart) throws IOException {

    // constraint representing the HB relation for the thread's segment
    StringBuilder orderConstraint = new StringBuilder();
    int segmentIt = 0;

    for (segmentIt = segmentStart; segmentIt < events.size(); segmentIt++) {
      Event e = events.get(segmentIt);

      // declare variable
      String var = solver.declareIntVar(e.toString(), "0", "MAX");
      solver.writeConst(var);

      // append event to the thread's segment
      orderConstraint.append(" " + e.toString());

      // handle partial order within message handler
      if (e.getType() == EventType.RCV
          && segmentIt < (events.size() - 1)
          && events.get(segmentIt + 1).getType() == EventType.HNDLBEG) {
        segmentIt = genSegmentOrderConstraints(events, segmentIt + 1);

        // store event next to RCV to later encode the message arrival order
        if (segmentIt < events.size() - 1) {
          rcvNextEvent.put((SocketEvent) e, events.get(segmentIt + 1));
        }
      } else if (e.getType() == EventType.HNDLEND) {
        break;
      }
    }

    // write segment's order constraint
    solver.writeConst(solver.postNamedAssert(solver.cLt(orderConstraint.toString()), "PC"));

    return segmentIt;
  }

  /**
   * Program order constraints encode the order within a thread's local trace. Asynchronous event
   * handling causes the same thread to have multiple segments (i.e. handlers), which breaks global
   * happens-before relation
   *
   * @throws IOException
   */
  private void genProgramOrderConstraints() throws IOException {
    System.out.println("[MinhaChecker] Generate program order constraints");
    solver.writeComment("PROGRAM ORDER CONSTRAINTS");
    int max = 0;
    for (SortedSet<Event> l : traceProcessor.eventsPerThread.values()) {
      max += l.size();
    }
    solver.writeConst(solver.declareIntVar("MAX"));
    solver.writeConst(solver.postAssert(solver.cEq("MAX", String.valueOf(max))));

    // generate program order variables and constraints
    for (SortedSet<Event> eventsSortedSet : traceProcessor.eventsPerThread.values()) {
      // TODO: be sure that the order of the list is the same as the SortedSet
      List<Event> events = new ArrayList<>(eventsSortedSet);
      if (events.isEmpty()) {
        continue;
      } else if (events.size() == 1) {
        // if there's only one event, we just need to declare it as there are no program order
        // constraints
        String var = solver.declareIntVar(events.get(0).toString(), "0", "MAX");
        solver.writeConst(var);
      } else {
        // generate program constraints for the thread segment
        genSegmentOrderConstraints(events, 0);
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

  private void genSendReceiveConstraints() throws IOException {
    System.out.println("[MinhaChecker] Generate communication constraints");
    solver.writeComment("SEND-RECEIVE CONSTRAINTS");
    for (MessageCausalPair pair : traceProcessor.msgEvents.values()) {

      if (pair.getSnd() == null && pair.getRcv() == null) {
        continue;
      }

      SocketEvent rcv = pair.getRcv();
      String cnst = "";

      // if there is a message handler, order SND with HANDLERBEGIN instead of RCV
      if (!traceProcessor.handlerEvents.containsKey(rcv)) {
        cnst = solver.cLt(pair.getSnd().toString(), pair.getRcv().toString());
      } else {
        Event handlerbegin = traceProcessor.handlerEvents.get(rcv).get(0);
        cnst = solver.cLt(pair.getSnd().toString(), handlerbegin.toString());
      }

      solver.writeConst(solver.postNamedAssert(cnst, "COM"));
    }
  }

  /**
   * Message Handling constraints encode: - message handler mutual exclusion - message arrival order
   *
   * @throws IOException
   */
  private void genMessageHandlingConstraints() throws IOException {
    System.out.println("[MinhaChecker] Generate message handling constraints");
    solver.writeComment("MESSAGE HANDLING CONSTRAINTS");
    String TAG = "HND";

    HashMap<String, HashSet<SocketEvent>> rcvPerThread = new HashMap<>();

    /* encode mutual exclusion constraints, which state that two message handlers in the same thread
    must occur one before the other in any order */
    for (SocketEvent rcv_i : traceProcessor.handlerEvents.keySet()) {
      // store all rcv events per thread-socket
      String key = rcv_i.getThread() + "-" + rcv_i.getDstPort();
      if (!rcvPerThread.containsKey(key)) {
        rcvPerThread.put(key, new HashSet<>());
      }
      rcvPerThread.get(key).add(rcv_i);

      for (SocketEvent rcv_j : traceProcessor.handlerEvents.keySet()) {
        if (rcv_i != rcv_j
            && rcv_i.getThread().equals(rcv_j.getThread())
            && rcv_i.getDstPort() == rcv_j.getDstPort()) {

          // mutual exclusion: HENDi < HBEGj V HENDj < HBEGi
          String handlerBegin_i = traceProcessor.handlerEvents.get(rcv_i).get(0).toString();
          String handlerEnd_i =
              traceProcessor
                  .handlerEvents
                  .get(rcv_i)
                  .get(traceProcessor.handlerEvents.get(rcv_i).size() - 1)
                  .toString();
          String handlerBegin_j = traceProcessor.handlerEvents.get(rcv_j).get(0).toString();
          String handlerEnd_j =
              traceProcessor
                  .handlerEvents
                  .get(rcv_j)
                  .get(traceProcessor.handlerEvents.get(rcv_j).size() - 1)
                  .toString();

          String mutexConst =
              solver.cOr(
                  solver.cLt(handlerEnd_i, handlerBegin_j),
                  solver.cLt(handlerEnd_j, handlerBegin_i));
          solver.writeConst(solver.postNamedAssert(mutexConst, TAG));
        }
      }
    }

    /* encode possible message arrival order constraints, which state that each RCV event may be
     * "matched with" any message on the same socket */
    for (HashSet<SocketEvent> rcvSet : rcvPerThread.values()) {
      // for all RCVi in rcvSet :
      // (RCVi < HNDBegin_i && HNDEnd_i < nextEvent) V (RCVi < HNDBegin_j && HNDEnd_j < nextEvent),
      // for all j != i
      for (SocketEvent rcv_i : rcvSet) {
        Event nextEvent = rcvNextEvent.get(rcv_i);

        // if the RCV is the last event, then nextEvent == null
        if (nextEvent == null) {
          continue;
        }

        StringBuilder outerOr = new StringBuilder();
        for (SocketEvent rcv_j : rcvSet) {
          String handlerBegin_j = traceProcessor.handlerEvents.get(rcv_j).get(0).toString();
          String handlerEnd_j =
              traceProcessor
                  .handlerEvents
                  .get(rcv_j)
                  .get(traceProcessor.handlerEvents.get(rcv_j).size() - 1)
                  .toString();
          String innerAnd =
              solver.cLt(
                  rcv_i.toString()
                      + " "
                      + handlerBegin_j
                      + " "
                      + handlerEnd_j
                      + " "
                      + nextEvent.toString());
          outerOr.append(innerAnd + " ");
        }
        solver.writeConst(solver.postNamedAssert(solver.cOr(outerOr.toString()), TAG));
      }
    }
  }

  private void genForkStartConstraints() throws IOException {
    System.out.println("[MinhaChecker] Generate fork-start constraints");
    solver.writeComment("FORK-START CONSTRAINTS");
    for (List<ThreadCreationEvent> l : traceProcessor.forkEvents.values()) {
      for (ThreadCreationEvent e : l) {

        // don't add fork-start constraints for threads that are spawned but don't start
        if (traceProcessor.eventsPerThread.containsKey(e.getChildThread())) {
          String cnst = solver.cLt(e.toString(), "START_" + e.getChildThread());
          solver.writeConst(solver.postNamedAssert(cnst, "FS"));
        }
      }
    }
  }

  private void genJoinExitConstraints() throws IOException {
    System.out.println("[MinhaChecker] Generate join-end constraints");
    solver.writeComment("JOIN-END CONSTRAINTS");
    for (List<ThreadCreationEvent> l : traceProcessor.joinEvents.values()) {
      for (ThreadCreationEvent e : l) {
        String cnst = solver.cLt("END_" + e.getChildThread(), e.toString());
        solver.writeConst(solver.postNamedAssert(cnst, "JE"));
      }
    }
  }

  private void genLockingConstraints() throws IOException {
    System.out.println("[MinhaChecker] Generate locking constraints");
    solver.writeComment("LOCKING CONSTRAINTS");
    for (String var : traceProcessor.lockEvents.keySet()) {
      // for two lock/unlock pairs on the same locking object,
      // one pair must be executed either before or after the other
      ListIterator<CausalPair<SyncEvent, SyncEvent>> pairIterator_i =
          traceProcessor.lockEvents.get(var).listIterator(0);
      ListIterator<CausalPair<SyncEvent, SyncEvent>> pairIterator_j;

      while (pairIterator_i.hasNext()) {
        CausalPair<SyncEvent, SyncEvent> pair_i = pairIterator_i.next();
        // advance iterator to have two different pairs
        pairIterator_j =
            traceProcessor.lockEvents.get(var).listIterator(pairIterator_i.nextIndex());

        while (pairIterator_j.hasNext()) {
          CausalPair<SyncEvent, SyncEvent> pair_j = pairIterator_j.next();

          // there is no need to add constraints for locking pairs of the same thread
          // as they are already encoded in the program order constraints
          if (pair_i.getFirst().getThread().equals(pair_j.getFirst().getThread())) {
            continue;
          }

          // Ui < Lj || Uj < Li
          String cnstUi_Lj =
              solver.cLt(pair_i.getSecond().toString(), pair_j.getFirst().toString());
          String cnstUj_Li =
              solver.cLt(pair_j.getSecond().toString(), pair_i.getFirst().toString());
          String cnst = solver.cOr(cnstUi_Lj, cnstUj_Li);
          solver.writeConst(solver.postNamedAssert(cnst, "LC"));
        }
      }
    }
  }

  private void genWaitNotifyConstraints() throws IOException {
    System.out.println("[MinhaChecker] Generate wait-notify constraints");
    solver.writeComment("WAIT-NOTIFY CONSTRAINTS");
    // map: notify event -> list of all binary vars corresponding to that
    // notify
    HashMap<SyncEvent, List<String>> binaryVars = new HashMap<>();

    // for a given condition, each notify can be mapped to any wait
    // but a wait can only have a single notify
    for (String condition : traceProcessor.waitEvents.keySet()) {
      for (SyncEvent wait : traceProcessor.waitEvents.get(condition)) {
        StringBuilder globalOr = new StringBuilder();

        for (SyncEvent notify : traceProcessor.notifyEvents.get(condition)) {
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

          if (!binaryVars.containsKey(notify)) {
            binaryVars.put(notify, new ArrayList<>());
          }
          binaryVars.get(notify).add(binVar);

          // const: Oa_sg < Oa_wt && b^{a_sg}_{a_wt} = 1
          globalOr.append(
              solver.cAnd(solver.cLt(notify.toString(), wait.toString()), solver.cEq(binVar, "1")));
          solver.writeConst(solver.declareIntVar(binVar, 0, 1));
        }
        solver.writeConst(solver.postNamedAssert(solver.cOr(globalOr.toString()), "WN"));
      }
    }

    // add constraints stating that a given notify can only be mapped to a single wait operation
    for (SyncEvent notify : binaryVars.keySet()) {
      // for notifyAll, we don't constrain the number of waits that can be matched with this notify
      if (notify.getType() == NOTIFYALL) {
        // const: Sum_{x \in WT} b^{a_sg}_{x} >= 0
        solver.writeConst(
            solver.postNamedAssert(
                solver.cGeq(solver.cSummation(binaryVars.get(notify)), "0"), "WN"));
      } else {
        // const: Sum_{x \in WT} b^{a_sg}_{x} <= 1
        solver.writeConst(
            solver.postNamedAssert(
                solver.cLeq(solver.cSummation(binaryVars.get(notify)), "1"), "WN"));
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
      Set<CausalPair<? extends Event, ? extends Event>> causalPairs) {
    return causalPairs.stream()
        .map(
            causalPair -> {
              String[] locations = {
                causalPair.getFirst().getLineOfCode(), causalPair.getSecond().getLineOfCode()
              };
              Arrays.sort(locations);
              return "(" + locations[0] + ", " + locations[1] + ")";
            })
        .collect(Collectors.toSet());
  }

  private long countDataRaces() {
    return getRacesAsLocationPairs(dataRaceCandidates).size();
  }
}
