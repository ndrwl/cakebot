package uk.co.andrewlee.cakebot.discord;

import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
public class BotSystem {

  private static final Logger logger = LoggerFactory.getLogger(BotSystem.class);
  private final GatewayDiscordClient discordClient;
  private final User ownUser;
  private final ConcurrentLinkedQueue<BotClient> botClients;

  private final AtomicBoolean hasStarted;

  public static BotSystem create(String token) {
    DiscordClient loginClient = DiscordClientBuilder.create(token)
        .build();
    GatewayDiscordClient discordClient = loginClient.login().block();
    return new BotSystem(discordClient, discordClient.getSelf().block());
  }

  private BotSystem(GatewayDiscordClient discordClient, User ownUser) {
    this.discordClient = discordClient;
    this.ownUser = ownUser;
    this.hasStarted = new AtomicBoolean(false);
    this.botClients = new ConcurrentLinkedQueue<>();
  }

  public void start() {
    if (hasStarted.getAndSet(true)) {
      return;
    }

    discordClient.getEventDispatcher().on(MessageCreateEvent.class)
        .subscribe(this::handleMessageCreateEvent);
  }

  public GatewayDiscordClient getDiscordClient() {
    return discordClient;
  }

  public void registerBotClient(BotClient client) {
    client.init();
    botClients.add(client);
  }

  public String selfMention() {
    return ownUser.getMention();
  }

  private void handleMessageCreateEvent(MessageCreateEvent event) {
    Message message = event.getMessage();
    Optional<List<String>> commandOpt = extractBotCommand(message);

    if (!commandOpt.isPresent()) {
      return;
    }

    List<String> command = commandOpt.get();
    if (command.size() == 0) {
      return;
    }

    botClients.forEach(client -> {
      try {
        client.handle(command, message);
      } catch (Exception e) {
        logger
            .error(String.format("Error executing handler for message, %s", message.getContent()),
                e);
        DiscordHelper.respond(message, ("Error processing command. Please check server logs."));
      }
    });
  }

  private Optional<List<String>> extractBotCommand(Message message) {
    String content = message.getContent().trim();

    if (!content.startsWith(selfMention())) {
      return Optional.empty();
    }

    String commandContent = content.substring(selfMention().length());
    List<String> splitCommand = Arrays.asList(commandContent.split("\\s+"));

    if (splitCommand.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(splitCommand);
  }
}
