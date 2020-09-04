package uk.co.andrewlee.cakebot.clients.lol.matchmaking;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NotThreadSafe
public class PlayerMatchmakingData {

  private static final Logger logger = LoggerFactory.getLogger(PlayerMatchmakingData.class);
  private static final Gson GSON = new GsonBuilder()
      .create();

  private final HashMap<Long, PlayerData> playerStats;

  PlayerMatchmakingData() {
    this.playerStats = new HashMap<>();
  }

  public void load(Reader reader) throws JsonParseException {
    playerStats.clear();

    SerializedData loadedData = GSON.fromJson(reader, SerializedData.class);

    loadedData.players.stream().filter(playerData -> playerData.getLaneStrengths().length == 5)
        .forEach(playerData -> playerStats.put(playerData.getPlayerId(), playerData));
    logger.info("Loaded {} LoL player stats.", loadedData.players.size());
  }

  public void save(Writer writer) throws JsonParseException {
    SerializedData serializedData = new SerializedData(ImmutableList.copyOf(playerStats.values()));

    GSON.toJson(serializedData, writer);
    logger.info("Saved {} LoL player stats.", serializedData.players.size());
  }

  @Nullable
  public PlayerData getPlayerData(long playerId) {
    return playerStats.get(playerId);
  }

  public boolean hasPlayerData(long playerId) {
    return playerStats.containsKey(playerId);
  }

  public void updatePlayer(long playerId, int[] laneStrength) {
    playerStats.put(playerId, new PlayerData(playerId, laneStrength));
  }

  public HashMap<Long, PlayerData> getAllPlayerStats() {
    return playerStats;
  }

  public static class SerializedData {

    public List<PlayerData> players;

    public SerializedData(
        List<PlayerData> players) {
      this.players = players;
    }
  }
}
