package pt.minha.checker.events;

/**
 * Created by nunomachado on 30/03/17.
 */
public enum EventType {
    //thread events
    CREATE("CREATE"),
    START("START"),
    END("END"),
    JOIN("JOIN"),

    //access events
    READ("R"),
    WRITE("W"),

    //communication events
    SND("SND"),
    RCV("RCV"),

    //socket handling partial-order events
    HNDLBEG("HANDLERBEGIN"),
    HNDLEND("HANDLEREND"),

    // lock and unlock events
    LOCK("LOCK"),
    UNLOCK("UNLOCK"),

    //thread synchronization events
    WAIT("WAIT"),
    NOTIFY("NOTIFY"),
    NOTIFYALL("NOTIFYALL");

    private final String desc;

    private EventType(String l){
        this.desc = l;
    }

    @Override
    public String toString() {
        return this.desc;
    }


    /**
     * Translates a string representing the type of event
     * the corresponding EventType enum element.
     * @param type
     * @return
     */
    public static EventType getEventType(String type){

        if(type.equals("CREATE"))
            return EventType.CREATE;
        else if(type.equals("START"))
            return EventType.START;
        else if(type.equals("END"))
            return EventType.END;
        else if(type.equals("JOIN"))
            return EventType.JOIN;
        else if(type.equals("R"))
            return EventType.READ;
        else if(type.equals("W"))
            return EventType.WRITE;
        else if(type.equals("SND"))
            return EventType.SND;
        else if(type.equals("RCV"))
            return EventType.RCV;
        else if(type.equals("HANDLERBEGIN"))
            return EventType.HNDLBEG;
        else if(type.equals("HANDLEREND"))
            return EventType.HNDLEND;
        else if(type.equals("LOCK"))
            return EventType.LOCK;
        else if(type.equals("UNLOCK"))
            return EventType.UNLOCK;
        else if(type.equals("WAIT"))
            return EventType.WAIT;
        else if(type.equals("NOTIFY"))
            return EventType.NOTIFY;
        else if(type.equals("NOTIFYALL"))
            return EventType.NOTIFYALL;
        else
            return null;
    }
}
