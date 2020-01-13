BEGIN {}
{
  # Fields needed:
  # #events in the trace, #contraints, #data race candidates, #data race candidate locations, #actual data races, #actual data race locations, time     to check candidates
  if (match($0, "Number of events in trace:[[:blank:]]*([[:digit:]]*)", res)) {
    results["num_revents"]=res[1];
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

END {
  printf("%s,%s,%s,%s,%s,%s,%s,", results["num_revents"], results["num_constraints"], results["number_candidates"], results["number_candidate_locations"], results["number_actual"], results["number_actual_locations"], results["time"]);
}
