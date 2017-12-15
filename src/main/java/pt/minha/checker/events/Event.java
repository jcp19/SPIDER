package pt.minha.checker.events;

/**
 * Created by nunomachado on 30/03/17.
 */
public class Event {
    String thread;
    EventType type;
    int id;

    //Allows for total order between events
    static int count = 0;

    public Event(){}

    public Event(String thread, EventType type) {
        this.thread = thread;
        this.type = type;
        this.id = ++count;
    }

    public String getThread() {
        return thread;
    }

    public void setThread(String thread) {
        this.thread = thread;
    }

    public EventType getType() {
        return type;
    }

    public void setType(EventType type) {
        this.type = type;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        String res = type+"_"+thread;
        return res;
    }

    @Override
    public boolean equals(Object o){
        if(o == this)
            return true;

        if (o == null || getClass() != o.getClass()) return false;

        Event tmp = (Event)o;
        return (tmp.getThread() == this.thread
                && tmp.getType() == this.type
        );
    }
}
