package pt.minha.checker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.haslab.taz.TraceProcessor;
import pt.haslab.taz.causality.CausalPair;
import pt.haslab.taz.causality.MessageCausalPair;
import pt.haslab.taz.events.*;
import pt.haslab.taz.utils.Utils;

import java.util.*;

import static pt.haslab.taz.events.EventType.*;

public class RedundantEventPruner {
  private TraceProcessor traceProcessor;
  private Logger logger;

  // Redundancy Elimination structures
  private Set<CausalPair<SocketEvent, SocketEvent>> redundantSndRcv;
  // Debug data
  private Set<Event> redundantEvents;
  // Map: thread id -> Msg Context (Counter)
  private Map<String, Integer> msgContexts;
  // Map: thread id -> list of he ids of the messages ids and lock ids in the concurrency context
  private Map<String, Set<String>> concurrencyContexts;
  // Map: location,hashCode(TETAthread)-> stack of Threads
  private Map<String, Stack<String>> stacks;
  // Map: str(location pair),hashCode(TETAthread)-> stack of
  private Map<String, Stack<CausalPair<SocketEvent, SocketEvent>>> msgStacks;

  public RedundantEventPruner(TraceProcessor traceProcessor) {
    msgContexts = new HashMap<>();
    msgStacks = new HashMap<>();
    redundantSndRcv = new HashSet<>();
    redundantEvents = new HashSet<>();
    concurrencyContexts = new HashMap<>();
    stacks = new HashMap<>();
    this.traceProcessor = traceProcessor;
    logger = LoggerFactory.getLogger(RedundantEventPruner.class);
  }

  // TODO: document code, removeRedundantEvents is the literall ReX implementation
  //       pruneEvents is the extension of ReX to handle distributed systems with
  //       message passing

  public long removeRedundantEvents() {
    // Assumptions:
    //  The order of the events iterator corresponds to the chronological order of the events
    //  the function getStack in ReX depends only on the current state of teta-loc
    long count = 0;
    EventIterator events = new EventIterator(traceProcessor.eventsPerThread.values());
    // Metadata for detecting possible redundant send/receives
    Map<String, Integer> threadCounters = new HashMap<>();

    // Map key (snd_location + thread counter of send + rcv_location + thread counter of rcv) ->
    // stack of pairs SND/RCV
    Map<String, Stack<MessageCausalPair>> socketStacks = new HashMap<>();

    // Records for each SND event its corresponding thread counter
    Map<Event, Integer> countersOnEvents = new HashMap<>();

    for (String thread : traceProcessor.eventsPerThread.keySet()) {
      threadCounters.put(thread, 0);
    }

    while (events.hasNext()) {
      Event e = events.next();
      String thread = e.getThread();
      EventType type = e.getType();

      if (type == null) {
        throw new RuntimeException("EventType not known");
      }

      switch (type) {
        case LOCK:
          SyncEvent le = (SyncEvent) e;
          Utils.insertInMapToSets(
              concurrencyContexts, thread, String.valueOf(le.getVariable().hashCode()));
          break;
        case UNLOCK:
          SyncEvent ue = (SyncEvent) e;
          concurrencyContexts.get(thread).remove(ue.getVariable().hashCode());
          break;
        case READ:
        case WRITE:
          // MEM Access
          RWEvent rwe = (RWEvent) e;
          if (checkRedundancy(rwe, thread)) {
            // if an event is redundant, remove from the trace
            logger.debug("Event " + e + " is redundant.");
            events.remove();
            redundantEvents.add(e);
            removeEventMetadata(rwe);
            count++;
          } else {
            threadCounters.put(thread, threadCounters.get(thread) + 1);
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
          SocketEvent snde = traceProcessor.sndFromMessageId(messageId);
          String key =
              snde.getLineOfCode()
                  + ":"
                  + countersOnEvents.get(snde)
                  + "::"
                  + rcve.getLineOfCode()
                  + ":"
                  + threadCounters.get(thread);
          Stack<MessageCausalPair> s = socketStacks.get(key);

          if (s == null) {
            s = new Stack<>();
            socketStacks.put(key, s);
          }

          if (s.size() >= 2) {
            redundantSndRcv.add(new CausalPair<>(snde, rcve));
          } else {
            s.push(traceProcessor.msgEvents.get(messageId));
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
      // System.out.println("-- Event " + e.getEventId() + " : " + e.toString());
      // printDebugInfo();
    }
    return count;
  }

  public long pruneEvents() {
    // can be optimized to check only once every part of the code
    Set<String> checkedThreads = new HashSet<>();
    Set<String> threadsToRemove = new HashSet<>();
    Set<Event> prunedEvents = new HashSet<>();

    for (String thread : traceProcessor.eventsPerThread.keySet()) {
      int i = 0;
      if (checkedThreads.contains(thread)) {
        continue;
      }

      // TODO: be sure that the order of the list is the same as the SortedSet
      List<Event> events = new ArrayList<>(traceProcessor.eventsPerThread.get(thread));
      for (Event e : events) {
        EventType type = e.getType();
        if (prunedEvents.contains(e)) {
          continue;
        }
        switch (type) {
          case CREATE:
            ThreadCreationEvent tce = (ThreadCreationEvent) e;
            String child = tce.getChildThread();

            if (canRemoveBlock(traceProcessor.eventsPerThread.get(child))) {
              // marks events to remove instead of removing in order to prevent changes in the
              // iterated collection
              ThreadCreationEvent join = traceProcessor.getCorrespondingJoin(tce);
              prunedEvents.add(tce);
              prunedEvents.addAll(traceProcessor.eventsPerThread.get(child));
              checkedThreads.add(child);
              threadsToRemove.add(child);

              if (join != null) {
                prunedEvents.add(join);
              }
            }
            break;

          case RCV:
            List<Event> handler = traceProcessor.handlerEvents.get(e);
            CausalPair<SocketEvent, SocketEvent> pair =
                getPairWithSameSecondTerm(redundantSndRcv, (SocketEvent) e);

            // if the send/rcv is redundant and there is no message handler

            if (handler == null && pair != null) {
              // removeEventMetadata(e);
              // prunedEvents.add(pair.getFirst());
              // prunedEvents.add(pair.getSecond());
              redundantSndRcv.remove(pair);
            }
            break;
          case LOCK:
            SyncEvent lockEvent = (SyncEvent) e;
            SyncEvent unlockEvent = traceProcessor.getCorrespondingUnlock(lockEvent);

            if (unlockEvent != null) {
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

    for (Event e : prunedEvents) {
      removeEventMetadata(e);
      System.out.println("To Remove: " + e);
      traceProcessor.eventsPerThread.get(e.getThread()).remove(e);
    }

    // remove redundant SND/RCV pairs that have redundant handlers
    for (CausalPair<SocketEvent, SocketEvent> pair : redundantSndRcv) {
      SocketEvent se = (SocketEvent) pair.getFirst();
      SocketEvent rcve = (SocketEvent) pair.getSecond();

      String thread = rcve.getThread();
      List<Event> list = traceProcessor.handlerEvents.get(rcve);
      // System.out.println("~~> " + pair);
      // System.out.println("LIST: " + list);
      if (canRemoveHandler(list)) {
        // TODO: be sure that the order of the list is the same as the SortedSet
        List<Event> events = new ArrayList<>(traceProcessor.eventsPerThread.get(thread));
        prunedEvents.addAll(list);
        prunedEvents.add(rcve);
        prunedEvents.add(se);

        events.removeAll(list);
        events.remove(rcve);
        events.remove(se);
        removeEventMetadata(rcve);
        removeEventMetadata(se);

        // trace.handlerEvents.remove(pair.getSecond());
        // trace.msgEvents.remove(pair.getFirst().getMessageId());
        // System.out.println("REMOVED SND: " + se);
      }
    }

    return prunedEvents.size();
  }

  /**
   * Removes the data associated with this event from the Trace Processor auxiliary structures.
   * Does NOT remove events from eventsPerThread.
   */
  private void removeEventMetadata(Event e) {
    if (e == null) {
      return;
    }
    String var;
    EventType type = e.getType();
    String thread = e.getThread();
    switch (type) {
      case SND:
        // removes both SND and RCV from msgEvents
        SocketEvent socketEvent = (SocketEvent) e;
        String msgId = socketEvent.getMessageId();
        traceProcessor.msgEvents.remove(msgId);
        break;
      case RCV:
        // remove msg handler
        SocketEvent rcvEvent = (SocketEvent) e;
        List<Event> handler = traceProcessor.handlerEvents.remove(rcvEvent);
        if (handler != null) {
          for (Event x : handler) {
            removeEventMetadata(x);
          }
        }
        break;
      case CREATE:
        traceProcessor.forkEvents.get(thread).remove(e);
        break;
      case JOIN:
        traceProcessor.joinEvents.get(thread).remove(e);
        break;
      case LOCK:
      case UNLOCK:
        SyncEvent syncEvent = (SyncEvent) e;
        var = syncEvent.getVariable();
        List<CausalPair<SyncEvent, SyncEvent>> pairs = traceProcessor.lockEvents.get(var);
        if (pairs != null) {
          CausalPair<SyncEvent, SyncEvent> res = null;
          for (CausalPair<SyncEvent, SyncEvent> pair : pairs) {
            SyncEvent fst = pair.getFirst();
            SyncEvent snd = pair.getSecond();
            if (syncEvent.equals(fst) || syncEvent.equals(snd)) {
              res = pair;
              break;
            }
          }

          if (res != null) {
            pairs.remove(res);
          }
        }
        break;
      case READ:
        RWEvent readEvent = (RWEvent) e;
        var = readEvent.getVariable();
        removeFromMapToLists(traceProcessor.readEvents, var, readEvent);
        break;
      case WRITE:
        RWEvent writeEvent = (RWEvent) e;
        var = writeEvent.getVariable();
        removeFromMapToLists(traceProcessor.writeEvents, var, writeEvent);
        break;
      case WAIT:
        SyncEvent waitEvent = (SyncEvent) e;
        var = waitEvent.getVariable();
        removeFromMapToLists(traceProcessor.waitEvents, var, waitEvent);
        break;
      case NOTIFY:
      case NOTIFYALL:
        SyncEvent notifyEvent = (SyncEvent) e;
        var = notifyEvent.getVariable();
        removeFromMapToLists(traceProcessor.notifyEvents, var, notifyEvent);
        break;
    }

    for (List<Event> l : traceProcessor.handlerEvents.values()) {
      if (l.remove(e)) {
        return;
      }
    }
  }

  private boolean checkRedundancy(RWEvent event, String thread) {
    String loc = event.getLineOfCode();
    Set<String> concurrencyContext = concurrencyContexts.get(thread);
    String key =
        event.getLineOfCode()
            + ":"
            + (concurrencyContext == null ? 0 : concurrencyContext.hashCode())
            + ":"
            + event.getType();

    Stack<String> stack = stacks.get(key);

    if (stack == null) {
      stack = new Stack<>();
      stacks.put(key, stack);

      stack.push(thread);
      return false;
    } else if (stack.contains(thread) || stack.size() == 2) {
      // if the stack already contains the thread or is full
      return true;
    } else if (stack.size() == 1) {
      // Stack has size 1 and does not contain the thread
      stack.push(thread);
      return false;
    }
    return false;
  }

  private boolean canRemoveHandler(List<Event> handler) {
    Set<SyncEvent> openLocks = new HashSet<>();
    for (Event e : handler) {
      EventType type = e.getType();
      if (redundantEvents.contains(e)) {
        continue;
      }
      switch (type) {
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
          if (!openLocks.remove((SyncEvent) e)) {
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

  private boolean canRemoveBlock(Iterable<Event> events) {
    for (Event e : events) {
      EventType type = e.getType();
      if (type == SND
          || type == RCV
          || type == WRITE
          || type == READ
          || type == NOTIFY
          || type == NOTIFYALL
          || type == WAIT) {
        return false;
      } else if (type == CREATE) {
        ThreadCreationEvent tce = (ThreadCreationEvent) e;
        if (!canRemoveBlock(traceProcessor.eventsPerThread.get(tce.getChildThread()))) {
          return false;
        }
      }
    }
    return true;
  }

  private static <X, Y> boolean contains2ndTerm(Collection<CausalPair<X, Y>> coll, Y elem) {
    return getPairWithSameSecondTerm(coll, elem) != null;
  }

  private static <K, V> boolean removeFromMapToLists(Map<K, List<V>> map, K key, V value) {
    List<V> l = map.get(key);
    if (l != null) {
      return l.remove(value);
    }
    return false;
  }

  private static <X, Y> CausalPair<X, Y> getPairWithSameSecondTerm(
      Collection<CausalPair<X, Y>> coll, Y term) {
    if (term == null) {
      for (CausalPair<X, Y> pair : coll) {
        Y snd = pair.getSecond();
        if (snd == null) return pair;
      }
      return null;
    }

    for (CausalPair<X, Y> pair : coll) {
      Y snd = pair.getSecond();
      if (term.equals(snd)) {
        return pair;
      }
    }

    return null;
  }

  public void printDebugInfo() {
    System.out.println("*************************************************");

    System.out.println("Concurrency contexts:");
    for (Map.Entry<String, Set<String>> cc : concurrencyContexts.entrySet()) {
      System.out.println(cc.getKey() + " : " + cc.getValue());
    }

    System.out.println("Stacks:");
    System.out.println(stacks.entrySet().toString());

    System.out.println("Redundant events:");
    System.out.println(redundantEvents.toString());

    System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
  }
}
