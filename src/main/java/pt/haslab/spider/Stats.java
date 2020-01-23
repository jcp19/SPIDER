package pt.haslab.spider;

import java.text.DecimalFormat;

/**
 * Created by nunomachado on 11/05/17.
 */
public enum Stats
{
    INSTANCE;

    // general variables
    public long numEventsTrace;

    public long numConstraints;

    public long redundantEvents;

    public long redundantMsgEvents;

    public double percentRedundantRW;
    // public static double buildingModelTime = 0;

    // data race variables
    public long totalDataRaceCandidates;

    public long totalDataRacePairs;

    public double checkingTimeDataRace;

    // message race variables
    public long totalMsgRaceCandidates;

    public long totalMsgRacePairs;

    public double checkingTimeMsgRace;

    public long totalDataRaceCandidateLocations;

    public long totalDataRacePairLocations;

    Stats()
    {
    }

    public static Stats getInstance()
    {
        return INSTANCE;
    }

    public String getSummary()
    {
        DecimalFormat df = new DecimalFormat( "00.00" );
        StringBuilder sb = new StringBuilder();
        appendLn( sb, "\n=======================" );
        appendLn( sb, "        RESULTS        " );
        appendLn( sb, "=======================" );
        appendLn( sb, "> Number of events in trace:\t\t" + numEventsTrace );
        appendLn( sb, "> Number of redundant events in trace:\t\t" + redundantEvents );
        appendLn(
                        sb,
                        "> Percentage of redundant RW events in trace:\t\t" + df.format( percentRedundantRW ) + "%" );
        appendLn( sb, "> Number of redundant inter-thread events in trace:\t\t" + redundantMsgEvents );
        appendLn( sb, "> Number of constraints in model:\t\t" + numConstraints );
    /*System.out.println(
    "> Time to generate constraint model:\t\t"
        + (buildingModelTime / (double) 1000)
        + " seconds");*/
        appendLn( sb, "\n## DATA RACES:" );
        appendLn( sb, "  > Number of data race candidates:\t\t" + totalDataRaceCandidates );
        appendLn(
                        sb, "  > Number of data race candidate locations:\t\t" + totalDataRaceCandidateLocations );
        appendLn( sb, "  > Number of actual data races:\t\t" + totalDataRacePairs );
        appendLn( sb, "  > Number of actual data race locations:\t\t" + totalDataRacePairLocations );
        appendLn(
                        sb,
                        "  > Time to check all candidates:\t\t"
                                        + ( checkingTimeDataRace / (double) 1000 )
                                        + " seconds" );
        appendLn( sb, "\n## MESSAGE RACES:" );
        appendLn( sb, "  > Number of message race candidates:\t\t" + totalMsgRaceCandidates );
        appendLn( sb, "  > Number of actual message races:\t\t" + totalMsgRacePairs );
        appendLn(
                        sb,
                        "  > Time to check all message race candidates:\t\t"
                                        + ( checkingTimeMsgRace / (double) 1000 )
                                        + " seconds" );
        return sb.toString();
    }

    private void appendLn( StringBuilder sb, String s )
    {
        sb.append( s );
        sb.append( "\n" );
    }
}
