# ============================================================================
# Publication-quality trajectory plots for the MATSim PT simulation output.
#
# Reads D:/Downloads/routes.gpkg (produced by en.phm.pt.ExportRoutesToGpkg)
# and produces:
#   - one PNG + PDF per agent
#   - one combined faceted PNG showing all agents on a single page
#
# Layer expectations (CRS = EPSG:27700, British National Grid):
#   - <agent_id>   : LineString features, one per leg, attributes include
#                    type ("access" | "egress" | "in_vehicle" | "transfer"),
#                    mode ("walk" | "bike" | "non_network_walk" | "pt"),
#                    dep_time, trav_time_s, distance_m
#   - activities   : Point features for all activities (real + interaction),
#                    attributes person_id, type, is_real, end_time
# ============================================================================

# ---- Dependencies ----------------------------------------------------------
required_pkgs <- c("sf", "ggplot2", "ggspatial", "dplyr", "patchwork", "scales")
to_install <- setdiff(required_pkgs, rownames(installed.packages()))
if (length(to_install) > 0) {
  install.packages(to_install, repos = "https://cloud.r-project.org")
}
suppressPackageStartupMessages({
  library(sf)
  library(ggplot2)
  library(ggspatial)
  library(dplyr)
  library(patchwork)
  library(scales)
})

# ---- Configuration ---------------------------------------------------------
GPKG       <- "D:/Downloads/routes.gpkg"
OUT_DIR    <- "D:/Downloads/route_plots"
USE_BASEMAP <- TRUE     # set FALSE if no internet or to skip OSM tiles
BUFFER_M   <- 500       # padding around each agent's bbox, in metres
DPI        <- 300

dir.create(OUT_DIR, recursive = TRUE, showWarnings = FALSE)

# ---- Read all layers -------------------------------------------------------
cat("Reading", GPKG, "...\n")
layer_names  <- st_layers(GPKG)$name
agent_layers <- setdiff(layer_names, "activities")

stopifnot(length(agent_layers) > 0, "activities" %in% layer_names)

read_layer <- function(ln) {
  x <- st_read(GPKG, layer = ln, quiet = TRUE)
  x$layer_name <- ln
  x
}

legs <- bind_rows(lapply(agent_layers, read_layer))
acts <- st_read(GPKG, layer = "activities", quiet = TRUE)

# GeoPackage stores Boolean as INTEGER (SQLite has no native bool), so coerce
# back to logical for the dplyr filter calls below.
if (!is.logical(acts$is_real)) acts$is_real <- as.logical(acts$is_real)

cat(sprintf("  %d leg features across %d agents, %d activity points\n",
            nrow(legs), length(agent_layers), nrow(acts)))

# ---- Aesthetic mapping -----------------------------------------------------
# Distinct colours by leg type. ColorBrewer Dark2 selection — print-safe.
type_colours <- c(
  access     = "#1b9e77",  # teal
  egress     = "#d95f02",  # orange
  in_vehicle = "#7570b3",  # purple
  transfer   = "#e7298a"   # pink
)

# Line style by mode. Network-routed walk/bike are solid; teleport hops are
# dotted so they read as approximations rather than measured paths.
mode_linetypes <- c(
  walk             = "solid",
  bike             = "longdash",
  pt               = "solid",
  non_network_walk = "dotted"
)

# Extra stroke width for the in-vehicle PT line so it stands out.
mode_widths <- c(
  walk             = 0.8,
  bike             = 0.8,
  pt               = 1.4,
  non_network_walk = 0.5
)

# ---- Helper: build the plot for one agent ----------------------------------
plot_agent <- function(agent_id, legs_a, acts_a, with_basemap = TRUE,
                       title_prefix = "Trajectory") {

  # Bbox derived from leg geometries plus a buffer; activities are usually
  # inside the legs anyway, but we include them defensively.
  bbox_geom <- st_union(c(st_geometry(legs_a), st_geometry(acts_a)))
  bb <- st_bbox(st_buffer(bbox_geom, BUFFER_M))

  real_acts <- acts_a %>% filter(is_real)
  pt_acts   <- acts_a %>% filter(type == "pt interaction")

  # Nice readable activity labels: "Home (07:38)", "Work", etc.
  real_acts <- real_acts %>%
    mutate(label = ifelse(is.na(end_time) | end_time == "",
                          tools::toTitleCase(type),
                          sprintf("%s\n(end %s)", tools::toTitleCase(type),
                                  substr(end_time, 1, 5))))

  p <- ggplot()

  if (with_basemap) {
    # Cartolight is a clean low-contrast OSM-derived basemap, perfect for
    # publication. Requires internet on first run; tiles are then cached.
    p <- p + annotation_map_tile(type = "cartolight", zoom = 13, alpha = 0.7,
                                 progress = "none")
  }

  p <- p +
    # Legs, ordered so that in_vehicle is drawn first and walk over the top.
    geom_sf(data = legs_a %>% arrange(type != "in_vehicle", trip_idx, leg_idx),
            aes(colour = type, linetype = mode, linewidth = mode),
            lineend = "round") +
    # PT stop interactions: small grey dots
    geom_sf(data = pt_acts, shape = 21, fill = "white", colour = "grey30",
            size = 1.8, stroke = 0.4) +
    # Real activities: large diamond markers + labels
    geom_sf(data = real_acts, shape = 23, fill = "#fdc086", colour = "black",
            size = 3.5, stroke = 0.6) +
    geom_sf_text(data = real_acts, aes(label = label),
                 nudge_x = 80, nudge_y = 80,
                 hjust = 0, vjust = 0, size = 3.2,
                 fontface = "bold",
                 colour = "grey10") +
    coord_sf(xlim = c(bb["xmin"], bb["xmax"]),
             ylim = c(bb["ymin"], bb["ymax"]),
             crs  = 27700, expand = FALSE) +
    scale_colour_manual(values = type_colours, name = "Leg type", drop = FALSE) +
    scale_linetype_manual(values = mode_linetypes, name = "Mode", drop = FALSE) +
    scale_linewidth_manual(values = mode_widths, guide = "none") +
    annotation_scale(location = "br", width_hint = 0.25,
                     style = "ticks", line_col = "grey20") +
    annotation_north_arrow(location = "tr",
                           style = north_arrow_minimal(line_col = "grey20"),
                           height = unit(0.8, "cm"),
                           width  = unit(0.8, "cm")) +
    labs(title    = sprintf("%s: %s", title_prefix, agent_id),
         subtitle = sprintf("%d legs across %d trip(s) — Greater Manchester (EPSG:27700)",
                            nrow(legs_a), max(legs_a$trip_idx) + 1L),
         x = NULL, y = NULL) +
    theme_minimal(base_size = 11, base_family = "sans") +
    theme(
      plot.title       = element_text(face = "bold", size = 13),
      plot.subtitle    = element_text(colour = "grey30", size = 10),
      panel.grid.major = element_line(colour = "grey92", linewidth = 0.3),
      panel.grid.minor = element_blank(),
      panel.border     = element_rect(colour = "grey60", fill = NA, linewidth = 0.4),
      axis.text        = element_text(size = 8, colour = "grey40"),
      legend.position  = "bottom",
      legend.box       = "horizontal",
      legend.title     = element_text(face = "bold", size = 9),
      legend.text      = element_text(size = 9),
      legend.key.width = unit(1.4, "cm")
    )
  p
}

# ---- Per-agent plots -------------------------------------------------------
cat("Building per-agent plots into", OUT_DIR, "...\n")
for (agent in agent_layers) {
  legs_a <- legs %>% filter(layer_name == agent)
  acts_a <- acts %>% filter(person_id == agent)
  if (nrow(legs_a) == 0) next

  p <- tryCatch(
    plot_agent(agent, legs_a, acts_a, with_basemap = USE_BASEMAP),
    error = function(e) {
      cat("  basemap failed for", agent, "-", conditionMessage(e),
          "- retrying without basemap\n")
      plot_agent(agent, legs_a, acts_a, with_basemap = FALSE)
    }
  )

  png_path <- file.path(OUT_DIR, paste0(agent, ".png"))
  pdf_path <- file.path(OUT_DIR, paste0(agent, ".pdf"))
  ggsave(png_path, p, width = 9, height = 8, dpi = DPI)
  ggsave(pdf_path, p, width = 9, height = 8, device = cairo_pdf)
  cat("  ", agent, "->", png_path, "\n")
}

# ---- Combined overview (patchwork of free-bbox panels) --------------------
# facet_wrap can't use scales = "free" with coord_sf, so we assemble individual
# per-agent panels via patchwork. Each panel keeps its own bbox.
cat("Building combined overview ...\n")

mini_panel <- function(agent_id, legs_a, acts_a) {
  bb <- st_bbox(st_buffer(
    st_union(c(st_geometry(legs_a), st_geometry(acts_a))), BUFFER_M))
  ggplot() +
    geom_sf(data = legs_a %>% arrange(type != "in_vehicle", trip_idx, leg_idx),
            aes(colour = type, linetype = mode, linewidth = mode),
            lineend = "round") +
    geom_sf(data = acts_a %>% filter(is_real),
            shape = 23, fill = "#fdc086", colour = "black",
            size = 2.2, stroke = 0.4) +
    coord_sf(xlim = c(bb["xmin"], bb["xmax"]),
             ylim = c(bb["ymin"], bb["ymax"]),
             crs  = 27700, expand = FALSE) +
    scale_colour_manual(values = type_colours, name = "Leg type", drop = FALSE) +
    scale_linetype_manual(values = mode_linetypes, name = "Mode", drop = FALSE) +
    scale_linewidth_manual(values = mode_widths, guide = "none") +
    labs(title = agent_id, x = NULL, y = NULL) +
    theme_minimal(base_size = 9) +
    theme(
      plot.title       = element_text(face = "bold", size = 10, hjust = 0.5),
      panel.grid       = element_blank(),
      panel.border     = element_rect(colour = "grey70", fill = NA, linewidth = 0.3),
      axis.text        = element_blank(),
      axis.ticks       = element_blank()
    )
}

panels <- lapply(agent_layers, function(agent) {
  legs_a <- legs %>% filter(layer_name == agent)
  acts_a <- acts %>% filter(person_id == agent)
  if (nrow(legs_a) == 0) return(NULL)
  mini_panel(agent, legs_a, acts_a)
})
panels <- Filter(Negate(is.null), panels)

overview <- wrap_plots(panels, ncol = 4) +
  plot_annotation(
    title    = "All agents — selected plan trajectories",
    subtitle = "Greater Manchester PT diagnostic simulation",
    theme    = theme(
      plot.title    = element_text(face = "bold", size = 14),
      plot.subtitle = element_text(colour = "grey30", size = 10)
    )
  ) +
  plot_layout(guides = "collect") &
  theme(legend.position = "bottom")

ggsave(file.path(OUT_DIR, "_all_agents.png"), overview,
       width = 14, height = 11, dpi = DPI)
ggsave(file.path(OUT_DIR, "_all_agents.pdf"), overview,
       width = 14, height = 11, device = cairo_pdf)

cat("Done. Outputs in", OUT_DIR, "\n")
