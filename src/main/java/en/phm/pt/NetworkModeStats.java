package en.phm.pt;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

import java.util.*;

/**
 * Reads a MATSim multimodal network and prints per-mode link counts.
 *
 * Usage: java NetworkModeStats [networkFile]
 * Default input: manchester_network_with_metrolink_tram_merged.xml.gz
 */
public class NetworkModeStats {

    private static final String DEFAULT_NETWORK =
            //"C:\\Users\\saadi\\Documents\\Cambridge\\manchester\\input\\mito\\pt\\MappedNetwork.xml.gz";
            "D:/Downloads/multimodal_network.xml.gz";

    public static void main(String[] args) {
        String networkFile = args.length > 0 ? args[0] : DEFAULT_NETWORK;

        System.out.println("Reading network: " + networkFile);
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(networkFile);

        int totalLinks = network.getLinks().size();
        int totalNodes = network.getNodes().size();

        // Count links per mode and track links with multiple modes
        Map<String, Integer> linksByMode = new TreeMap<>();
        Map<String, Integer> linksByModeCombo = new TreeMap<>();

        for (Link link : network.getLinks().values()) {
            Set<String> modes = link.getAllowedModes();

            // Per-mode counts (a link with walk+car increments both)
            for (String mode : modes) {
                linksByMode.merge(mode, 1, Integer::sum);
            }

            // Mode combination counts (exact set per link)
            String combo = new TreeSet<>(modes).toString();
            linksByModeCombo.merge(combo, 1, Integer::sum);
        }

        // --- Summary ---
        System.out.println("\n=== Network Summary ===");
        System.out.printf("  Nodes : %,d%n", totalNodes);
        System.out.printf("  Links : %,d%n", totalLinks);

        System.out.println("\n=== Links by Allowed Mode ===");
        System.out.printf("  %-20s  %s%n", "Mode", "Link count");
        System.out.println("  " + "-".repeat(35));
        linksByMode.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-20s  %,d%n", e.getKey(), e.getValue()));

        System.out.println("\n=== Links by Mode Combination (top 20) ===");
        System.out.printf("  %-50s  %s%n", "Mode set", "Link count");
        System.out.println("  " + "-".repeat(65));
        linksByModeCombo.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(20)
                .forEach(e -> System.out.printf("  %-50s  %,d%n", e.getKey(), e.getValue()));
    }
}
