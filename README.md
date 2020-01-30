<?xml version="1.0"?>

<br/>
<p align="center"><h1 align="center">SPIDER</h1><p align="center">
Automated distributed data race detection from distributed logs via SMT constraint solving.
<br/>
<!--<a href=""><strong>Explore the docs &#xBB;</strong></a>
<br/>
<br/>-->
<a href="https://github.com/jcp19/Minha-checker/issues">Report Bug</a>
&#xB7;
<a href="https://github.com/jcp19/Minha-checker/issues">Request Feature</a>
</p></p>

## About The Project
Data races [have been shown](https://ucare.cs.uchicago.edu/pdf/asplos16-TaxDC.pdf) to be a frequent source of distributed concurrency bugs in major distributed programs.
They occur when two memory accesses to the same variable (where at least one of them is a *write*) are concurrent
and thus, their relative order is unpredictable: if two memory accesses `A` and `B` are concurrent, then
`A` may occur before `B` or `B` may occur before `A`.

*SPIDER* provides automated distributed data race detection via SMT constraint solving. It receives as input an event trace captured at runtime, and generates a happens-before model that encodes the causal relationships between the events. After building the constraint system, SPIDER resorts to *Z3*, an [SMT solver](https://en.wikipedia.org/wiki/Satisfiability_modulo_theories), to check for data races. It is capable of detecting race conditions **even if no bug caused by race conditions manifests itself in the traced execution**. SPIDER was initially intended to be run alongside [*Minha*](https://github.com/jopereira/minha) (in fact, it was originally named *Minha-Checker*) but it became an independent tool.

## Getting Started
### Prerequisites
In order to compile SPIDER on your machine, you first need to have the following packages installed:
- [Z3](https://github.com/Z3Prover/z3) in your PATH
- [falcon-taz](https://github.com/fntneves/falcon/tree/master/falcon-taz) - the easiest way to obtain falcon-taz is to follow these steps:
```bash
# 1. clone the repo
git clone git@github.com:fntneves/falcon.git

# 2. change directory to the falcon-taz folder
cd falcon/falcon-taz

# 3. checkout the branch fix_message_handler (might not be
#    needed in future versions of falcon-taz)
git checkout fix_message_handler

# 4. install the package using Maven
mvn clean install
```

### Building SPIDER
```bash
# 1. clone this repo
git clone git@github.com:jcp19/SPIDER.git

# 2. build a jar using Maven
mvn clean package
```

After these commands, SPIDER will be available as a .jar in your `target/` folder.

## Usage
### Tracing Your Target
SPIDER was designed to inspect event traces from distributed systems and look for data races. Currently,
it can only read traces in the [Event Trace JSON API](https://github.com/fntneves/falcon/tree/master/falcon-taz).
As such, before using SPIDER, you must collect a trace from your target system using a suitable method.
The language in which your target system is written is not important as long as there is an effective
way to trace your system at runtime - take a look at [Minha](https://github.com/jopereira/minha) and [falcon-tracer](https://github.com/fntneves/falcon/tree/master/falcon-tracer).

### Running SPIDER
Having built the jar file, you can run it with a combination of the following command and flags:
```sh
java -jar spider.jar
 -d,--dataRaces          Check for data races.
 -f,--file <arg>         File containing the distributed trace.
 -m,--messageRaces       Check for message races.
 -r,--removeRedundancy   Removes redundant events before checking for race
                         conditions.
```

`-f` is the only required flag and it is used for specifying the path to your trace file. The flag `-d`
indicates that SPIDER will look for data races between different threads while `-m` indicates
that SPIDER should look into data races caused by racing messages (**still an experimental feature!**).
`-r` is used to perform a (safe) optimization which decreases the time needed to find data races in the trace.

During its execution, SPIDER outputs a list of pairs of code locations that contain racing instructions.

### A Brief Example
This section provides a small but descriptive example of how to use SPIDER. We show how to find
data races in the following Java program:
```java
class Example1 implements Runnable {
    static int counter = 0;

    public static void main(String[] args) {
        Thread t1 = new Thread(new Ex1());
        t1.start();
        counter++;
        System.out.println("The value of counter is " + counter);
    }

    public void run() {
        counter++;
    }
}
```

This is by no means a complex program (heck, it only runs two simple threads on a single node!) but
it suffers from two data races - that's enough to demonstrate SPIDER's functionality.
The two races in question occur between the instructions on lines 7 and 12 because the
increments to the static variable `counter` are not synchronized
and
between the instructions on lines 8 and 12 because the increment operation on line 12 is not
causally related with the reading of `counter`'s value on line 8. Because of this,
Example1 has two possible outputs:
- Possible Output #1:
```
The value of counter is 1
```
- Possible Output #2:
```
The value of counter is 2
```

Running this program with a tracing mechanism will produce a trace similar to [this one](https://github.com/jcp19/SPIDER/blob/master/traces/Example1.log):
```javascript
{
    "thread": "main@10.0.0.1",
    "type": "START",
    "timestamp": 1525270020050
}{
    "thread": "main@10.0.0.1",
    "type": "CREATE",
    "child": "Thread-1@10.0.0.1",
    "timestamp": 1525270020069
}{
    "thread": "Thread-1@10.0.0.1",
    "type": "START",
    "timestamp": 1525270021812
}{
    "thread": "main@10.0.0.1",
    "loc": "demos.Example1.main.7",
    "variable": "demos.Example1.counter",
    "type": "W",
    "timestamp": 1525270021837
}{
    "thread": "main@10.0.0.1",
    "loc": "demos.Example1.main.8",
    "variable": "demos.Example1.counter",
    "type": "R",
    "timestamp": 1525270021852
}{
    "thread": "Thread-1@10.0.0.1",
    "loc": "demos.Example1.run.12",
    "variable": "demos.Example1.counter",
    "type": "W",
    "timestamp": 1525270021875
}{
    "thread": "Thread-1@10.0.0.1",
    "type": "END",
    "timestamp": 1525270022188
}{
    "thread": "main@10.0.0.1",
    "type": "END",
    "timestamp": 1525270022200
}
```

> Note: if you are looking for more complex examples, we suggest you take a look at the programs in this [repo](https://github.com/jcp19/micro-benchmarks) whose traces are available [here](https://github.com/jcp19/SPIDER/tree/master/traces).

We are now ready to run SPIDER with the flag `-d` to detect data races resulting from concurrent accesses in
different threads, which will produce the following simplified output:
```bash
java -jar spider.jar -d -f traces/Example1.log
[main] Loading events from traces/Example1.log
[main] Trace successfully loaded!
[RaceDetector] Generate program order constraints
[RaceDetector] Generate fork-start constraints
[RaceDetector] Generate join-end constraints
[RaceDetector] Generate wait-notify constraints
[RaceDetector] Generate locking constraints
[RaceDetector] Generate communication constraints
[RaceDetector] Generate message handling constraints

Data Race Candidates:
-- (W_demos.Example1.counter_main@10.0.0.1_3@demos.Example1.main.7,
    W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12)
-- (R_demos.Example1.counter_main@10.0.0.1_4@demos.Example1.main.8,
    W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12)

Actual Data Races:
-- (W_demos.Example1.counter_main@10.0.0.1_3@demos.Example1.main.7,
    W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12)
-- (R_demos.Example1.counter_main@10.0.0.1_4@demos.Example1.main.8,
    W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12)

=======================
        RESULTS
=======================
> Number of events in trace:          8
> Number of constraints in model:     6
> Time to generate constraint model:  0.001 seconds

## DATA RACES:
> Number of data race candidates:     2
> Number of actual data races:        2
> Time to check all candidates:       0.007 seconds
```
SPIDER's output is structured in the following maner: after some logging messages, it presents the
candidate pairs of events which form a data race.
> Even though the candidate pairs look complicated, they are actually very easy to read - each element of the pair is an identifier of an event which starts with `W` if it is a *write* or with `R` if it is a *read* and is followed by the accessed variable, the thread where it occurs, the node where the thread is running and the line of code responsible for the event.

A pair of events constitutes a candidate if both events access the same variable from different threads
and at least one of them
is a *write*. After listing the candidate pairs, the output lists the actual data races, i.e. the candidate pairs
whose events are **concurrent**. Finally, a brief summary is shown.

## How Does It Work?
SPIDER builds a causality relation `<` between the traced events such that events `a` and `b`
are related (`a < b`) if and only if `a` must occur before `b` in any execution of the system.

> Note: `<` is in fact a [*poset*](https://en.wikipedia.org/wiki/Partially_ordered_set).

Using `<`, it is possible to detect which candidate pairs of memory accesses are in fact concurrent -
they are exactly the pairs of the form `(a,b)` such that neither `a < b` nor `b < a`.
If you're more of a visual learner, you can look at the causality relation `<` as a directed graph whose nodes
are the traced events and whose edges connect nodes `a` and `b` iff `a < b`. Thus, ignoring the actual
identifiers for the events and threads, the causality relation for the `Example1` trace shown above
looks like this:

<center><img src="misc/example1.png" alt="abstract causality relation of Example1"></center>

> this image was obtained in [Alloy](http://alloytools.org/) with [this model](misc/causality_model.als) of the causality relation.

Determining wether two events are concurrent can also be done "visually" - two events are concurrent if there
is not a directed path in the graph that contains both events. For example, there's no directed path that goes
through `Write0` and `Write1`. Thus, they are concurrent.

Internally, SPIDER builds the causality relation as a set of constraints
([e.g. for Example1](misc/example1.smt2))
and queries an SMT solver to decide if two memory accesses are concurrent given those constraints.

**If you are curious and want to know more about this project
(e.g. how we generate the constraints, benchmarks, etc),
stay tuned for my Master's Thesis and
our upcoming paper or send us a private message ;)**

## Roadmap
See the [open issues](https://github.com/jcp19/SPIDER/issues) for a list of proposed features and known issues.

## Contact
SPIDER was developed by
- [João Pereira](http://joaocpereira.me/) - [Twitter](https://twitter.com/joaopereira_19) - [GitHub](https://github.com/jcp19)
- [Nuno Machado](https://www.nunomachado.me) - [GitHub](https://github.com/nunomachado)

## Acknowledgements
SPIDER couldn't be developed without the work that has been put into some great projects such as
* [MINHA](https://github.com/jopereira/minha) by [José Orlando Pereira](https://www.inesctec.pt/en/people/jose-orlando-pereira#)
* [Falcon](https://github.com/fntneves/falcon) by [Francisco Neves](https://www.inesctec.pt/pt/pessoas/francisco-teixeira-neves#)
