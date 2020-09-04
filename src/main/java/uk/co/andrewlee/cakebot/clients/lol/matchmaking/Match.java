package uk.co.andrewlee.cakebot.clients.lol.matchmaking;

import com.google.common.collect.ImmutableList;
import javax.annotation.concurrent.Immutable;

@Immutable
public class Match {
  private final ImmutableList<Long> team1;
  private final ImmutableList<Long> team2;

  private final double maxStrengthDiff;
  private final double laneVariance;
  private final double averageStrengthDiff;

  public Match(ImmutableList<Long> team1,
      ImmutableList<Long> team2, double maxStrengthDiff, double laneVariance,
      double averageStrengthDiff) {
    this.team1 = team1;
    this.team2 = team2;
    this.maxStrengthDiff = maxStrengthDiff;
    this.laneVariance = laneVariance;
    this.averageStrengthDiff = averageStrengthDiff;
  }
}
