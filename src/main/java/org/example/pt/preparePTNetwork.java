package org.example.pt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.util.HashSet;
import java.util.Set;

public class preparePTNetwork {

    public static void main(String[] args) {
        Network baseNetwork = NetworkUtils.readNetwork("C:\\Users\\saadi\\Documents\\Cambridge\\manchester\\input\\mito\\trafficAssignment\\network.xml.gz");
        Network tramNetwork = NetworkUtils.readNetwork("C:\\Users\\saadi\\Documents\\Cambridge\\dare\\pt\\greater_manchester_metrolink_matsim_network.xml.gz");  // <-- the NEW file with osmID

        mergeTramNetworkIntoBase(baseNetwork, tramNetwork);

        NetworkUtils.writeNetwork(baseNetwork, "C:\\Users\\saadi\\Documents\\Cambridge\\dare\\pt\\multimodal_network.xml.gz");

        System.out.println("✅ Tram network merged successfully!");
    }

    /**
     * Merges tram network into base:
     * - If a tram link has the same osmID as a link already in the base network → treat as embedded/street-running:
     *   → add "tram" to allowed modes of the existing link
     *   → copy tram-specific attributes (colour, route_ref, operator...) if missing
     * - Otherwise → add as dedicated parallel tram link with id "tram_<osmID>"
     * This gives you a proper multimodal network: shared sections use the same link (shared capacity), dedicated sections have separate links.
     */
    public static void mergeTramNetworkIntoBase(Network baseNetwork, Network tramNetwork) {

        int addedSeparate = 0;
        int mergedEmbedded = 0;

        // 1. Add missing nodes (same OSM node id → automatic merge if base network also uses OSM node ids)
        for (Node node : tramNetwork.getNodes().values()) {
            if (baseNetwork.getNodes().get(node.getId()) == null) {
                baseNetwork.addNode(node);
            }
        }

        // 2. Process tram links
        for (Link tramLink : tramNetwork.getLinks().values()) {

            // Get tram's osmID (Long)
            Object tramOsmObj = tramLink.getAttributes().getAttribute("osmID");
            if (tramOsmObj == null) {
                // fallback – very unlikely now
                addDedicatedTramLink(baseNetwork, tramLink, "tram_fallback_" + tramLink.getId());
                addedSeparate++;
                continue;
            }

            long tramOsmId = (tramOsmObj instanceof Number)
                    ? ((Number) tramOsmObj).longValue()
                    : Long.parseLong(tramOsmObj.toString());

            // Find existing link in base with same osmID
            Link existingLink = baseNetwork.getLinks().values().stream()
                    .filter(l -> {
                        Object o = l.getAttributes().getAttribute("osmID");
                        if (o == null) return false;
                        long baseId = (o instanceof Number) ? ((Number) o).longValue() : Long.parseLong(o.toString());
                        return baseId == tramOsmId;
                    })
                    .findAny()
                    .orElse(null);

            if (existingLink != null) {
                // === EMBEDDED / STREET-RUNNING: add "tram" mode + copy tram attributes ===
                Set<String> modes = new HashSet<>(existingLink.getAllowedModes());
                boolean modeAdded = modes.add("tram");
                existingLink.setAllowedModes(modes);

                // Copy tram-specific attributes if missing (colour for visualisation, operator, route_ref...)
                tramLink.getAttributes().getAsMap().forEach((key, value) -> {
                    if (!key.equals("osmID") && existingLink.getAttributes().getAttribute(key.toString()) == null) {
                        existingLink.getAttributes().putAttribute(key.toString(), value);
                    }
                });

                if (modeAdded) mergedEmbedded++;
            } else {
                // === DEDICATED TRACK: add separate tram link ===
                Node fromNode = baseNetwork.getNodes().get(tramLink.getFromNode().getId());
                Node toNode   = baseNetwork.getNodes().get(tramLink.getToNode().getId());

                Id<Link> newLinkId = Id.createLinkId("tram_" + tramOsmId);

                // Safety: if id already exists (extremely unlikely), append _2, _3...
                int suffix = 2;
                while (baseNetwork.getLinks().containsKey(newLinkId)) {
                    newLinkId = Id.createLinkId("tram_" + tramOsmId + "_" + suffix);
                    suffix++;
                }

                Link newLink = baseNetwork.getFactory().createLink(newLinkId, fromNode, toNode);
                newLink.setLength(tramLink.getLength());
                newLink.setFreespeed(tramLink.getFreespeed());
                newLink.setCapacity(tramLink.getCapacity());
                newLink.setNumberOfLanes(tramLink.getNumberOfLanes());
                newLink.setAllowedModes(tramLink.getAllowedModes());

                // Copy all attributes including osmID
                tramLink.getAttributes().getAsMap().forEach((k, v) -> newLink.getAttributes().putAttribute(k.toString(), v));

                baseNetwork.addLink(newLink);
                addedSeparate++;
            }
        }

        System.out.println("   Embedded/street-running sections (added 'tram' mode): " + mergedEmbedded);
        System.out.println("   Dedicated sections (added separate link):         " + addedSeparate);
        System.out.println("   Total tram links processed:                       " + (addedSeparate + mergedEmbedded));
    }

    // Helper for fallback (if no osmID)
    private static void addDedicatedTramLink(Network baseNetwork, Link tramLink, String idPrefix) {
        Node fromNode = baseNetwork.getNodes().get(tramLink.getFromNode().getId());
        Node toNode   = baseNetwork.getNodes().get(tramLink.getToNode().getId());
        Id<Link> newLinkId = Id.createLinkId(idPrefix + tramLink.getId());
        Link newLink = baseNetwork.getFactory().createLink(newLinkId, fromNode, toNode);
        newLink.setLength(tramLink.getLength());
        newLink.setFreespeed(tramLink.getFreespeed());
        newLink.setCapacity(tramLink.getCapacity());
        newLink.setNumberOfLanes(tramLink.getNumberOfLanes());
        newLink.setAllowedModes(tramLink.getAllowedModes());
        tramLink.getAttributes().getAsMap().forEach((k, v) -> newLink.getAttributes().putAttribute(k.toString(), v));
        baseNetwork.addLink(newLink);
    }
}