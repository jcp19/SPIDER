package pt.minha.checker.stats;

/** Created by nunomachado on 11/05/17. */
public class Stats {

  // TODO: change static fields to instance variables
  // TODO: change printStats to generate a String with the stats THAT WERE UPDATED instead of all
  //       stats and instead of printing directly to the screen

  // general variables
  public static long numEventsTrace = 0;
  public static long numConstraints = 0;
  public static long redundantEvents = 0;
  public static long redundantSocketEvents = 0;
  public static long prunedEvents = 0;
  //public static double buildingModelTime = 0;

  // data race variables
  public static long totalDataRaceCandidates = 0;
  public static long totalDataRacePairs = 0;
  public static double checkingTimeDataRace = 0;

  // message race variables
  public static long totalMsgRaceCandidates = 0;
  public static long totalMsgRacePairs = 0;
  public static double checkingTimeMsgRace = 0;
  public static long totalDataRaceCandidateLocations;
  public static long totalDataRacePairLocations;

  public static void printStats() {
    System.out.println("\n=======================");
    System.out.println("        RESULTS        ");
    System.out.println("=======================");
    System.out.println("> Number of events in trace:\t\t\t" + numEventsTrace);
    System.out.println("> Number of redundant events in trace:\t\t" + redundantEvents);
    System.out.println("> Number of redundant socket events in trace:\t" + redundantSocketEvents);
    System.out.println("> Number of pruned events in trace:\t\t" + prunedEvents);
    System.out.println("> Number of constraints in model:\t\t" + numConstraints);
    /*System.out.println(
        "> Time to generate constraint model:\t\t"
            + (buildingModelTime / (double) 1000)
            + " seconds");*/
    System.out.println("\n## DATA RACES:");
    System.out.println("  > Number of data race candidates:\t\t" + totalDataRaceCandidates);
    System.out.println("  > Number of data race candidate locations:\t\t" + totalDataRaceCandidateLocations);
    System.out.println("  > Number of actual data races:\t\t" + totalDataRacePairs);
    System.out.println("  > Number of actual data race locations:\t\t" + totalDataRacePairLocations);
    System.out.println(
        "  > Time to check all candidates:\t\t"
            + (checkingTimeDataRace / (double) 1000)
            + " seconds");
    System.out.println("\n## MESSAGE RACES:");
    System.out.println("  > Number of message race candidates:\t\t" + totalMsgRaceCandidates);
    System.out.println("  > Number of actual message races:\t\t" + totalMsgRacePairs);
    System.out.println(
        "  > Time to check all candidates:\t\t"
            + (checkingTimeMsgRace / (double) 1000)
            + " seconds");
  }
}
