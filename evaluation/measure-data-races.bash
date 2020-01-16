#!/usr/bin/env bash

CSV_OUTPUT_FILE='data_race_results.csv'
CSV_HEADER='"Test Case", "#events in the trace", "#contraints", "#data race candidates", "#data race candidate locations", "#actual data races", "#actual data race locations", "time to check candidates", "#removed events (RW + IT) (Redundant RW %)", "#contraints(R)", "#data race candidates (R)", "#data race candidate locations(R)", "#actual data races(R)", "#actual data race locations(R)", "time to check candidates(R)"'
FOLDERS_WITH_TRACES=('../traces/micro-benchmarks' '../traces/cyclon-spider')
#FOLDERS_WITH_TRACES=('../traces/micro-benchmarks')
TIMEOUT='2h'

YELLOW='\033[1;33m'
NO_COLOR='\033[0m'
echo -e "${YELLOW}Notice that the values with the tag (R) refer\
  to metrics collected with redundancy elimination${NO_COLOR}"

echo $CSV_HEADER > $CSV_OUTPUT_FILE
for folder in ${FOLDERS_WITH_TRACES[@]}; do
  echo "Running tests in $folder"
  FOLDER_PATH="${folder}/*"
  for filename in $FOLDER_PATH; do
    echo -n "\"$(basename $filename)\"," >> $CSV_OUTPUT_FILE
    timeout $TIMEOUT java -jar ../target/minha-checker-1.0-SNAPSHOT-jar-with-dependencies.jar -d -f $filename \
      | gawk -f no_rex_data_races.awk >> $CSV_OUTPUT_FILE
    timeout $TIMEOUT java -jar ../target/minha-checker-1.0-SNAPSHOT-jar-with-dependencies.jar -d -f $filename -r \
      | gawk -f rex_data_races.awk >> $CSV_OUTPUT_FILE
    echo "" >> $CSV_OUTPUT_FILE
    echo "> Test $filename done"
  done
done
