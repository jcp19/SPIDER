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

*SPIDER* provides automated distributed data race detection via SMT constraint solving. It receives as input an event trace captured at runtime, and generates a happens-before model that encodes the causal relationships between the events. After building the constraint system, SPIDER resorts to *Z3*, an [SMT solver](https://en.wikipedia.org/wiki/Satisfiability_modulo_theories), to check for data races. It is capable of detecting race conditions **even if no bug caused by race conditions manifests itself in the traced execution**. SPIDER was initially intended to be run alongside [*Minha*]() (in fact, it was originally named *Minha-Checker*) but it became an independent tool.

## Getting Started
### Prerequisites
In order to compile SPIDER on your machine, you first need to have the following packages installed:
- [Z3](https://github.com/Z3Prover/z3) in your PATH
- [falcon-taz](https://github.com/fntneves/falcon/tree/master/falcon-taz) - the easiest way to obtain falcon-taz is to follow these steps:
```bash
# 1. clone the repo
$ git clone git@github.com:fntneves/falcon.git

# 2. change directory to the falcon-taz folder
$ cd falcon/falcon-taz

# 3. install the package using Maven
$ mvn install
```

### Building SPIDER
```bash
# 1. clone this repo
$ git clone git@github.com:jcp19/SPIDER.git

# 2. build a jar using Maven
$ mvn package
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
$ java -jar spider.jar
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
The two races in question occur between the between the instructions on lines 7 and 12 because the
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
$ java -jar spider.jar -d -f traces/Example1.log
[main] Loading events from traces/Example1.log
[main] Trace successfully loaded!
[RaceDetector] Generate program order constraints
[RaceDetector] Generate fork-start constraints
[RaceDetector] Generate join-end constraints
[RaceDetector] Generate wait-notify constraints
[RaceDetector] Generate locking constraints
[RaceDetector] Generate communication constraints
[RaceDetector] Generate message handling constraints

Data Race candidates:
-- (W_demos.Example1.counter_main@10.0.0.1_3@demos.Example1.main.7,
    W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12)
-- (R_demos.Example1.counter_main@10.0.0.1_4@demos.Example1.main.8,
    W_demos.Example1.counter_main@10.0.0.1_3@demos.Example1.main.7)
-- (R_demos.Example1.counter_main@10.0.0.1_4@demos.Example1.main.8,
    W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12)

#Data Race Candidates: 3 | #Actual Data Races: 2
-- (W_demos.Example1.counter_main@10.0.0.1_3@demos.Example1.main.7,
    W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12)
-- (R_demos.Example1.counter_main@10.0.0.1_4@demos.Example1.main.8,
    W_demos.Example1.counter_Thread-1@10.0.0.1_5@demos.Example1.run.12)

=======================
        RESULTS
=======================
> Number of events in trace:          8
> Number of constraints in model:     7
> Time to generate constraint model:  0.001 seconds

## DATA RACES:
> Number of data race candidates:     3
> Number of actual data races:        2
> Time to check all candidates:       0.007 seconds

```

Explicar como interpretar o output, ver que o 2º @ nos eventos indica o local das instrucoes, o primeiro
é o IP do nodo onde surgiu o erro, explicar o que é o numero de constraints, o que são os pares candidatos

## How Does It Work?
Falar brevemente das race conditions, Smts e modelo HB
por código aqui tb incluindo input e output e comandos invocados e trace e imagem do alloy
por link para a minha tese e paper para quem quiser saber mais
Basically, we build a causality relation, which you can see as a graph which
has as nodes events and it has directed edges from a to b if event a must
happen before event b. Visually, two events form a data race if there is not a path from a to b or the other way around (in the directed graph) and a and b are memory accesses to the same variable and either a or b is a write.

- Explicar causalidade como relacao HB que é um [poset](), dizer que isto pode ser visto como um grafo em que
os nodos sao X e ha uma aresta entre X e Y se ...
- Modelo visual em alloy para a execução 1 (explicar que foi gerado com modelo de alloy e por link para o ficheiro no repo)
- Ficheiro em modelo SMT-Lib para exemplo
- Como funciona, que condições sao geradas
- explicar utilizacao dos smt solvers
- If you want to know more about this, stay tuned for my master thesis and a paper that will be published very soon
check this paper or my dissertation

## Roadmap
See the [open issues](https://github.com/jcp19/SPIDER/issues) for a list of proposed features and known issues.

## Contact
SPIDER was developed by
- [Nuno Machado](pagina pessoal) - [@your_twitter](https://twitter.com/your_username) - email@example.com - github
- [João Pereira](pagina pessoal) - [@your_twitter](https://twitter.com/your_username) - email@example.com - github

## Acknowledgements
Besides, SPIDER couldn't be developed without the great work of
* [MINHA]() by [JOP]()
* [Falcon]() by [Neves]()

TODO: por tutorial da tese e por trace na pasta logs
TODO: por link para o modelo do alloy4fun http://alloy4fun.inesctec.pt com o modelo
TODO: por link para o paper
TODO: por alloy no repo
