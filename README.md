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

## Table of Contents

* [About the Project](#about-the-project)
* [Getting Started](#getting-started)
  * [Prerequisites](#prerequisites)
  * [Installation](#installation)
* [Usage](#usage)
  * [Tracing your target]()
  * [Runing SPIDER]()
  * [A brief example]()
* [Roadmap](#roadmap)
* [Contact](#contact)
* [Acknowledgements](#acknowledgements)

## About The Project
Data races [have been shown](https://ucare.cs.uchicago.edu/pdf/asplos16-TaxDC.pdf) to be a frequent source of distributed concurrency bugs in major distributed programs.
They occur when two memory accesses to the same variable (where at least one of them is a *write*) are concurrent
and thus, their relative order is unpredictable: if two memory accesses `A` and `B` are concurrent, then
`A` may occur before `B` or `B` may occur before `A`.

*SPIDER* provides automated distributed data race detection via SMT constraint solving. It receives as input an event trace captured at runtime, and generates a happens-before model that encodes the causal relationships between the events. After building the constraint system, SPIDER resorts to *Z3*, an [SMT solver](https://en.wikipedia.org/wiki/Satisfiability_modulo_theories), to check for data races. It is capable of detecting race conditions **even if no bug caused by race conditions manifests itself in the traced execution**. SPIDER was initially intended to be run alongside [*Minha*]() (in fact, it was originally called *Minha-Checker*) but it became an independent tool.

## Getting Started
### Prerequisites
In order to compile SPIDER on your machine, you first need to have the following packages installed:
- [z3](https://github.com/Z3Prover/z3) in your PATH
- [falcon-taz](https://github.com/fntneves/falcon/tree/master/falcon-taz) - the easiest way to obtain falcon-taz is to follow these steps:
```sh
# 1. clone the repo
git clone git@github.com:fntneves/falcon.git

# 2. change directory to the falcon-taz folder
cd falcon/falcon-taz

# 3. install the package using Maven
mvn install
```

### Building SPIDER
```sh
# 1. clone this repo
git clone git@github.com:jcp19/SPIDER.git

# 2. build a jar using Maven
$ mvn package
```

After these commands, SPIDER will be available as a .jar in your `target/` folder.

## Usage
### Tracing your target
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

### A brief example
- Código Programa
- Trace caputrado
- Output
- Apontar para exemplos maiores na pasta dos traces (Cyclon 5N 5R)

por link para o ficheiro no repo do Exemplo1 da tese
por código aqui tb incluindo input e output e comandos invocados e trace e imagem do alloy

## How Does it Work?
Falar brevemente das race conditions, Smts e modelo HB
por link para a minha tese e paper para quem quiser saber mais

- Modelo visual em alloy para a execução 1 (explicar que foi gerado com modelo de alloy e por link para o ficheiro no repo)
- Ficheiro em modelo SMT-Lib para exemplo
- Como funciona, que condições sao geradas
- If you want to know more about this, check this paper or my dissertation

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
