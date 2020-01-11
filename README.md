<?xml version="1.0"?>

[![Build Status][build-shield]][build-url]
[![Contributors][contributors-shield]][contributors-url]
[![MIT License][license-shield]][license-url]

<br/>
<p align="center"><h1 align="center">minha-checker</h1><p align="center">
Automated distributed data race detection from distributed logs via SMT constraint solving.
<br/>
<!--<a href=""><strong>Explore the docs &#xBB;</strong></a>
<br/>
<br/>-->
<a href="https://github.com/jcp19/Minha-checker/issues">Report Bug</a>
&#xB7;
<a href="https://github.com/jcp19/Minha-checker/issues">Request Feature</a>
</p></p>


&gt; TODO: rewrite README
&gt; EXPERIMENTAL: Message race analysis
TODO: por tutorial da tese e por trace na pasta logs
TODO: por o mesmo tutorial que vou por na tese
TODO: por link para o modelo do alloy4fun http://alloy4fun.inesctec.pt com o modelo

Minha checker provides automated distributed data race detection for Minha via SMT constraint solving. More concretely, Minha checker receives as input an event trace captured at runtime, and generates a happens-before model that encodes the causal relationships between the events. After building the constraint system, Minha checker resorts to an SMT solver, namely Z3, to check for data races. A data race occurs when any two events over the same variable (where at least one of them is a write) are not ordered by a happens-before relationship. 

## Configuration 

Edit configuration file `/src/main/resources/checker.racedetection.properties` as follows:
- **event-file** should indicate the path to the file containing the events captured at runtime. By default, this should point to `minhaTRACER.log`.
- **solver-bin** should indicate the path to the solver binary. By default, this should point to `lib/z3_4.4.1`. *NOTE: The solver binaries in this repo refer to a version of Z3 compiled for MacOS. For different operating systems, please check the [official site](https://github.com/Z3Prover/z3).*


## Building the project

## Usage
POR FLAGS!!!
**1. Compile:**

```
$ mvn package 
```

**2. Run jar:**

```
$ java -jar ./target/minha-checker-1.0-SNAPSHOT-jar-with-dependencies.jar
```

**3. Output:** Minha checker outputs a list of event pairs that correspond to data races. TODO: mudar para linhas de código (?)

## Trce format
mandar para site do falcon

## Dependencies
- Falcon (https://github.com/fntneves/falcon/tree/master/falcon-taz)
- [z3](https://github.com/Z3Prover/z3) in PATH