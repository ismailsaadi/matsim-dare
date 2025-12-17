package org.example.pt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * A standalone Java program that merges a secondary MATSim network into a base network:
 * - Adds missing nodes (with their attributes)
 * - Adds missing links (with all attributes, including allowed modes)
 * - For existing links: combines (unions) the allowed transport modes from both networks
 * - Other link attributes (length, capacity, freespeed, lanes) remain as in the base network
 *
 * Usage: java MergeNetworks baseNetwork.xml.gz secondaryNetwork.xml.gz mergedNetwork.xml.gz
 */
public class MergeNetworks {

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: MergeNetworks <baseNetwork.xml> <secondaryNetwork.xml> <outputMergedNetwork.xml>");
            System.exit(1);
        }

        String baseFile = args[0];
        String secondaryFile = args[1];
        String outputFile = args[2];

        Config config = ConfigUtils.createConfig();
        ScenarioUtils.ScenarioBuilder scenarioBuilder = new ScenarioUtils.ScenarioBuilder(config);

        // Read base network
        Network baseNetwork = scenarioBuilder.build().getNetwork();
        new MatsimNetworkReader(baseNetwork).readFile(baseFile);

        // Read secondary network
        Network secondaryNetwork = NetworkUtils.createNetwork();
        new MatsimNetworkReader(secondaryNetwork).readFile(secondaryFile);

        // Merge nodes from secondary into base
        for (Node secondaryNode : secondaryNetwork.getNodes().values()) {
            Node existingNode = baseNetwork.getNodes().get(secondaryNode.getId());
            if (existingNode == null) {
                // Add missing node
                Node newNode = baseNetwork.getFactory().createNode(secondaryNode.getId(), secondaryNode.getCoord());
                baseNetwork.addNode(newNode);
                // Copy node attributes
                AttributesUtils.copyAttributesFromTo(secondaryNode, newNode);
            }
            // If node already exists, attributes are kept as in base (no merge attempted)
        }

        // Merge links from secondary into base
        for (Link secondaryLink : secondaryNetwork.getLinks().values()) {
            Link existingLink = baseNetwork.getLinks().get(secondaryLink.getId());

            if (existingLink == null) {
                // Link does not exist → create and add it using secondary properties
                Node fromNode = baseNetwork.getNodes().get(secondaryLink.getFromNode().getId());
                Node toNode = baseNetwork.getNodes().get(secondaryLink.getToNode().getId());

                // Nodes must exist at this point (added in previous step if missing)
                if (fromNode == null || toNode == null) {
                    throw new RuntimeException("Node missing for link " + secondaryLink.getId());
                }

                Link newLink = baseNetwork.getFactory().createLink(
                        secondaryLink.getId(),
                        fromNode,
                        toNode
                );
                newLink.setLength(secondaryLink.getLength());
                newLink.setFreespeed(secondaryLink.getFreespeed());
                newLink.setCapacity(secondaryLink.getCapacity());
                newLink.setNumberOfLanes(secondaryLink.getNumberOfLanes());
                newLink.setAllowedModes(secondaryLink.getAllowedModes());

                baseNetwork.addLink(newLink);

                // Copy all other link attributes (type, origid, etc.)
                AttributesUtils.copyAttributesFromTo(secondaryLink, newLink);
            } else {
                // Link exists → combine allowed modes (union)
                Set<String> baseModes = new HashSet<>(existingLink.getAllowedModes());
                Set<String> secondaryModes = secondaryLink.getAllowedModes();

                // If secondary has no explicit modes, it defaults to {car} in many cases, but we merge anyway
                baseModes.addAll(secondaryModes);

                // Convert back to immutable set expected by MATSim
                existingLink.setAllowedModes(Set.copyOf(baseModes));

                // Note: other attributes (length, capacity, etc.) remain as in base network
            }
        }

        // Write merged network
        new NetworkWriter(baseNetwork).write(outputFile);

        System.out.println("Merge complete. Merged network written to: " + outputFile);
        System.out.println("Final network has " + baseNetwork.getNodes().size() + " nodes and " +
                baseNetwork.getLinks().size() + " links.");
    }

    // Helper to convert Set<String> to immutable set (MATSim uses immutable sets internally)
    private static Set<String> toImmutableSet(Set<String> modes) {
        return Set.copyOf(modes);
    }
}
