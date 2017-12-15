package pt.minha.checker.events;

import java.util.*;

public class EventIterator implements Iterator<Event>{
    private PriorityQueue<MyPair<Iterator<Event>, Event>> eventHeap;

    public final Comparator<MyPair<Iterator<Event>,Event>> heapOrder = new Comparator<MyPair<Iterator<Event>, Event>>() {
        //doesnt support null arguments
        public int compare(MyPair<Iterator<Event>, Event> o1, MyPair<Iterator<Event>, Event> o2) {
            return o2.getSecond().getId() - o1.getSecond().getId();
        }
    };

    public EventIterator(Collection<List<Event>> eventLists) {
        //Holds the first value of a list and an iterator of the rest of the list
        eventHeap = new PriorityQueue<MyPair<Iterator<Event>, Event>>(eventLists.size(), heapOrder);

        for(List<Event> eventList : eventLists) {
            if(!eventList.isEmpty()) {
                Iterator<Event> it = eventList.iterator();
                Event firstElem = it.next();
                eventHeap.add(new MyPair<Iterator<Event>, Event>(it, firstElem));
            }
        }
    }

    public boolean hasNext() {
        return eventHeap.isEmpty();
    }

    public Event next() {
        if(!this.hasNext()) {
            throw new NoSuchElementException("There are no more elements");
        }
        MyPair<Iterator<Event>, Event> heapTop = eventHeap.poll();

        Iterator<Event> it = heapTop.getFirst();
        Event next = heapTop.getSecond();

        if(it.hasNext()) {
            Event toInsert = it.next();
            eventHeap.add(new MyPair<Iterator<Event>, Event>(it, toInsert));
        }
        return next;
    }

    public void remove() {
        //Not implemented
    }
}
