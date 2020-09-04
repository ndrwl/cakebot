package uk.co.andrewlee.cakebot.clients.lol.matchmaking;

import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NotThreadSafe
public class PlayerMatchmakingSystem {

  private static final Logger logger = LoggerFactory.getLogger(PlayerMatchmakingSystem.class);
  private static final String SAVE_FILE = "lol";
  private static final String SAVE_FILE_EXTENSION = ".json";

  private static final int CANDIDATE_TEAMS_TO_CONSIDER = 5;
  private static final int CANDIDATE_TEAMS_TO_RETURN = 2;
  private static final int TEAM_PERMUTATIONS_TO_CONSIDER = 8;

  // TODO: Think of a nicer way of doing this
  private static final int[] LANE_WEIGHTS = {7, 10, 10, 3, 4, 3, 3};
  private static final int LANE_WEIGHT_SUM = Arrays.stream(LANE_WEIGHTS).sum();

  private final PlayerMatchmakingData playerMatchmakingData;
  private final Path saveFile;

  public static PlayerMatchmakingSystem create(Path saveDirectory) {
    PlayerMatchmakingData playerMatchmakingData = new PlayerMatchmakingData();

    Path saveFile = saveDirectory.resolve(SAVE_FILE + SAVE_FILE_EXTENSION);

    File saveDirectoryFile = saveDirectory.toFile();
    if (saveDirectoryFile.exists() && !saveDirectoryFile.isDirectory()) {
      throw new IllegalArgumentException(String.format("Save directory, %s, is not a directory.",
          saveDirectoryFile.getAbsolutePath()));
    }

    if (Files.exists(saveFile) && !Files.isRegularFile(saveFile)) {
      throw new IllegalArgumentException(String.format("AoE save file, %s, is not a file.",
          saveFile));
    }

    return new PlayerMatchmakingSystem(playerMatchmakingData, saveFile);
  }

  private PlayerMatchmakingSystem(PlayerMatchmakingData playerMatchmakingData, Path saveFile) {
    this.playerMatchmakingData = playerMatchmakingData;
    this.saveFile = saveFile;
  }

  public void init() throws IOException {
    if (Files.exists(saveFile)) {
      loadFromFile(saveFile);
    }
  }

  public boolean hasPlayerData(long playerId) {
    return playerMatchmakingData.hasPlayerData(playerId);
  }

  public HashMap<Long, PlayerData> getAllPlayerStats() {
    return playerMatchmakingData.getAllPlayerStats();
  }

  public void updatePlayerData(long playerId, int[] laneRatings) throws IOException {
    playerMatchmakingData.updatePlayer(playerId, laneRatings);
    saveToFile(saveFile);
  }

  /**
   * Returns the top N potential matches.
   *
   * Will return an emtpy list if any of the players do not have data. Use {@link #hasPlayerData}
   * to verify that all players have data before calling this.
   */
  public ImmutableList<Match> findMatchCandidates(ImmutableSet<Long> allPlayers) {
    Preconditions.checkState(allPlayers.stream().allMatch(this::hasPlayerData));
    Preconditions.checkState(allPlayers.size() == 10);

    ImmutableSet<PlayerData> allPlayersData = allPlayers.stream()
        .map(playerMatchmakingData::getPlayerData)
        .collect(ImmutableSet.toImmutableSet());
    Set<Set<PlayerData>> team1Candidates = Sets.combinations(allPlayersData, 5);

    // Since there are an even number of players; team1Candidates will contain mirrored versions
    // of themselves. To prevent this, we will choose a player and fix them to one of the teams.
    PlayerData fixedPlayer = allPlayersData.stream().findFirst().get();

    PriorityQueue<Match> potentialMatches = new PriorityQueue<>(
        Comparator.comparingDouble(match -> -matchStrength(match)));

    for (Set<PlayerData> playersOnTeam1 : team1Candidates) {
      if (!playersOnTeam1.contains(fixedPlayer)) {
        continue;
      }

      Set<PlayerData> playersOnTeam2 = Sets.difference(allPlayersData, playersOnTeam1);
      Match match = evaluateMatch(playersOnTeam1, playersOnTeam2);

      potentialMatches.add(match);

      if (potentialMatches.size() > CANDIDATE_TEAMS_TO_CONSIDER) {
        potentialMatches.poll();
      }
    }

    ArrayList<Match> finalCandidateMatches = new ArrayList<>();
    finalCandidateMatches.addAll(potentialMatches);
    Collections.shuffle(finalCandidateMatches);

    Random random = new Random();
    // Randomly mirror a few matches
    List<Match> returnList = finalCandidateMatches.subList(0, CANDIDATE_TEAMS_TO_RETURN);
    for (int i = 0; i < CANDIDATE_TEAMS_TO_RETURN; i++) {
      if (random.nextBoolean()) {
        returnList.set(i, returnList.get(i).mirror());
      }
    }

    return ImmutableList.copyOf(returnList);
  }

  /**
   * Arbitrary function to estimate the match strength
   */
  private double matchStrength(Match match) {
    return Math.abs(match.getExpectedLaneVariance())
        + Math.abs(match.getMaxStrengthDiff() / 20.0);
  }

  private Match evaluateMatch(Set<PlayerData> playersOnTeam1,
      Set<PlayerData> playersOnTeam2) {
    // 1. For each team determine the top N permutations which maximize the team strength.
    ImmutableList<TeamConfiguration> topPermutationsForTeam1 = topTeamPermutations(playersOnTeam1,
        TEAM_PERMUTATIONS_TO_CONSIDER);
    ImmutableList<TeamConfiguration> topPermutationsForTeam2 = topTeamPermutations(playersOnTeam2,
        TEAM_PERMUTATIONS_TO_CONSIDER);

    // 2. Calculate the lane variance for all permutations of team 1 vs all permutations of team 2.
    //    Also calculate the total variance for each team configuration - this is used in step 3.
    double[][] laneVariances = new double[TEAM_PERMUTATIONS_TO_CONSIDER]
        [TEAM_PERMUTATIONS_TO_CONSIDER];
    double[] totalVariancesForTeam1 = new double[TEAM_PERMUTATIONS_TO_CONSIDER];
    double[] totalVariancesForTeam2 = new double[TEAM_PERMUTATIONS_TO_CONSIDER];

    for (int team1Index = 0; team1Index < TEAM_PERMUTATIONS_TO_CONSIDER; team1Index++) {
      for (int team2Index = 0; team2Index < TEAM_PERMUTATIONS_TO_CONSIDER; team2Index++) {
        double laneVariance = calculateLaneVariance(
            topPermutationsForTeam1.get(team1Index),
            topPermutationsForTeam2.get(team2Index));
        laneVariances[team1Index][team2Index] = laneVariance;
        totalVariancesForTeam1[team1Index] += laneVariance;
        totalVariancesForTeam2[team2Index] += laneVariance;
      }
    }

    // 3. For each permutation, use the totalVariance to determine a probability function that
    //    represents the probability that the team will pick that permutation.
    //    To calculate the probability function, normalize the totalVariances.

    double totalVarianceSumTeam1 = Arrays.stream(totalVariancesForTeam1).sum();
    double totalVarianceSumTeam2 = Arrays.stream(totalVariancesForTeam2).sum();

    for (int i = 0; i < TEAM_PERMUTATIONS_TO_CONSIDER; i++) {
      totalVariancesForTeam1[i] /= totalVarianceSumTeam1;
      totalVariancesForTeam2[i] /= totalVarianceSumTeam2;
    }

    // 4. Using the probability functions, calculate the expected variance by assuming that the
    //    probability that each team picks their respective permutation is independent.
    //    (ie. we can multiply the probabilities to determine the probability of the two
    //         teams facing with those particular permutations).
    double expectedVariance = 0.0;
    for (int team1Index = 0; team1Index < TEAM_PERMUTATIONS_TO_CONSIDER; team1Index++) {
      for (int team2Index = 0; team2Index < TEAM_PERMUTATIONS_TO_CONSIDER; team2Index++) {
        double probabilityOfOccurring = totalVariancesForTeam1[team1Index]
            * totalVariancesForTeam2[team2Index];
        expectedVariance += probabilityOfOccurring * laneVariances[team1Index][team2Index];
      }
    }

    // 5. Calculate the other stats.
    int maxTeamStrengthDiff = topPermutationsForTeam1.get(TEAM_PERMUTATIONS_TO_CONSIDER - 1)
        .getTeamStrength() -  topPermutationsForTeam2.get(TEAM_PERMUTATIONS_TO_CONSIDER - 1)
        .getTeamStrength();
    int averageTeamStrengthDiff =
        topPermutationsForTeam1.stream().mapToInt(TeamConfiguration::getTeamStrength).sum() -
            topPermutationsForTeam2.stream().mapToInt(TeamConfiguration::getTeamStrength).sum();

    return new Match(ImmutableList.copyOf(playersOnTeam1), ImmutableList.copyOf(playersOnTeam2),
        maxTeamStrengthDiff, expectedVariance, averageTeamStrengthDiff);
  }

  private double calculateLaneVariance(TeamConfiguration team1, TeamConfiguration team2) {
    int squareVariance = 0;
    for (int laneId = 0; laneId < 5; laneId++) {
      int strengthDiff = team1.players.get(laneId).getLaneStrength(laneId)
          - team2.players.get(laneId).getLaneStrength(laneId);
      int squareDiff = strengthDiff * strengthDiff * LANE_WEIGHTS[laneId];
      if (strengthDiff > 0) {
        squareVariance += squareDiff;
      } else {
        squareVariance -= squareDiff;
      }
    }
    // Special cross-lane variance for Support <-> Bot
    {
      int strengthDiff = team1.players.get(3).getLaneStrength(3)
          - team2.players.get(4).getLaneStrength(4);
      int squareDiff = strengthDiff * strengthDiff * LANE_WEIGHTS[5];
      if (strengthDiff > 0) {
        squareVariance += squareDiff;
      } else {
        squareVariance -= squareDiff;
      }
    }
    {
      int strengthDiff = team1.players.get(4).getLaneStrength(4)
          - team2.players.get(3).getLaneStrength(3);
      int squareDiff = strengthDiff * strengthDiff * LANE_WEIGHTS[6];
      if (strengthDiff > 0) {
        squareVariance += squareDiff;
      } else {
        squareVariance -= squareDiff;
      }
    }

    double absVariance = Math.sqrt((double) Math.abs(squareVariance) / LANE_WEIGHT_SUM);
    if (squareVariance > 0) {
      return absVariance;
    }
    return -absVariance;
  }

  private ImmutableList<TeamConfiguration> topTeamPermutations(Set<PlayerData> players,
      int numberOfPermutations) {
    PriorityQueue<TeamConfiguration> potentialTeamConfigurations = new PriorityQueue<>(
        Comparator.comparingInt(TeamConfiguration::getTeamStrength));

    Collection<List<PlayerData>> teamPermutations = Collections2.permutations(players);

    for (List<PlayerData> teamPermutation: teamPermutations) {
      TeamConfiguration teamConfiguration = TeamConfiguration.create(teamPermutation);
      potentialTeamConfigurations.add(teamConfiguration);

      if (potentialTeamConfigurations.size() > numberOfPermutations) {
        potentialTeamConfigurations.poll();
      }
    }

    return ImmutableList.copyOf(potentialTeamConfigurations);
  }

  private void loadFromFile(Path file) throws IOException {
    logger.info("Loading from save file, {}.", file);
    try (BufferedReader bufferedReader = Files.newBufferedReader(file)) {
      playerMatchmakingData.load(bufferedReader);
    }
    logger.info("Finished loading from save file, {}.", file);
  }

  private void saveToFile(Path file) throws IOException {
    Files.deleteIfExists(file);
    logger.info("Saving to save file, {}.", file);
    try (BufferedWriter bufferedWriter = Files.newBufferedWriter(file)) {
      playerMatchmakingData.save(bufferedWriter);
    }
    logger.info("Finished saving to save file, {}.", file);
  }

  private static class TeamConfiguration {
    private final List<PlayerData> players;
    private final int teamStrength;

    public static TeamConfiguration create(List<PlayerData> players) {
      int teamStrength = players.get(0).getLaneStrength(0) +
          players.get(1).getLaneStrength(1) +
          players.get(2).getLaneStrength(2) +
          players.get(3).getLaneStrength(3) +
          players.get(4).getLaneStrength(4);
      return new TeamConfiguration(players, teamStrength);
    }

    private TeamConfiguration(List<PlayerData> players, int teamStrength) {
      this.players = players;
      this.teamStrength = teamStrength;
    }

    public List<PlayerData> getPlayers() {
      return players;
    }

    public int getTeamStrength() {
      return teamStrength;
    }
  }
}
