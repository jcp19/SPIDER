package pt.minha.checker;

import pt.haslab.taz.events.CatIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MinhaCheckerParallelTest {

  // Even though this code does not belong to this library,
  // it is important to make sure it gives the intended functionality.
  @org.junit.jupiter.api.Test
  void testCatIterator() {
    List<Integer> a = IntStream.of(1, 4, 7).boxed().collect(Collectors.toList());
    List<Integer> b = IntStream.of(2, 5, 8).boxed().collect(Collectors.toList());
    List<Integer> c = IntStream.of(3, 6, 9).boxed().collect(Collectors.toList());
    List<List<Integer>> aggregate = Stream.of(c, b, a).collect(Collectors.toList());

    List<Integer> expectedResult =
        IntStream.of(1, 2, 3, 4, 5, 6, 7, 8, 9).boxed().collect(Collectors.toList());

    CatIterator<Integer> catIterator = new CatIterator<>(aggregate);
    List<Integer> concatList = new ArrayList<>();
    catIterator.forEachRemaining(concatList::add);

    assertTrue(concatList.equals(expectedResult));
  }

  @org.junit.jupiter.api.Test
  void printIteratorOrder() {}

  @org.junit.jupiter.api.Test
  void main() {}

  @org.junit.jupiter.api.Test
  void printDebugInfo() {}

  @org.junit.jupiter.api.Test
  void removeRedundantEvents() {}

  @org.junit.jupiter.api.Test
  void genDataRaceCandidates() {}

  @org.junit.jupiter.api.Test
  void genMsgRaceCandidates() {}

  @org.junit.jupiter.api.Test
  void checkDataRaces() {}

  @org.junit.jupiter.api.Test
  void orderedToString() {}

  @org.junit.jupiter.api.Test
  void checkMsgRaces() {}

  @org.junit.jupiter.api.Test
  void prettyPrintDataRaces() {}

  @org.junit.jupiter.api.Test
  void prettyPrintMessageRaces() {}

  @org.junit.jupiter.api.Test
  void initSolver() {}

  @org.junit.jupiter.api.Test
  void genIntraNodeConstraints() {}

  @org.junit.jupiter.api.Test
  void genInterNodeConstraints() {}

  @org.junit.jupiter.api.Test
  void genSegmentOrderConstraints() {}

  @org.junit.jupiter.api.Test
  void genProgramOrderConstraints() {}

  @org.junit.jupiter.api.Test
  void genSendReceiveConstraints() {}

  @org.junit.jupiter.api.Test
  void genMessageHandlingConstraints() {}

  @org.junit.jupiter.api.Test
  void genForkStartConstraints() {}

  @org.junit.jupiter.api.Test
  void genJoinExitConstraints() {}

  @org.junit.jupiter.api.Test
  void genLockingConstraints() {}

  @org.junit.jupiter.api.Test
  void genWaitNotifyConstraints() {}
}
