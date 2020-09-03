package uk.co.andrewlee.cakebot.clients.aoe.ranking;

import com.google.common.collect.ImmutableList;

import java.util.Optional;
import javax.annotation.concurrent.Immutable;

@Immutable
public class Match {

  private final ImmutableList<Long> team1;
  private final ImmutableList<Long> team2;
  private final Optional<Double> matchQuality;

  public Match(ImmutableList<Long> team1, ImmutableList<Long> team2,
      Optional<Double> matchQuality) {
    this.team1 = team1;
    this.team2 = team2;
    this.matchQuality = matchQuality;
  }

  public ImmutableList<Long> getTeam1() {
    return team1;
  }

  public ImmutableList<Long> getTeam2() {
    return team2;
  }

  public Optional<Double> getMatchQuality() {
    return matchQuality;
  }
}
