package pt.haslab.spider;

import java.io.IOException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.json.JSONException;
import pt.haslab.spider.solver.Z3SolverParallel;
import pt.haslab.taz.TraceProcessor;

public class CheckerParallel {
  public static void main(String[] args) throws IOException, JSONException {
    Options options = new Options();
    options.addOption(
        "r",
        "removeRedundancy",
        false,
        "Removes redundant events before checking for race conditions.");
    options.addOption("d", "dataRaces", false, "Check for data races.");
    options.addOption("m", "messageRaces", false, "Check for message races.");
    options.addOption("f", "file", true, "File containing the distributed trace.");
    options.addOption("s", "solver", true, "SMT solver used.");
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
      formatter.printHelp("java -jar spider", options);
      System.exit(1);
    }

    TraceProcessor trace = TraceProcessor.INSTANCE;
    trace.loadEventTrace(traceFilePath);
    Stats.INSTANCE.numEventsTrace = trace.getNumberOfEvents();
    // aggregate partitioned messages to facilitate message race detection
    trace.aggregateAllPartitionedMessages();

    if (cmd.hasOption("r")) {
      RedundantEventPruner eventPruner = new RedundantEventPruner(trace);
      Stats.INSTANCE.redundantEvents = eventPruner.removeRedundantRW();
      Stats.INSTANCE.redundantMsgEvents = eventPruner.removeRedundantBlocks();
    }

    Z3SolverParallel solver;
    if (cmd.hasOption("s")) {
      solver = initSolver(cmd.getOptionValue("s"));
    } else {
      solver = initSolver("z3");
    }

    RaceDetector raceDetector = new RaceDetector(solver, trace);
    raceDetector.generateConstraintModel();

    // Instead of using 'raceDetector.checkConflicts()' to check all kinds of conflicts, it
    // executes the analysis for data races and message races depending on the program flags
    if (cmd.hasOption("d")) {
      raceDetector.genDataRaceCandidates();
      raceDetector.computeActualDataRaces();
    }

    if (cmd.hasOption("m")) {
      raceDetector.genMsgRaceCandidates();
      raceDetector.computeActualMsgRaces();
    }

    solver.flush();
    solver.close();
    System.out.println(Stats.getInstance().getSummary());
  }

  public static Z3SolverParallel initSolver(String solverPath) throws IOException {
    // Solver path is now set to be z3
    Z3SolverParallel solver = Z3SolverParallel.getInstance();
    solver.init(solverPath);
    return solver;
  }
}
