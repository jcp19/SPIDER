#!/usr/bin/env bash

CSV_OUTPUT_FILE='msg_msg_race_results.csv'
CSV_HEADER='Test Case, #events in the trace, #contraints, #msg-msg race candidates, #actual msg-msg race candidates, #Data races from msg races, time to check candidates, #removed events (RW + Others) (Redundant RW %), #contraints(R), #msg-msg race candidates(R), #actual msg-msg race candidates(R), #Data races from msg races, time to check candidates(R)'
FOLDERS_WITH_TRACES=('../traces/micro-benchmarks' '../traces/cyclon-spider')
#FOLDERS_WITH_TRACES=('../traces/micro-benchmarks')
TIMEOUT='2h'
OUTPUTS_FOLDER="program_outputs/msg_data_race_detection/"

YELLOW='\033[1;33m'
NO_COLOR='\033[0m'
echo -e "${YELLOW}Notice that the values with the tag (R) refer\
  to metrics collected with redundancy elimination${NO_COLOR}"

mkdir -p $OUTPUTS_FOLDER/no_rex $OUTPUTS_FOLDER/rex
echo $CSV_HEADER > $CSV_OUTPUT_FILE
for folder in ${FOLDERS_WITH_TRACES[@]}; do
  echo "Running tests in $folder"
  FOLDER_PATH="${folder}/*"
  for filename in $FOLDER_PATH; do
    echo -n "$(basename $filename)," >> $CSV_OUTPUT_FILE
    timeout $TIMEOUT java -jar ../target/spider-1.0-SNAPSHOT-jar-with-dependencies.jar -m -f $filename \
      | tee $OUTPUTS_FOLDER/no_rex/$(basename $filename) \
      | gawk -f no_rex_msg_races.awk >> $CSV_OUTPUT_FILE
    timeout $TIMEOUT java -jar ../target/spider-1.0-SNAPSHOT-jar-with-dependencies.jar -m -f $filename -r \
      | tee $OUTPUTS_FOLDER/rex/$(basename $filename) \
      | gawk -f rex_msg_races.awk >> $CSV_OUTPUT_FILE
    echo "" >> $CSV_OUTPUT_FILE
    echo "> Test $filename done"
  done
done
