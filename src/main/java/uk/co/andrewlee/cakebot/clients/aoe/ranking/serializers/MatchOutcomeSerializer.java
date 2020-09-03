package uk.co.andrewlee.cakebot.clients.aoe.ranking.serializers;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.util.Optional;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.Match;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.MatchOutcome;

import java.lang.reflect.Type;
import java.util.List;

public class MatchOutcomeSerializer implements JsonSerializer<MatchOutcome>,
    JsonDeserializer<MatchOutcome> {

  private static final Type PLAYER_LIST_TYPE = new TypeToken<List<Long>>() {
  }.getType();
  private static final Type BOOLEAN_TYPE = new TypeToken<Boolean>() {
  }.getType();

  /**
   * New Style format constants
   **/
  private static final String TEAM1 = "team1";
  private static final String TEAM2 = "team2";
  private static final String OUTCOME = "outcome";


  @Override
  public JsonElement serialize(MatchOutcome matchOutcome, Type type,
      JsonSerializationContext jsonSerializationContext) {
    JsonObject jsonObject = new JsonObject();
    jsonObject.add(TEAM1, jsonSerializationContext.serialize(matchOutcome.getMatch().getTeam1()));
    jsonObject.add(TEAM2, jsonSerializationContext.serialize(matchOutcome.getMatch().getTeam2()));
    jsonObject.add(OUTCOME, jsonSerializationContext.serialize(matchOutcome.isTeam1Won()));
    return jsonObject;
  }

  @Override
  public MatchOutcome deserialize(JsonElement jsonElement, Type type,
      JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
    JsonObject jsonObject = jsonElement.getAsJsonObject();

    List<Long> team1 = jsonDeserializationContext
        .deserialize(jsonObject.get(TEAM1), PLAYER_LIST_TYPE);
    List<Long> team2 = jsonDeserializationContext
        .deserialize(jsonObject.get(TEAM2), PLAYER_LIST_TYPE);
    boolean isTeam1Won = jsonDeserializationContext
        .deserialize(jsonObject.get(OUTCOME), BOOLEAN_TYPE);

    Match match = new Match(ImmutableList.copyOf(team1), ImmutableList.copyOf(team2),
        Optional.empty());

    if (isTeam1Won) {
      return MatchOutcome.createTeam1Won(match);
    } else {
      return MatchOutcome.createTeam2Won(match);
    }
  }
}
