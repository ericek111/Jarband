package eu.lixko.jarband.app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public record AirbandConfig(
        String soapyArgs,
        double sampleRateHz,
        double centerFrequencyHz,
        Map<String, Double> gains,
        List<Double> merge25kHzFrequencies,
        List<String> skipChannels,
        Path outputDirectory,
        int opusSampleRateHz,
        int opusBitrateBps,
        int opusFrameMillis,
        int opusComplexity,
        float squelchOpenDb,
        float squelchCloseDb,
        // Do not open the recorder for single-frame spikes. A real AM voice
        // transmission has a carrier that stays up; short RF bursts are discarded.
        int squelchOpenConfirmMillis,
        // Delay writing by this much so the recorder can see the carrier drop
        // and trim the tail instead of committing post-squelch noise.
        int squelchCloseLookaheadMillis,
        int squelchPrerollMillis,
        boolean waterfall,
        boolean channelWaterfall,
        boolean vdl2Waterfall,
        boolean webEnabled,
        String webHost,
        int webPort,
        int webRecentChannelLimit,
        boolean vdl2Enabled,
        List<Integer> vdl2FrequenciesHz,
        InetSocketAddress vdl2Output) {

    public static final Path DEFAULT_PATH = Path.of("airband-recorder.toml");

    private static final AirbandConfig DEFAULTS = new AirbandConfig(
            "remote=10.0.34.40,remote:prot=tcp",
            8_000_000.0,
            133_000_005.0,
            Map.of("LNA", 1d, "Baseband", 23d, "Mixer", 0d, "Mixbuffer", 0d),
            List.of(),
            List.of(),
            Path.of("recordings"),
            16_000,
            24_000,
            20,
            5,
            18.0f,
            18.0f,
            30,
            100,
            80,
            false,
            false,
            false,
            true,
            "127.0.0.1",
            8080,
            32,
            false,
            List.of(136_675_000, 136_725_000, 136_775_000, 136_825_000,
                    136_875_000, 136_900_000, 136_975_000),
            new InetSocketAddress("127.0.0.1", 45502));

    public static AirbandConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return DEFAULTS;
        }

        TomlParseResult toml = Toml.parse(path);
        if (toml.hasErrors()) {
            throw new IllegalArgumentException("Invalid TOML in " + path + ": " + toml.errors());
        }

        int opusSampleRateHz = (int) number(toml, "opus.sample_rate_hz", DEFAULTS.opusSampleRateHz);
        if (opusSampleRateHz != 8_000 && opusSampleRateHz != 16_000) {
            throw new IllegalArgumentException("opus.sample_rate_hz must be 8000 or 16000");
        }

        String vdl2Host = string(toml, "vdl2.output_host", DEFAULTS.vdl2Output.getHostString());
        int vdl2Port = (int) number(toml, "vdl2.output_port", DEFAULTS.vdl2Output.getPort());
        return new AirbandConfig(
                string(toml, "sdr.args", DEFAULTS.soapyArgs),
                number(toml, "sdr.sample_rate_hz", DEFAULTS.sampleRateHz),
                number(toml, "sdr.center_frequency_hz", DEFAULTS.centerFrequencyHz),
                gains(toml.getTable("gains"), DEFAULTS.gains),
                frequencies(toml.getArray("channels.merge_25khz")),
                strings(toml.getArray("channels.skip")),
                Path.of(string(toml, "recording.output_directory", DEFAULTS.outputDirectory.toString())),
                opusSampleRateHz,
                (int) number(toml, "opus.bitrate_bps", DEFAULTS.opusBitrateBps),
                (int) number(toml, "opus.frame_millis", DEFAULTS.opusFrameMillis),
                (int) number(toml, "opus.complexity", DEFAULTS.opusComplexity),
                (float) number(toml, "squelch.open_db", DEFAULTS.squelchOpenDb),
                (float) number(toml, "squelch.close_db", DEFAULTS.squelchCloseDb),
                (int) number(toml, "squelch.open_confirm_millis", DEFAULTS.squelchOpenConfirmMillis),
                (int) number(toml, "squelch.close_lookahead_millis", DEFAULTS.squelchCloseLookaheadMillis),
                (int) number(toml, "squelch.preroll_millis", DEFAULTS.squelchPrerollMillis),
                bool(toml, "debug.waterfall", DEFAULTS.waterfall),
                bool(toml, "debug.channel_waterfall", DEFAULTS.channelWaterfall),
                bool(toml, "debug.vdl2_waterfall", DEFAULTS.vdl2Waterfall),
                bool(toml, "web.enabled", DEFAULTS.webEnabled),
                string(toml, "web.host", DEFAULTS.webHost),
                (int) number(toml, "web.port", DEFAULTS.webPort),
                (int) number(toml, "web.recent_channel_limit", DEFAULTS.webRecentChannelLimit),
                bool(toml, "vdl2.enabled", DEFAULTS.vdl2Enabled),
                intFrequencies(toml.getArray("vdl2.frequencies_hz"), DEFAULTS.vdl2FrequenciesHz),
                new InetSocketAddress(vdl2Host, vdl2Port));
    }

    private static String string(TomlParseResult toml, String key, String fallback) {
        String value = toml.getString(key);
        return value != null ? value : fallback;
    }

    private static double number(TomlParseResult toml, String key, double fallback) {
        Double value = numberValue(toml.get(key));
        return value != null ? value : fallback;
    }

    private static boolean bool(TomlParseResult toml, String key, boolean fallback) {
        Boolean value = toml.getBoolean(key);
        return value != null ? value : fallback;
    }

    private static Map<String, Double> gains(TomlTable table, Map<String, Double> fallback) {
        if (table == null) {
            return fallback;
        }
        var gains = new LinkedHashMap<String, Double>();
        for (String key : table.keySet()) {
            Double value = tableNumber(table, key);
            if (value == null) {
                throw new IllegalArgumentException("[gains]." + key + " must be a number");
            }
            gains.put(key, value);
        }
        return Map.copyOf(gains);
    }

    private static List<Double> frequencies(TomlArray array) {
        if (array == null) {
            return List.of();
        }
        var frequencies = new ArrayList<Double>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Double value = arrayNumber(array, i);
            if (value == null) {
                throw new IllegalArgumentException("Frequency array entries must be numbers");
            }
            frequencies.add(value);
        }
        return List.copyOf(frequencies);
    }

    private static List<Integer> intFrequencies(TomlArray array, List<Integer> fallback) {
        if (array == null) {
            return fallback;
        }
        var frequencies = new ArrayList<Integer>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Double value = arrayNumber(array, i);
            if (value == null) {
                throw new IllegalArgumentException("Frequency array entries must be numbers");
            }
            frequencies.add(Math.toIntExact(Math.round(value)));
        }
        return List.copyOf(frequencies);
    }

    private static List<String> strings(TomlArray array) {
        if (array == null) {
            return List.of();
        }
        var values = new ArrayList<String>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (!(value instanceof String string)) {
                throw new IllegalArgumentException("String array entries must be strings");
            }
            values.add(string);
        }
        return List.copyOf(values);
    }

    private static Double tableNumber(TomlTable table, String key) {
        return numberValue(table.get(key));
    }

    private static Double arrayNumber(TomlArray array, int index) {
        return numberValue(array.get(index));
    }

    private static Double numberValue(Object value) {
        return switch (value) {
            case null -> null;
            case Double d -> d;
            case Long l -> l.doubleValue();
            case Integer i -> i.doubleValue();
            case Number n -> n.doubleValue();
            default -> null;
        };
    }
}
