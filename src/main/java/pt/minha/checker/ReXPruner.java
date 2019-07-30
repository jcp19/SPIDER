package pt.minha.checker;

import pt.haslab.taz.TraceProcessor;

public class ReXPruner {
  private TraceProcessor traceProcessor;

  public ReXPruner(TraceProcessor traceProcessor) {
    this.traceProcessor = traceProcessor;
  }

  public long removeRedundantEvents() {
    return 0;
  }
}
