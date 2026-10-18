package com.example.voicecommand;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Mod(
        modid = VoiceCommandMod.MODID,
        name = VoiceCommandMod.NAME,
        version = VoiceCommandMod.VERSION,
        clientSideOnly = true,
        dependencies = "required-after:voicechat"
)
@ForgeVoicechatPlugin
public class VoiceCommandMod implements VoicechatPlugin {

    public static final String MODID = "voicecommand";
    public static final String NAME = "Voice Command Mod";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    private static final float VOSK_SAMPLE_RATE = 16000.0f;
    private static final int DOWNSAMPLE_FACTOR = 3;
    private static final long COMMAND_COOLDOWN_MS = 800L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Map<String, String> allowedCommands = new LinkedHashMap<>();
    private static File configDir;
    private static File modelDir;

    private static final BlockingQueue<short[]> AUDIO_QUEUE = new LinkedBlockingQueue<>(150);
    private static volatile boolean workerRunning = false;
    private static long lastExecutionTime = 0L;
    private static String lastTriggeredWord = "";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        configDir = new File(event.getModConfigurationDirectory(), MODID);
        if (!configDir.exists()) {
            configDir.mkdirs();
        }

        modelDir = new File(configDir, "model");
        loadOrCreateCommandConfig(new File(configDir, "commands.json"));
        startSpeechRecognitionWorker();
    }

    private void loadOrCreateCommandConfig(File configFile) {
        if (!configFile.exists()) {
            allowedCommands.put("도약", "/도약");
            allowedCommands.put("화염구", "/화염구");
            allowedCommands.put("회복", "/회복");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
                GSON.toJson(allowedCommands, writer);
            } catch (Exception e) {
                LOGGER.error("기본 commands.json 생성 실패", e);
            }
            return;
        }

        try (Reader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
            Map<String, String> loaded = GSON.fromJson(reader, type);
            if (loaded != null && !loaded.isEmpty()) {
                allowedCommands = loaded;
            }
        } catch (Exception e) {
            LOGGER.error("commands.json 로드 실패, 기본값을 사용합니다.", e);
            allowedCommands.put("도약", "/도약");
        }
    }

    private String buildGrammarJson() {
        JsonArray grammarArray = new JsonArray();
        for (String word : allowedCommands.keySet()) {
            grammarArray.add(word.trim());
        }
        grammarArray.add("[unk]");
        return GSON.toJson(grammarArray);
    }

    private void startSpeechRecognitionWorker() {
        if (workerRunning) {
            return;
        }
        workerRunning = true;

        Thread worker = new Thread(() -> {
            if (!modelDir.exists() || !modelDir.isDirectory()) {
                LOGGER.warn("Vosk 한국어 모델 폴더가 없습니다: {}", modelDir.getAbsolutePath());
                return;
            }

            LibVosk.setLogLevel(LogLevel.WARNINGS);
            String grammarJson = buildGrammarJson();

            try (Model model = new Model(modelDir.getAbsolutePath());
                 Recognizer recognizer = new Recognizer(model, VOSK_SAMPLE_RATE, grammarJson)) {

                while (workerRunning) {
                    short[] pcm48k = AUDIO_QUEUE.poll(100, TimeUnit.MILLISECONDS);
                    if (pcm48k == null) {
                        continue;
                    }

                    byte[] pcm16kBytes = downsample48kTo16kBytes(pcm48k);

                    if (recognizer.acceptWaveForm(pcm16kBytes, pcm16kBytes.length)) {
                        processVoskResult(recognizer.getResult(), true);
                        lastTriggeredWord = "";
                    } else {
                        processVoskResult(recognizer.getPartialResult(), false);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Vosk 음성 인식 스레드 오류 발생", e);
            }
        }, "SVC-Vosk-STT-Worker");

        worker.setDaemon(true);
        worker.start();
    }

    private static byte[] downsample48kTo16kBytes(short[] samples48k) {
        int outSamples = samples48k.length / DOWNSAMPLE_FACTOR;
        byte[] outBytes = new byte[outSamples * 2];

        for (int i = 0; i < outSamples; i++) {
            int srcIdx = i * DOWNSAMPLE_FACTOR;
            int avg = (samples48k[srcIdx] + samples48k[srcIdx + 1] + samples48k[srcIdx + 2]) / 3;
            short sample = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, avg));

            outBytes[i * 2] = (byte) (sample & 0xFF);
            outBytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return outBytes;
    }

    private static void processVoskResult(String jsonResult, boolean isFinal) {
        if (jsonResult == null || jsonResult.isEmpty()) {
            return;
        }

        try {
            JsonObject obj = GSON.fromJson(jsonResult, JsonObject.class);
            String key = isFinal ? "text" : "partial";
            if (!obj.has(key)) {
                return;
            }

            String recognizedText = obj.get(key).getAsString().trim();
            if (recognizedText.isEmpty() || recognizedText.equals("[unk]")) {
                return;
            }

            String[] tokens = recognizedText.split("\\s+");
            for (String token : tokens) {
                if (allowedCommands.containsKey(token)) {
                    executeAsPlayer(token, allowedCommands.get(token), isFinal);
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void executeAsPlayer(String word, String rawCommand, boolean isFinal) {
        long now = System.currentTimeMillis();
        if (!isFinal && word.equals(lastTriggeredWord) && (now - lastExecutionTime) < COMMAND_COOLDOWN_MS) {
            return;
        }
        if ((now - lastExecutionTime) < COMMAND_COOLDOWN_MS) {
            return;
        }

        lastExecutionTime = now;
        lastTriggeredWord = word;

        final Minecraft client = Minecraft.getMinecraft();
        if (client == null) {
            return;
        }

        client.addScheduledTask(() -> {
            if (client.player == null) {
                return;
            }

            String cmd = rawCommand.startsWith("/") ? rawCommand : "/" + rawCommand;

            if (ClientCommandHandler.instance.executeCommand(client.player, cmd) != 0) {
                return;
            }

            client.player.sendChatMessage(cmd);
        });
    }

    @Override
    public String getPluginId() {
        return MODID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientSoundEvent.class, this::onClientSound);
        registration.registerEvent(ClientVoicechatConnectionEvent.class, this::onConnection);
    }

    private void onClientSound(ClientSoundEvent event) {
        short[] rawAudio = event.getRawAudio();
        if (rawAudio != null && rawAudio.length > 0) {
            if (!AUDIO_QUEUE.offer(rawAudio.clone())) {
                AUDIO_QUEUE.poll();
                AUDIO_QUEUE.offer(rawAudio.clone());
            }
        }
    }

    private void onConnection(ClientVoicechatConnectionEvent event) {
        if (event.isConnected()) {
            AUDIO_QUEUE.clear();
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                if (mc.player != null) {
                    mc.player.sendMessage(new TextComponentString(
                            "§a[음성커맨드] §f마이크 음성 인식이 활성화되었습니다. (인식 단어: "
                                    + allowedCommands.keySet() + ")"
                    ));
                }
            });
        }
    }
}
