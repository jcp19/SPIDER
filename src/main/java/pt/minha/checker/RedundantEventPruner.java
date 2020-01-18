package pt.minha.checker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.haslab.taz.TraceProcessor;
import pt.haslab.taz.causality.CausalPair;
import pt.haslab.taz.causality.MessageCausalPair;
import pt.haslab.taz.events.Event;
import pt.haslab.taz.events.EventIterator;
import pt.haslab.taz.events.EventType;
import pt.haslab.taz.events.RWEvent;
import pt.haslab.taz.events.SocketEvent;
import pt.haslab.taz.events.SyncEvent;
import pt.haslab.taz.events.ThreadCreationEvent;
import pt.haslab.taz.utils.Utils;

import static pt.haslab.taz.events.EventType.*;

public class RedundantEventPruner {
  private TraceProcessor traceProcessor;
  private static final Logger logger = LoggerFactory.getLogger(RedundantEventPruner.class);

  // Redundancy Elimination structures
  private Set<Event> redundantEvents;
  // Map: thread id -> list of he ids of the messages ids and lock ids in the concurrency context
  private Map<String, Set<String>> concurrencyContexts;
  // Map: loc id -> concurrency history of location
  private Map<String, Set<String>> concurrencyHistories;
  // Map: key -> list of he ids of the messages ids and lock ids in the concurrency history
  private Map<String, Stack<String>> stacks;

  public RedundantEventPruner(TraceProcessor traceProcessor) {
    redundantEvents = new HashSet<>();
    concurrencyContexts = new HashMap<>();
    concurrencyHistories = new HashMap<>();
    stacks = new HashMap<>();
    this.traceProcessor = traceProcessor;
  }

  /**
   * Removes redundant events for data race detection. Literal implementation the ReX algorithm
   * (https://parasol.tamu.edu/~jeff/academic/rex.pdf). Assumes: 1) the order of the event iterator
   * matches the partial order defined by the HB relation 2) the function getStack in ReX depends
   * only on the current concurrency context and location (the concurrency history is not important)
   * This is based on my interpretation of the ReX algorithm, whose presentation in the paper is not
   * clear. This version of the algorithm is not paralelizable.
   *
   * @return the number of removed events
   */
  public long removeRedundantRW() {
    EventIterator events = new EventIterator(traceProcessor.eventsPerThread.values());
    long count = 0;
    long rwEvents = 0;

    for (String t : traceProcessor.eventsPerThread.keySet()) {
      concurrencyContexts.put(t, new HashSet<>());
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
          break;
        case UNLOCK:
          SyncEvent ue = (SyncEvent) e;
          Utils.insertInMapToSets(concurrencyContexts, thread, ue.getVariable());
          break;
        case READ:
        case WRITE:
          // MEM Access
          RWEvent rwe = (RWEvent) e;
          if (checkRedundancyRW(rwe)) {
            // if an event is redundant, remove from the trace
            logger.debug("ReX (Phase 1): Event " + e + " is redundant.");
            events.remove();
            redundantEvents.add(e);
            removeEventMetadata(rwe);
            count++;
          }
          rwEvents++;
          break;
        case SND:
          SocketEvent se = (SocketEvent) e;
          Utils.insertInMapToSets(concurrencyContexts, thread, se.getSocket() + se.getLineOfCode());
          break;
        case CREATE:
          // handles CREATE events the same way it handles SND because it also introduces
          // an outgoing HB edge
          ThreadCreationEvent tse = (ThreadCreationEvent) e;
          Utils.insertInMapToSets(concurrencyContexts, thread, tse.getChildThread());
          break;
        default:
          // advance e
          break;
      }
    }
    Stats.INSTANCE.percentRedundantRW = 100d * ((double) count) / rwEvents;
    return count;
  }

  private boolean checkRedundancyRW(RWEvent event) {
    String thread = event.getThread();
    Set<String> concurrencyContext = concurrencyContexts.get(thread);
    Stack<String> stack = getStack(event.getLineOfCode(), concurrencyContext);

    if (stack.empty()) {
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

  private Stack<String> getStack(String loc, Set<String> concurrencyContext) {
    Set<String> concurrencyHistory = concurrencyHistories.get(loc);
    if (concurrencyHistory == null) {
      concurrencyHistory = new HashSet<>();
      concurrencyHistories.put(loc, concurrencyHistory);
    }

    // If a statement can produce more than one event, than this line shoudld change.
    // The key should be extended with some string which can differentiate between events
    // produced on the same statement.
    String key = "loc:" + loc + ":" + concurrencyContext.hashCode();
    Stack<String> stack = stacks.get(key);

    if (stack == null) {
      stack = new Stack<>();
      stacks.put(key, stack);
    }

    return stack;
  }

  public long removeRedundantInterThreadEvents() {
    return removeRedundantInterThreadEvents(false);
  }

  /**
   * Generalize ReX algorithm to distributed systems, making it capable of pruning SND and RCV
   * events. For maximum effectiveness, this mehtod should be run after `removeRedundantRW`.
   *
   * @return the number of removed events
   */
  public long removeRedundantInterThreadEvents(boolean removeMsgsWithoutHandler) {
    // can be optimized to check only once every part of the code
    Set<String> checkedThreads = new HashSet<>();
    Set<Event> redundantInterThreadEvents = new HashSet<>();

    for (String thread : traceProcessor.eventsPerThread.keySet()) {
      int i = 0;
      if (checkedThreads.contains(thread)) {
        continue;
      }

      // TODO: be sure that the order of the list is the same as the SortedSet
      List<Event> events = new ArrayList<>(traceProcessor.eventsPerThread.get(thread));
      for (Event e : events) {
        EventType type = e.getType();

        if (redundantInterThreadEvents.contains(e)) {
          continue;
        }

        switch (type) {
          case CREATE:
            ThreadCreationEvent tce = (ThreadCreationEvent) e;
            String child = tce.getChildThread();

            if (canRemoveBlock(traceProcessor.eventsPerThread.get(child))) {
              // marks events to remove instead of removing in order to prevent changes in the
              // iterated collection
              redundantInterThreadEvents.add(tce);
              redundantInterThreadEvents.addAll(traceProcessor.eventsPerThread.get(child));
              checkedThreads.add(child);

              ThreadCreationEvent join = getCorrespondingJoin(tce);
              if (join != null) {
                redundantInterThreadEvents.add(join);
              }
            }
            break;

          case RCV:
            SocketEvent rcve = (SocketEvent) e;
            List<Event> handler = traceProcessor.handlerEvents.get(rcve);
            MessageCausalPair pair = traceProcessor.sndRcvPairs.get(rcve.getMessageId());

            // if the send/rcv is redundant and there is no message handler
            if (((handler == null && removeMsgsWithoutHandler) || canRemoveHandler(handler))
                && pair != null) {

              // I'm assuming that there is no message partition or at least that partitioning
              // is abstracted from the trace
              List<SocketEvent> sndList = pair.getSndList();
              List<SocketEvent> rcvList = pair.getRcvList();

              if (sndList != null) {
                redundantInterThreadEvents.addAll(sndList);
              }

              if (rcvList != null) {
                redundantInterThreadEvents.addAll(rcvList);
              }

              if (handler != null) {
                redundantInterThreadEvents.addAll(handler);
              }
            }
            break;
          case LOCK:
            SyncEvent lockEvent = (SyncEvent) e;
            SyncEvent unlockEvent = getCorrespondingUnlock(lockEvent);

            if (unlockEvent != null) {
              List<Event> subTrace = events.subList(i, events.indexOf(unlockEvent) + 1);
              if (canRemoveLockedBlock(subTrace)) {
                redundantInterThreadEvents.add(e);
                redundantInterThreadEvents.add(unlockEvent);
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

    for (Event e : redundantInterThreadEvents) {
      logger.debug("ReX (Phase 2): Event " + e + " is redundant.");
      removeEventMetadata(e);
      traceProcessor.eventsPerThread.get(e.getThread()).remove(e);
    }

    return redundantInterThreadEvents.size();
  }

  /**
   * Removes the data associated with this event from the Trace Processor auxiliary structures. Does
   * NOT remove events from eventsPerThread. This operation is idempotent.
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
      case RCV:
        removeEventMetadataAux((SocketEvent) e);
        break;
      case CREATE:
        traceProcessor.forkEvents.get(thread).remove(e);
        break;
      case JOIN:
        traceProcessor.joinEvents.get(thread).remove(e);
        break;
      case LOCK:
      case UNLOCK:
        removeEventMetadataAux((SyncEvent) e);
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

  /**
   * This methods is responsible for removing metadata associated with SND and RCV events.
   *
   * @param e - the event whose metadata is to be prunned
   */
  private void removeEventMetadataAux(SocketEvent e) {
    // Did not call this method removeEventMetadata to avoid erroneous invocations -> the method
    // removeEventMetada runs necessary operations for all events. Didn't want to make people
    // call removeEventMetadataAux by mistake so I named it this way.
    switch (e.getType()) {
      case SND:
        // removes both SND and RCV from msgEvents
        String msgId = e.getMessageId();
        traceProcessor.sndRcvPairs.remove(msgId);
        break;
      case RCV:
        // remove msg handler
        List<Event> handler = traceProcessor.handlerEvents.remove(e);
        if (handler != null) {
          for (Event x : handler) {
            removeEventMetadata(x);
          }
        }
        break;
      default:
        throw new RuntimeException("Removing event metadata with the wrong method");
    }
  }

  /**
   * This methods is responsible for removing metadata associated with LOCK and UNLOCK events.
   *
   * @param e - the event whose metadata is to be prunned
   */
  private void removeEventMetadataAux(SyncEvent e) {
    EventType type = e.getType();

    if (e.equals(LOCK) && e.equals(UNLOCK)) {
      throw new RuntimeException("Removing event metadata with the wrong method");
    }

    String var = e.getVariable();
    List<CausalPair<SyncEvent, SyncEvent>> pairs = traceProcessor.lockEvents.get(var);
    if (pairs != null) {
      CausalPair<SyncEvent, SyncEvent> res = null;
      for (CausalPair<SyncEvent, SyncEvent> pair : pairs) {
        SyncEvent fst = pair.getFirst();
        SyncEvent snd = pair.getSecond();
        if (e.equals(fst) || e.equals(snd)) {
          res = pair;
          break;
        }
      }

      if (res != null) {
        pairs.remove(res);
      }
    }
  }

  private boolean canRemoveHandler(List<Event> handler) {
    Set<String> openLocks = new HashSet<>();
    if (handler == null) {
      return false;
    }
    for (Event e : handler) {
      EventType type = e.getType();
      if (redundantEvents.contains(e)) {
        continue;
      }
      // If a block has any instruction capable of inducing a specific ordering of events,
      // it cannot be removed
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
          openLocks.add(((SyncEvent) e).getVariable());
          break;
        case UNLOCK:
          if (!openLocks.remove(((SyncEvent) e).getVariable())) {
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
      // If a block has any instruction capable of inducing a specific ordering of events,
      // or capable of creating races, it cannot be removed
      if (type == SND
          || type == RCV
          || type == WRITE
          || type == READ
          || type == NOTIFY
          || type == NOTIFYALL
          || type == LOCK
          || type == UNLOCK
          || type == WAIT) {
        return false;
      } else if (type == CREATE) {
        // Possible improvement: do the same thing for the SND events
        ThreadCreationEvent tce = (ThreadCreationEvent) e;
        if (!canRemoveBlock(traceProcessor.eventsPerThread.get(tce.getChildThread()))) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean canRemoveLockedBlock(Iterable<Event> events) {
    Set<String> openLocks = new HashSet<>();
    for (Event e : events) {
      EventType type = e.getType();
      // If a block has any instruction capable of inducing a specific ordering of events,
      // or capable of creating races, it cannot be removed
      if (type == SND
          || type == RCV
          || type == WRITE
          || type == READ
          || type == NOTIFY
          || type == NOTIFYALL
          || type == WAIT) {
        return false;
      } else if (type == CREATE) {
        // Possible improvement: do the same thing for the SND events
        ThreadCreationEvent tce = (ThreadCreationEvent) e;
        if (!canRemoveBlock(traceProcessor.eventsPerThread.get(tce.getChildThread()))) {
          return false;
        }
      } else if (type == LOCK) {
        openLocks.add(((SyncEvent) e).getVariable());
      } else if (type == UNLOCK) {
        if (!openLocks.remove(((SyncEvent) e).getVariable())) {
          // tried to unlock a thread open outside the handler
          return false;
        }
      }
    }
    return true;
  }

  private static <K, V> boolean removeFromMapToLists(Map<K, List<V>> map, K key, V value) {
    List<V> l = map.get(key);
    if (l != null) {
      return l.remove(value);
    }
    return false;
  }

  /**
   * Returns the UNLOCK event that matches with a given LOCK event.
   *
   * @param lockEvent an object representing a particular LOCK event.
   * @return the UNLOCK event that is causally-related to the LOCK event passed as input.
   */
  private SyncEvent getCorrespondingUnlock(SyncEvent lockEvent) {
    String thread = lockEvent.getThread();
    List<CausalPair<SyncEvent, SyncEvent>> pairs =
        traceProcessor.lockEvents.get(lockEvent.getVariable());
    for (CausalPair<SyncEvent, SyncEvent> se : pairs) {
      if (se.getFirst().equals(lockEvent)) {
        return se.getSecond();
      }
    }
    return null;
  }

  /**
   * Returns the JOIN event that happens after a given END event.
   *
   * @param endEvent an object representing a particular END event.
   * @return the JOIN event that is causally-related to the END event passed as input.
   */
  private ThreadCreationEvent getCorrespondingJoin(ThreadCreationEvent endEvent) {
    List<ThreadCreationEvent> joins = traceProcessor.joinEvents.get(endEvent.getThread());
    String childThread = endEvent.getChildThread();
    if (joins == null) return null;
    for (ThreadCreationEvent join : joins) {
      if (join != null && childThread.equals(join.getChildThread())) {
        return join;
      }
    }
    return null;
  }
}
