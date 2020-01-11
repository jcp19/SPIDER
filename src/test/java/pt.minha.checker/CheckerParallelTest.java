package pt.minha.checker;

import org.junit.jupiter.api.Test;
import pt.haslab.taz.events.CatIterator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CheckerParallelTest {

  // Even though this code does not belong to this library,
  // it is important to make sure it gives the intended functionality.
  @Test
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

    assertEquals(concatList, expectedResult);
  }

  @Test
  void testSortedSetToList() {
    // TODO
  }
}
