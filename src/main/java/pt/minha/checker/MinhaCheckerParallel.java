package pt.minha.checker;

import java.io.IOException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.json.JSONException;
import pt.haslab.taz.TraceProcessor;
import pt.minha.checker.solver.Solver;
import pt.minha.checker.solver.Z3SolverParallel;
import pt.minha.checker.stats.Stats;

/** Created by nunomachado on 30/03/17. */
public class MinhaCheckerParallel {

  public static void main(String[] args) throws IOException, JSONException {
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

    TraceProcessor trace = TraceProcessor.INSTANCE;
    trace.loadEventTrace(traceFilePath);
    Stats.numEventsTrace = trace.getNumberOfEvents();
    // aggregate partitioned messages to facilitate message race detection
    trace.aggregateAllPartitionedMessages();

    if (cmd.hasOption("r")) {
      RedundantEventPruner eventPruner = new RedundantEventPruner(trace);
      Stats.redundantEvents = eventPruner.removeRedundantEvents();
      Stats.prunedEvents = eventPruner.pruneEvents();
    }

    Solver solver = initSolver();
    RaceDetector raceDetector = new RaceDetector(solver, trace);
    raceDetector.generateConstraintModel();
    raceDetector.checkConflicts();
    solver.close();
    Stats.printStats();
  }

  public static Solver initSolver() throws IOException {
    // Solver path is now set to be z3
    String solverPath = "z3";
    Solver solver = Z3SolverParallel.getInstance();
    solver.init(solverPath);
    return solver;
  }
}
