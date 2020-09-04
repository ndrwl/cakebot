package uk.co.andrewlee.cakebot.clients.lol.matchmaking;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.andrewlee.cakebot.clients.lol.matchmaking.PlayerMatchmakingData.PlayerData;

@NotThreadSafe
public class PlayerMatchmakingSystem {

  private static final Logger logger = LoggerFactory.getLogger(PlayerMatchmakingSystem.class);
  private static final String SAVE_FILE = "lol";
  private static final String SAVE_FILE_EXTENSION = ".json";

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
    return ImmutableList.of();
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
}
