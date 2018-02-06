package pt.minha.checker.stats;

/**
 * Created by nunomachado on 11/05/17.
 */
public class Stats {

    public static long numEventsTrace = 0;
    public static long numConstraints = 0;
    public static long totalCandidatePairs = 0;
    public static long totalDataRacePairs = 0;
    public static long redundantEvents = 0;
    public static double buildingModeltime = 0;
    public static double checkingTime = 0;

    public static void printStats(){
        System.out.println("======= RESULTS =======");
        System.out.println("> Number of events in trace:\t\t"+numEventsTrace);
        System.out.println("> Number of redundant events in trace:\t"+redundantEvents);
        System.out.println("> Number of constraints in model:\t"+numConstraints);
        System.out.println("> Number of candidate pairs:\t\t"+totalCandidatePairs);
        System.out.println("> Number of data race pairs:\t\t"+totalDataRacePairs);
        System.out.println("> Time to generate constraint model:\t"+(buildingModeltime/(double)1000)+" seconds");
        System.out.println("> Time to check all candidate pairs:\t"+(checkingTime/(double)1000)+" seconds");
    }
}
