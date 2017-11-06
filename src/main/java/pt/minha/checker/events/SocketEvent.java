package pt.minha.checker.events;

/**
 * Created by nunomachado on 31/03/17.
 */
public class SocketEvent extends Event {
    long msgId;
    String src;
    String dst;
    String socketId;
    long timestamp;

    public SocketEvent(String thread, EventType type, long msgId, String src, String dst, String socketId, long timestamp) {
        super(thread, type);
        this.msgId = msgId;
        this.src = src;
        this.dst = dst;
        this.socketId = socketId;
        this.timestamp = timestamp;
    }

    public long getMsgId() {
        return msgId;
    }

    public void setMsgId(long msgId) {
        this.msgId = msgId;
    }

    public String getSrc() {
        return src;
    }

    public void setSrc(String src) {
        this.src = src;
    }

    public String getDst() {
        return dst;
    }

    public void setDst(String dst) {
        this.dst = dst;
    }

    public String getSocketId() {
        return socketId;
    }

    public void setSocketId(String socketId) {
        this.socketId = socketId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public boolean equals(Object o){
        if(o == this)
            return true;

        if (o == null || getClass() != o.getClass()) return false;

        SocketEvent tmp = (SocketEvent)o;
        return (tmp.getDst() == this.dst
                && tmp.getMsgId() == this.msgId
                && tmp.getSrc() == this.src
                && tmp.getSocketId() == this.socketId
                && tmp.getThread() == this.thread
                && tmp.getTimestamp() == this.timestamp
        );
    }

    @Override
    public String toString() {
        String res = type+"_"+thread+"_"+msgId;
        return res;
    }
}
