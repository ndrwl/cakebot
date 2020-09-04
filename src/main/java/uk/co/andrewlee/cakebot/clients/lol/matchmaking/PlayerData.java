package uk.co.andrewlee.cakebot.clients.lol.matchmaking;

import java.util.Objects;
import javax.annotation.concurrent.Immutable;

@Immutable
public class PlayerData {

  private final long playerId;
  private final int[] laneStrength;

  public PlayerData(long playerId, int[] laneStrength) {
    this.playerId = playerId;
    this.laneStrength = laneStrength;
  }

  public long getPlayerId() {
    return playerId;
  }

  public int[] getLaneStrengths() {
    return laneStrength;
  }

  public int getLaneStrength(int laneId) {
    return laneStrength[laneId];
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PlayerData that = (PlayerData) o;
    return playerId == that.playerId;
  }

  @Override
  public int hashCode() {
    return Objects.hash(playerId);
  }
}
