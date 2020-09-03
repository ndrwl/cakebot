package uk.co.andrewlee.cakebot.clients.aoe.ranking;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.gson.JsonParseException;
import de.gesundkrank.jskills.GameInfo;
import de.gesundkrank.jskills.Player;
import de.gesundkrank.jskills.Rating;
import de.gesundkrank.jskills.SkillCalculator;
import de.gesundkrank.jskills.Team;
import de.gesundkrank.jskills.TrueSkillCalculator;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.PlayerRankingData.PlayerStats;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.RankingOperation.CreatePlayerRankingOperation;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.RankingOperation.MatchOutcomeRankingOperation;

@NotThreadSafe
public class PlayerRankingSystem {

  private static final Logger logger = LoggerFactory.getLogger(PlayerRankingSystem.class);
  private static final SimpleDateFormat BACKUP_FILE_NAME_FORMAT = new SimpleDateFormat(
      "yyyy-HH-dd_HH-mm-ss-SSS");
  private static final String BACKUP_FOLDER = "backups";
  private static final String SAVE_FILE = "data";
  private static final String SAVE_FILE_EXTENSION = ".json";

  private final PlayerRankingData playerRankingData;
  private final SkillCalculator skillCalculator;
  private final GameInfo gameInfo;

  private final int maxOperationHistory;
  private final Deque<RankingOperationState> rankingOperationStates;

  private final Path backupDirectory;
  private final Path saveFile;

  public static PlayerRankingSystem create(SkillCalculator skillCalculator, GameInfo gameInfo,
      int maxOperationHistory, Path saveDirectory)
      throws IllegalArgumentException, IOException, JsonParseException {
    PlayerRankingData playerRankingData = new PlayerRankingData(skillCalculator, gameInfo);

    Path backupDirectory = saveDirectory.resolve(BACKUP_FOLDER);
    Path saveFile = saveDirectory.resolve(SAVE_FILE + SAVE_FILE_EXTENSION);

    File saveDirectoryFile = saveDirectory.toFile();
    if (saveDirectoryFile.exists() && !saveDirectoryFile.isDirectory()) {
      throw new IllegalArgumentException(String.format("Save directory, %s, is not a directory.",
          saveDirectoryFile.getAbsolutePath()));
    }

    if (Files.exists(saveFile) && !Files.isRegularFile(saveFile)) {
      throw new IllegalArgumentException(String.format("Save file, %s, is not a file.",
          saveFile));
    }

    if (Files.exists(backupDirectory) && !Files.isDirectory(backupDirectory)) {
      throw new IllegalArgumentException(String.format("Backup directory, %s, is not a directory.",
          backupDirectory));
    }

    if (!Files.exists(backupDirectory)) {
      Files.createDirectory(backupDirectory);
    }

    return new PlayerRankingSystem(playerRankingData,
        skillCalculator, gameInfo, maxOperationHistory, backupDirectory, saveFile);
  }

  private PlayerRankingSystem(PlayerRankingData playerRankingData,
      SkillCalculator skillCalculator, GameInfo gameInfo, int maxOperationHistory,
      Path backupDirectory, Path saveFile) {
    this.playerRankingData = playerRankingData;
    this.skillCalculator = skillCalculator;
    this.gameInfo = gameInfo;
    this.maxOperationHistory = maxOperationHistory;
    this.backupDirectory = backupDirectory;
    this.saveFile = saveFile;
    this.rankingOperationStates = new LinkedList<>();
  }

  public void init() throws IOException {
    if (Files.exists(saveFile)) {
      loadFromFile(saveFile);
    }
  }

  public Match findBalancedMatch(ImmutableSet<Long> allPlayers) {
    Preconditions.checkArgument(allPlayers.size() >= 2);

    int numberOfConfigurationsToConsider = numberOfTopConfigurationsToConsider(allPlayers.size());

    // If there are an even number of players, then this algorithm will consider both:
    // A B C vs D E F and D E F vs A B C. To offset this problem, we consider double the number
    // of combinations.
    if (allPlayers.size() % 2 == 0) {
      numberOfConfigurationsToConsider *= 2;
    }

    ImmutableMap<Long, Player<Long>> playerIdToPlayer = allPlayers.stream()
        .collect(ImmutableMap.toImmutableMap(Function.identity(), Player::new));
    ImmutableMap<Long, Rating> playerIdToRating = allPlayers.stream()
        .collect(ImmutableMap.toImmutableMap(Function.identity(),
            playerRankingData::getPlayerRatingOrDefault));

    int numberOfPlayersOnTeam1 = allPlayers.size() / 2;

    Set<Set<Long>> combinations = Sets.combinations(allPlayers, numberOfPlayersOnTeam1);

    PriorityQueue<Match> potentialMatches = new PriorityQueue<>(
        Comparator.comparingDouble(match -> match.getMatchQuality().get()));

    for (Set<Long> playersOnTeam1 : combinations) {
      Set<Long> playersOnTeam2 = Sets.difference(allPlayers, playersOnTeam1);

      Team team1 = new Team();
      Team team2 = new Team();

      playersOnTeam1.forEach(playerId -> team1.addPlayer(playerIdToPlayer.get(playerId),
          playerIdToRating.get(playerId)));
      playersOnTeam2.forEach(playerId -> team2.addPlayer(playerIdToPlayer.get(playerId),
          playerIdToRating.get(playerId)));

      double quality = TrueSkillCalculator.calculateMatchQuality(gameInfo,
          ImmutableList.of(team1, team2));

      potentialMatches.add(new Match(ImmutableList.copyOf(playersOnTeam1),
          ImmutableList.copyOf(playersOnTeam2), Optional.of(quality)));

      if (potentialMatches.size() > numberOfConfigurationsToConsider) {
        potentialMatches.poll();
      }
    }

    ImmutableList<Match> bestMatches = ImmutableList.copyOf(potentialMatches);
    return bestMatches.get(new Random().nextInt(bestMatches.size()));
  }

  public boolean hasPlayer(long playerId) {
    return playerRankingData.hasPlayer(playerId);
  }

  public Optional<PlayerStats> getPlayerStats(long playerId) {
    return playerRankingData.getPlayerStats(playerId);
  }

  public HashMap<Long, PlayerStats> getAllPlayerStats() {
    return playerRankingData.getAllPlayerStats();
  }

  public void createPlayerWithDefaultRating(long playerId) throws Exception {
    performRankingOperation(new CreatePlayerRankingOperation(playerId, gameInfo.getInitialMean()),
        () -> playerRankingData.createPlayerWithRating(playerId, gameInfo.getInitialMean()));
  }

  public void createPlayerWithRating(long playerId, double meanRating) throws Exception {
    performRankingOperation(new CreatePlayerRankingOperation(playerId, meanRating),
        () -> playerRankingData.createPlayerWithRating(playerId, meanRating));
  }

  public void recordMatchOutcome(MatchOutcome matchOutcome) throws Exception {
    performRankingOperation(new MatchOutcomeRankingOperation(matchOutcome),
        () -> playerRankingData.recordMatchOutcome(matchOutcome));
  }

  public Optional<RankingOperation> lastOperation() {
    return Optional.ofNullable(rankingOperationStates.peekLast())
        .map(RankingOperationState::getRankingOperation);
  }

  public Optional<RankingOperation> undoLastRankingChange() throws Exception {
    Optional<RankingOperationState> lastOperationOpt = Optional
        .ofNullable(rankingOperationStates.pollLast());

    if (!lastOperationOpt.isPresent()) {
      return Optional.empty();
    }
    RankingOperationState lastOperation = lastOperationOpt.get();

    playerRankingData.clear();
    if (lastOperation.dataBeforeOperation.isPresent()) {
      Path backupFile = lastOperation.dataBeforeOperation.get();
      loadFromFile(backupFile);
      Files.deleteIfExists(backupFile);
    }
    saveToFile(saveFile);

    return Optional.of(lastOperation.rankingOperation);
  }

  private void performRankingOperation(RankingOperation rankingOperation,
      Runnable runnableOperation) throws Exception {
    Optional<Path> dataBeforeOperation = Optional.empty();

    // Create a backup
    if (Files.exists(saveFile)) {
      Path backupFile = backupDirectory.resolve(
          BACKUP_FILE_NAME_FORMAT.format(new Date()) + SAVE_FILE_EXTENSION);

      logger.info("Backing up data to {}.", backupFile);
      Files.copy(saveFile, backupFile);
      logger.info("Finished backing up data to {}.", backupFile);

      dataBeforeOperation = Optional.of(backupFile);
    }

    runnableOperation.run();

    // Save
    saveToFile(saveFile);

    // Add to operation stack.
    rankingOperationStates.addLast(new RankingOperationState(rankingOperation,
        dataBeforeOperation));

    while (rankingOperationStates.size() > maxOperationHistory) {
      rankingOperationStates.pollFirst();
    }
  }

  private void loadFromFile(Path file) throws IOException {
    logger.info("Loading from save file, {}.", file);
    try (BufferedReader bufferedReader = Files.newBufferedReader(file)) {
      playerRankingData.load(bufferedReader);
    }
    logger.info("Finished loading from save file, {}.", file);
  }

  private void saveToFile(Path file) throws IOException {
    Files.deleteIfExists(file);
    logger.info("Saving to save file, {}.", file);
    try (BufferedWriter bufferedWriter = Files.newBufferedWriter(file)) {
      playerRankingData.save(bufferedWriter);
    }
    logger.info("Finished saving to save file, {}.", file);
  }

  @Immutable
  private static class RankingOperationState {

    private final RankingOperation rankingOperation;
    private final Optional<Path> dataBeforeOperation;

    private RankingOperationState(RankingOperation rankingOperation,
        Optional<Path> dataBeforeOperation) {
      this.rankingOperation = rankingOperation;
      this.dataBeforeOperation = dataBeforeOperation;
    }

    RankingOperation getRankingOperation() {
      return rankingOperation;
    }

    public Optional<Path> getDataBeforeOperation() {
      return dataBeforeOperation;
    }
  }

  private static int numberOfTopConfigurationsToConsider(int numberOfPlayers) {
    if (numberOfPlayers <= 4) {
      return 1;
    }
    if (numberOfPlayers <= 6) {
      return 2;
    }
    return 3;
  }
}
