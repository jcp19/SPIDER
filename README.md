
## Minha Checker

Minha checker provides automated distributed data race detection for Minha via SMT constraint solving. More concretely, Minha checker receives as input an event trace captured at runtime, and generates a happens-before model that encodes the causal relationships between the events. After building the constraint system, Minha checker resorts to an SMT solver, namely Z3, to check for data races. A data race occurs when any two events over the same variable (where at least one of them is a write) are not ordered by a happens-before relationship. 

### Configuration 

Edit configuration file `/src/main/resources/checker.racedetection.properties` as follows:
- **event-file** should indicate the path to the file containing the events captured at runtime. By default, this should point to `minhaTRACER.log`.
- **solver-bin** should indicate the path to the solver binary. By default, this should point to `lib/z3_4.4.1`.

### Usage
**1. Compile:**

```
$ mvn package 
```

**2. Run jar:**

```
$ java -jar ./target/minha-checker-1.0-SNAPSHOT-jar-with-dependencies.jar
```

**3. Output:** Minha checker outputs a list of event pairs that correspond to data races.



