#!/usr/bin/env bash

# imprime header csv
# para todos os scripts nas pastas que quero:
#   - corre jar data races sem redundancia | usa o resultado no gawk para imprimir meia linha do csv (sem newline no final)
#   - corre jar data races com redundancia | usa o resultado no gawk para imprimir meia linha do csv (com newline no final)
CSV_OUTPUT_FILE='data_race_results.csv'
CSV_HEADER='Test Case, #events in the trace, #contraints, #data race candidates, #data race candidate locations, #actual data races, #actual data race locations, time to check candidates, #contraints(R), #data race candidate locations(R), #actual data races(R), #actual data race locations(R), time to check candidates(R)'
#FOLDERS_WITH_TRACES=('../traces/micro-benchmarks' '../traces/cyclon-spider')
FOLDERS_WITH_TRACES=('../traces/micro-benchmarks')
TIMEOUT='120s'

YELLOW='\033[1;33m'
NO_COLOR='\033[0m'
echo -e "${YELLOW}Notice that the values with the tag (R) are refer\
  to metrics collected with redundancy elimination${NO_COLOR}"

echo $CSV_HEADER > $CSV_OUTPUT_FILE
for folder in ${FOLDERS_WITH_TRACES[@]}; do
  echo "Running tests in $folder"
  FOLDER_PATH="${folder}/*"
  for filename in $FOLDER_PATH; do
    echo -n "$(basename $filename)," >> $CSV_OUTPUT_FILE
    timeout $TIMEOUT java -jar ../target/minha-checker-1.0-SNAPSHOT-jar-with-dependencies.jar -d -f $filename \
      | gawk -f no_rex_data_races.awk >> $CSV_OUTPUT_FILE
    timeout $TIMEOUT java -jar ../target/minha-checker-1.0-SNAPSHOT-jar-with-dependencies.jar -d -f $filename -r \
      | gawk -f rex_data_races.awk >> $CSV_OUTPUT_FILE
    echo "\n" >> $CSV_OUTPUT_FILE
    echo "> Test $filename done"
  done
done
