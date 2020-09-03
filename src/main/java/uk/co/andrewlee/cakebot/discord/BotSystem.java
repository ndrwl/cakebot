package uk.co.andrewlee.cakebot.discord;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sx.blah.discord.api.ClientBuilder;
import sx.blah.discord.api.IDiscordClient;
import sx.blah.discord.api.events.EventSubscriber;
import sx.blah.discord.handle.impl.events.guild.channel.message.MessageReceivedEvent;
import sx.blah.discord.handle.obj.IMessage;
import sx.blah.discord.util.DiscordException;

@ThreadSafe
public class BotSystem {

  private static final Logger logger = LoggerFactory.getLogger(BotSystem.class);

  private final IDiscordClient discordClient;
  private final ConcurrentLinkedQueue<BotClient> botClients;

  private final AtomicBoolean hasStarted;

  public static BotSystem create(String token) {
    IDiscordClient client = new ClientBuilder()
        .withToken(token)
        .build();
    return new BotSystem(client);
  }

  private BotSystem(IDiscordClient discordClient) {
    this.discordClient = discordClient;
    this.hasStarted = new AtomicBoolean(false);
    this.botClients = new ConcurrentLinkedQueue<>();
  }

  public void start() throws DiscordException {
    if (hasStarted.getAndSet(true)) {
      return;
    }
    discordClient.getDispatcher().registerListener(new DiscordEventListener());
    discordClient.login();
  }

  public IDiscordClient getDiscordClient() {
    return discordClient;
  }

  public void registerBotClient(ChannelSpecificBotClient client) {
    botClients.add(client);
    client.init();
  }

  private class DiscordEventListener {

    @EventSubscriber
    public void onMessageReceivedEvent(MessageReceivedEvent messageReceivedEvent) {
      IMessage message = messageReceivedEvent.getMessage();
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
          message.reply("Error processing command. Please check server logs.");
        }
      });
    }

    private Optional<List<String>> extractBotCommand(IMessage message) {
      String content = message.getContent().trim();
      if (!content.startsWith(discordClient.getOurUser().mention()) && !content.startsWith(
          discordClient.getOurUser().mention(false))) {
        return Optional.empty();
      }

      String commandContent = content.substring(discordClient.getOurUser().mention().length());
      List<String> splitCommand = Arrays.asList(commandContent.split("\\s+"));

      if (splitCommand.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(splitCommand);
    }
  }
}
