# clean_tfgm_gtfs.R
# -----------------------------------------------------------------------------
# Clean the TfGM tram + bus GTFS feed (TfGMgtfsnew.zip) ahead of merging it
# with the rail feed for the matsim-dare PT pipeline.
#
# Driven by the MobilityData validator report on the raw feed, this script:
#   1. clips the feed to Greater Manchester + a 50 km buffer (filter_by_sf);
#   2. drops services whose calendar has fully expired, and their trips
#      -> clears the `expired_calendar` warnings;
#   3. drops the `block_id` column from trips.txt
#      -> clears all 457 `block_trips_with_overlapping_stop_times` ERRORS
#         (MATSim / pt2matsim do not model vehicle blocks -- a transit vehicle
#          is created per departure -- so block_id is unused downstream);
#   4. prunes shapes not referenced by any remaining trip
#      -> clears the `unused_shape` warnings;
#   5. moves over-long route_short_name values into route_long_name
#      -> clears `route_short_name_too_long`;
#   6. drops the non-standard `agency_noc` column -> clears `unknown_column`.
#
# Cosmetic warnings left untouched on purpose (harmless for routing, and the
# fringe ones survive a generous 50 km buffer): fast_travel_between_
# consecutive_stops, stop_too_far_from_shape, trip_distance_exceeds_shape_
# distance, duplicate_route_name, missing_feed_contact_email_and_url.
#
# Re-validate the output afterwards by adding it to FEEDS in
# validate_gtfs_feeds.R.
#
# DISK SPACE: write_gtfs() stages the whole feed as uncompressed .txt files in
# R's session temp directory before zipping. This feed is large, so if the
# system drive (C:) is short on space, point R's temp dir at a roomier drive
# BEFORE starting R -- e.g. add `TMPDIR=D:/Rtemp` to .Renviron, or set the
# TMPDIR environment variable -- then restart R. Check with tempdir().
#
# Run from the repository root:
#   Rscript src/main/java/en/phm/pt/clean_tfgm_gtfs.R
# -----------------------------------------------------------------------------

## ---- configuration ----------------------------------------------------------
INPUT_ZIP  <- "D:/Downloads/TfGMgtfsnew.zip"
OUTPUT_ZIP <- "D:/Downloads/TfGMgtfsnew_clean.zip"

# Clip extent. By default Greater Manchester's bounding box is used; with a
# 50 km buffer the bbox-vs-exact-polygon difference is immaterial. To clip
# against a precise boundary instead, set BOUNDARY_FILE to any sf-readable
# file (.gpkg / .shp / .geojson) covering Greater Manchester.
BOUNDARY_FILE <- NULL
GM_BBOX_WGS84 <- c(xmin = -2.75, ymin = 53.30, xmax = -1.90, ymax = 53.70)
BUFFER_M      <- 50000          # 50 km buffer, applied in metres (EPSG:27700)

# Any route_short_name longer than this is treated as descriptive text and
# moved to route_long_name (matches the validator's threshold).
MAX_SHORT_NAME <- 12

# Non-standard columns to drop, as table = column.
DROP_COLUMNS <- list(agency = "agency_noc")
## ----------------------------------------------------------------------------


# ---- dependencies -----------------------------------------------------------
CRAN <- "https://cloud.r-project.org"
for (pkg in c("gtfstools", "sf", "data.table")) {
  if (!requireNamespace(pkg, quietly = TRUE)) {
    message("Installing ", pkg, " ...")
    install.packages(pkg, repos = CRAN)
  }
}
library(gtfstools)


# ---- helpers ----------------------------------------------------------------
# GTFS dates are YYYYMMDD; gtfstools may return them as Date, integer or
# character -- normalise to Date.
parse_gtfs_date <- function(x) {
  if (inherits(x, "Date")) return(x)
  as.Date(as.character(x), format = "%Y%m%d")
}

feed_counts <- function(g) {
  c(routes   = nrow(g$routes),
    trips    = nrow(g$trips),
    stops    = nrow(g$stops),
    shapes   = if (is.null(g$shapes))   0L else length(unique(g$shapes$shape_id)),
    services = if (is.null(g$calendar)) 0L else nrow(g$calendar))
}


# ---- 0. read ----------------------------------------------------------------
if (!file.exists(INPUT_ZIP)) stop("Input feed not found: ", INPUT_ZIP, call. = FALSE)
message("Reading ", INPUT_ZIP, " ...")
gtfs   <- read_gtfs(INPUT_ZIP)
before <- feed_counts(gtfs)
message("  before: ", paste(names(before), before, sep = "=", collapse = "  "))


# ---- 1. spatial clip: Greater Manchester + 50 km buffer ---------------------
message("Clipping to Greater Manchester + ", BUFFER_M / 1000, " km buffer ...")
if (!is.null(BOUNDARY_FILE) && file.exists(BOUNDARY_FILE)) {
  gm <- sf::st_geometry(sf::st_read(BOUNDARY_FILE, quiet = TRUE))
} else {
  bb   <- GM_BBOX_WGS84
  ring <- matrix(c(bb[["xmin"]], bb[["ymin"]],
                   bb[["xmax"]], bb[["ymin"]],
                   bb[["xmax"]], bb[["ymax"]],
                   bb[["xmin"]], bb[["ymax"]],
                   bb[["xmin"]], bb[["ymin"]]),
                 ncol = 2, byrow = TRUE)
  gm <- sf::st_sfc(sf::st_polygon(list(ring)), crs = 4326)
}
# Buffer in EPSG:27700 (British National Grid, metres), then back to WGS84 --
# GTFS coordinates, and gtfstools' internal geometries, are lon/lat.
gm        <- sf::st_transform(gm, 27700)
clip_sf   <- sf::st_as_sf(sf::st_transform(sf::st_buffer(gm, BUFFER_M), 4326))

gtfs        <- filter_by_sf(gtfs, clip_sf, keep = TRUE)
after_clip  <- feed_counts(gtfs)
message("  after clip: ",
        paste(names(after_clip), after_clip, sep = "=", collapse = "  "))


# ---- 2. drop fully-expired calendar services --------------------------------
# `expired_calendar`: services whose end_date is entirely in the past. Their
# trips will never run. filter_by_service_id cascades to trips / stop_times.
# NOTE: only calendar.txt end_date is checked; a service living solely in
# calendar_dates.txt is not evaluated here.
if (!is.null(gtfs$calendar) && nrow(gtfs$calendar) > 0) {
  today   <- Sys.Date()
  end_dt  <- parse_gtfs_date(gtfs$calendar$end_date)
  expired <- gtfs$calendar$service_id[!is.na(end_dt) & end_dt < today]
  if (length(expired) > 0) {
    message("Dropping ", length(expired),
            " expired service(s) and their trips ...")
    gtfs <- filter_by_service_id(gtfs, expired, keep = FALSE)
  } else {
    message("No expired calendar services found.")
  }
}


# ---- 3. drop block_id -------------------------------------------------------
# `block_trips_with_overlapping_stop_times` (457 ERRORS): TfGM's block_ids are
# internally inconsistent (reused across overlapping services). MATSim does not
# model vehicle blocks, so the column is simply removed.
if ("block_id" %in% names(gtfs$trips)) {
  data.table::set(gtfs$trips, j = "block_id", value = NULL)
  message("Removed block_id column from trips.txt.")
}


# ---- 4. prune unused shapes -------------------------------------------------
# `unused_shape`: shapes not referenced by any trip (filter_by_sf can retain a
# shape on geometry alone). Keep only shapes still referenced by a trip.
if (!is.null(gtfs$shapes) && nrow(gtfs$shapes) > 0) {
  used     <- unique(gtfs$trips$shape_id)
  used     <- used[!is.na(used) & used != ""]
  n_before <- length(unique(gtfs$shapes$shape_id))
  gtfs$shapes <- gtfs$shapes[gtfs$shapes$shape_id %in% used, ]
  message("Pruned ", n_before - length(unique(gtfs$shapes$shape_id)),
          " unused shape(s).")
}


# ---- 5. fix over-long route_short_name --------------------------------------
# `route_short_name_too_long`: descriptive text in the short-name field. Move
# it to route_long_name (when empty) and blank the short name.
if ("route_short_name" %in% names(gtfs$routes)) {
  sn  <- as.character(gtfs$routes$route_short_name)
  idx <- which(!is.na(sn) & nchar(sn) > MAX_SHORT_NAME)
  if (length(idx) > 0) {
    ln   <- as.character(gtfs$routes$route_long_name)
    fill <- idx[is.na(ln[idx]) | ln[idx] == ""]
    if (length(fill) > 0) {
      data.table::set(gtfs$routes, i = fill, j = "route_long_name",
                      value = sn[fill])
    }
    data.table::set(gtfs$routes, i = idx, j = "route_short_name", value = "")
    message("Fixed ", length(idx), " over-long route_short_name value(s).")
  }
}


# ---- 6. drop non-standard columns -------------------------------------------
# `unknown_column`: fields outside the GTFS reference (e.g. agency_noc).
for (tbl in names(DROP_COLUMNS)) {
  col <- DROP_COLUMNS[[tbl]]
  if (!is.null(gtfs[[tbl]]) && col %in% names(gtfs[[tbl]])) {
    data.table::set(gtfs[[tbl]], j = col, value = NULL)
    message("Removed non-standard column ", tbl, ".", col, ".")
  }
}


# ---- 7. write ---------------------------------------------------------------
after <- feed_counts(gtfs)
message("Writing cleaned feed to ", OUTPUT_ZIP, " ...")
write_gtfs(gtfs, OUTPUT_ZIP)

cat("\n", strrep("=", 60), "\n", sep = "")
cat("CLEANING SUMMARY (", INPUT_ZIP, ")\n", sep = "")
cat(strrep("=", 60), "\n", sep = "")
for (k in names(before)) {
  cat(sprintf("  %-9s %9s  ->  %9s\n", k, before[[k]], after[[k]]))
}
cat("\n  Output: ", OUTPUT_ZIP, "\n", sep = "")
cat("  Re-validate by adding it to FEEDS in validate_gtfs_feeds.R\n")
