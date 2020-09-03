package uk.co.andrewlee.discord;

import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.stream.Collectors;
import sx.blah.discord.handle.obj.IMessage;
import sx.blah.discord.handle.obj.IUser;
import sx.blah.discord.handle.obj.Permissions;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiscordHelper {

  private static final Pattern MENTION_PATTERN = Pattern.compile("<@[!]?([0-9]*)>");

  public static Optional<Long> extractUserId(String string) {
    Matcher matcher = MENTION_PATTERN.matcher(string);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(Long.parseLong(matcher.group(1)));
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

  public static boolean messageIsFromAdmin(IMessage message) {
    EnumSet<Permissions> authorPermissions = message.getAuthor()
        .getPermissionsForGuild(message.getGuild());
    return authorPermissions.contains(Permissions.ADMINISTRATOR);
  }

  public static String mentionListOfPLayers(BotSystem botSystem, Collection<Long> playerIds) {
    return playerIds.stream().map(playerId -> mentionPlayer(botSystem, playerId))
        .collect(Collectors.joining(", "));
  }

  public static String mentionPlayer(BotSystem botSystem, long playerId) {
    IUser user = botSystem.getDiscordClient().getUserByID(playerId);
    if (user == null) {
      return String.format("UnknownPlayer-%s", playerId);
    }
    return user.mention(true);
  }

  public static String playerName(BotSystem botSystem, long playerId, IMessage message) {
    return botSystem.getDiscordClient().getUserByID(playerId).getDisplayName(message.getGuild());
  }
}
