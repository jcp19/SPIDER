package pt.minha.checker.events;

public class SyncEvent extends Event {
    String loc;
    String var;
    // unique identifier of lock/unlock events
    long counter;

    public SyncEvent(String thread, EventType type, String loc, String var, long counter) {
        super(thread, type);
        this.loc = loc;
        this.var = var;
        this.counter = counter;
    }

    public String getLoc() {
        return loc;
    }

    public void setLoc(String loc) {
        this.loc = loc;
    }

    public String getVariable() {
        return var;
    }

    public void setVar(String var) {
        this.var = var;
    }

    public long getCounter() {
        return counter;
    }

    public void setCounter(long counter) {
        this.counter = counter;
    }

    @Override
    public String toString() {
        String res = type+"_"+var+"_"+thread+"_"+counter+"@"+loc;
        return res;
    }
}
