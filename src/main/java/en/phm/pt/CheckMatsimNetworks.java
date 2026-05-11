package en.phm.pt;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two MATSim networks and reports elements present in the base
 * network but missing from the multimodal network.
 * <p>
 * Useful for validating that network merging preserved all original nodes and links.
 * <p>
 * Usage: java CheckMatsimNetworks &lt;baseNetwork.xml&gt; &lt;multimodalNetwork.xml&gt;
 */
public class CheckMatsimNetworks {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java CheckMatsimNetworks <baseNetwork.xml> <multimodalNetwork.xml>");
            System.exit(1);
        }

        Network baseNetwork = NetworkUtils.createNetwork();
        new MatsimNetworkReader(baseNetwork).readFile(args[0]);

        Network multiNetwork = NetworkUtils.createNetwork();
        new MatsimNetworkReader(multiNetwork).readFile(args[1]);

        // Find nodes in base but not in multimodal
        Set<String> missingNodeIds = new TreeSet<>();
        for (Node node : baseNetwork.getNodes().values()) {
            if (multiNetwork.getNodes().get(node.getId()) == null) {
                missingNodeIds.add(node.getId().toString());
            }
        }

        // Find links in base but not in multimodal
        Set<String> missingLinkIds = new TreeSet<>();
        for (Link link : baseNetwork.getLinks().values()) {
            if (multiNetwork.getLinks().get(link.getId()) == null) {
                missingLinkIds.add(link.getId().toString());
            }
        }

        System.out.println("Missing nodes: " + missingNodeIds.size());
        System.out.println("Missing links: " + missingLinkIds.size());

        if (missingNodeIds.isEmpty() && missingLinkIds.isEmpty()) {
            System.out.println("All base network elements are present in the multimodal network.");
        }
    }
}
