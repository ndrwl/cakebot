package uk.co.andrewlee.cakebot.drafter;

import com.google.common.collect.ImmutableList;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

public class RandomCivDrafter {
  private final static String CIV_FILE = "civilizations.txt";

  private final ImmutableList<String> civList;

  public static RandomCivDrafter create() throws IOException {
    return new RandomCivDrafter(loadCivs(CIV_FILE));
  }

  private RandomCivDrafter(ImmutableList<String> civList) {
    this.civList = civList;
  }

  private static ImmutableList<String> loadCivs(String filename) throws IOException {
    try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
        ClassLoader.getSystemResourceAsStream(filename)))) {
      return bufferedReader.lines().collect(ImmutableList.toImmutableList());
    }
  }

  public ImmutableList<String> randomDraft(int numberOfCivs) {
    ArrayList<String> civSelectionList = new ArrayList<>(civList);
    Collections.shuffle(civSelectionList);
    if (numberOfCivs > civList.size()) {
      return ImmutableList.copyOf(civSelectionList);
    }
    return ImmutableList.copyOf(civSelectionList.subList(0, numberOfCivs));
  }
}
