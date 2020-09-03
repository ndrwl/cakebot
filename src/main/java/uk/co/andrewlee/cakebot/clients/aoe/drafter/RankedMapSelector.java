package uk.co.andrewlee.cakebot.clients.aoe.drafter;

import com.google.common.collect.ImmutableList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Random;

public class RankedMapSelector {
    private final static String MAPS_FILE = "rankedmaps.txt";

    private final ImmutableList<String> mapList;
    private final Random random;

    public static RankedMapSelector create() throws IOException {
        return new RankedMapSelector(loadMaps(MAPS_FILE), new Random(111111));
    }

    public String randomMap() {
        return mapList.get(random.nextInt(mapList.size()));
    }

    public ImmutableList<String> allRankedMaps() {
        return mapList;
    }

    private RankedMapSelector(ImmutableList<String> mapList, Random random) {
        this.mapList = mapList;
        this.random = random;
    }

    private static ImmutableList<String> loadMaps(String filename) throws IOException {
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                ClassLoader.getSystemResourceAsStream(filename)))) {
            return bufferedReader.lines().collect(ImmutableList.toImmutableList());
        }
    }
}
