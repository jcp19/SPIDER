BEGIN {}
{
  # Fields needed:
  # removed events (RW + IT) (Redundant RW %), #contraints(R), #data race candidate locations(R), #actual data races(R), #actual data race locations(R), time to check candidates(R)
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

  if (match($0, "Number of data race candidates:[[:blank:]]*([[:digit:]]*)", res)) {
    results["number_candidates"]=res[1]
  }

  if (match($0, "Number of data race candidate locations:[[:blank:]]*([[:digit:]]*)", res)) {
    results["number_candidate_locations"]=res[1]
  }

  if (match($0, "Number of actual data races:[[:blank:]]*([[:digit:]]*)", res)) {
    results["number_actual"]=res[1]
  }

  if (match($0, "Number of actual data race locations:[[:blank:]]*([[:digit:]]*)", res)) {
    results["number_actual_locations"]=res[1]
  }

  if (match($0, "Time to check all candidates:[[:blank:]]*([[:digit:]]*.[[:digit:]][[:blank:]]*seconds)", res)) {
    results["time"]=res[1]
  }
}

  # removed events (RW + IT) (Redundant RW %), #contraints(R), # data race candidates (R), #data race candidate locations(R), #actual data races(R), #actual data race locations(R), time to check candidates(R)

END {
  printf("(%s + %s) (%s),%s,%s,%s,%s,%s,%s", results["redundant_events_rw"], results["redundant_events_it"], results["percentage_redundant_rw_events"], results["num_constraints"], results["number_candidates"], results["number_candidate_locations"], results["number_actual"], results["number_actual_locations"], results["time"]);
}
