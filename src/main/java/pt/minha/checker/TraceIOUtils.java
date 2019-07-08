package pt.minha.checker;

import pt.haslab.taz.TraceProcessor;
import pt.haslab.taz.events.Event;
import pt.haslab.taz.events.EventIterator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class TraceIOUtils {
  private static void writeTrace(TraceProcessor trace, String path) {
    PrintWriter pw = null;

    try {
      File file = new File(path);
      FileWriter fw = new FileWriter(file, true);
      pw = new PrintWriter(fw);
      EventIterator events = new EventIterator(trace.eventsPerThread.values());
      while (events.hasNext()) {
        Event e = events.next();
        pw.println(e);
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (pw != null) {
        pw.close();
      }
    }
  }

  public void printIteratorOrder(TraceProcessor trace) {
    EventIterator events = new EventIterator(trace.eventsPerThread.values());
    while (events.hasNext()) {
      Event e = events.next();
      System.out.println(e.getEventId() + " :: " + e.toString());
    }
  }
}
