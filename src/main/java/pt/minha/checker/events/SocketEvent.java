package pt.minha.checker.events;

/**
 * Created by nunomachado on 31/03/17.
 */
public class SocketEvent extends Event {

    public enum ChannelType { TCP, UDP };

    long msgId;
    String src;
    String dst;
    String socketId;
    long timestamp;
    ChannelType channel;
    //represents the n-th event in a thread's local trace
    //(used to compare the order of SND events when encoding TCP constraints)
    int traceOrder;

    public SocketEvent(String thread, EventType type, long msgId, String src, String dst, String socketId, long timestamp, String channel, int localOrder) {
        super(thread, type);
        this.msgId = msgId;
        this.src = src;
        this.dst = dst;
        this.socketId = socketId;
        this.timestamp = timestamp;
        if(channel.equals("TCP"))
            this.channel = ChannelType.TCP;
        else
            this.channel = ChannelType.UDP;
        this.traceOrder = localOrder;
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

    public int getTraceOrder() {
        return traceOrder;
    }

    public void setTraceOrder(int traceOrder) {
        this.traceOrder = traceOrder;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public void setChannel(ChannelType channel) {
        this.channel = channel;
    }


    public boolean conflictsWith(SocketEvent e){
        //two socket events conflict if:
        // a) they are distinct RCV events
        // b) occur at the same node

        if((type == EventType.SND || e.getType() == EventType.SND))
            return false;

        return (this.dst.equals(e.getDst())
                && this.socketId.equals(e.getSocketId())
                && !this.equals(e));
    }

    @Override
    public boolean equals(Object o){
        if(o == this)
            return true;

        if (o == null || getClass() != o.getClass()) return false;

        SocketEvent tmp = (SocketEvent)o;
        return (tmp.getDst().equals(this.dst)
                && tmp.getMsgId() == this.msgId
                && tmp.getSrc().equals(this.src)
                && tmp.getSocketId().equals(this.socketId)
                && tmp.getThread().equals(this.thread)
                && tmp.getTimestamp() == this.timestamp
        );
    }

    @Override
    public String toString() {
        String res = type+"_"+msgId+"_"+thread;
        return res;
    }
}
