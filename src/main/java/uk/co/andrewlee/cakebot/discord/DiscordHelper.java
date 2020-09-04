package uk.co.andrewlee.cakebot.discord;

import com.google.common.collect.ImmutableList;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.rest.util.Permission;
import java.util.Collection;
import java.util.stream.Collectors;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiscordHelper {
  private static final Pattern MENTION_PATTERN = Pattern.compile("(?:<@[!]?([0-9]*)>|([0-9]*))");

  public static Optional<Long> extractUserId(String string) {
    Matcher matcher = MENTION_PATTERN.matcher(string);
    if (!matcher.matches()) {
      return Optional.empty();
    }

    // TODO: Something weird is happening with regex here. Not sure what...
    return Optional
        .of(Long.parseLong(Optional.ofNullable(matcher.group(1)).orElse(matcher.group(0))));
  }

  public static ImmutableList<Long> parseUserList(List<String> listOfUserMentions)
      throws IllegalArgumentException {
    ImmutableList.Builder<Long> users = ImmutableList.builder();
    for (String userMention : listOfUserMentions) {
      Optional<Long> userIdOpt = extractUserId(userMention);
      if (!userIdOpt.isPresent()) {
        throw new IllegalArgumentException(
            String.format("Unknown user %s. Please mention each user.", userMention));
      }
      users.add(userIdOpt.get());
    }
    return users.build();
  }

  public static void respond(Message originalMessage, String reply) {
    originalMessage.getChannel().flatMap(channel -> channel.createMessage(reply)).block();
  }

  public static boolean messageIsFromAdmin(Message message) {
    Boolean isMessageFromAdmin = message.getAuthorAsMember().flatMap(Member::getBasePermissions)
        .map(permissionSet -> permissionSet.contains(Permission.ADMINISTRATOR))
        .block();
    return isMessageFromAdmin;
  }

  public static String mentionListOfPLayers(BotSystem botSystem, Collection<Long> playerIds) {
    return playerIds.stream().map(playerId -> mentionPlayer(botSystem, playerId))
        .collect(Collectors.joining(", "));
  }

  public static String mentionPlayer(BotSystem botSystem, long playerId) {
    return botSystem.getDiscordClient().getUserById(Snowflake.of(playerId))
        .map(User::getMention)
        .block();
  }

  public static String playerName(BotSystem botSystem, long playerId, Message message) {
    try {
      return message.getGuild()
          .flatMap(guild -> guild.getMemberById(Snowflake.of(playerId)))
          .map(member -> member.getNickname().orElse(member.getDisplayName()))
          .block();
    } catch (Exception e) {
      try {
        return botSystem.getDiscordClient().getUserById(Snowflake.of(playerId))
            .map(User::getUsername)
            .block();
      } catch (Exception e2) {
        return String.format("Unknown-%s", playerId);
      }
    }
  }
}
