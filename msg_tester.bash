java -jar ./target/minha-checker-1.0-SNAPSHOT-jar-with-dependencies.jar -r | tee /tmp/nre.log |
grep "^~~ (" | sed -e 's/.*@\(.*\),.*@\(.*\))/(\1,\2)/' |  sort | uniq > mc.txt

java -jar ./target/minha-checker-1.0-SNAPSHOT-jar-with-dependencies.jar | tee /tmp/re.log |grep "^~~ (" | sed -e 's/.*@\(.*\),.*@\(.*\))/(\1,\2)/' |  sort | uniq > mc_msg.txt

diff mc_msg.txt mc.txt

#rm mc_rex.txt mc.txt
