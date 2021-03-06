BEGIN {
    results["data_races_from_msg"]="0";
}

{
  # Fields needed:
  # - #events in the trace, 
  # - #contraints,
  # - #msg-msg race candidates, 
  # - #actual msg-msg race candidates, 
  # - time to check candidates,
  if (match($0, "Number of events in trace:[[:blank:]]*([[:digit:]]*)", res)) {
    results["num_revents"]=res[1];
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

  if (match($0, "#Data Races from Message Races:[[:blank:]]*([[:digit:]]*)", res)) {
    results["data_races_from_msg"]=res[1]
  }
}

  # - #events in the trace, 
  # - #contraints,
  # - #msg-msg race candidates, 
  # - #actual msg-msg race candidates, 
  # - #data races from msg races
  # - time to check candidates,
END {
  printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",", results["num_revents"], results["num_constraints"], results["number_candidates"], results["number_actual"], results["data_races_from_msg"], results["time"]);
}
