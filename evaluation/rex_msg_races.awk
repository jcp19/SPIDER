BEGIN {}
{
  # Fields needed:
  # - #removed events (RW + IT) (Redundant RW %),
  # - #contraints(R),
  # - #msg-msg race candidates(R),
  # - #actual msg-msg race candidates(R),
  # - time to check candidates(R)
  if (match($0, "Number of redundant events in trace:[[:blank:]]*([[:digit:]]*)", res)) {
    results["redundant_events_rw"]=res[1];
  }

  if (match($0, "Percentage of redundant RW events in trace:[[:blank:]]*([[:digit:]]*.[[:digit:]]*%)", res)) {
    results["percentage_redundant_rw_events"]=res[1];
  }

  if (match($0, "Number of redundant inter-thread events in trace:[[:blank:]]*([[:digit:]]*)", res)) {
    results["redundant_events_it"]=res[1];
  }

  if (match($0, "Number of constraints in model:[[:blank:]]*([[:digit:]]*)", res)) {
    results["num_constraints"]=res[1]
  }

  if (match($0, "Number of message race candidates:[[:blank:]]*([[:digit:]]*)", res)) {
    results["number_candidates"]=res[1]
  }

  if (match($0, "Number of actual message races:[[:blank:]]*([[:digit:]]*)", res)) {
    results["number_actual"]=res[1]
  }

  if (match($0, "Time to check all message race candidates:[[:blank:]]*([[:digit:]]*.[[:digit:]]*[[:blank:]]*seconds)", res)) {
    results["time"]=res[1]
  }
}

# - #removed events (RW + IT) (Redundant RW %),
# - #contraints(R),
# - #msg-msg race candidates(R),
# - #actual msg-msg race candidates(R),
# - time to check candidates(R)
END {
  printf("\"(%s + %s) (%s)\",\"%s\",\"%s\",\"%s\",\"%s\"", results["redundant_events_rw"], results["redundant_events_it"], results["percentage_redundant_rw_events"], results["num_constraints"], results["number_candidates"], results["number_actual"], results["time"]);
}
