package org.example.pt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

import java.util.Set;
import java.util.TreeSet;

public class CheckMatsimNetworks {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java CheckMatsimNetworks base_network.xml multimodal_network.xml");
            System.exit(1);
        }

        String baseFile = args[0];
        String multiFile = args[1];

        Network baseNetwork = NetworkUtils.createNetwork();
        new MatsimNetworkReader(baseNetwork).readFile(baseFile);

        Network multiNetwork = NetworkUtils.createNetwork();
        new MatsimNetworkReader(multiNetwork).readFile(multiFile);

        Set<String> missingNodeIds = new TreeSet<>();
        for (Node node : baseNetwork.getNodes().values()) {
            if (multiNetwork.getNodes().get(node.getId()) == null) {
                missingNodeIds.add(node.getId().toString());
            }
        }

        Set<String> missingLinkIds = new TreeSet<>();
        for (Link link : baseNetwork.getLinks().values()) {
            if (multiNetwork.getLinks().get(link.getId()) == null) {
                missingLinkIds.add(link.getId().toString());
            }
        }

        System.out.println("Number of missing nodes: " + missingNodeIds.size());
        System.out.println("Number of missing links: " + missingLinkIds.size());


        /*
        if (missingNodeIds.isEmpty() && missingLinkIds.isEmpty()) {
            System.out.println("All nodes and links from the base network are present in the multimodal network.");
        } else {
            if (!missingNodeIds.isEmpty()) {
                System.out.println("Missing nodes in multimodal network:");
                for (String nodeId : missingNodeIds) {
                    System.out.println(nodeId);
                }
            }

            if (!missingLinkIds.isEmpty()) {
                System.out.println("Missing links in multimodal network:");
                for (String linkId : missingLinkIds) {
                    System.out.println(linkId);
                }
            }
        }

         */
    }
}
