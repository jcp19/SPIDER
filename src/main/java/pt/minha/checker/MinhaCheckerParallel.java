package pt.minha.checker;

import org.apache.commons.cli.*;
import pt.haslab.taz.TraceProcessor;
import pt.minha.checker.solver.Solver;
import pt.minha.checker.solver.Z3SolverParallel;
import pt.minha.checker.stats.Stats;

import java.io.IOException;

/** Created by nunomachado on 30/03/17. */
public class MinhaCheckerParallel {

  public static void main(String args[]) {
    // TODO: add flags for message race and data race detection
    Options options = new Options();
    options.addOption(
        "r",
        "removeRedundancy",
        false,
        "Removes redundant events before checking for race conditions.");
    options.addOption("f", "file", true, "File containing the distributed trace");
    CommandLineParser parser = new DefaultParser();
    String traceFilePath = null;
    CommandLine cmd = null;

    try {
      cmd = parser.parse(options, args);
      if (!cmd.hasOption("f")) {
        throw new ParseException("No file path specified.");
      }
      traceFilePath = cmd.getOptionValue("f");
    } catch (ParseException e) {
      System.err.println("Error: " + e);
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("java -jar minha-checker", options);
      System.exit(0);
    }

    try {
      // populate data structures
      TraceProcessor trace = TraceProcessor.INSTANCE;
      /* trace.loadEventTrace(traceFile); */
      trace.loadEventTrace(traceFilePath);
      Stats.numEventsTrace = trace.getNumberOfEvents();

      // aggregate partitioned messages to facilitate message race detection
      trace.aggregateAllPartitionedMessages();

      if (cmd.hasOption("r")) {
        RedundantEventPruner eventPruner = new RedundantEventPruner(trace);
        // remove redundant events
        Stats.redundantEvents = eventPruner.removeRedundantEvents();
        // writeTrace("toCleanTrace.txt");
        Stats.prunedEvents = eventPruner.pruneEvents();
        // writeTrace("cleanTrace.txt");
      }

      Solver solver = initSolver();
      // generate constraint model
      RaceDetector raceDetector = new RaceDetector(solver, trace);
      // check conflicts
      raceDetector.checkConflicts();
      solver.close();
      Stats.printStats();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static Solver initSolver() throws IOException {
    // Solver path is now set to be z3
    String solverPath = "z3";
    Solver solver = Z3SolverParallel.getInstance();
    solver.init(solverPath);
    return solver;
  }
}
