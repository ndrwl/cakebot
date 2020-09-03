package uk.co.andrewlee.cakebot.clients.channelregistration;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.andrewlee.cakebot.clients.channelregistration.serializers.ChannelRegistrationSerializer;

@ThreadSafe
public class ChannelRegistrar {

  private static final Logger logger = LoggerFactory.getLogger(ChannelRegistrar.class);
  private static final Type CHANNEL_MULTIMAP_TYPE = TypeToken
      .getParameterized(HashMultimap.class, String.class, Long.class).getType();
  private static final Gson GSON = new GsonBuilder()
      .registerTypeAdapter(CHANNEL_MULTIMAP_TYPE, new ChannelRegistrationSerializer())
      .create();

  private static final String SAVE_FILE = "channels";
  private static final String SAVE_FILE_EXTENSION = ".json";

  private final ListeningExecutorService executor;
  private final Path saveFile;

  @GuardedBy("executor")
  private final HashMultimap<String, Long> registeredChannels;

  @GuardedBy("executor")
  private final ListMultimap<String, RegistrationCallback> registrationCallbacks;
  @GuardedBy("executor")
  private final ListMultimap<String, RegistrationCallback> unregistrationCallbacks;

  public static ChannelRegistrar create(Path saveDirectory) {
    Path saveFile = saveDirectory.resolve(SAVE_FILE + SAVE_FILE_EXTENSION);

    if (Files.exists(saveFile) && !Files.isRegularFile(saveFile)) {
      throw new IllegalArgumentException(String.format("Channel Registration Save file, %s, is not"
          + " a file.", saveFile));
    }

    ListeningExecutorService executor = MoreExecutors
        .listeningDecorator(Executors.newSingleThreadExecutor());
    return new ChannelRegistrar(executor, saveFile, HashMultimap.create(),
        LinkedListMultimap.create(), LinkedListMultimap.create());
  }

  private ChannelRegistrar(ListeningExecutorService executor, Path saveFile,
      HashMultimap<String, Long> registeredChannels,
      ListMultimap<String, RegistrationCallback> registrationCallbacks,
      ListMultimap<String, RegistrationCallback> unregistrationCallbacks) {
    this.executor = executor;
    this.saveFile = saveFile;
    this.registeredChannels = registeredChannels;
    this.registrationCallbacks = registrationCallbacks;
    this.unregistrationCallbacks = unregistrationCallbacks;
  }

  public void init() {
    executor.execute(() -> {
      try {
        load();
      } catch (Exception e) {
        logger.info("Error loading registered channels from file.", e);
      }
    });
  }

  private void save() throws Exception {
    Files.deleteIfExists(saveFile);
    try (BufferedWriter bufferedWriter = Files.newBufferedWriter(saveFile)) {
      GSON.toJson(registeredChannels, bufferedWriter);
      logger.info("Saved {} registered channels.", registeredChannels.size());
    }
  }

  private void load() throws Exception {
    try (BufferedReader bufferedReader = Files.newBufferedReader(saveFile)) {
      registeredChannels.clear();

      HashMultimap<String, Long> newRegisteredChannels = GSON.fromJson(bufferedReader,
          CHANNEL_MULTIMAP_TYPE);

      registeredChannels.putAll(newRegisteredChannels);
      logger.info("Loaded {} registered channels.", newRegisteredChannels.size());
    }
  }

  public ListenableFuture<Boolean> registerChannel(long channelId, String registrationTag) {
    return executor.submit(() -> {
      if (registeredChannels.put(registrationTag, channelId)) {
        registrationCallbacks.get(registrationTag).forEach(registrationCallback ->
            registrationCallback.callback(channelId));
        save();
        return true;
      }
      return false;
    });
  }

  public ListenableFuture<Boolean> unregisterChannel(long channelId, String registrationTag) {
    return executor.submit(() -> {
      if (registeredChannels.remove(registrationTag, channelId)) {
        unregistrationCallbacks.get(registrationTag).forEach(unregistrationCallback ->
            unregistrationCallback.callback(channelId));
        save();
        return true;
      }
      return false;
    });
  }

  /**
   * Calls {@param registerCallback} for all currently registered channelIds and all future
   * registered channelIds.
   * <p>
   * Calls {@param unregisterCallback} when a previously registered channelId is unregistered.
   * <p>
   * All callbacks will be made on a single-threaded executor.
   */
  public void registerCallback(String registrationTag, RegistrationCallback registerCallback,
      RegistrationCallback unregisterCallback) {
    executor.execute(() -> {
      registeredChannels.get(registrationTag).forEach(registerCallback::callback);
      registrationCallbacks.put(registrationTag, registerCallback);
      unregistrationCallbacks.put(registrationTag, unregisterCallback);
    });
  }

  @FunctionalInterface
  public interface RegistrationCallback {

    void callback(long channelId);
  }
}