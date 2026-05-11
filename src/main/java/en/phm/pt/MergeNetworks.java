package en.phm.pt;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * Merges a secondary MATSim network into a base network.
 * <p>
 * Merge strategy:
 * <ul>
 *   <li>Missing nodes are added with their attributes</li>
 *   <li>Missing links are added with all properties from the secondary network</li>
 *   <li>Existing links get their allowed transport modes merged (union)</li>
 *   <li>Other link attributes (length, capacity, freespeed, lanes) are kept from the base network</li>
 * </ul>
 * <p>
 * Usage: java MergeNetworks &lt;baseNetwork.xml&gt; &lt;secondaryNetwork.xml&gt; &lt;outputMergedNetwork.xml&gt;
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

        // Read both networks
        Config config = ConfigUtils.createConfig();
        Network baseNetwork = new ScenarioUtils.ScenarioBuilder(config).build().getNetwork();
        new MatsimNetworkReader(baseNetwork).readFile(baseFile);

        Network secondaryNetwork = NetworkUtils.createNetwork();
        new MatsimNetworkReader(secondaryNetwork).readFile(secondaryFile);

        // Merge nodes from secondary into base
        for (Node secondaryNode : secondaryNetwork.getNodes().values()) {
            if (baseNetwork.getNodes().get(secondaryNode.getId()) == null) {
                Node newNode = baseNetwork.getFactory().createNode(secondaryNode.getId(), secondaryNode.getCoord());
                baseNetwork.addNode(newNode);
                AttributesUtils.copyAttributesFromTo(secondaryNode, newNode);
            }
        }

        // Merge links from secondary into base
        for (Link secondaryLink : secondaryNetwork.getLinks().values()) {
            Link existingLink = baseNetwork.getLinks().get(secondaryLink.getId());

            if (existingLink == null) {
                // New link: add with all secondary network properties
                Node fromNode = baseNetwork.getNodes().get(secondaryLink.getFromNode().getId());
                Node toNode = baseNetwork.getNodes().get(secondaryLink.getToNode().getId());

                if (fromNode == null || toNode == null) {
                    throw new RuntimeException("Node missing for link " + secondaryLink.getId());
                }

                Link newLink = baseNetwork.getFactory().createLink(secondaryLink.getId(), fromNode, toNode);
                newLink.setLength(secondaryLink.getLength());
                newLink.setFreespeed(secondaryLink.getFreespeed());
                newLink.setCapacity(secondaryLink.getCapacity());
                newLink.setNumberOfLanes(secondaryLink.getNumberOfLanes());
                newLink.setAllowedModes(secondaryLink.getAllowedModes());
                AttributesUtils.copyAttributesFromTo(secondaryLink, newLink);
                baseNetwork.addLink(newLink);
            } else {
                // Existing link: merge allowed modes (union)
                Set<String> mergedModes = new HashSet<>(existingLink.getAllowedModes());
                mergedModes.addAll(secondaryLink.getAllowedModes());
                existingLink.setAllowedModes(Set.copyOf(mergedModes));
            }
        }

        // Write merged network
        new NetworkWriter(baseNetwork).write(outputFile);

        System.out.println("Merge complete. Output: " + outputFile);
        System.out.println("Final network: " + baseNetwork.getNodes().size() + " nodes, " +
                baseNetwork.getLinks().size() + " links.");
    }
}
