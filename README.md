# MATSim-DARe

A MATSim-based pipeline that builds and simulates a multimodal public-transit
network for Greater Manchester (EPSG:27700 / British National Grid). The
project converts GTFS into a routable transit schedule, runs an agent-based
PT simulation with the Swiss Rail Raptor router, and exports the resulting
trajectories for QGIS / R visualisation. A small companion package explores
flood-induced road exposure on the same network.

## Requirements

- Java 21
- Maven (project resolves MATSim artifacts from `https://repo.matsim.org/repository/matsim/`)
- R + `sf`, `ggplot2`, `ggspatial`, `dplyr`, `patchwork`, `scales` (auto-installed by the plot script)
- Python 3 + `numpy`, `matplotlib`, `rasterio`, `geopandas` (only for `en.phm.hazard`)

## Build

```bash
mvn compile          # compile
mvn package          # produces target/matsim-dare-1.0-SNAPSHOT.jar
```

## GTFS data preparation

The transit schedule consumed by `PT2MATSimExample` is assembled from open data
by a set of R scripts in `src/main/java/en/phm/pt/`. They build a single
multimodal GTFS feed for Greater Manchester from two sources — the TfGM
tram/bus feed and the GB National Rail timetable.

Inputs:

- **TfGM tram + bus** — `TfGMgtfsnew.zip`, downloaded manually from TfGM open
  data; already scoped to Greater Manchester.
- **National Rail** — the GB-wide ATOC/CIF timetable, downloaded by the first
  script below.

Run in order — each script has a configurable block of paths/options at the top:

| Script | Purpose |
|---|---|
| `get_rail_data.R` | Download the full GB National Rail timetable (ATOC/CIF) from the National Rail Open Data Portal via `UK2GTFS::nrdp_timetable()`. Needs free NRDP credentials in `.Renviron` (`NRDP_username` / `NRDP_password`). |
| `convert_rail_gtfs.R` | Convert the ATOC/CIF timetable to GTFS (`UK2GTFS::atoc2gtfs()`), then clip it to Greater Manchester + a 50 km buffer. |
| `clean_rail_gtfs.R` | Repair the rail GTFS: drop expired services, monotonic-clamp stop times (fixes ATOC half-minute conversion artifacts), drop non-standard columns. |
| `clean_tfgm_gtfs.R` | Clean the TfGM feed: drop `block_id`, fully-expired services, and unused shapes. (Already GM-scoped, so not clipped.) |
| `validate_gtfs_feeds.R` | Validate any feed with the MobilityData Canonical GTFS Validator (`gtfstools::validate_gtfs()`); writes an HTML/JSON report and a console summary. Run after each step to check the result. |
| `merge_gtfs_feeds.R` | Merge the two cleaned feeds into one GTFS, prefixing every id per feed (`tfgm_*`, `rail_*`) so their independent id spaces do not collide. |

The merged feed (`gtfs_gm_all_modes.zip`) is the GTFS input for
`PT2MATSimExample` — place it under `pt2matsim/input/` (the path the Java class
reads).

Notes:

- R packages (`UK2GTFS`, `gtfstools`, `sf`, `data.table`, `jsonlite`) are
  auto-installed on first run; `UK2GTFS` comes from GitHub.
- `validate_gtfs_feeds.R` needs Java 11+ (the project's Java 21 is fine).
- The ATOC timetable and the merged feed are large, and `atoc2gtfs()` /
  `write_gtfs()` stage big uncompressed temp files — point R's `TMPDIR` at a
  drive with free space if the system drive is short.

## Pipeline (run in order)

```bash
mvn exec:java -Dexec.mainClass="en.phm.pt.PreparePTNetwork"
mvn exec:java -Dexec.mainClass="en.phm.pt.PT2MATSimExample"
mvn exec:java -Dexec.mainClass="en.phm.pt.SimulatePT" \
    -Dexec.args="<network.xml.gz> <schedule.xml.gz> <vehicles.xml.gz> [outputDir]"
mvn exec:java -Dexec.mainClass="en.phm.pt.ExportRoutesToGpkg"
Rscript scripts/plot_routes.R
```

## Java entry points (`en.phm.pt`)

| Class | Purpose |
|---|---|
| `PreparePTNetwork` | Merge the Metrolink tram network into the JIBE base street network. Embedded tram sections add the `tram` mode to the existing road link; dedicated tracks become new parallel `tram_<osmId>` links. Output: `pt2matsim/output/network_with_tramLinks.xml.gz`. |
| `PT2MATSimExample` | Convert GTFS (`pt2matsim/input/all-modes-adjusted-gtfs.zip`) to an unmapped schedule using the `dayWithMostTrips` strategy, then map it onto the tram-augmented network with `PublicTransitMapper`. Writes a plausibility CSV and GeoJSON to `pt2matsim/output/plausibilityResults/`. |
| `SimulatePT` | Run a MATSim simulation with the Swiss Rail Raptor router. Walk and bike are network-routed access/egress modes. Generates 10 dummy agents with home–work–home plans across representative GM coordinates. |
| `ExportRoutesToGpkg` | Post-simulation: read the simulated plans and export per-agent leg geometries plus an activities layer to a GeoPackage (default `D:/Downloads/routes.gpkg`) for QGIS. |
| `MergeNetworks` | Generic helper: merge a secondary MATSim network into a base network (union of allowed modes on shared links, additive otherwise). |
| `CheckMatsimNetworks` | Diagnostic: report nodes/links present in a base network but missing from a derived multimodal network. |
| `NetworkModeStats` | Diagnostic: read a multimodal network and print per-mode link counts. |

## Scripts

- `scripts/plot_routes.R` — Publication-quality trajectory plots from the
  GeoPackage produced by `ExportRoutesToGpkg`. Writes per-agent PNG/PDF and a
  4-column overview to `D:/Downloads/route_plots/`. Uses a CartoLight OSM
  basemap (falls back to no basemap on tile-fetch failure), colours legs by
  type, distinguishes modes by linetype, and annotates home/work activities.

## Flood-exposure package (`en.phm.hazard`)

Independent of the PT pipeline. Quantifies road-network exposure to modelled
flood depths over Greater Manchester:

- `plot_flood_extent.py` — Render a flood-depth GeoTIFF over a buildings layer.
- `flood_road_exposure.ipynb` — Notebook that classifies links by flood depth
  and produces the exposure plots / GeoPackages in this directory.
- `flood_networkChangeEvents.xml` — MATSim `NetworkChangeEvents` describing
  flood-driven link capacity / freespeed reductions.

## File layout

| Path | Contents |
|---|---|
| `pt2matsim/input/` | GTFS zip, OSM extracts, tram network XML |
| `pt2matsim/intermediate/` | Unmapped schedule, default config XMLs |
| `pt2matsim/output/` | Mapped schedule, multimodal network, plausibility results |
| `input/mito/trafficAssignment/` | External JIBE base street network (`network_base.xml`) |
| `output_pt_simulation/` | MATSim run outputs (plans, events, scoring) |
| `src/main/java/en/phm/pt/` | PT pipeline Java sources and GTFS-preparation R scripts |
| `src/main/java/en/phm/hazard/` | Flood-exposure scripts and outputs |
| `scripts/` | R post-processing |

## Key dependencies

- `org.matsim:pt2matsim:25.8` — GTFS/HAFAS/OSM → MATSim schedule conversion and PT mapper.
- `org.matsim.contrib:simwrapper:2025.0` — SimWrapper visualisation.
- `org.geotools:gt-geopkg:33.2` — GeoPackage writer (`commons-jxpath` excluded
  for GHSA-wrx5-rp7m-mm49; see `pom.xml`).
- Swiss Rail Raptor (`ch.sbb.matsim`) — pulled transitively via pt2matsim, used by `SimulatePT`.
