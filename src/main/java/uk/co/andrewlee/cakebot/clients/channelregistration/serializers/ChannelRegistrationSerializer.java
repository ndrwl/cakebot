package uk.co.andrewlee.cakebot.clients.channelregistration.serializers;

import com.google.common.collect.HashMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;

public class ChannelRegistrationSerializer implements JsonSerializer<HashMultimap<String, Long>>,
    JsonDeserializer<HashMultimap<String, Long>> {

  @Override
  public JsonElement serialize(HashMultimap<String, Long> multimap, Type typeOfSrc,
      JsonSerializationContext context) {
    JsonObject jsonObject = new JsonObject();

    multimap.keySet().forEach(
        (registrationTag) -> {
          JsonArray jsonChannelList = new JsonArray();
          multimap.get(registrationTag).forEach(jsonChannelList::add);
          jsonObject.add(registrationTag, jsonChannelList);
        }
    );

    return jsonObject;
  }

  @Override
  public HashMultimap<String, Long> deserialize(JsonElement jsonElement, Type type,
      JsonDeserializationContext context) throws JsonParseException {
    HashMultimap<String, Long> multimap = HashMultimap.create();

    JsonObject jsonObject = jsonElement.getAsJsonObject();
    jsonObject.entrySet().forEach(
        entry -> {
          String registrationTag = entry.getKey();
          JsonArray jsonChannelList = entry.getValue().getAsJsonArray();
          jsonChannelList.forEach(jsonChannelId ->
              multimap.put(registrationTag, jsonChannelId.getAsLong()));
        });

    return multimap;
  }
}
