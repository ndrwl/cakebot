package uk.co.andrewlee.drafter;

import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

public class RandomCivDrafter {
    private final static Path CIV_PATH = Paths.get("src/main/resources/civilizations.txt");

    private final ImmutableList<String> civList;

    public static RandomCivDrafter create() throws IOException
    {
        return new RandomCivDrafter(loadCivs(CIV_PATH));
    }

    private RandomCivDrafter(ImmutableList<String> civList) {
        this.civList = civList;
    }
    
    private static ImmutableList<String> loadCivs(Path pathToFile) throws IOException
    {
        try (BufferedReader bufferedReader = Files.newBufferedReader(pathToFile)) {
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
