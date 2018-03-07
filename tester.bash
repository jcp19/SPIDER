java -jar ./target/minha-checker-1.0-SNAPSHOT-jar-with-dependencies.jar -r |
grep "^-- (" | sed -e 's/.*@\(.*\),.*@\(.*\))/(\1,\2)/' |  sort | uniq > mc.txt

java -jar ./target/minha-checker-1.0-SNAPSHOT-jar-with-dependencies.jar | grep "^-- (" | sed -e 's/.*@\(.*\),.*@\(.*\))/(\1,\2)/' |  sort | uniq > mc_rex.txt

diff mc_rex.txt mc.txt

rm mc_rex.txt mc.txt
